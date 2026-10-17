package com.meetme.server.shared.operations

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.shared.application.port.output.operations.OperationsQueryRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real socket HTTP, actual #109 request authentication/interceptor, actual #110 service/audit and PostgreSQL.
 * Only AccessTokenPort is replaced. The JDBC repository spy observes calls without replacing behavior.
 * Synthetic token labels exercise the #109 port failure contract, not JWT cryptographic verification.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "meetme.operations.enabled=true",
        "meetme.operations.operator-user-ids=00000000-0000-0000-0000-000000000110",
    ],
)
class OperatorAccountHttpPostgresTest : OperatorAccountHttpFixture() {
    @Test
    fun `absent Authorization cannot use caller identity headers or guest HOST cookie`() {
        val response = request(null, extra = listOf("X-User-Id" to OPERATOR.toString(), "Cookie" to "meet_me_guest=synthetic-host"))
        denied(response, 401)
        assertEquals(0, tokens.verifications.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["synthetic-invalid", "synthetic-expired"])
    fun `invalid or expired bearer never degrades into guest or unavailable`(token: String) {
        denied(request("Bearer $token"), 401)
        assertEquals(1, tokens.verifications.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["Basic synthetic-operator", "Bearer", "Bearer synthetic operator", "Bearer synthetic-operator,"])
    fun `malformed bearer is rejected before token verification`(header: String) {
        denied(request(header), 401)
        assertEquals(0, tokens.verifications.get())
    }

    @Test
    fun `duplicate Authorization fields reject even a valid operator token`() {
        denied(request("Bearer synthetic-operator", extra = listOf("Authorization" to "Bearer synthetic-operator")), 401)
        assertEquals(0, tokens.verifications.get())
    }

    @Test
    fun `authentication infrastructure failure remains unavailable and audited once`() {
        denied(request("Bearer synthetic-unavailable"), 503, "UNAVAILABLE")
        assertEquals(1, tokens.verifications.get())
    }

    @Test
    fun `verified ordinary member is forbidden without reading business projections`() {
        denied(request("Bearer synthetic-member"), 403, actor = MEMBER)
    }

    @Test
    fun `verified account owning a real HOST participant is still not an operator`() {
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,'synthetic-host-name',now())", HOST)
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at,invite_code)
            VALUES (?,'synthetic-private-purpose','REMOTE','Asia/Seoul','2026-10-18','2026-10-20',
                'HOST_SPECIFIED',true,'COLLECTING',now(),'syntheticinvitecode110')
            """.trimIndent(),
            ROOM,
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name) VALUES (?,?,?,'HOST',now(),'synthetic-host-name')",
            UUID(0, 114),
            ROOM,
            HOST,
        )
        denied(request("Bearer synthetic-host", "/api/operations/rooms/$ROOM"), 403, actor = HOST, target = ROOM)
    }

    @Test
    fun `allowed verified account gets real safe summary and exactly one durable allowed audit`() {
        val response = request("Bearer synthetic-operator")
        assertEquals(200, response.statusCode(), response.body())
        safeHeaders(response)
        val body = mapper.readValue(response.body(), Map::class.java)
        assertEquals(setOf("items", "next_cursor"), body.keys)
        assertEquals(emptyList<Any>(), body["items"])
        assertEquals(null, body["next_cursor"])
        audit("ALLOWED", OPERATOR, null)
        assertEquals(1, tokens.verifications.get(), "The verified request principal must be cached within this request")
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["meetme.operations.operator-user-ids=00000000-0000-0000-0000-000000000110"],
)
class OperatorAccountDefaultDisabledHttpTest : OperatorAccountHttpFixture() {
    @Test
    fun `feature defaults disabled even for a verified allowlisted account`() {
        denied(request("Bearer synthetic-operator"), 403, actor = OPERATOR)
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["meetme.operations.enabled=true"],
)
class OperatorAccountEmptyAllowlistHttpTest : OperatorAccountHttpFixture() {
    @Test
    fun `empty allowlist defaults deny even for a valid account`() {
        denied(request("Bearer synthetic-operator"), 403, actor = OPERATOR)
    }
}

@Import(OperatorAccountHttpFixture.TokenConfiguration::class)
abstract class OperatorAccountHttpFixture {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var mapper: ObjectMapper

    @Autowired
    protected lateinit var tokens: SyntheticAccessTokens

    @MockitoSpyBean
    private lateinit var query: OperationsQueryRepository

    @BeforeEach
    fun resetSyntheticRowsAndObservations() {
        jdbc.execute("TRUNCATE TABLE meeting_rooms, account_users, operations_access_audits CASCADE")
        clearInvocations(query)
        tokens.verifications.set(0)
    }

    protected fun request(
        authorization: String?,
        path: String = "/api/operations/rooms",
        extra: List<Pair<String, String>> = emptyList(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10)).GET()
        authorization?.let { builder.header("Authorization", it) }
        extra.forEach { (key, value) -> builder.header(key, value) }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun denied(
        response: HttpResponse<String>,
        status: Int,
        outcome: String = "DENIED",
        actor: UUID? = null,
        target: UUID? = null,
    ) {
        assertEquals(status, response.statusCode(), response.body())
        safeHeaders(response)
        val body = mapper.readValue(response.body(), Map::class.java)
        assertEquals(setOf("code", "title", "status", "detail"), body.keys)
        assertEquals(status, (body["status"] as Number).toInt())
        assertEquals(
            when (status) {
                401 -> "OPERATOR_UNAUTHENTICATED"
                403 -> "OPERATOR_FORBIDDEN"
                else -> "OPERATOR_UNAVAILABLE"
            },
            body["code"],
        )
        listOf("synthetic", "Bearer", "Authorization", "/api/", "AUTH_", "private", HOST.toString()).forEach {
            assertFalse(response.body().contains(it), "Protected response contains forbidden marker: $it")
        }
        audit(outcome, actor, target)
        verifyNoInteractions(query)
    }

    protected fun safeHeaders(response: HttpResponse<String>) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElse(null))
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null))
        assertFalse(response.headers().firstValue("Set-Cookie").isPresent)
        assertTrue(
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .contains("json"),
        )
    }

    protected fun audit(
        outcome: String,
        actor: UUID?,
        target: UUID?,
    ) {
        val rows = jdbc.queryForList("SELECT actor_user_id,route,target_id,outcome FROM operations_access_audits")
        assertEquals(1, rows.size, "Every protected HTTP request must create exactly one durable audit")
        assertEquals(actor, rows.single()["actor_user_id"])
        assertEquals(if (target == null) "ROOM_LIST" else "ROOM_DETAIL", rows.single()["route"])
        assertEquals(target, rows.single()["target_id"])
        assertEquals(outcome, rows.single()["outcome"])
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TokenConfiguration {
        @Bean
        @Primary
        fun syntheticAccessTokens() = SyntheticAccessTokens()
    }

    class SyntheticAccessTokens : AccessTokenPort {
        val verifications = AtomicInteger()

        override fun issue(principal: AccountPrincipal): String = error("No token issuance in the operations contract")

        override fun verify(token: String): AccountPrincipal {
            verifications.incrementAndGet()
            return when (token) {
                "synthetic-operator" -> AccountPrincipal(OPERATOR, "synthetic-operator-name")
                "synthetic-member" -> AccountPrincipal(MEMBER, "synthetic-member-name")
                "synthetic-host" -> AccountPrincipal(HOST, "synthetic-host-name")
                "synthetic-unavailable" -> throw AuthFailure("AUTH_UNAVAILABLE")
                else -> throw AuthFailure("AUTH_ACCESS_INVALID")
            }
        }
    }

    companion object {
        val OPERATOR: UUID = UUID(0, 0x110)
        val MEMBER: UUID = UUID(0, 0x111)
        val HOST: UUID = UUID(0, 0x112)
        val ROOM: UUID = UUID(0, 0x113)
        private val HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.operations.cleanup-enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.auth.signing-key-id") { "" }
            registry.add("meetme.auth.signing-private-key-path") { "" }
            registry.add("meetme.auth.verification-jwks-path") { "" }
        }
    }
}
