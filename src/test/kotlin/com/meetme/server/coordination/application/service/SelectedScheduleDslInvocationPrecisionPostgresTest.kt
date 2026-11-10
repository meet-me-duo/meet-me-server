package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.persistence.JdbcAnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Real pgjdbc TIMESTAMPTZ precision, not a fake repository or relaxed owner/timestamp comparison. */
@SpringBootTest
@Import(SelectedScheduleDslPublicationPostgresTest.Configuration::class)
class SelectedScheduleDslInvocationPrecisionPostgresTest {
    @Autowired private lateinit var service: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var repository: SelectedScheduleDslPublicationPostgresTest.PublicationRepository

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Test
    fun `SDPN01 submicrosecond admission returns exact persisted invocation accepted by strict binding and publication`() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        repository.afterPublish = {}
        clock.set(F.selectedAt)
        F.seed(jdbc, mapper)
        val route = routes.freeze(F.ir.run, 1, F.choice())
        val now = F.selectedAt.plusNanos(123456789)
        clock.set(now)
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(service.admit(run, UUID(0, 8811), Duration.ofSeconds(60)))
        val fresh = assertNotNull(JdbcAnalysisInvocationRepository(jdbc).findByRunVersion(run.id, run.version))
        assertEquals(fresh, admitted.binding.invocation)
        assertEquals(0, fresh.startedAt.nano % 1000)
        assertEquals(0, fresh.deadlineAt.nano % 1000)
        assertNotEquals(now, fresh.startedAt)
        assertEquals(UUID(0, 8811), fresh.ownerToken)
        assertEquals(route, admitted.binding.route)
        routes.requireDispatchCurrent(admitted.binding)
        val request =
            service.request(
                admitted.binding,
                admitted.attempt,
                System.nanoTime() + Duration.ofSeconds(10).toNanos(),
            )
        assertEquals(fresh, request.binding.invocation)
        assertEquals(admitted.attempt.id, request.dispatchContext.finalAttemptId)
        val response =
            ScheduleDslProviderResponse(
                F.q.wire.json(),
                ParserUsage(17, 23, 100),
                AnalysisProvider.GEMINI,
                "gemini-3.8-flash",
            )
        assertTrue(service.completeDsl(admitted.binding, admitted.attempt, response) { true })
        assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_execution_bindings", Int::class.java))
    }

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
