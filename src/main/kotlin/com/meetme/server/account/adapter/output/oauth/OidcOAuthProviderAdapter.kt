package com.meetme.server.account.adapter.output.oauth

import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.domain.AuthEntropy
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
import com.meetme.server.account.domain.ProviderIdentity
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Secrets intentionally do not appear in generated data-class diagnostics.
data class OidcClient(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
) {
    override fun toString(): String = "OidcClient(configured=${clientId.isNotBlank()})"
}

data class OidcEndpoints(
    val authorizationUri: String,
    val tokenUri: String,
    val jwksUri: String,
    val issuer: String,
    val scope: String,
)

class OidcOAuthProviderAdapter(
    private val clients: Map<OAuthProvider, OidcClient>,
    private val clock: Clock,
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(
                Duration.ofSeconds(2),
            ).followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    private val endpoints: Map<OAuthProvider, OidcEndpoints> = PRODUCTION_ENDPOINTS,
    private val allowLoopbackTestEndpoints: Boolean = false,
    private val timeout: Duration = Duration.ofSeconds(5),
) : OAuthProviderPort {
    init {
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofSeconds(30))
        require(http.followRedirects() == HttpClient.Redirect.NEVER) { "OAuth redirects must not forward credentials" }
        if (allowLoopbackTestEndpoints) {
            require(
                endpoints.values.all { endpoint ->
                    listOf(endpoint.authorizationUri, endpoint.tokenUri, endpoint.jwksUri).all { value ->
                        val uri = URI.create(value)
                        uri.scheme in setOf("http", "https") &&
                            uri.host in setOf("127.0.0.1", "localhost", "::1") &&
                            uri.userInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null
                    }
                },
            ) { "Isolated OAuth endpoints must remain on loopback" }
        } else {
            require(endpoints == PRODUCTION_ENDPOINTS) { "Production OAuth endpoints are fixed" }
        }
    }

    override fun authorizationUri(
        provider: OAuthProvider,
        state: String,
        codeChallenge: String,
        nonce: String,
    ): String {
        val (client, endpoint) = configured(provider)
        val query =
            form(
                mapOf(
                    "response_type" to "code",
                    "client_id" to client.clientId,
                    "redirect_uri" to client.redirectUri,
                    "scope" to endpoint.scope,
                    "state" to state,
                    "nonce" to nonce,
                    "code_challenge" to codeChallenge,
                    "code_challenge_method" to "S256",
                ),
            )
        return "${endpoint.authorizationUri}?$query"
    }

    override fun exchange(
        provider: OAuthProvider,
        code: String,
        codeVerifier: String,
        expectedNonce: String,
    ): ProviderIdentity {
        try {
            val startedAt = System.nanoTime()
            val (client, endpoint) = configured(provider)
            val request =
                HttpRequest
                    .newBuilder(URI.create(endpoint.tokenUri))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            form(
                                mapOf(
                                    "grant_type" to "authorization_code",
                                    "client_id" to client.clientId,
                                    "client_secret" to client.clientSecret,
                                    "redirect_uri" to client.redirectUri,
                                    "code" to code,
                                    "code_verifier" to codeVerifier,
                                ),
                            ),
                        ),
                    ).build()
            val response = mapper.readTree(read(request, startedAt))
            val rawIdToken = response.path("id_token").stringValue() ?: throw AuthFailure("AUTH_OAUTH_INVALID")
            if (rawIdToken.length > 16_384) throw AuthFailure("AUTH_OAUTH_INVALID")
            val jwt = SignedJWT.parse(rawIdToken)
            if (jwt.header.algorithm != JWSAlgorithm.RS256 || jwt.header.keyID.isNullOrBlank()) throw AuthFailure("AUTH_OAUTH_INVALID")
            val jwks =
                JWKSet.parse(
                    read(
                        HttpRequest
                            .newBuilder(URI.create(endpoint.jwksUri))
                            .timeout(timeout)
                            .GET()
                            .build(),
                        startedAt,
                    ),
                )
            val key = jwks.keys.singleOrNull { it.keyID == jwt.header.keyID } as? RSAKey ?: throw AuthFailure("AUTH_OAUTH_INVALID")
            if (key.isPrivate ||
                key.keyUse?.let { it != KeyUse.SIGNATURE } == true ||
                key.algorithm?.let { it != JWSAlgorithm.RS256 } == true ||
                key.toRSAPublicKey().modulus.bitLength() !in 2048..8192 ||
                !jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
            ) {
                throw AuthFailure("AUTH_OAUTH_INVALID")
            }
            val claims = jwt.jwtClaimsSet
            val now = clock.instant()
            val azp = claims.getStringClaim("azp")
            val nonce = claims.getStringClaim("nonce") ?: throw AuthFailure("AUTH_OAUTH_INVALID")
            val expires = claims.expirationTime?.toInstant() ?: throw AuthFailure("AUTH_OAUTH_INVALID")
            val issued = claims.issueTime?.toInstant() ?: throw AuthFailure("AUTH_OAUTH_INVALID")
            val audiences = claims.audience
            // Google documents exactly these two issuer spellings; every other provider remains exact.
            val trustedIssuer =
                claims.issuer == endpoint.issuer ||
                    (
                        provider == OAuthProvider.GOOGLE &&
                            endpoint.issuer == "https://accounts.google.com" &&
                            claims.issuer == "accounts.google.com"
                    )
            if (!trustedIssuer ||
                client.clientId !in audiences ||
                !expires.isAfter(now) ||
                issued.isAfter(now) ||
                !expires.isAfter(issued) ||
                claims.notBeforeTime?.toInstant()?.isAfter(now) == true ||
                (audiences.size > 1 && azp != client.clientId) ||
                (azp != null && azp != client.clientId) ||
                !AuthEntropy.same(nonce, expectedNonce)
            ) {
                throw AuthFailure("AUTH_OAUTH_INVALID")
            }
            val subject = claims.subject ?: throw AuthFailure("AUTH_OAUTH_INVALID")
            if (subject.isBlank() || subject.length > 255 || '\u0000' in subject) throw AuthFailure("AUTH_OAUTH_INVALID")
            val suppliedName = (claims.getStringClaim("name") ?: claims.getStringClaim("nickname"))?.trim()
            val name = suppliedName?.takeIf { it.isNotBlank() } ?: "${provider.name.lowercase().replaceFirstChar(Char::titlecase)} 회원"
            val safeName = if (name.codePointCount(0, name.length) <= 50) name else name.substring(0, name.offsetByCodePoints(0, 50))
            // Provider access/refresh tokens are discarded; login requests no Calendar/offline scope.
            if (remaining(startedAt) <= 0) throw AuthFailure("AUTH_UNAVAILABLE")
            return ProviderIdentity(subject, safeName)
        } catch (failure: AuthFailure) {
            throw failure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AuthFailure("AUTH_UNAVAILABLE")
        } catch (_: java.io.IOException) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        } catch (_: Exception) {
            throw AuthFailure("AUTH_OAUTH_INVALID")
        }
    }

    private fun configured(provider: OAuthProvider): Pair<OidcClient, OidcEndpoints> {
        val client = clients[provider] ?: throw AuthFailure("AUTH_UNAVAILABLE")
        val endpoint = endpoints[provider] ?: throw AuthFailure("AUTH_UNAVAILABLE")
        if (client.clientId.isBlank() || client.clientSecret.isBlank()) throw AuthFailure("AUTH_UNAVAILABLE")
        val redirect = URI.create(client.redirectUri)
        if (redirect.userInfo != null ||
            redirect.rawQuery != null ||
            redirect.rawFragment != null ||
            (redirect.scheme != "https" && !(redirect.scheme == "http" && redirect.host in setOf("localhost", "127.0.0.1")))
        ) {
            throw AuthFailure("AUTH_UNAVAILABLE")
        }
        return client to endpoint
    }

    private fun remaining(startedAt: Long) = timeout.toNanos() - (System.nanoTime() - startedAt)

    private fun read(
        request: HttpRequest,
        startedAt: Long,
    ): String {
        val budget = remaining(startedAt)
        if (budget <= 0) throw AuthFailure("AUTH_UNAVAILABLE")
        val future = http.sendAsync(request) { BoundedBodySubscriber() }
        val response =
            try {
                future.get(budget, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                throw AuthFailure("AUTH_UNAVAILABLE")
            } catch (_: ExecutionException) {
                future.cancel(true)
                throw AuthFailure("AUTH_UNAVAILABLE")
            } catch (failure: InterruptedException) {
                future.cancel(true)
                throw failure
            }
        if (response.statusCode() !in 200..299) {
            throw AuthFailure(if (response.statusCode() >= 500) "AUTH_UNAVAILABLE" else "AUTH_OAUTH_INVALID")
        }
        return response.body().toString(Charsets.UTF_8)
    }

    /** Completion includes the complete response body; neither timeout nor memory cap ends at headers. */
    private class BoundedBodySubscriber : HttpResponse.BodySubscriber<ByteArray> {
        private val completion = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = completion

        override fun onSubscribe(value: Flow.Subscription) {
            if (subscription != null) {
                value.cancel()
                return
            }
            subscription = value
            value.request(1)
        }

        override fun onNext(buffers: List<ByteBuffer>) {
            for (buffer in buffers) {
                if (buffer.remaining().toLong() + bytes.size() > 65_536) {
                    subscription?.cancel()
                    completion.completeExceptionally(IOException("OAuth response exceeds limit"))
                    return
                }
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                bytes.write(chunk)
            }
            subscription?.request(1)
        }

        override fun onError(failure: Throwable) {
            completion.completeExceptionally(IOException("OAuth response unavailable"))
        }

        override fun onComplete() {
            completion.complete(bytes.toByteArray())
        }
    }

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }

    companion object {
        private val mapper = JsonMapper.builder().build()
        val PRODUCTION_ENDPOINTS =
            mapOf(
                OAuthProvider.GOOGLE to
                    OidcEndpoints(
                        "https://accounts.google.com/o/oauth2/v2/auth",
                        "https://oauth2.googleapis.com/token",
                        "https://www.googleapis.com/oauth2/v3/certs",
                        "https://accounts.google.com",
                        "openid profile",
                    ),
                OAuthProvider.KAKAO to
                    OidcEndpoints(
                        "https://kauth.kakao.com/oauth/authorize",
                        "https://kauth.kakao.com/oauth/token",
                        "https://kauth.kakao.com/.well-known/jwks.json",
                        "https://kauth.kakao.com",
                        "openid,profile_nickname",
                    ),
            )
    }
}
