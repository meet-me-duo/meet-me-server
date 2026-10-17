package com.meetme.server.account.adapter.output.oauth

import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.account.domain.OAuthProvider
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
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Independent review P1: response headers must not disable the total token+JWKS HTTP deadline.
class OidcProviderDeadlineSecurityTest {
    @Test
    fun `token response body stall is bounded by exchange deadline`() = assertBounded("token")

    @Test
    fun `JWKS response body stall is bounded by exchange deadline`() = assertBounded("jwks")

    private fun assertBounded(stalled: String) {
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        val origin = "http://127.0.0.1:${server.address.port}"
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key =
            RSAKey
                .Builder(pair.public as RSAPublicKey)
                .privateKey(pair.private as RSAPrivateKey)
                .keyID("synthetic")
                .build()
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(origin)
                .audience("synthetic-client")
                .subject("synthetic-subject")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", "synthetic-nonce")
                .build()
        val token =
            SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic").build(), claims)
                .apply { sign(RSASSASigner(key)) }
                .serialize()
        for (route in listOf("token", "jwks")) {
            server.createContext("/$route") { exchange ->
                val body =
                    if (route ==
                        "token"
                    ) {
                        ObjectMapper().writeValueAsBytes(mapOf("id_token" to token))
                    } else {
                        JWKSet(key.toPublicJWK()).toString().toByteArray()
                    }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.flush()
                try {
                    if (route == stalled) Thread.sleep(1800)
                    exchange.responseBody.use { it.write(body) }
                } finally {
                    exchange.close()
                }
            }
        }
        server.start()
        try {
            val adapter =
                OidcOAuthProviderAdapter(
                    OAuthProvider.entries.associateWith {
                        OidcClient(
                            "synthetic-client",
                            "synthetic-secret",
                            "https://api.synthetic.example/callback",
                        )
                    },
                    Clock.systemUTC(),
                    endpoints =
                        OAuthProvider.entries.associateWith {
                            OidcEndpoints(
                                "$origin/authorize",
                                "$origin/token",
                                "$origin/jwks",
                                origin,
                                "openid",
                            )
                        },
                    allowLoopbackTestEndpoints = true,
                    timeout = Duration.ofMillis(250),
                )
            val started = System.nanoTime()
            val failure =
                assertFailsWith<AuthFailure> {
                    adapter.exchange(
                        OAuthProvider.GOOGLE,
                        "synthetic-code",
                        "synthetic-verifier",
                        "synthetic-nonce",
                    )
                }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertEquals("AUTH_UNAVAILABLE", failure.code)
            assertTrue(elapsed < Duration.ofMillis(1200), "exchange elapsed ${elapsed.toMillis()} ms exceeds bounded test margin")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
