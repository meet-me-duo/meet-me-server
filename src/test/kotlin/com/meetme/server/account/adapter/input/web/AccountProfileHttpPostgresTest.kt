package com.meetme.server.account.adapter.input.web

import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.application.port.output.RefreshSessionPort
import com.meetme.server.account.domain.AccountPrincipal
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue228: actual MVC, RS256, Redis rotation, Flyway schema and PostgreSQL composition. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountProfileHttpPostgresTest.SyntheticSigning::class)
class AccountProfileHttpPostgresTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var access: AccessTokenPort

    @Autowired
    private lateinit var sessions: RefreshSessionPort

    private val mapper = ObjectMapper()

    @Test
    fun `P228-C01 old JWT and existing Redis sessions resolve latest profile after authenticated rename`() {
        val id = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,?),(?,?,?)",
            id,
            "Kakao 회원",
            java.sql.Timestamp.from(Instant.now()),
            otherId,
            "별도 회원",
            java.sql.Timestamp.from(Instant.now()),
        )
        val original = AccountPrincipal(id, "Kakao 회원")
        val oldAccess = access.issue(original)
        val firstSession = sessions.create(original)
        val secondSession = sessions.create(original)

        val renamed =
            mvc
                .perform(
                    put("/api/auth/me")
                        .header("Origin", ORIGIN)
                        .header("Authorization", "Bearer $oldAccess")
                        .contentType("application/json")
                        .content("{\"displayName\":\"  새 표시 이름  \"}"),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        val body = mapper.readTree(renamed.contentAsByteArray)
        assertEquals(setOf("id", "displayName", "capabilities"), body.properties().map { it.key }.toSet())
        assertEquals(id.toString(), body.get("id").asText())
        assertEquals("새 표시 이름", body.get("displayName").asText())
        assertTrue(renamed.getHeaders("Set-Cookie").isEmpty())
        assertEquals("no-store", renamed.getHeader("Cache-Control"))
        assertFalse(body.toString().contains(firstSession.refreshToken))
        assertFalse(body.toString().contains(oldAccess))
        assertEquals("새 표시 이름", jdbc.queryForObject("SELECT display_name FROM account_users WHERE id=?", String::class.java, id))
        assertEquals("별도 회원", jdbc.queryForObject("SELECT display_name FROM account_users WHERE id=?", String::class.java, otherId))

        val current =
            mvc
                .perform(
                    get("/api/auth/me").header("Authorization", "Bearer $oldAccess"),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals("새 표시 이름", mapper.readTree(current.contentAsByteArray).get("displayName").asText())
        // Updating a profile does not mutate an already issued token or mint another one.
        assertEquals("Kakao 회원", access.verify(oldAccess).displayName)

        for (session in listOf(firstSession, secondSession)) {
            val refreshed =
                mvc
                    .perform(
                        post("/api/auth/refresh").header("Origin", ORIGIN).cookie(Cookie("meetme_refresh", session.refreshToken)),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
            val response = mapper.readTree(refreshed.contentAsByteArray)
            assertEquals(id.toString(), response.get("account").get("id").asText())
            assertEquals("새 표시 이름", response.get("account").get("displayName").asText())
            assertEquals(AccountPrincipal(id, "새 표시 이름"), access.verify(response.get("accessToken").asText()))
            val cookie = assertNotNull(refreshed.getHeader("Set-Cookie"))
            assertNotEquals(session.refreshToken, cookie.substringAfter('=').substringBefore(';'))
            assertTrue(cookie.contains("Path=/api/auth"))
            assertTrue(cookie.contains("HttpOnly"))
            assertTrue(cookie.contains("Secure"))
            assertTrue(cookie.contains("SameSite=Lax"))
            assertFalse(cookie.contains("Domain=", ignoreCase = true))
        }
    }

    @Test
    fun `P228-C02 native OpenAPI and existing PUT CORS policy expose the profile contract`() {
        val schema =
            mvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val document = mapper.readTree(schema.contentAsByteArray)
        val operation = assertNotNull(document.get("paths").get("/api/auth/me").get("put"))
        assertEquals(
            setOf("200", "400", "401", "403", "503"),
            operation
                .get("responses")
                .properties()
                .map { it.key }
                .toSet(),
        )
        val responseContent =
            operation
                .get("responses")
                .get("200")
                .get("content")
        assertTrue(responseContent.properties().isNotEmpty())
        responseContent.properties().forEach { media ->
            assertTrue(
                media.value
                    .get("schema")
                    .get("${'$'}ref")
                    .asText()
                    .endsWith("/AccountResponse"),
            )
        }
        val requestSchema =
            operation
                .get("requestBody")
                .get("content")
                .get("application/json")
                .get("schema")
        val requestName = requestSchema.get("${'$'}ref").asText().substringAfterLast('/')
        val properties =
            document
                .get("components")
                .get("schemas")
                .get(requestName)
                .get("properties")
        assertEquals(setOf("displayName"), properties.properties().map { it.key }.toSet())
        assertTrue(
            properties
                .get("displayName")
                .get("description")
                .asText()
                .contains("50"),
        )

        val preflight =
            mvc
                .perform(
                    options("/api/auth/me")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "authorization,content-type"),
                ).andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals(ORIGIN, preflight.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", preflight.getHeader("Access-Control-Allow-Credentials"))
        val methods = assertNotNull(preflight.getHeader("Access-Control-Allow-Methods")).split(',').map(String::trim)
        assertTrue("PUT" in methods)
        assertFalse("PATCH" in methods)
        mvc
            .perform(
                options("/api/auth/me").header("Origin", "$ORIGIN.evil").header("Access-Control-Request-Method", "PUT"),
            ).andExpect(status().isForbidden)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SyntheticSigning {
        @Bean
        @Primary
        fun syntheticAccessTokens(): AccessTokenPort =
            Rs256AccessTokenAdapter(
                keys.private,
                mapOf("synthetic-profile" to keys.public),
                "synthetic-profile",
                Clock.systemUTC(),
                "https://api.synthetic.example",
                "meetme-web",
            )
    }

    companion object {
        private const val ORIGIN = "https://app.synthetic.example"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val redis = GenericContainer(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379).apply { start() }
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

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
        fun closeResources() {
            redis.stop()
            postgres.stop()
        }
    }
}
