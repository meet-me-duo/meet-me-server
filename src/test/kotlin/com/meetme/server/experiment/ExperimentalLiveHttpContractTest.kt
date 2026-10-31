package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Free HTTP contract check: no workers, real provider keys explicitly empty, no replacement AI beans. */
@SpringBootTest(
    classes = [MeetMeServerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "meetme.reliability.enabled=false", "meetme.rate-limit.enabled=false", "meetme.data-retention.enabled=false",
        "meetme.precheck.provider.enabled=false", "meetme.precheck.decision-routing.enabled=false",
        "meetme.precheck.classifier.enabled=false", "meetme.ai.budget.enabled=false",
        "meetme.precheck.evidence-enabled=true", "meetme.gemini.api-key=", "meetme.openai.api-key=", "meetme.typesafe.api-key=",
        "meetme.anonymous.allowed-origins=http://localhost:18931", "meetme.anonymous.cookie-secure=false", "server.address=127.0.0.1",
    ],
)
@Import(ExperimentalLiveHttpContractTest.LocalSecurity::class)
class ExperimentalLiveHttpContractTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun `actual HTTP routes accept evaluator commands and expose exact selection schemas without inference`() {
        val principal = AccountPrincipal(UUID.randomUUID(), "Synthetic dry host")
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,?,now())", principal.accountId, principal.displayName)
        val bearer = "Bearer ${tokens.issue(principal)}"
        val created =
            call(
                "POST",
                "/api/rooms",
                bearer = bearer,
                body =
                    mapOf(
                        "purpose" to "Synthetic HTTP contract only",
                        "meeting_mode" to "EITHER",
                        "manual_only" to true,
                        "search_start_date" to "2026-11-10",
                        "search_end_date" to "2026-11-11",
                    ),
            )
        assertEquals(201, created.status)
        val path = "/api/rooms/${created.body.path("invite_code").asText()}"
        repeat(3) { index ->
            val joined = call("POST", "$path/participants", body = mapOf("display_name" to "Dry participant $index"))
            assertEquals(201, joined.status)
            val cookie = requireNotNull(joined.cookie)
            val submitted =
                call(
                    "POST",
                    "$path/submission/checks",
                    cookie = cookie,
                    body =
                        mapOf(
                            "request_id" to UUID.randomUUID(),
                            "expected_revision" to 0,
                            "raw_text" to "11월 10일 오후 2시부터 5시까지 가능",
                        ),
                )
            assertEquals(202, submitted.status)
            val current = call("GET", "$path/submission/checks/current", cookie = cookie)
            assertEquals(200, current.status)
            assertEquals(
                1,
                current.body
                    .path("candidate")
                    .path("revision")
                    .asInt(),
            )
            assertEquals(
                "QUEUED",
                current.body
                    .path("check")
                    .path("status")
                    .asText(),
            )
        }
        val closed = call("POST", "$path/close", bearer = bearer, body = mapOf("confirm_early" to true))
        assertEquals(200, closed.status)
        assertEquals("CLOSED", closed.body.path("collection_status").asText())
        listOf("/candidates", "/candidates/unapplied-inputs", "/recommendations", "/result").forEach { route ->
            val response = call("GET", path + route, bearer = bearer)
            assertEquals(409, response.status)
            assertFalse(
                response.body
                    .path("code")
                    .asText()
                    .contains("RESOURCE"),
            )
        }
        val api = call("GET", "/v3/api-docs")
        assertEquals(200, api.status)
        (System.getProperty("meetme.experiment.openapi-output") ?: System.getenv("MEETME_OPENAPI_OUTPUT"))?.let { value ->
            val output =
                java.nio.file.Path
                    .of(value)
                    .toAbsolutePath()
            require(
                output.startsWith(
                    java.nio.file.Path
                        .of("/workspace/scratch/live-eval"),
                ),
            )
            java.nio.file.Files
                .createDirectories(output.parent)
            java.nio.file.Files
                .writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(api.body))
        }
        val paths = api.body.path("paths")
        assertTrue(paths.path("/api/rooms/{inviteCode}/submission/checks/{checkId}/answer").has("post"))
        assertTrue(paths.path("/api/rooms/{inviteCode}/candidates/unapplied-inputs").has("get"))
        assertTrue(paths.path("/api/rooms/{inviteCode}/recommendations/{optionId}/confirmation").has("post"))
        val schemas = api.body.path("components").path("schemas")
        assertTrue(schemas.path("RecommendationOptionResponse").path("properties").has("time_range"))
        assertFalse(schemas.path("RecommendationOptionResponse").path("properties").has("time_ranges"))
        listOf("analysis_id", "variant_id", "start_at", "end_at").forEach { field ->
            assertTrue(schemas.path("RecommendationConfirmationRequest").path("properties").has(field))
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM precheck_executions WHERE started_at IS NOT NULL", Int::class.java))
    }

    private data class Response(
        val status: Int,
        val body: JsonNode,
        val cookie: String?,
    )

    private fun call(
        method: String,
        path: String,
        bearer: String? = null,
        cookie: String? = null,
        body: Any? = null,
    ): Response {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
        bearer?.let { builder.header("Authorization", it) }
        cookie?.let { builder.header("Cookie", it) }
        if (method == "POST") builder.header("Origin", "http://localhost:18931").header("Content-Type", "application/json")
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Response(
            response.statusCode(),
            json.readTree(response.body()),
            response
                .headers()
                .allValues("set-cookie")
                .firstOrNull {
                    it.startsWith("meet_me_guest=")
                }?.substringBefore(';'),
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class LocalSecurity {
        @Bean @Primary
        fun dryClock(): Clock = Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), ZoneOffset.UTC)

        @Bean @Primary
        fun dryTokens(clock: Clock): AccessTokenPort {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            return Rs256AccessTokenAdapter(
                keys.private,
                mapOf("dry-local" to keys.public),
                "dry-local",
                clock,
                "https://synthetic.invalid",
                "meetme-web",
            )
        }
    }

    companion object {
        private val postgres by lazy {
            PostgreSQLContainer(
                "postgres:18-alpine",
            ).withLabel("meetme.experiment", "dry-contract").apply { start() }
        }
        private val redis by lazy {
            GenericContainer(
                DockerImageName.parse("redis:8-alpine"),
            ).withExposedPorts(6379).withLabel("meetme.experiment", "dry-contract").apply {
                start()
            }
        }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
            redis.stop()
        }
    }
}
