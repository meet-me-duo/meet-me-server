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
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Google official OIDC validation explicitly permits these two exact issuer strings, with no prefix matching.
class GoogleIssuerCompatibilitySecurityTest {
    @Test
    fun `Google canonical and documented legacy issuer identify same subject while lookalikes fail`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key =
            RSAKey
                .Builder(pair.public as RSAPublicKey)
                .privateKey(pair.private as RSAPrivateKey)
                .keyID("synthetic")
                .build()
        val issuer = AtomicReference("https://accounts.google.com")
        server.createContext("/token") { exchange ->
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer(issuer.get())
                    .audience("synthetic-client")
                    .subject("synthetic-subject")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("nonce", "synthetic-nonce")
                    .claim("name", "Synthetic")
                    .build()
            val token =
                SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic").build(), claims)
                    .apply { sign(RSASSASigner(key)) }
                    .serialize()
            val body = ObjectMapper().writeValueAsBytes(mapOf("id_token" to token))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/jwks") { exchange ->
            val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val adapter =
                OidcOAuthProviderAdapter(
                    mapOf(
                        OAuthProvider.GOOGLE to
                            OidcClient(
                                "synthetic-client",
                                "synthetic-secret",
                                "https://api.synthetic.example/callback",
                            ),
                    ),
                    Clock.systemUTC(),
                    endpoints =
                        mapOf(
                            OAuthProvider.GOOGLE to
                                OidcEndpoints(
                                    "$origin/authorize",
                                    "$origin/token",
                                    "$origin/jwks",
                                    "https://accounts.google.com",
                                    "openid",
                                ),
                        ),
                    allowLoopbackTestEndpoints = true,
                )
            val canonical = adapter.exchange(OAuthProvider.GOOGLE, "synthetic-code", "synthetic-verifier", "synthetic-nonce")
            issuer.set("accounts.google.com")
            assertEquals(canonical, adapter.exchange(OAuthProvider.GOOGLE, "synthetic-code", "synthetic-verifier", "synthetic-nonce"))
            for (invalid in listOf("https://accounts.google.com.evil", "http://accounts.google.com", "https://accounts.google.com/")) {
                issuer.set(invalid)
                assertFailsWith<AuthFailure> {
                    adapter.exchange(
                        OAuthProvider.GOOGLE,
                        "synthetic-code",
                        "synthetic-verifier",
                        "synthetic-nonce",
                    )
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
