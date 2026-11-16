package com.meetme.server.shared.operations

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.account.domain.AccountPrincipal
import com.meetme.server.account.domain.AuthFailure
import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandPort
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue236 public HTTP boundary: actual bearer parser, operator gate, controller, filter and durable PG audit.
 * Synthetic AccessTokenPort labels exercise verified-principal outcomes, not JWT cryptography. Only business
 * enqueue is replaced; its source/state/atomicity contract is tested independently by the persistence designer.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "meetme.operations.enabled=true",
        "meetme.operations.operator-user-ids=00000000-0000-0000-0000-000000002361",
    ],
)
@Import(OperatorReanalysisHttpSecurityTest.BoundaryConfiguration::class)
class OperatorReanalysisHttpSecurityTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var command: RecordingCommand

    @Autowired
    private lateinit var tokens: SyntheticAccessTokens

    @BeforeEach
    fun resetBoundaryObservations() {
        jdbc.execute("TRUNCATE TABLE meeting_rooms,account_users,operations_access_audits CASCADE")
        command.calls.clear()
        command.auditCountsBeforeCommand.clear()
        command.failure = null
        tokens.verifications.set(0)
    }

    @Test
    fun `ORH01 missing bearer cannot use actor headers or guest HOST credential and never parses malformed body`() {
        val response =
            request(
                body = "synthetic-private-invalid-json",
                authorization = emptyList(),
                path = "/api/operations/rooms/synthetic-private-invalid-target/analyses",
                extra = listOf("X-User-Id" to OPERATOR.toString(), "Cookie" to "meet_me_guest=synthetic-host"),
            )
        problem(response, 401, "OPERATOR_UNAUTHENTICATED")
        assertEquals(0, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        audit("DENIED", null, null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Basic synthetic-operator", "Bearer", "Bearer synthetic operator", "Bearer synthetic-operator,"])
    fun `ORH02 malformed bearer is rejected before verification body decoding or business access`(header: String) {
        val response = request(body = "synthetic-private-invalid-json", authorization = listOf(header))
        problem(response, 401, "OPERATOR_UNAUTHENTICATED")
        assertEquals(0, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        audit("DENIED", null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["synthetic-invalid", "synthetic-expired"])
    fun `ORH03 invalid or expired token cannot fall back to guest ownership`(token: String) {
        problem(request(authorization = listOf("Bearer $token")), 401, "OPERATOR_UNAUTHENTICATED")
        assertEquals(1, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        audit("DENIED", null)
    }

    @Test
    fun `ORH04 duplicate Authorization rejects a valid operator token without verifying either field`() {
        val response = request(authorization = listOf("Bearer synthetic-operator", "Bearer synthetic-operator"))
        problem(response, 401, "OPERATOR_UNAUTHENTICATED")
        assertEquals(0, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        audit("DENIED", null)
    }

    @Test
    fun `ORH05 authentication infrastructure failure stays unavailable and audited`() {
        problem(request(authorization = listOf("Bearer synthetic-unavailable")), 503, "OPERATOR_UNAVAILABLE")
        assertEquals(1, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        audit("UNAVAILABLE", null)
    }

    @Test
    fun `ORH06 ordinary verified member is forbidden before malformed JSON validation`() {
        val response = request(body = "synthetic-private-invalid-json", authorization = listOf("Bearer synthetic-member"))
        problem(response, 403, "OPERATOR_FORBIDDEN")
        assertTrue(command.calls.isEmpty())
        audit("DENIED", MEMBER)
    }

    @Test
    fun `ORH07 a real HOST account cannot gain operator mutation permission`() {
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,'synthetic-private-host',now())", HOST)
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
                search_range_source,manual_only,collection_status,created_at,invite_code)
            VALUES (?,'synthetic-private-purpose','REMOTE','Asia/Seoul','2039-02-03','2039-02-06',
                'HOST_SPECIFIED',true,'COLLECTING',now(),'synthetic236hostinvite')
            """.trimIndent(),
            ROOM,
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name) VALUES (?,?,?,'HOST',now(),'synthetic-private-host')",
            UUID(0, 0x2370),
            ROOM,
            HOST,
        )
        problem(request(authorization = listOf("Bearer synthetic-host")), 403, "OPERATOR_FORBIDDEN")
        assertTrue(command.calls.isEmpty())
        audit("DENIED", HOST)
    }

    @Test
    fun `ORH08 verified operator obtains only the immutable acceptance receipt after durable audit`() {
        val response = request()
        assertEquals(202, response.statusCode(), response.body())
        privacy(response)
        val body = document(response)
        assertEquals(RECEIPT_FIELDS, body.keys)
        assertEquals(REQUEST_ID.toString(), body["request_id"])
        assertEquals(SOURCE.toString(), body["source_analysis_id"])
        assertEquals(ANALYSIS.toString(), body["analysis_id"])
        assertEquals(8, (body["accepted_state_version"] as Number).toInt())
        assertEquals(AT.toString(), body["accepted_at"])
        assertEquals("schedule-dsl-evidence-response-6", body["analysis_contract_version"])
        assertEquals(listOf(Triple(OPERATOR, ROOM, OperatorReanalysisRequest(REQUEST_ID, SOURCE, 7))), command.calls)
        assertEquals(listOf(1), command.auditCountsBeforeCommand, "Admission audit must already be durable when business access begins")
        audit("ALLOWED", OPERATOR)
        assertEquals(1, tokens.verifications.get(), "The real request principal is cached, not reverified by audit/filter")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "empty", "array", "null-root", "malformed", "trailing", "duplicate", "unknown", "actor-override",
            "contract-override", "raw-text", "missing", "bad-uuid", "short-uuid", "numeric-uuid", "null-uuid",
            "bad-source-uuid", "short-source-uuid",
            "negative-version", "fraction-version", "decimal-version", "string-version", "boolean-version",
            "overflow-version", "null-version", "oversize",
        ],
    )
    fun `ORH09 strict authorized request grammar rejects malformed or unsupported data without business access`(kind: String) {
        val body =
            when (kind) {
                "empty" -> ""
                "array" -> "[]"
                "null-root" -> "null"
                "malformed" -> "{"
                "trailing" -> VALID + " null"
                "duplicate" -> VALID.dropLast(1) + ",\"expected_state_version\":7}"
                "unknown" -> VALID.dropLast(1) + ",\"unexpected\":true}"
                "actor-override" -> VALID.dropLast(1) + ",\"actor_user_id\":\"$OPERATOR\"}"
                "contract-override" -> VALID.dropLast(1) + ",\"analysis_contract_version\":\"synthetic-private-version\"}"
                "raw-text" -> VALID.dropLast(1) + ",\"raw_text\":\"synthetic-private-input\"}"
                "missing" -> "{\"request_id\":\"$REQUEST_ID\",\"source_analysis_id\":\"$SOURCE\"}"
                "bad-uuid" -> VALID.replace(REQUEST_ID.toString(), "synthetic-private-invalid-uuid")
                "short-uuid" -> VALID.replace(REQUEST_ID.toString(), "1-1-1-1-1")
                "numeric-uuid" -> VALID.replace("\"$REQUEST_ID\"", "123")
                "null-uuid" -> VALID.replace("\"$SOURCE\"", "null")
                "bad-source-uuid" -> VALID.replace(SOURCE.toString(), "synthetic-private-invalid-source")
                "short-source-uuid" -> VALID.replace(SOURCE.toString(), "1-1-1-1-1")
                "negative-version" -> version("-1")
                "fraction-version" -> version("1.25")
                "decimal-version" -> version("7.0")
                "string-version" -> version("\"7\"")
                "boolean-version" -> version("true")
                "overflow-version" -> version("9223372036854775808")
                "null-version" -> version("null")
                "oversize" -> VALID.padEnd(8193, ' ')
                else -> error("Unknown frozen grammar case")
            }
        problem(request(body), 400, "OPERATOR_INVALID_QUERY")
        assertTrue(command.calls.isEmpty())
        audit("INVALID_QUERY", OPERATOR)
    }

    @Test
    fun `ORH10 request at the 8192 byte boundary and canonical uppercase UUIDs are accepted`() {
        val body = VALID.replace(SOURCE.toString(), SOURCE.toString().uppercase()).padEnd(8192, ' ')
        assertEquals(8192, body.toByteArray(StandardCharsets.UTF_8).size)
        val response = request(body, path = "/api/operations/rooms/${ROOM.toString().uppercase()}/analyses")
        assertEquals(202, response.statusCode(), response.body())
        privacy(response)
        assertEquals(
            SOURCE,
            command.calls
                .single()
                .third.sourceAnalysisId,
        )
        audit("ALLOWED", OPERATOR)
    }

    @ParameterizedTest
    @ValueSource(strings = ["synthetic-invalid-id", "1-1-1-1-1"])
    fun `ORH11 authorized noncanonical target UUID never reaches business access`(roomId: String) {
        problem(request(path = "/api/operations/rooms/$roomId/analyses"), 400, "OPERATOR_INVALID_QUERY")
        assertTrue(command.calls.isEmpty())
        audit("INVALID_QUERY", OPERATOR, null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "null", "trailing-slash", "unapproved", "duplicate", "combined"])
    fun `ORH12 only one exact approved Origin may authorize a mutation`(kind: String) {
        val origins =
            when (kind) {
                "absent" -> emptyList()
                "null" -> listOf("null")
                "trailing-slash" -> listOf("$ORIGIN/")
                "unapproved" -> listOf("https://unapproved.example")
                "duplicate" -> listOf(ORIGIN, ORIGIN)
                "combined" -> listOf("$ORIGIN, $ORIGIN")
                else -> error("Unknown frozen Origin case")
            }
        problem(request(origins = origins), 403, "OPERATOR_FORBIDDEN")
        assertTrue(command.calls.isEmpty())
        audit("DENIED", OPERATOR)
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPERATOR_NOT_FOUND", "OPERATOR_REANALYSIS_CONFLICT", "OPERATOR_REANALYSIS_UNSUPPORTED_INPUT"])
    fun `ORH13 business not found and conflict statuses survive fixed operator response sanitizing`(name: String) {
        val code = OperatorQueryError.valueOf(name)
        command.failure = OperatorQueryException(code)
        problem(request(), code.status, name)
        assertEquals(1, command.calls.size)
        audit("ALLOWED", OPERATOR)
    }

    @Test
    fun `ORH14 business rate rejection retains 429 and bounded Retry After`() {
        command.failure = OperatorQueryException(OperatorQueryError.OPERATOR_REANALYSIS_RATE_LIMITED, retryAfterSeconds = 9)
        val response = request()
        problem(response, 429, "OPERATOR_REANALYSIS_RATE_LIMITED")
        assertEquals("9", response.headers().firstValue("Retry-After").orElse(null))
        audit("ALLOWED", OPERATOR)
    }

    @Test
    fun `ORH15 unexpected business failures are fixed unavailable errors without a second access audit`() {
        command.failure = IllegalStateException("synthetic-private-storage-error")
        problem(request(), 503, "OPERATOR_UNAVAILABLE")
        audit("ALLOWED", OPERATOR)
    }

    @Test
    fun `ORH16 allowed POST browser preflight performs no authentication audit or business access`() {
        val response = preflight(ORIGIN)
        assertTrue(response.statusCode() in 200..299, response.body())
        privacy(response)
        assertEquals(ORIGIN, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null))
        assertTrue(
            response
                .headers()
                .firstValue("Access-Control-Allow-Methods")
                .orElse("")
                .split(',')
                .any { it.trim() == "POST" },
        )
        assertEquals("", response.body())
        assertEquals(0, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        assertEquals(0, auditCount())
    }

    @Test
    fun `ORH17 unapproved preflight Origin is rejected without business access`() {
        val response = preflight("https://unapproved.example")
        assertEquals(403, response.statusCode())
        privacy(response)
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent)
        assertEquals(0, tokens.verifications.get())
        assertTrue(command.calls.isEmpty())
        assertEquals(0, auditCount())
    }

    @Test
    fun `ORH18 native OpenAPI publishes the exact protected command and immutable receipt contract`() {
        val response =
            HTTP.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode(), response.body())
        val spec = document(response)
        val paths = obj(spec, "paths")
        val post = obj(obj(paths, "/api/operations/rooms/{roomId}/analyses"), "post")
        val body = obj(post, "requestBody")
        assertEquals(true, body["required"])
        val requestSchema = schema(spec, obj(obj(obj(body, "content"), "application/json"), "schema"))
        assertEquals(setOf("request_id", "source_analysis_id", "expected_state_version"), obj(requestSchema, "properties").keys)
        assertEquals(setOf("request_id", "source_analysis_id", "expected_state_version"), (requestSchema["required"] as List<*>).toSet())
        assertEquals(false, requestSchema["additionalProperties"])
        val version = obj(obj(requestSchema, "properties"), "expected_state_version")
        assertEquals("integer", version["type"])
        assertEquals("int64", version["format"])
        assertEquals(0, (version["minimum"] as Number).toInt())
        val responses = obj(post, "responses")
        assertTrue(responses.keys.containsAll(setOf("202", "400", "401", "403", "404", "409", "429", "503")))
        val accepted = obj(obj(obj(responses, "202"), "content"), "application/json")
        val receipt = schema(spec, obj(accepted, "schema"))
        assertEquals(RECEIPT_FIELDS, obj(receipt, "properties").keys)
        assertEquals(RECEIPT_FIELDS, (receipt["required"] as List<*>).toSet())
        val summaries = obj(obj(spec, "components"), "schemas").values.filterIsInstance<Map<*, *>>()
        val room =
            summaries.single {
                (it["properties"] as? Map<*, *>)?.keys?.containsAll(setOf("host_submitted", "current_run_status")) == true
            }
        val metadata = room["properties"] as Map<*, *>
        assertTrue(
            metadata.keys.containsAll(
                setOf("active_analysis_id", "state_version", "current_analysis_confirmed", "has_open_revision_round"),
            ),
        )
        assertTrue(command.calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "2147483648", "9223372036854775807"])
    fun `ORH19 nonnegative Long state versions retain zero and values beyond Int without truncation`(value: String) {
        val response = request(version(value))
        assertEquals(202, response.statusCode(), response.body())
        privacy(response)
        assertEquals(
            value.toLong(),
            command.calls
                .single()
                .third.expectedStateVersion,
        )
        audit("ALLOWED", OPERATOR)
    }

    private fun request(
        body: String = VALID,
        authorization: List<String> = listOf("Bearer synthetic-operator"),
        origins: List<String> = listOf(ORIGIN),
        path: String = PATH,
        extra: List<Pair<String, String>> = emptyList(),
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        authorization.forEach { builder.header("Authorization", it) }
        origins.forEach { builder.header("Origin", it) }
        extra.forEach { (key, value) -> builder.header(key, value) }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun preflight(origin: String): HttpResponse<String> =
        HTTP.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$PATH"))
                .timeout(Duration.ofSeconds(10))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun problem(
        response: HttpResponse<String>,
        status: Int,
        code: String,
    ) {
        assertEquals(status, response.statusCode(), response.body())
        privacy(response)
        val body = document(response)
        assertEquals(setOf("code", "title", "status", "detail"), body.keys)
        assertEquals(code, body["code"])
        assertEquals(status, (body["status"] as Number).toInt())
        listOf("synthetic-private", "Bearer", "Authorization", "/api/", "AUTH_", "raw_text", "invite_code").forEach {
            assertFalse(response.body().contains(it), "Protected error contains forbidden marker: $it")
        }
    }

    private fun privacy(response: HttpResponse<String>) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElse(null))
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(null))
        assertFalse(response.headers().firstValue("Set-Cookie").isPresent)
    }

    private fun auditCount(): Int = jdbc.queryForObject("SELECT count(*) FROM operations_access_audits", Int::class.java)!!

    private fun audit(
        outcome: String,
        actor: UUID?,
        target: UUID? = ROOM,
    ) {
        val rows = jdbc.queryForList("SELECT route,target_id,actor_user_id,outcome FROM operations_access_audits")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("ROOM_REANALYSIS", row["route"])
        assertEquals(outcome, row["outcome"])
        assertEquals(actor, row["actor_user_id"])
        if (target == null) assertNull(row["target_id"]) else assertEquals(target, row["target_id"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun document(response: HttpResponse<String>): Map<String, Any?> =
        mapper.readValue(response.body(), Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun obj(
        value: Map<String, Any?>,
        key: String,
    ): Map<String, Any?> = value.getValue(key) as Map<String, Any?>

    private fun schema(
        spec: Map<String, Any?>,
        value: Map<String, Any?>,
    ): Map<String, Any?> {
        val ref = value["\$ref"] as? String ?: return value
        return obj(obj(obj(spec, "components"), "schemas"), ref.substringAfterLast('/'))
    }

    private fun version(raw: String): String = VALID.replace("\"expected_state_version\":7", "\"expected_state_version\":$raw")

    @TestConfiguration(proxyBeanMethods = false)
    class BoundaryConfiguration {
        @Bean
        @Primary
        fun reanalysisAccessTokens() = SyntheticAccessTokens()

        @Bean
        @Primary
        fun reanalysisRecordingCommand(jdbc: JdbcTemplate) = RecordingCommand(jdbc)
    }

    class SyntheticAccessTokens : AccessTokenPort {
        val verifications = AtomicInteger()

        override fun issue(principal: AccountPrincipal): String = error("No token issuance in this security contract")

        override fun verify(token: String): AccountPrincipal {
            verifications.incrementAndGet()
            return when (token) {
                "synthetic-operator" -> AccountPrincipal(OPERATOR, "synthetic-private-operator")
                "synthetic-member" -> AccountPrincipal(MEMBER, "synthetic-private-member")
                "synthetic-host" -> AccountPrincipal(HOST, "synthetic-private-host")
                "synthetic-unavailable" -> throw AuthFailure("AUTH_UNAVAILABLE")
                else -> throw AuthFailure("AUTH_ACCESS_INVALID")
            }
        }
    }

    class RecordingCommand(
        private val jdbc: JdbcTemplate,
    ) : OperatorReanalysisCommandPort {
        val calls = mutableListOf<Triple<UUID, UUID, OperatorReanalysisRequest>>()
        val auditCountsBeforeCommand = mutableListOf<Int>()
        var failure: RuntimeException? = null

        override fun enqueue(
            actorId: UUID,
            roomId: UUID,
            request: OperatorReanalysisRequest,
        ): OperatorReanalysisReceipt {
            auditCountsBeforeCommand += jdbc.queryForObject("SELECT count(*) FROM operations_access_audits", Int::class.java)!!
            calls += Triple(actorId, roomId, request)
            failure?.let { throw it }
            return OperatorReanalysisReceipt(
                request.requestId,
                request.sourceAnalysisId,
                ANALYSIS,
                8,
                AT,
                "schedule-dsl-evidence-response-6",
            )
        }
    }

    companion object {
        private val HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val OPERATOR = UUID(0, 0x2361)
        private val MEMBER = UUID(0, 0x2362)
        private val HOST = UUID(0, 0x2367)
        private val ROOM = UUID.fromString("123e4567-e89b-12d3-a456-426614172360")
        private val REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614172364")
        private val SOURCE = UUID.fromString("123e4567-e89b-12d3-a456-426614172365")
        private val ANALYSIS = UUID(0, 0x2366)
        private val AT = Instant.parse("2039-02-03T04:05:06Z")
        private const val ORIGIN = "https://app.meet-me.co.kr"
        private val PATH = "/api/operations/rooms/$ROOM/analyses"
        private val VALID = "{\"request_id\":\"$REQUEST_ID\",\"source_analysis_id\":\"$SOURCE\",\"expected_state_version\":7}"
        private val RECEIPT_FIELDS =
            setOf("request_id", "source_analysis_id", "analysis_id", "accepted_state_version", "accepted_at", "analysis_contract_version")

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
