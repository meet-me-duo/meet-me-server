package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryProperties
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import com.meetme.server.shared.application.port.output.operations.OperatorIdentityPort
import com.meetme.server.shared.application.service.operations.OperatorAuditRetentionService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Issue #110 public contract, real PostgreSQL, synthetic rows, and a verified-identity test adapter only. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OperatorQueryPostgresTest.IdentityConfiguration::class)
class OperatorQueryPostgresTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var identity: TestIdentity

    @Autowired
    private lateinit var audit: OperatorAccessAuditPort

    private val at = Instant.parse("2026-10-17T10:00:00Z")
    private val room = id(100)
    private val run = id(200)
    private val oldInvocation = id(301)
    private val invocation = id(302)
    private val unfinishedInvocation = id(303)

    @BeforeEach
    fun resetSyntheticDatabase() {
        jdbc.execute("TRUNCATE TABLE meeting_rooms, guest_browser_sessions, operations_access_audits CASCADE")
        identity.actor = OPERATOR
    }

    @Test
    fun `caller controlled identity headers guest cookie and malformed targets never authenticate an operator`() {
        identity.actor = null
        listOf(
            "/api/operations/rooms?limit=private-invalid-limit",
            "/api/operations/rooms/private-invalid-target",
            "/api/operations/invocations/private-invalid-target",
        ).forEach { path ->
            val response =
                request(
                    get(path)
                        .header("X-User-Id", OPERATOR)
                        .header("X-Operator-Id", OPERATOR)
                        .header("Authorization", "Bearer synthetic-invalid-token")
                        .cookie(Cookie("meet_me_guest", "synthetic-host-cookie")),
                    401,
                )
            problem(response, "OPERATOR_UNAUTHENTICATED")
        }
        assertEquals(3, count("operations_access_audits"))
        assertEquals(0, count("meeting_rooms"))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_access_audits WHERE actor_user_id IS NOT NULL OR target_id IS NOT NULL",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `authenticated nonoperator gets indistinguishable forbidden for existing missing and malformed resources`() {
        seedRoom(room)
        identity.actor = id(999)
        listOf(room.toString(), id(998).toString(), "private-invalid-target").forEach { target ->
            problem(request(get("/api/operations/rooms/$target"), 403), "OPERATOR_FORBIDDEN")
        }
        assertEquals(setOf("DENIED"), jdbc.queryForList("SELECT outcome FROM operations_access_audits", String::class.java).toSet())
    }

    @Test
    fun `room list has exact safe projection and keyset pagination remains stable across newer inserts`() {
        listOf(id(101), id(102), id(103)).forEach { seedRoom(it) }
        val first = body(request(get("/api/operations/rooms").param("limit", "2")))
        assertEquals(setOf("items", "next_cursor"), first.keys)
        val firstItems = objects(first, "items")
        assertEquals(listOf(id(103).toString(), id(102).toString()), firstItems.map { it["id"] })
        firstItems.forEach { assertEquals(ROOM_FIELDS, it.keys) }
        val cursor = first["next_cursor"] as String
        assertTrue(cursor.length < 1024)
        seedRoom(id(104), at.plusSeconds(1))
        val second = body(request(get("/api/operations/rooms").param("limit", "2").param("cursor", cursor)))
        assertEquals(listOf(id(101).toString()), objects(second, "items").map { it["id"] })
        assertNull(second["next_cursor"])
    }

    @Test
    fun `room detail retains historical invocations and distinguishes revisions legacy attempts and current room state`() {
        seedHistory()
        seedParticipants()
        seedRevision(1, "QUEUED")
        seedRevision(2, "REUSED")
        seedRevision(3, null)
        val before = businessState()
        val first = body(request(get("/api/operations/rooms/$room").param("limit", "2")))
        assertEquals(setOf("room", "invocations", "next_cursor", "revision_counts", "legacy_attempt_count"), first.keys)
        val summary = child(first, "room")
        assertEquals(ROOM_FIELDS, summary.keys)
        assertEquals(2L, number(summary, "participant_count"))
        assertEquals(1L, number(summary, "submitted_participant_count"))
        assertEquals(true, summary["host_submitted"])
        assertEquals("ANALYSIS_DELAYED", summary["current_run_status"])
        assertEquals("2026-10-20", summary["search_end_date"])
        assertEquals(mapOf("open" to 1, "queued" to 1, "reused" to 1), child(first, "revision_counts"))
        assertEquals(1L, number(first, "legacy_attempt_count"))
        val invocations = objects(first, "invocations")
        assertEquals(listOf(6L, 4L), invocations.map { number(it, "run_version") })
        assertEquals(listOf(3L, 2L), invocations.map { number(it, "execution_ordinal") })
        invocations.forEach { assertInvocationUnknownCounts(it) }
        assertTrue(invocations.all { it["revision_linked"] == true })
        assertNull(invocations.first()["recorded_elapsed_millis"])
        assertEquals(5000L, number(invocations.last(), "recorded_elapsed_millis"))
        val second = body(request(get("/api/operations/rooms/$room").param("limit", "2").param("cursor", first["next_cursor"] as String)))
        assertEquals(listOf(oldInvocation.toString()), objects(second, "invocations").map { it["id"] })
        assertNull(second["next_cursor"])
        assertEquals(before, businessState())
    }

    @Test
    fun `current room policy and closure fields are mapped without implying historical setting snapshots`() {
        seedRoom(room)
        jdbc.update(
            """
            UPDATE meeting_rooms SET manual_only=false,expected_participants=2,submission_deadline=?,
            collection_status='CLOSED',closure_reason='DEADLINE',closed_at=? WHERE id=?
            """.trimIndent(),
            Timestamp.from(at.plusSeconds(3600)),
            Timestamp.from(at.plusSeconds(3600)),
            room,
        )
        val summary = child(body(request(get("/api/operations/rooms/$room"))), "room")
        assertEquals(false, summary["manual_only"])
        assertEquals(2L, number(summary, "expected_participants"))
        assertEquals("CLOSED", summary["collection_status"])
        assertEquals("DEADLINE", summary["closure_reason"])
        assertEquals(at.plusSeconds(3600), Instant.parse(summary["submission_deadline"] as String))
        assertEquals(at.plusSeconds(3600), Instant.parse(summary["closed_at"] as String))
        assertEquals("Asia/Seoul", summary["time_zone_id"])
        assertEquals("REMOTE", summary["meeting_mode"])
        assertNull(summary["current_run_status"])
    }

    @Test
    fun `invocation isolates attempts normalizes failure strings and preserves nullable usage per provider`() {
        seedHistory()
        val response = request(get("/api/operations/invocations/$invocation"))
        val detail = body(response)
        assertEquals(setOf("room_id", "invocation", "attempts", "usage_by_provider"), detail.keys)
        assertEquals(room.toString(), detail["room_id"])
        val summary = child(detail, "invocation")
        assertEquals(INVOCATION_FIELDS, summary.keys)
        assertEquals(4L, number(summary, "run_version"))
        assertEquals(2L, number(summary, "gemini_admitted_attempts"))
        assertEquals(1L, number(summary, "luna_admitted_attempts"))
        assertInvocationUnknownCounts(summary)
        val attempts = objects(detail, "attempts")
        assertEquals(listOf(7L, 8L, 9L), attempts.map { number(it, "attempt_number") })
        assertEquals(listOf("INITIAL", "RETRY", "FALLBACK"), attempts.map { it["role"] })
        assertEquals(listOf("OTHER", "PUBLICATION_FENCED", null), attempts.map { it["failure_kind"] })
        attempts.forEach {
            assertEquals(ATTEMPT_FIELDS, it.keys)
            assertNull(it["parser_dispatched"])
        }
        val usage = objects(detail, "usage_by_provider").associateBy { it["provider"] }
        assertEquals(setOf("GEMINI", "OPENAI"), usage.keys)
        val gemini = usage.getValue("GEMINI")
        assertEquals(USAGE_FIELDS, gemini.keys)
        assertEquals("RECORDED_ADMISSIONS", gemini["coverage_basis"])
        assertEquals(
            listOf(
                2L,
                1L,
                1L,
                1L,
                1L,
                1L,
                1L,
            ),
            listOf(
                "recorded_attempt_count",
                "input_known_count",
                "output_known_count",
                "cost_known_count",
                "input_unknown_count",
                "output_unknown_count",
                "cost_unknown_count",
            ).map {
                number(gemini, it)
            },
        )
        assertEquals(0L, number(gemini, "known_input_tokens"))
        assertEquals(2L, number(gemini, "known_output_tokens"))
        assertEquals(0, BigDecimal(gemini["known_cost_usd"] as String).compareTo(BigDecimal.ZERO))
        val openai = usage.getValue("OPENAI")
        assertEquals(1L, number(openai, "recorded_attempt_count"))
        listOf("input", "output", "cost").forEach {
            assertEquals(0L, number(openai, "${it}_known_count"))
            assertEquals(1L, number(openai, "${it}_unknown_count"))
        }
        listOf("known_input_tokens", "known_output_tokens", "known_cost_usd").forEach { assertNull(openai[it]) }
        assertNoSecrets(response)
    }

    @Test
    fun `unfinished invocation exposes unknown elapsed and no fabricated provider observations`() {
        seedHistory()
        val detail = body(request(get("/api/operations/invocations/$unfinishedInvocation")))
        val summary = child(detail, "invocation")
        assertNull(summary["finished_at"])
        assertNull(summary["winner_attempt_id"])
        assertNull(summary["recorded_elapsed_millis"])
        assertInvocationUnknownCounts(summary)
        assertTrue(objects(detail, "attempts").isEmpty())
        assertTrue(objects(detail, "usage_by_provider").isEmpty())
    }

    @Test
    fun `authorized invalid query and missing target use sanitized audited errors with no store`() {
        listOf(
            get("/api/operations/rooms").param("limit", "101"),
            get("/api/operations/rooms").param("cursor", "!!private-invalid-cursor!!"),
            get("/api/operations/rooms/private-invalid-target"),
            get("/api/operations/invocations/private-invalid-target"),
        ).forEach { problem(request(it, 400), "OPERATOR_INVALID_QUERY") }
        problem(request(get("/api/operations/rooms/${id(998)}"), 404), "OPERATOR_NOT_FOUND")
        problem(request(get("/api/operations/invocations/${id(998)}"), 404), "OPERATOR_NOT_FOUND")
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM operations_access_audits WHERE outcome='INVALID_QUERY'", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM operations_access_audits WHERE outcome='NOT_FOUND'", Int::class.java))
    }

    @Test
    fun `allowed response fails closed when PostgreSQL cannot persist audit`() {
        seedRoom(room)
        jdbc.execute("ALTER TABLE operations_access_audits ADD CONSTRAINT test_reject_operator_audit CHECK (false) NOT VALID")
        try {
            val response = request(get("/api/operations/rooms"), 503)
            problem(response, "OPERATOR_UNAVAILABLE")
            assertFalse(response.contentAsString.contains(room.toString()))
            assertEquals(0, count("operations_access_audits"))
        } finally {
            jdbc.execute("ALTER TABLE operations_access_audits DROP CONSTRAINT test_reject_operator_audit")
        }
    }

    @Test
    fun `durable audit has only six bounded fields and no foreign key dependence on business lifecycle`() {
        seedHistory()
        request(get("/api/operations/invocations/$invocation"))
        val columns =
            jdbc
                .queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='operations_access_audits'",
                    String::class.java,
                ).toSet()
        assertEquals(setOf("id", "occurred_at", "actor_user_id", "route", "target_id", "outcome"), columns)
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_name='operations_access_audits' AND constraint_type='FOREIGN KEY'",
                Int::class.java,
            ),
        )
        val row = jdbc.queryForMap("SELECT actor_user_id,route,target_id,outcome,occurred_at FROM operations_access_audits")
        assertEquals(OPERATOR, row["actor_user_id"])
        assertEquals(invocation, row["target_id"])
        assertEquals("INVOCATION_DETAIL", row["route"])
        assertEquals("ALLOWED", row["outcome"])
        assertNotNull(row["occurred_at"])
    }

    @Test
    fun `audit cleanup removes only older rows in bounded batches preserving cutoff and business rows`() {
        seedRoom(room)
        val now = at.plus(Duration.ofDays(31))
        val cutoff = now.minus(Duration.ofDays(30))
        listOf(cutoff.minusSeconds(3), cutoff.minusSeconds(2), cutoff.minusSeconds(1), cutoff, cutoff.plusSeconds(1)).forEach { occurred ->
            jdbc.update(
                "INSERT INTO operations_access_audits(id,occurred_at,actor_user_id,route,target_id,outcome) VALUES (?,?,?,'ROOM_LIST',NULL,'ALLOWED')",
                UUID.randomUUID(),
                Timestamp.from(occurred),
                OPERATOR,
            )
        }
        val properties = OperatorQueryProperties(auditCleanupBatchSize = 2)
        val service = OperatorAuditRetentionService(audit, properties, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(2, service.cleanup())
        assertEquals(3, count("operations_access_audits"))
        assertEquals(1, service.cleanup())
        assertEquals(2, count("operations_access_audits"))
        assertEquals(0, service.cleanup())
        assertEquals(1, count("meeting_rooms"))
        assertEquals(
            listOf(Timestamp.from(cutoff), Timestamp.from(cutoff.plusSeconds(1))),
            jdbc.queryForList("SELECT occurred_at FROM operations_access_audits ORDER BY occurred_at", Timestamp::class.java),
        )
    }

    @Test
    fun `OpenAPI documents all operator response errors and only safe DTO fields`() {
        val response = mvc.perform(get("/v3/api-docs")).andReturn().response
        val document = body(response)
        val paths = child(document, "paths")
        mapOf(
            "/api/operations/rooms" to setOf("200", "400", "401", "403", "503"),
            "/api/operations/rooms/{roomId}" to setOf("200", "400", "401", "403", "404", "503"),
            "/api/operations/invocations/{invocationId}" to setOf("200", "400", "401", "403", "404", "503"),
        ).forEach { (path, codes) ->
            val operation = child(child(paths, path), "get")
            assertEquals(codes, child(operation, "responses").keys)
        }
        val schemas = child(child(document, "components"), "schemas")
        listOf(
            "RoomSummaryResponse" to ROOM_FIELDS,
            "InvocationSummaryResponse" to INVOCATION_FIELDS,
            "AttemptSummaryResponse" to ATTEMPT_FIELDS,
            "ProviderUsageResponse" to USAGE_FIELDS,
        ).forEach { (schema, fields) -> assertEquals(fields, child(child(schemas, schema), "properties").keys) }
        val output = Path.of("build", "verification", "operator-query-openapi.json")
        Files.createDirectories(output.parent)
        Files.write(output, response.contentAsByteArray)
    }

    @Test
    fun `operator query routes reject mutation methods and still disable caching`() {
        request(post("/api/operations/rooms"), 405)
        assertEquals(0, count("meeting_rooms"))
    }

    private fun seedRoom(
        id: UUID,
        created: Instant = at,
    ) {
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,created_at,invite_code)
            VALUES (?,'synthetic-private-purpose','REMOTE','Asia/Seoul','2026-10-18','2026-10-20',
            'HOST_SPECIFIED',true,'COLLECTING',?,?)
            """.trimIndent(),
            id,
            Timestamp.from(created),
            id.toString().replace("-", "").takeLast(22),
        )
    }

    private fun seedHistory() {
        seedRoom(room)
        val batch = id(201)
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", batch, room, Timestamp.from(at))
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'ANALYSIS_DELAYED',?,99)",
            run,
            room,
            batch,
            Timestamp.from(at),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", run, room)
        seedInvocation(oldInvocation, 2, at, 2, 0, true)
        seedInvocation(invocation, 4, at.plusSeconds(60), 2, 1, true)
        seedInvocation(unfinishedInvocation, 6, at.plusSeconds(120), 0, 0, false)
        seedAttempt(id(401), null, 1, "GEMINI", 1000, 1000, "99.00", null)
        seedAttempt(id(402), oldInvocation, 2, "GEMINI", 20, 30, "0.1", null)
        seedAttempt(id(403), oldInvocation, 3, "GEMINI", null, null, null, null)
        seedAttempt(id(407), invocation, 7, "GEMINI", 0, null, "0", "synthetic-private-failure")
        seedAttempt(id(408), invocation, 8, "GEMINI", null, 2, null, "PUBLICATION_FENCED")
        seedAttempt(id(409), invocation, 9, "OPENAI", null, null, null, null)
        jdbc.update("UPDATE analysis_invocations SET winner_attempt_id=? WHERE id=?", id(408), invocation)
    }

    private fun seedInvocation(
        id: UUID,
        version: Long,
        started: Instant,
        gemini: Int,
        luna: Int,
        finished: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,owner_token,
            gemini_attempts,luna_attempts,finished_at,policy_version) VALUES (?,?,?,?,?,?,?,?,?,'bounded-luna-v1')
            """.trimIndent(),
            id,
            run,
            version,
            Timestamp.from(started),
            Timestamp.from(started.plusSeconds(60)),
            id(777),
            gemini,
            luna,
            if (finished) Timestamp.from(started.plusSeconds(5)) else null,
        )
    }

    private fun seedAttempt(
        id: UUID,
        invocationId: UUID?,
        number: Int,
        provider: String,
        input: Long?,
        output: Long?,
        cost: String?,
        failure: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,finished_at,
            provider,model,policy_version,invocation_id,input_tokens,output_tokens,estimated_cost_usd,failure_kind,response_bytes)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,12345)
            """.trimIndent(),
            id,
            run,
            number,
            Timestamp.from(at.plusSeconds(60)),
            Timestamp.from(at.plusSeconds(61)),
            provider,
            if (provider == "GEMINI") "gemini-3.8-flash" else "gpt-6-luna",
            if (invocationId == null) "gemini-v1" else "bounded-luna-v1",
            invocationId,
            input,
            output,
            cost?.let(::BigDecimal),
            failure,
        )
    }

    private fun seedParticipants() {
        listOf("HOST", "MEMBER").forEachIndexed { index, role ->
            val guest = id(500 + index)
            val participant = id(510 + index)
            jdbc.update(
                "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                guest,
                "synthetic-private-digest-$index",
                Timestamp.from(at.plusSeconds(3600)),
                Timestamp.from(at),
            )
            jdbc.update(
                "INSERT INTO participants(id,room_id,guest_session_id,role,joined_at,display_name) VALUES (?,?,?,?,?,'synthetic-private-name')",
                participant,
                room,
                guest,
                role,
                Timestamp.from(at),
            )
            val head = id(520 + index)
            jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, room, participant)
            if (role == "HOST") {
                listOf(1, 2).forEach { revision ->
                    val version = id(530 + revision)
                    jdbc.update(
                        "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,?,'synthetic-private-raw-text','ko-KR',?)",
                        version,
                        head,
                        revision,
                        Timestamp.from(at),
                    )
                    jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
                }
            }
        }
    }

    private fun seedRevision(
        generation: Int,
        outcome: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO input_revision_rounds(id,room_id,generation,source_run_id,reopen_request_id,expected_generation,
            status,opened_at,consumed_at,analyze_request_id,force_reparse,resolved_run_id,outcome)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            id(600 + generation),
            room,
            generation,
            run,
            id(610 + generation),
            generation - 1,
            if (outcome == null) "OPEN" else "CONSUMED",
            Timestamp.from(at),
            if (outcome == null) null else Timestamp.from(at.plusSeconds(1)),
            if (outcome == null) null else id(620 + generation),
            if (outcome == null) null else false,
            if (outcome == null) null else run,
            outcome,
        )
    }

    private fun businessState(): Map<String, Any> =
        mapOf(
            "counts" to
                listOf(
                    "meeting_rooms",
                    "participants",
                    "submission_heads",
                    "submission_versions",
                    "coordination_runs",
                    "analysis_invocations",
                    "coordination_attempts",
                    "input_revision_rounds",
                    "outbox_events",
                ).associateWith(::count),
            "room" to
                jdbc.queryForMap(
                    "SELECT version,active_run_id,revision_generation,correction_analysis_count FROM meeting_rooms WHERE id=?",
                    room,
                ),
            "run" to jdbc.queryForMap("SELECT version,status FROM coordination_runs WHERE id=?", run),
        )

    private fun request(
        builder: MockHttpServletRequestBuilder,
        status: Int = 200,
    ): MockHttpServletResponse {
        val response = mvc.perform(builder).andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertNoSecrets(response)
        return response
    }

    private fun assertNoSecrets(response: MockHttpServletResponse) {
        listOf(
            "synthetic-private",
            "owner_token",
            "response_bytes",
            "raw_text",
            "invite_code",
            "display_name",
            "credential_digest",
            "guest_session_id",
        ).forEach { forbidden ->
            assertFalse(response.contentAsString.contains(forbidden), "Response leaked $forbidden")
        }
    }

    private fun problem(
        response: MockHttpServletResponse,
        code: String,
    ) {
        assertTrue(response.contentType.orEmpty().startsWith("application/problem+json"))
        val problem = body(response)
        assertEquals(setOf("code", "title", "status", "detail"), problem.keys)
        assertEquals(code, problem["code"])
        assertEquals(response.status.toLong(), number(problem, "status"))
        assertFalse(response.contentAsString.contains("private-invalid"))
    }

    private fun assertInvocationUnknownCounts(summary: Map<String, Any?>) {
        assertNull(summary["parser_dispatch_count"])
        assertNull(summary["http_request_count"])
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun number(
        value: Map<String, Any?>,
        field: String,
    ): Long = (value.getValue(field) as Number).toLong()

    @Suppress("UNCHECKED_CAST")
    private fun body(response: MockHttpServletResponse): Map<String, Any?> =
        mapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun child(
        value: Map<String, Any?>,
        field: String,
    ): Map<String, Any?> = value.getValue(field) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun objects(
        value: Map<String, Any?>,
        field: String,
    ): List<Map<String, Any?>> = value.getValue(field) as List<Map<String, Any?>>

    private fun id(value: Int): UUID = UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")

    class TestIdentity : OperatorIdentityPort {
        var actor: UUID? = OPERATOR

        override fun currentUserId(): UUID? = actor
    }

    @TestConfiguration
    class IdentityConfiguration {
        @Bean
        @Primary
        fun testOperatorIdentity() = TestIdentity()
    }

    companion object {
        private val OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000110")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }
        private val ROOM_FIELDS =
            setOf(
                "id",
                "created_at",
                "meeting_mode",
                "time_zone_id",
                "search_start_date",
                "search_end_date",
                "expected_participants",
                "submission_deadline",
                "manual_only",
                "collection_status",
                "closure_reason",
                "closed_at",
                "participant_count",
                "submitted_participant_count",
                "host_submitted",
                "current_run_status",
            )
        private val INVOCATION_FIELDS =
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
            )
        private val ATTEMPT_FIELDS =
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
            )
        private val USAGE_FIELDS =
            setOf(
                "provider",
                "recorded_attempt_count",
                "input_known_count",
                "output_known_count",
                "cost_known_count",
                "input_unknown_count",
                "output_unknown_count",
                "cost_unknown_count",
                "known_input_tokens",
                "known_output_tokens",
                "known_cost_usd",
                "coverage_basis",
            )

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
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.operations.enabled") { true }
            registry.add("meetme.operations.operator-user-ids") { OPERATOR.toString() }
            registry.add("meetme.operations.cleanup-enabled") { false }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
