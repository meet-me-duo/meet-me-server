package com.meetme.server.account.adapter.output.oauth

import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Real HTTP and RS256/JWKS with isolated providers only; no external client/secret or operational login.
class OidcProviderSecurityContractTest {
    @Test
    fun `both provider authorization redirects include exact client redirect state nonce and S256 PKCE`() {
        ProviderFixture().use { fixture ->
            for (provider in OAuthProvider.entries) {
                val uri = URI(fixture.adapter.authorizationUri(provider, "synthetic-state", "synthetic-challenge", "synthetic-nonce"))
                assertEquals("/authorize", uri.path)
                val query = fields(requireNotNull(uri.rawQuery))
                assertEquals("code", query["response_type"])
                assertEquals("synthetic-client", query["client_id"])
                assertEquals(REDIRECT, query["redirect_uri"])
                assertEquals("synthetic-state", query["state"])
                assertEquals("synthetic-nonce", query["nonce"])
                assertEquals("S256", query["code_challenge_method"])
                assertEquals("synthetic-challenge", query["code_challenge"])
                assertTrue(requireNotNull(query["scope"]).split(' ').contains("openid"))
            }
        }
    }

    @Test
    fun `both providers exchange exact form and validate signed subject without requiring email`() {
        ProviderFixture().use { fixture ->
            for (provider in OAuthProvider.entries) {
                fixture.token = fixture.signedToken()
                val identity = fixture.adapter.exchange(provider, "synthetic-code &+", "synthetic-verifier/+", "synthetic-nonce")
                assertEquals("provider-subject", identity.subject)
                assertTrue(identity.displayName.isNotBlank())
                val form = fields(fixture.forms.last())
                assertEquals("authorization_code", form["grant_type"])
                assertEquals("synthetic-client", form["client_id"])
                assertEquals("synthetic-secret-only", form["client_secret"])
                assertEquals(REDIRECT, form["redirect_uri"])
                assertEquals("synthetic-code &+", form["code"])
                assertEquals("synthetic-verifier/+", form["code_verifier"])
            }
        }
    }

    @Test
    fun `invalid signature kid algorithm issuer audience nonce expiry future issue subject and azp reject both providers`() {
        ProviderFixture().use { fixture ->
            val variants =
                listOf(
                    fixture.signedToken(signingKey = wrongKey),
                    fixture.signedToken(kid = "unknown"),
                    fixture.signedToken(algorithm = JWSAlgorithm.RS512),
                    fixture.signedToken(issuer = "https://attacker.synthetic"),
                    fixture.signedToken(audience = listOf("other-client")),
                    fixture.signedToken(nonce = "wrong-nonce"),
                    fixture.signedToken(expiration = NOW),
                    fixture.signedToken(issuedAt = NOW.plusSeconds(120)),
                    fixture.signedToken(subject = ""),
                    fixture.signedToken(audience = listOf("synthetic-client", "other-client"), azp = "other-client"),
                    fixture.signedToken(audience = listOf("synthetic-client", "other-client")),
                    "not-an-id-token",
                )
            for (provider in OAuthProvider.entries) {
                for (token in variants) {
                    fixture.token = token
                    val failure =
                        assertFailsWith<AuthFailure> {
                            fixture.adapter.exchange(
                                provider,
                                "synthetic-code",
                                "synthetic-verifier",
                                "synthetic-nonce",
                            )
                        }
                    assertFalse(requireNotNull(failure.message).contains("synthetic-secret-only"))
                    assertFalse(requireNotNull(failure.message).contains(token))
                }
            }
        }
    }

    @Test
    fun `provider HTTP errors missing ID token and JWKS errors fail without leaking provider response`() {
        ProviderFixture().use { fixture ->
            for (provider in OAuthProvider.entries) {
                fixture.status = 500
                fixture.responseOverride = "synthetic-sensitive-provider-body"
                val failure =
                    assertFailsWith<AuthFailure> {
                        fixture.adapter.exchange(
                            provider,
                            "synthetic-code",
                            "synthetic-verifier",
                            "synthetic-nonce",
                        )
                    }
                assertFalse(requireNotNull(failure.message).contains("synthetic-sensitive-provider-body"))
                fixture.status = 200
                fixture.responseOverride = "{\"access_token\":\"synthetic-provider-access\"}"
                assertFailsWith<AuthFailure> {
                    fixture.adapter.exchange(
                        provider,
                        "synthetic-code",
                        "synthetic-verifier",
                        "synthetic-nonce",
                    )
                }
                fixture.responseOverride = null
                fixture.token = fixture.signedToken()
                fixture.jwksStatus = 503
                assertFailsWith<AuthFailure> {
                    fixture.adapter.exchange(
                        provider,
                        "synthetic-code",
                        "synthetic-verifier",
                        "synthetic-nonce",
                    )
                }
            }
        }
    }

    private class ProviderFixture : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val forms = CopyOnWriteArrayList<String>()
        var token = ""
        var status = 200
        var jwksStatus = 200
        var responseOverride: String? = null
        private val origin: String
        val adapter: OidcOAuthProviderAdapter

        init {
            server.createContext("/token") { exchange ->
                forms.add(exchange.requestBody.readAllBytes().toString(UTF_8))
                val body =
                    (
                        responseOverride
                            ?: ObjectMapper().writeValueAsString(mapOf("id_token" to token, "access_token" to "synthetic-provider-access"))
                    ).toByteArray(UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            server.createContext("/jwks") { exchange ->
                val body = JWKSet(signingKey.toPublicJWK()).toString().toByteArray(UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(jwksStatus, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            server.start()
            origin = "http://127.0.0.1:${server.address.port}"
            adapter =
                OidcOAuthProviderAdapter(
                    OAuthProvider.entries.associateWith { OidcClient("synthetic-client", "synthetic-secret-only", REDIRECT) },
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    endpoints =
                        OAuthProvider.entries.associateWith {
                            OidcEndpoints("$origin/authorize", "$origin/token", "$origin/jwks", origin, "openid profile")
                        },
                    allowLoopbackTestEndpoints = true,
                )
        }

        fun signedToken(
            signingKey: RSAKey = Companion.signingKey,
            kid: String = "synthetic-rsa-key",
            algorithm: JWSAlgorithm = JWSAlgorithm.RS256,
            issuer: String = origin,
            audience: List<String> = listOf("synthetic-client"),
            nonce: String = "synthetic-nonce",
            expiration: Instant = NOW.plusSeconds(300),
            issuedAt: Instant = NOW,
            subject: String = "provider-subject",
            azp: String? = null,
        ): String {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiration))
                    .claim("nonce", nonce)
                    .claim("name", "Synthetic name")
            if (azp != null) claims.claim("azp", azp)
            return SignedJWT(
                JWSHeader
                    .Builder(algorithm)
                    .keyID(kid)
                    .type(JOSEObjectType.JWT)
                    .build(),
                claims.build(),
            ).apply { sign(RSASSASigner(signingKey)) }
                .serialize()
        }

        override fun close() = server.stop(0)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-17T12:00:00Z")
        private const val REDIRECT = "https://api.synthetic.example/api/auth/provider/callback"
        private val signingKey = rsaKey()
        private val wrongKey = rsaKey()

        private fun rsaKey(): RSAKey {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return RSAKey
                .Builder(pair.public as RSAPublicKey)
                .privateKey(pair.private as RSAPrivateKey)
                .keyID("synthetic-rsa-key")
                .build()
        }

        private fun fields(encoded: String): Map<String, String> =
            encoded.split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, UTF_8)
            }
    }
}
