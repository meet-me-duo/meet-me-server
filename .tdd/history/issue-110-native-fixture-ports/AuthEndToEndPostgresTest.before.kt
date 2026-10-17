package com.meetme.server.account.adapter.input.web

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.oauth.OidcClient
import com.meetme.server.account.adapter.output.oauth.OidcEndpoints
import com.meetme.server.account.adapter.output.oauth.OidcOAuthProviderAdapter
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.OAuthProviderPort
import com.meetme.server.account.domain.OAuthProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Composition verification of the frozen independent auth contracts: actual HTTP provider/JWKS + Redis + PostgreSQL + MVC.
// Ephemeral synthetic keys and identities only. This does not assert operational Google/Kakao login capability.
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthEndToEndPostgresTest.IsolatedAuthProviders::class)
class AuthEndToEndPostgresTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `Google and Kakao full callback refresh account identity current and all-device logout preserve boundaries`() {
        val google = restore(login("google"))
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer ${google.access}")).andExpect(status().isOk)
        val otherGoogleDevice = restore(login("google"))
        assertEquals(google.account, otherGoogleDevice.account)
        val kakao = restore(login("kakao"))
        assertNotEquals(google.account, kakao.account)
        val identities =
            jdbc.queryForList(
                "SELECT provider, subject FROM account_identities WHERE user_id IN (?, ?)",
                UUID.fromString(google.account),
                UUID.fromString(kakao.account),
            )
        assertEquals(setOf("GOOGLE", "KAKAO"), identities.map { it["provider"] }.toSet())
        assertEquals(setOf("shared-synthetic-subject"), identities.map { it["subject"] }.toSet())

        mvc.perform(post("/api/auth/logout").header("Origin", ORIGIN).cookie(google.cookie)).andExpect(status().is2xxSuccessful)
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(google.cookie)).andExpect(status().isUnauthorized)
        // Access is stateless and remains valid after logout, as ADR-031 explicitly requires.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer ${google.access}")).andExpect(status().isOk)
        val remaining = restore(otherGoogleDevice.cookie)
        mvc
            .perform(
                post(
                    "/api/auth/logout-all",
                ).header("Origin", ORIGIN).header("Authorization", "Bearer ${remaining.access}").cookie(remaining.cookie),
            ).andExpect(status().is2xxSuccessful)
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(remaining.cookie)).andExpect(status().isUnauthorized)
        assertEquals(kakao.account, restore(kakao.cookie).account)
    }

    @Test
    fun `callback browser mismatch never reaches provider and state replay cannot issue another session`() {
        val (provider, state, browser, nonce) = start("google")
        val baseline = providerCalls.get()
        val code = registerCode(nonce)
        mvc
            .perform(
                get("/api/auth/$provider/callback").param("state", state).param("code", code).cookie(Cookie(browser.name, "wrong-browser")),
            ).andExpect(status().isUnauthorized)
        assertEquals(baseline, providerCalls.get())
        mvc
            .perform(get("/api/auth/$provider/callback").param("state", state).param("code", code).cookie(browser))
            .andExpect(status().isUnauthorized)
        assertEquals(baseline, providerCalls.get())
    }

    @Test
    fun `refresh replay revokes latest descendant and nonexact Origin has no rotation side effect`() {
        val original = login("google")
        mvc.perform(post("/api/auth/refresh").header("Origin", "$ORIGIN.evil").cookie(original)).andExpect(status().isForbidden)
        mvc.perform(post("/api/auth/refresh").cookie(original)).andExpect(status().isForbidden)
        val rotated = restore(original)
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(original)).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/auth/refresh").header("Origin", ORIGIN).cookie(rotated.cookie)).andExpect(status().isUnauthorized)
    }

    private fun login(provider: String): Cookie {
        val started = start(provider)
        val code = registerCode(started.nonce)
        val result =
            mvc
                .perform(
                    get("/api/auth/${started.provider}/callback").param("state", started.state).param("code", code).cookie(started.browser),
                ).andExpect(status().is3xxRedirection)
                .andReturn()
        val location = requireNotNull(result.response.getHeader("Location"))
        assertEquals("https://app.synthetic.example/auth/callback", location)
        assertFalse(location.contains("token", ignoreCase = true))
        val cookie = responseCookie(result.response.getHeaders("Set-Cookie"), "meetme_refresh")
        mvc
            .perform(
                get("/api/auth/${started.provider}/callback").param("state", started.state).param("code", code).cookie(started.browser),
            ).andExpect(status().isUnauthorized)
        return cookie
    }

    private fun start(provider: String): Started {
        val response =
            mvc
                .perform(get("/api/auth/$provider/start"))
                .andExpect(status().is3xxRedirection)
                .andReturn()
                .response
        val location = URI(requireNotNull(response.getHeader("Location")))
        assertEquals(providerOrigin, "${location.scheme}://${location.authority}")
        val fields = fields(requireNotNull(location.rawQuery))
        assertEquals("S256", fields["code_challenge_method"])
        return Started(
            provider,
            requireNotNull(fields["state"]),
            responseCookie(response.getHeaders("Set-Cookie"), "meetme_oauth_browser"),
            requireNotNull(fields["nonce"]),
        )
    }

    private fun restore(cookie: Cookie): Restored {
        val response =
            mvc
                .perform(
                    post("/api/auth/refresh").header("Origin", ORIGIN).cookie(cookie),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        val body = ObjectMapper().readTree(response.contentAsByteArray)
        val next = responseCookie(response.getHeaders("Set-Cookie"), "meetme_refresh")
        assertNotEquals(cookie.value, next.value)
        assertFalse(response.contentAsString.contains(next.value))
        val access = body.path("accessToken").stringValue()
        assertEquals(3, access.split('.').size)
        return Restored(next, access, body.path("account").path("id").stringValue())
    }

    private data class Started(
        val provider: String,
        val state: String,
        val browser: Cookie,
        val nonce: String,
    )

    private data class Restored(
        val cookie: Cookie,
        val access: String,
        val account: String,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class IsolatedAuthProviders {
        @Bean
        @Primary
        fun isolatedAccessTokenPort(): AccessTokenPort =
            Rs256AccessTokenAdapter(
                keyPair.private,
                mapOf("synthetic" to keyPair.public),
                "synthetic",
                Clock.systemUTC(),
                "https://api.synthetic.example",
                "meetme-web",
            )

        @Bean
        @Primary
        fun isolatedOAuthProviderPort(): OAuthProviderPort =
            OidcOAuthProviderAdapter(
                OAuthProvider.entries.associateWith {
                    val base = System.getProperty("meetme.auth.test.redirect-base", "https://api.synthetic.example")
                    OidcClient("synthetic-client", "synthetic-client-secret", "$base/api/auth/${it.name.lowercase()}/callback")
                },
                Clock.systemUTC(),
                endpoints =
                    OAuthProvider.entries.associateWith {
                        OidcEndpoints(
                            "$providerOrigin/authorize",
                            "$providerOrigin/token",
                            "$providerOrigin/jwks",
                            providerOrigin,
                            "openid profile",
                        )
                    },
                allowLoopbackTestEndpoints = true,
            )
    }

    companion object {
        private const val ORIGIN = "https://app.synthetic.example"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).apply { start() }
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val providerKey =
            RSAKey
                .Builder(
                    keyPair.public as RSAPublicKey,
                ).privateKey(keyPair.private as RSAPrivateKey)
                .keyID("synthetic")
                .build()
        private val codeNonces = ConcurrentHashMap<String, String>()
        private val codeChallenges = ConcurrentHashMap<String, String>()
        private val providerCalls = AtomicInteger()
        private val providerServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/authorize") { exchange ->
                    val query = fields(requireNotNull(exchange.requestURI.rawQuery))
                    val code = registerCode(requireNotNull(query["nonce"]))
                    codeChallenges[code] = requireNotNull(query["code_challenge"])
                    val redirect = requireNotNull(query["redirect_uri"])
                    if (!redirect.startsWith("http://localhost:8080/api/auth/")) {
                        exchange.sendResponseHeaders(400, -1)
                    } else {
                        exchange.responseHeaders.add("Location", "$redirect?code=$code&state=${query["state"]}")
                        exchange.sendResponseHeaders(302, -1)
                    }
                    exchange.close()
                }
                createContext("/token") { exchange ->
                    providerCalls.incrementAndGet()
                    val form = fields(exchange.requestBody.readAllBytes().toString(UTF_8))
                    val challenge = codeChallenges.remove(form["code"])
                    if (challenge != null) {
                        val actual =
                            Base64.getUrlEncoder().withoutPadding().encodeToString(
                                MessageDigest.getInstance("SHA-256").digest(requireNotNull(form["code_verifier"]).toByteArray(UTF_8)),
                            )
                        if (actual != challenge) {
                            exchange.sendResponseHeaders(400, -1)
                            exchange.close()
                            return@createContext
                        }
                    }
                    val nonce = codeNonces.remove(form["code"]) ?: "invalid-code"
                    val claims =
                        JWTClaimsSet
                            .Builder()
                            .issuer("http://127.0.0.1:${address.port}")
                            .audience("synthetic-client")
                            .subject(
                                "shared-synthetic-subject",
                            ).issueTime(Date.from(Instant.now()))
                            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                            .claim("nonce", nonce)
                            .claim("name", "Synthetic account")
                            .claim("email", "same-synthetic@example.invalid")
                            .build()
                    val id =
                        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic").build(), claims)
                            .apply {
                                sign(RSASSASigner(providerKey))
                            }.serialize()
                    val body =
                        ObjectMapper().writeValueAsBytes(
                            mapOf("id_token" to id, "access_token" to "unused-synthetic-provider-access"),
                        )
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                createContext("/jwks") { exchange ->
                    val body = JWKSet(providerKey.toPublicJWK()).toString().toByteArray(UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        private val providerOrigin = "http://127.0.0.1:${providerServer.address.port}"

        private fun registerCode(nonce: String): String = UUID.randomUUID().toString().also { codeNonces[it] = nonce }

        private fun fields(encoded: String): Map<String, String> =
            encoded.split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, UTF_8)
            }

        private fun responseCookie(
            headers: List<String>,
            name: String,
        ): Cookie {
            val header = headers.single { it.startsWith("$name=") && !it.contains("Max-Age=0") }
            assertTrue(header.contains("Secure"))
            assertTrue(header.contains("HttpOnly"))
            assertTrue(header.contains("Path=/api/auth"))
            assertFalse(header.contains("Domain=", ignoreCase = true))
            return Cookie(name, header.substringAfter('=').substringBefore(';'))
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("meetme.anonymous.allowed-origins") { ORIGIN }
            registry.add("meetme.auth.web-callback-uri") { "$ORIGIN/auth/callback" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            providerServer.stop(0)
            redis.stop()
            postgres.stop()
        }

        @JvmStatic
        fun runBrowserServer() {
            System.setProperty("meetme.auth.test.redirect-base", "http://localhost:8080")
            mapOf(
                "server.port" to "8080",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.flyway.enabled" to "true",
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to redis.getMappedPort(6379).toString(),
                "meetme.anonymous.allowed-origins" to "http://localhost:5173",
                "meetme.anonymous.cookie-secure" to "false",
                "meetme.auth.web-callback-uri" to "http://localhost:5173/auth/callback",
                "meetme.reliability.enabled" to "false",
                "meetme.rate-limit.enabled" to "false",
                "meetme.data-retention.enabled" to "false",
            ).forEach { (name, value) -> System.setProperty(name, value) }
            SpringApplicationBuilder(MeetMeServerApplication::class.java, IsolatedAuthProviders::class.java).run()
            println("ISSUE109_SYNTHETIC_BROWSER_SERVER_READY http://localhost:8080")
        }
    }
}

fun main() = AuthEndToEndPostgresTest.runBrowserServer()
