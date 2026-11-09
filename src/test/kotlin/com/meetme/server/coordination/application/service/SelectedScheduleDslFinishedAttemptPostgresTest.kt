package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Real PostgreSQL row authority defeats stale unfinished caller objects without closing retry ownership. */
@SpringBootTest
@Import(SelectedScheduleDslPublicationPostgresTest.Configuration::class)
class SelectedScheduleDslFinishedAttemptPostgresTest {
    @Autowired private lateinit var service: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Autowired private lateinit var publication: SelectedScheduleDslPublicationPostgresTest.PublicationRepository

    @BeforeEach
    fun nativeRowsOnly() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        publication.afterPublish = {}
        F.seed(jdbc, mapper)
        routes.freeze(F.ir.run, 1, F.choice())
    }

    @Test
    fun `SFBPG01 durable failed attempt cannot redispatch or publish using an unfinished caller copy`() {
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(service.admit(run, UUID(0, 9751), Duration.ofSeconds(60)))
        // A live physical attempt owns the invocation until its durable completion.
        assertNull(service.admitAttempt(admitted.binding, AnalysisProvider.GEMINI))
        assertNull(service.admitAttempt(admitted.binding, AnalysisProvider.OPENAI))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT gemini_attempts FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT luna_attempts FROM analysis_invocations", Int::class.java))
        val unfinished = admitted.attempt.copy(finishedAt = null, failureKind = null)
        assertNull(unfinished.finishedAt)
        service.failAttempt(admitted.binding, unfinished, ParserFailureKind.SERVER, ParserUsage(17, 23, 100))
        val stored = assertNotNull(attempts.findById(unfinished.id))
        assertNotNull(stored.finishedAt)
        assertEquals("SERVER", stored.failureKind)

        assertFailsWith<RuntimeException> {
            service.request(admitted.binding, unfinished, System.nanoTime() + Duration.ofSeconds(10).toNanos())
        }
        val stalePublication = runCatching { service.completeDsl(admitted.binding, unfinished, response()) { true } }
        assertTrue(stalePublication.isFailure || stalePublication.getOrNull() == false)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
        assertEquals("STRUCTURING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertNull(jdbc.queryForObject("SELECT finished_at FROM analysis_invocations", java.sql.Timestamp::class.java))
        assertNull(jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))

        val retry = assertNotNull(service.admitAttempt(admitted.binding, AnalysisProvider.GEMINI))
        assertNotEquals(unfinished.id, retry.id)
        val request = service.request(admitted.binding, retry, System.nanoTime() + Duration.ofSeconds(10).toNanos())
        assertEquals(retry.id, request.attempt.id)
        assertTrue(service.completeDsl(admitted.binding, retry, response()) { true })
        assertEquals(retry.id, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        assertEquals("SERVER", assertNotNull(attempts.findById(unfinished.id)).failureKind)
        assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
    }

    private fun response() =
        ScheduleDslProviderResponse(
            F.q.wire.json(),
            ParserUsage(17, 23, 100),
            AnalysisProvider.GEMINI,
            "gemini-3.8-flash",
        )

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        @JvmStatic @AfterAll
        fun stopDatabase() {
            postgres.stop()
        }
    }
}
