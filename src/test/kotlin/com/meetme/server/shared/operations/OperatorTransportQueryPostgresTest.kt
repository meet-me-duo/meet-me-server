package com.meetme.server.shared.operations

import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
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
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** v2 safe public projection over real PostgreSQL. Counts describe local client lifecycles only. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OperatorTransportQueryPostgresTest.IdentityConfiguration::class)
class OperatorTransportQueryPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper
    private val room = UUID(0, 810)
    private val run = UUID(0, 811)
    private val invocation = UUID(0, 812)
    private val otherInvocation = UUID(0, 813)
    private val attempts = (1..3).map { UUID(0, 820L + it) }
    private val at = Instant.parse("2026-10-17T12:00:00Z")

    @BeforeEach
    fun seed() {
        jdbc.execute("TRUNCATE TABLE meeting_rooms,operations_access_audits CASCADE")
        jdbc.update(
            """INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,manual_only,collection_status,created_at,invite_code)
            VALUES (?,'synthetic-private','REMOTE','Asia/Seoul','2026-10-18','2026-10-20','HOST_SPECIFIED',true,'COLLECTING',?,'transportquery00000001')""",
            room,
            Timestamp.from(at),
        )
        val batch = UUID(0, 814)
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", batch, room, Timestamp.from(at))
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'ANALYSIS_DELAYED',?,99)",
            run,
            room,
            batch,
            Timestamp.from(at),
        )
        listOf(invocation, otherInvocation).forEachIndexed { i, id ->
            jdbc.update(
                """INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,owner_token,gemini_attempts,luna_attempts,policy_version)
                VALUES (?,?,?,?,?,?,2,1,'bounded-luna-v1')""",
                id,
                run,
                i + 1,
                Timestamp.from(at.plusSeconds(i.toLong())),
                Timestamp.from(at.plusSeconds(60)),
                UUID(0, 899),
            )
        }
        attempts.forEachIndexed { i, id ->
            val provider = if (i == 2) "OPENAI" else "GEMINI"
            val model = if (i == 2) "gpt-6-luna" else "gemini-3.8-flash"
            jdbc.update(
                """INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,provider,model,policy_version,invocation_id)
                VALUES (?,?,?,?,?,?,'bounded-luna-v1',?)""",
                id,
                run,
                i + 1,
                Timestamp.from(at),
                provider,
                model,
                invocation,
            )
        }
    }

    @Test
    fun `missing transport stays unknown and coverage uses recorded admissions rather than zero client calls`() {
        val detail = read("/api/operations/invocations/$invocation")
        assertCoverage(obj(detail, "invocation"), 0, 3, null, null, 0)
        objects(detail, "attempts").forEach {
            assertNull(it["parser_dispatched"])
            assertNull(it["client_calls"])
        }
    }

    @Test
    fun `complete observations distinguish not dispatched parser only and settled HTTP error call`() {
        observe(0, false, emptyList())
        observe(1, true, emptyList())
        observe(2, true, listOf(listOf(1, 123L, "COMPLETED", 503)))
        val detail = read("/api/operations/invocations/$invocation")
        assertCoverage(obj(detail, "invocation"), 3, 0, 2, 1, 1)
        val rows = objects(detail, "attempts")
        assertEquals(listOf(false, true, true), rows.map { it["parser_dispatched"] })
        assertEquals(emptyList(), objects(rows[0], "client_calls"))
        assertEquals(emptyList(), objects(rows[1], "client_calls"))
        val call = objects(rows[2], "client_calls").single()
        assertEquals(setOf("call_number", "elapsed_nanos", "outcome", "http_status"), call.keys)
        assertEquals(1L, number(call, "call_number"))
        assertEquals(123L, number(call, "elapsed_nanos"))
        assertEquals("COMPLETED", call["outcome"])
        assertEquals(503L, number(call, "http_status"))
        val roomHistory = read("/api/operations/rooms/$room")
        val historical = objects(roomHistory, "invocations").single { it["id"] == invocation.toString() }
        assertCoverage(historical, 3, 0, 2, 1, 1)
    }

    @Test
    fun `partial observation exposes known only subtotal and cannot claim complete dispatch or call counts`() {
        observe(0, true, listOf(listOf(1, 77L, "FAILED", null)))
        val detail = read("/api/operations/invocations/$invocation")
        assertCoverage(obj(detail, "invocation"), 1, 2, null, null, 1)
        val rows = objects(detail, "attempts")
        assertEquals("FAILED", objects(rows[0], "client_calls").single()["outcome"])
        assertNull(objects(rows[0], "client_calls").single()["http_status"])
        assertNull(rows[1]["client_calls"])
        assertNull(rows[2]["client_calls"])
        assertCoverage(obj(read("/api/operations/invocations/$otherInvocation"), "invocation"), 0, 3, null, null, 0)
    }

    @Test
    fun `OpenAPI exposes only bounded local client call fields and nullable transport facts`() {
        val document = read("/v3/api-docs")
        val paths = obj(document, "paths").filterKeys { it.startsWith("/api/operations") }
        assertEquals(
            setOf("/api/operations/rooms", "/api/operations/rooms/{roomId}", "/api/operations/invocations/{invocationId}"),
            paths.keys,
        )
        paths.values.forEach { path ->
            @Suppress("UNCHECKED_CAST")
            assertEquals(setOf("get"), (path as Map<String, Any?>).keys)
        }
        val schemas = obj(obj(document, "components"), "schemas")
        val invocationFields = obj(obj(schemas, "InvocationSummaryResponse"), "properties")
        assertEquals(
            setOf(
                "id",
                "run_id",
                "run_version",
                "started_at",
                "deadline_at",
                "finished_at",
                "winner_attempt_id",
                "gemini_admitted_attempts",
                "luna_admitted_attempts",
                "execution_ordinal",
                "revision_linked",
                "recorded_elapsed_millis",
                "parser_dispatch_count",
                "http_request_count",
                "client_call_count",
                "known_client_call_count",
                "transport_observed_attempt_count",
                "transport_unknown_attempt_count",
            ),
            invocationFields.keys,
        )
        val attemptFields = obj(obj(schemas, "AttemptSummaryResponse"), "properties")
        assertEquals(
            setOf(
                "id",
                "attempt_number",
                "provider",
                "model",
                "policy_version",
                "started_at",
                "finished_at",
                "failure_kind",
                "input_tokens",
                "output_tokens",
                "estimated_cost_usd",
                "recorded_elapsed_millis",
                "role",
                "parser_dispatched",
                "client_calls",
            ),
            attemptFields.keys,
        )
        val callRef = obj(obj(attemptFields, "client_calls"), "items").getValue("\$ref") as String
        val callFields = obj(obj(schemas, callRef.substringAfterLast('/')), "properties")
        assertEquals(setOf("call_number", "elapsed_nanos", "outcome", "http_status"), callFields.keys)
        val output = Path.of("build", "issue-110.openapi.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, mapper.writeValueAsString(document))
    }

    private fun observe(
        index: Int,
        dispatched: Boolean,
        calls: List<List<Any?>>,
    ) {
        val provider = if (index == 2) "OPENAI" else "GEMINI"
        jdbc.update(
            "INSERT INTO parser_transport_observations(attempt_id,invocation_id,provider,parser_dispatched,call_count) VALUES (?,?,?,?,?)",
            attempts[index],
            invocation,
            provider,
            dispatched,
            calls.size,
        )
        calls.forEach { call ->
            jdbc.update(
                "INSERT INTO parser_transport_client_calls(attempt_id,call_number,elapsed_nanos,outcome,http_status) VALUES (?,?,?,?,?)",
                attempts[index],
                call[0],
                call[1],
                call[2],
                call[3],
            )
        }
    }

    private fun assertCoverage(
        row: Map<String, Any?>,
        observed: Long,
        unknown: Long,
        dispatch: Long?,
        calls: Long?,
        knownCalls: Long,
    ) {
        assertEquals(observed, number(row, "transport_observed_attempt_count"))
        assertEquals(unknown, number(row, "transport_unknown_attempt_count"))
        assertEquals(dispatch, (row["parser_dispatch_count"] as Number?)?.toLong())
        assertEquals(calls, (row["client_call_count"] as Number?)?.toLong())
        assertEquals(knownCalls, number(row, "known_client_call_count"))
        assertNull(row["http_request_count"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(path: String): Map<String, Any?> {
        val response = mvc.perform(get(path)).andReturn().response
        assertEquals(200, response.status)
        if (path.startsWith("/api/operations")) assertEquals("no-store", response.getHeader("Cache-Control"))
        assertFalse(response.contentAsString.contains("synthetic-private"))
        return mapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(
        row: Map<String, Any?>,
        key: String,
    ) = row.getValue(key) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun objects(
        row: Map<String, Any?>,
        key: String,
    ) = row.getValue(key) as List<Map<String, Any?>>

    private fun number(
        row: Map<String, Any?>,
        key: String,
    ) = (row.getValue(key) as Number).toLong()

    @TestConfiguration
    class IdentityConfiguration {
        @Bean @Primary
        fun identity(): OperatorIdentityPort = OperatorIdentityPort { OPERATOR }
    }

    companion object {
        private val OPERATOR = UUID(0, 110)
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.operations.enabled") { true }
            registry.add("meetme.operations.operator-user-ids") { OPERATOR.toString() }
            registry.add("meetme.operations.cleanup-enabled") { false }
        }

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
