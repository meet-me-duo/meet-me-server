package com.meetme.server.shared.operations

import com.meetme.server.coordination.adapter.output.persistence.JdbcParserTransportObservationAdapter
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserClientCallObservation
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #110 V15: actual PostgreSQL constraints, atomic snapshots, no Spring context or external provider calls. */
class ParserTransportObservationPostgresTest {
    private val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
    private val jdbc = JdbcTemplate(source)
    private val adapter = JdbcParserTransportObservationAdapter(jdbc, DataSourceTransactionManager(source))
    private val at = Instant.parse("2026-10-17T12:00:00Z")
    private val room = UUID(0, 100)
    private val run = UUID(0, 200)
    private val firstInvocation = UUID(0, 300)
    private val secondInvocation = UUID(0, 301)
    private val gemini = ParserObservationContext(firstInvocation, UUID(0, 400))
    private val openai = ParserObservationContext(firstInvocation, UUID(0, 401))

    @BeforeEach
    fun migrateAndSeedSyntheticAdmissions() {
        Flyway
            .configure()
            .dataSource(source)
            .load()
            .migrate()
        jdbc.execute("TRUNCATE TABLE meeting_rooms CASCADE")
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,
            search_range_source,manual_only,collection_status,created_at,invite_code)
            VALUES (?,'synthetic private transport fixture','REMOTE','Asia/Seoul','2026-10-18','2026-10-20',
            'HOST_SPECIFIED',true,'COLLECTING',?,'transportfixture000001')
            """.trimIndent(),
            room,
            Timestamp.from(at),
        )
        val batch = UUID(0, 201)
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", batch, room, Timestamp.from(at))
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'ANALYSIS_DELAYED',?,99)",
            run,
            room,
            batch,
            Timestamp.from(at),
        )
        listOf(firstInvocation, secondInvocation).forEachIndexed { index, id ->
            jdbc.update(
                """
                INSERT INTO analysis_invocations(id,coordination_run_id,run_version,started_at,deadline_at,
                owner_token,gemini_attempts,luna_attempts,policy_version) VALUES (?,?,?,?,?,?,1,1,'bounded-luna-v1')
                """.trimIndent(),
                id,
                run,
                index + 1,
                Timestamp.from(at),
                Timestamp.from(at.plusSeconds(60)),
                UUID(0, 999),
            )
        }
        seedAttempt(gemini.attemptId, firstInvocation, 1, "GEMINI")
        seedAttempt(openai.attemptId, firstInvocation, 2, "OPENAI")
        seedAttempt(UUID(0, 402), secondInvocation, 3, "GEMINI")
        seedAttempt(UUID(0, 403), null, 4, "GEMINI")
    }

    @Test
    fun `missing legacy and unfinished observations remain absent while explicit no dispatch is known zero`() {
        assertEquals(0, count("parser_transport_observations"))
        adapter.record(ParserTransportSnapshot(gemini, AnalysisProvider.GEMINI, false, emptyList()))
        val row =
            jdbc.queryForMap(
                "SELECT invocation_id,provider,parser_dispatched,call_count FROM parser_transport_observations WHERE attempt_id=?",
                gemini.attemptId,
            )
        assertEquals(firstInvocation, row["invocation_id"])
        assertEquals("GEMINI", row["provider"])
        assertEquals(false, row["parser_dispatched"])
        assertEquals(0, (row["call_count"] as Number).toInt())
        assertEquals(0, count("parser_transport_client_calls"))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM parser_transport_observations WHERE attempt_id IN (?,?)",
                Int::class.java,
                UUID(0, 402),
                UUID(0, 403),
            ),
        )
    }

    @Test
    fun `atomic settled snapshot preserves client call facts and identical duplicate is idempotent`() {
        val snapshot = snapshot()
        adapter.record(snapshot)
        adapter.record(snapshot)
        assertEquals(1, count("parser_transport_observations"))
        assertEquals(2, count("parser_transport_client_calls"))
        val calls =
            jdbc.queryForList(
                "SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls WHERE attempt_id=? ORDER BY call_number",
                gemini.attemptId,
            )
        assertEquals(listOf(1, 2), calls.map { (it["call_number"] as Number).toInt() })
        assertEquals(listOf(123L, 456L), calls.map { (it["elapsed_nanos"] as Number).toLong() })
        assertEquals(listOf("COMPLETED", "FAILED"), calls.map { it["outcome"] })
        assertEquals(503, calls[0]["http_status"])
        assertNull(calls[1]["http_status"])
    }

    @Test
    fun `OpenAI zero call parser entry is distinct from not dispatched`() {
        adapter.record(ParserTransportSnapshot(openai, AnalysisProvider.OPENAI, true, emptyList()))
        val row =
            jdbc.queryForMap(
                "SELECT provider,parser_dispatched,call_count FROM parser_transport_observations WHERE attempt_id=?",
                openai.attemptId,
            )
        assertEquals("OPENAI", row["provider"])
        assertEquals(true, row["parser_dispatched"])
        assertEquals(0, (row["call_count"] as Number).toInt())
    }

    @Test
    fun `conflicting duplicate cannot overwrite an already settled snapshot or child rows`() {
        adapter.record(snapshot())
        val before =
            jdbc.queryForList(
                "SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls ORDER BY call_number",
            )
        assertFailsWith<RuntimeException> {
            adapter.record(snapshot().copy(calls = listOf(ParserClientCallObservation(1, 999, ParserClientCallOutcome.COMPLETED, 200))))
        }
        assertEquals(1, count("parser_transport_observations"))
        assertEquals(
            before,
            jdbc.queryForList(
                "SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls ORDER BY call_number",
            ),
        )
    }

    @Test
    fun `wrong invocation provider and legacy context are rejected without attaching guessed facts`() {
        listOf(
            snapshot().copy(context = ParserObservationContext(secondInvocation, gemini.attemptId)),
            snapshot().copy(provider = AnalysisProvider.OPENAI),
            snapshot().copy(context = ParserObservationContext(firstInvocation, UUID(0, 403))),
        ).forEach { invalid ->
            assertFailsWith<RuntimeException> { adapter.record(invalid) }
            assertEquals(0, count("parser_transport_observations"))
            assertEquals(0, count("parser_transport_client_calls"))
        }
    }

    @Test
    fun `invalid bounded call facts cannot create partial or misleading snapshots`() {
        val first = snapshot().calls.first()
        listOf(
            snapshot().copy(parserDispatched = false),
            snapshot().copy(calls = listOf(first.copy(callNumber = 0))),
            snapshot().copy(calls = listOf(first.copy(callNumber = 2))),
            snapshot().copy(calls = listOf(first, first)),
            snapshot().copy(calls = listOf(first.copy(elapsedNanos = -1))),
            snapshot().copy(calls = listOf(first.copy(httpStatus = 99))),
            snapshot().copy(calls = listOf(first.copy(httpStatus = 600))),
            snapshot().copy(calls = List(ParserTransportSnapshot.MAX_CLIENT_CALLS + 1) { first.copy(callNumber = it + 1) }),
        ).forEach { invalid ->
            assertFailsWith<RuntimeException> { adapter.record(invalid) }
            assertEquals(0, count("parser_transport_observations"))
            assertEquals(0, count("parser_transport_client_calls"))
        }
    }

    @Test
    fun `child insert failure rolls back both parent snapshot and earlier child inserts`() {
        jdbc.execute("ALTER TABLE parser_transport_client_calls ADD CONSTRAINT test_reject_second_call CHECK (call_number <> 2) NOT VALID")
        try {
            assertFailsWith<RuntimeException> { adapter.record(snapshot()) }
            assertEquals(0, count("parser_transport_observations"))
            assertEquals(0, count("parser_transport_client_calls"))
        } finally {
            jdbc.execute("ALTER TABLE parser_transport_client_calls DROP CONSTRAINT test_reject_second_call")
        }
        adapter.record(snapshot())
        assertEquals(1, count("parser_transport_observations"))
        assertEquals(2, count("parser_transport_client_calls"))
    }

    @Test
    fun `attempt deletion cascades observation and client calls without extending business retention`() {
        adapter.record(snapshot())
        jdbc.update("DELETE FROM coordination_attempts WHERE id=?", gemini.attemptId)
        assertEquals(0, count("parser_transport_observations"))
        assertEquals(0, count("parser_transport_client_calls"))
        assertEquals(1, count("meeting_rooms"))
    }

    @Test
    fun `persisted observation schema permits only identity lifecycle count duration outcome and HTTP status`() {
        fun columns(table: String): Set<String> =
            jdbc
                .queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=?",
                    String::class.java,
                    table,
                ).filterNotNull()
                .toSet()
        assertEquals(
            setOf("attempt_id", "invocation_id", "provider", "parser_dispatched", "call_count"),
            columns("parser_transport_observations"),
        )
        assertEquals(
            setOf("attempt_id", "call_number", "elapsed_nanos", "outcome", "http_status"),
            columns("parser_transport_client_calls"),
        )
        assertTrue(
            jdbc
                .queryForList(
                    "SELECT constraint_type FROM information_schema.table_constraints WHERE table_name='parser_transport_observations'",
                    String::class.java,
                ).contains("FOREIGN KEY"),
        )
    }

    private fun snapshot() =
        ParserTransportSnapshot(
            gemini,
            AnalysisProvider.GEMINI,
            true,
            listOf(
                ParserClientCallObservation(1, 123, ParserClientCallOutcome.COMPLETED, 503),
                ParserClientCallObservation(2, 456, ParserClientCallOutcome.FAILED, null),
            ),
        )

    private fun seedAttempt(
        id: UUID,
        invocation: UUID?,
        number: Int,
        provider: String,
    ) {
        jdbc.update(
            """
            INSERT INTO coordination_attempts(id,coordination_run_id,attempt_number,started_at,provider,model,policy_version,invocation_id)
            VALUES (?,?,?,?,?,?,?,?)
            """.trimIndent(),
            id,
            run,
            number,
            Timestamp.from(at),
            provider,
            if (provider == "GEMINI") "gemini-3.8-flash" else "gpt-6-luna",
            if (invocation == null) "gemini-v1" else "bounded-luna-v1",
            invocation,
        )
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
