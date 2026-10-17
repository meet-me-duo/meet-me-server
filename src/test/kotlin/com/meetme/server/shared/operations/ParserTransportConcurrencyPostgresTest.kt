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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #110 V15: actual PostgreSQL constraints, atomic snapshots, no Spring context or external provider calls. */
class ParserTransportConcurrencyPostgresTest {
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
    fun `concurrent identical repeat settles once without conflicting retry errors`() {
        val snapshot = snapshot()
        val results = concurrently(snapshot, snapshot)
        assertEquals(listOf(true, true), results)
        assertEquals(1, count("parser_transport_observations"))
        assertEquals(2, count("parser_transport_client_calls"))
    }

    @Test
    fun `concurrent conflicting repeat accepts exactly one complete snapshot without mixing child rows`() {
        val first = snapshot()
        val second = first.copy(calls = listOf(ParserClientCallObservation(1, 999, ParserClientCallOutcome.COMPLETED, 200)))
        val results = concurrently(first, second)
        assertEquals(1, results.count { it })
        assertEquals(1, count("parser_transport_observations"))
        val calls =
            jdbc.queryForList(
                "SELECT call_number,elapsed_nanos,outcome,http_status FROM parser_transport_client_calls ORDER BY call_number",
            )
        val elapsed = calls.map { (it["elapsed_nanos"] as Number).toLong() }
        assertTrue(elapsed == listOf(123L, 456L) || elapsed == listOf(999L))
        assertEquals(calls.size, (jdbc.queryForObject("SELECT call_count FROM parser_transport_observations", Int::class.java)))
    }

    private fun concurrently(
        first: ParserTransportSnapshot,
        second: ParserTransportSnapshot,
    ): List<Boolean> {
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks =
                listOf(first, second).map { snapshot ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            check(go.await(3, TimeUnit.SECONDS))
                            runCatching { adapter.record(snapshot) }.isSuccess
                        },
                    )
                }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            go.countDown()
            return tasks.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            go.countDown()
            executor.shutdownNow()
        }
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
