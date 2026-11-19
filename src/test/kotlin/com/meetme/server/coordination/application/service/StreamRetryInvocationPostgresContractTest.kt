package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.persistence.JdbcAnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationRetryScheduled
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxProcessingClaim
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Durable admission, not a provider mock: no paid request is made by this fixture. */
@SpringBootTest
@Import(StreamRetryInvocationPostgresContractTest.TimeConfiguration::class)
class StreamRetryInvocationPostgresContractTest {
    @Autowired private lateinit var service: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var clock: RetryClock

    @Autowired private lateinit var outbox: OutboxRepository

    @BeforeEach
    fun nativeRows() {
        jdbc.execute("TRUNCATE outbox_events")
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        val input =
            SelectedDslPhysicalInputFixture.approvedInput().copy(
                responseContractVersion = "schedule-dsl-response-4",
                semanticVersion = "schedule-ir-product-5",
                policy = ScheduleDslContextModePolicy.contextual(),
            )
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val context = ScheduleDslOfflinePipeline.snapshotContextMode(input).snapshot.context
        routes.freeze(
            F.ir.run,
            1,
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", input.policy.version),
                input.policy,
                context,
                input.meetingMode,
                "ko-time-terms-1",
            ),
        )
    }

    @Test
    fun `SR02 SR04 due admission reuses the persisted invocation and counts only admitted attempts`() {
        val first = admit()
        val due =
            service.scheduleAttempt(
                first.binding,
                first.attempt,
                ParserFailureKind.SERVER,
                AnalysisProvider.GEMINI,
                Duration.ofSeconds(1),
            )
        val repository = JdbcAnalysisInvocationRepository(jdbc)
        val waiting = assertNotNull(repository.findByRunVersion(first.binding.invocation.runId, first.binding.invocation.runVersion))
        assertEquals(due, waiting.nextAttemptAt)
        assertEquals(AnalysisProvider.GEMINI, waiting.nextProvider)
        assertEquals(1, waiting.geminiAttempts)
        assertEquals("SERVER", jdbc.queryForObject("SELECT failure_kind FROM coordination_attempts", String::class.java))

        clock.set(due.minusNanos(1_000))
        val early = assertFailsWith<CoordinationRetryScheduled> { admit() }
        assertEquals(due, early.nextAttemptAt)
        assertEquals(1, count("coordination_attempts"))

        clock.set(due)
        val resumed = admit()
        assertEquals(first.binding.invocation.id, resumed.binding.invocation.id)
        assertEquals(first.binding.invocation.startedAt, resumed.binding.invocation.startedAt)
        assertEquals(first.binding.invocation.deadlineAt, resumed.binding.invocation.deadlineAt)
        assertEquals(first.binding.invocation.ownerToken, resumed.binding.invocation.ownerToken)
        assertEquals("c5-stream-retry-v3", resumed.binding.invocation.policyVersion)
        assertEquals(2, resumed.binding.invocation.geminiAttempts)
        assertEquals(1, count("analysis_invocations"))
        assertEquals(2, count("coordination_attempts"))
        assertNull(resumed.binding.invocation.nextAttemptAt)
        assertNull(resumed.binding.invocation.nextProvider)
    }

    @Test
    fun `SR03 simultaneous due redeliveries admit at most one additional provider attempt`() {
        val first = admit()
        val due =
            service.scheduleAttempt(
                first.binding,
                first.attempt,
                ParserFailureKind.SERVER,
                AnalysisProvider.GEMINI,
                Duration.ofSeconds(1),
            )
        clock.set(due)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val outcomes = executor.invokeAll(List(8) { Callable { runCatching { admit() } } }).map { it.get() }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertTrue(outcomes.filter { it.isFailure }.all { it.exceptionOrNull() is CoordinationRetryScheduled })
            assertEquals(2, count("coordination_attempts"))
            assertEquals(1, count("analysis_invocations"))
            assertEquals(2, jdbc.queryForObject("SELECT gemini_attempts FROM analysis_invocations", Int::class.java))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `SR04 four primary attempts and one fallback retain the first deadline across deliveries`() {
        val first = admit()
        var admitted = first
        repeat(3) {
            val due =
                service.scheduleAttempt(
                    admitted.binding,
                    admitted.attempt,
                    ParserFailureKind.SERVER,
                    AnalysisProvider.GEMINI,
                    Duration.ofSeconds(1),
                )
            clock.set(due)
            admitted = admit()
            assertEquals(first.binding.invocation.id, admitted.binding.invocation.id)
            assertEquals(first.binding.invocation.deadlineAt, admitted.binding.invocation.deadlineAt)
        }
        assertEquals(4, admitted.binding.invocation.geminiAttempts)
        val due =
            service.scheduleAttempt(
                admitted.binding,
                admitted.attempt,
                ParserFailureKind.SERVER,
                AnalysisProvider.OPENAI,
                Duration.ZERO,
            )
        clock.set(due)
        val fallback = admit()
        assertEquals(AnalysisProvider.OPENAI, fallback.attempt.provider)
        assertEquals(4, fallback.binding.invocation.geminiAttempts)
        assertEquals(1, fallback.binding.invocation.lunaAttempts)
        assertEquals(first.binding.invocation.id, fallback.binding.invocation.id)
        assertEquals(first.binding.invocation.deadlineAt, fallback.binding.invocation.deadlineAt)
        service.fail(fallback.binding, fallback.attempt, ParserFailureKind.SERVER)

        val run = service.start(SubmissionBatchId(F.ir.batch))
        assertNull(run)
        assertEquals(5, count("coordination_attempts"))
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts WHERE provider='GEMINI'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts WHERE provider='OPENAI'", Int::class.java))
        assertEquals("ANALYSIS_DELAYED", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
    }

    @Test
    fun `SR12 unfinished send remains in doubt and cannot authorize a fresh paid attempt`() {
        val first = admit()
        clock.set(F.selectedAt.plusSeconds(1))

        val blocked = assertFailsWith<CoordinationRetryScheduled> { admit() }

        assertEquals(first.binding.invocation.deadlineAt, blocked.nextAttemptAt)
        assertEquals("ATTEMPT_IN_DOUBT", blocked.failureKind)
        assertEquals(1, count("analysis_invocations"))
        assertEquals(1, count("coordination_attempts"))
        assertEquals(1, jdbc.queryForObject("SELECT gemini_attempts FROM analysis_invocations", Int::class.java))
        clock.set(first.binding.invocation.deadlineAt)
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))
        assertNull(service.admitQueued(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        assertEquals("ANALYSIS_DELAYED", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertEquals(1, count("coordination_attempts"))
    }

    @Test
    fun `SR05 queue delay past the primary phase admits fallback within the unchanged total budget`() {
        val first = admit()
        service.scheduleAttempt(first.binding, first.attempt, ParserFailureKind.SERVER, AnalysisProvider.GEMINI, Duration.ofSeconds(1))
        clock.set(F.selectedAt.plusSeconds(31))

        val resumed = admit()

        assertEquals(AnalysisProvider.OPENAI, resumed.attempt.provider)
        assertEquals(1, resumed.binding.invocation.geminiAttempts)
        assertEquals(1, resumed.binding.invocation.lunaAttempts)
        assertEquals(first.binding.invocation.id, resumed.binding.invocation.id)
        assertEquals(first.binding.invocation.deadlineAt, resumed.binding.invocation.deadlineAt)
        assertEquals(Duration.ofSeconds(29), service.remainingBudget(resumed.binding))
        assertEquals(2, count("coordination_attempts"))
    }

    @Test
    fun `SR04 completion reserve boundary terminates without admitting a provider attempt`() {
        val first = admit()
        service.scheduleAttempt(first.binding, first.attempt, ParserFailureKind.SERVER, AnalysisProvider.GEMINI, Duration.ofSeconds(1))
        clock.set(F.selectedAt.plusSeconds(57))
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))

        assertNull(service.admitQueued(run, UUID.randomUUID(), Duration.ofSeconds(60)))

        assertEquals("ANALYSIS_DELAYED", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
        assertEquals(1, count("coordination_attempts"))
        assertEquals(
            first.binding.invocation.deadlineAt,
            assertNotNull(JdbcAnalysisInvocationRepository(jdbc).findByRunVersion(run.id, run.version)).deadlineAt,
        )
    }

    @Test
    fun `SR08 successful continuation atomically commits failed attempt invocation schedule and Outbox release`() {
        val first = admit()
        val claim = publishedClaim()

        val due =
            service.scheduleDelivery(
                first.binding,
                first.attempt,
                ParserFailureKind.SERVER,
                AnalysisProvider.GEMINI,
                Duration.ofSeconds(1),
                null,
                claim,
            )

        val invocation =
            assertNotNull(
                JdbcAnalysisInvocationRepository(
                    jdbc,
                ).findByRunVersion(first.binding.invocation.runId, first.binding.invocation.runVersion),
            )
        val released = assertNotNull(outbox.findById(claim.event.id))
        assertEquals(F.selectedAt.plusSeconds(1), due)
        assertEquals(due, invocation.nextAttemptAt)
        assertEquals(AnalysisProvider.GEMINI, invocation.nextProvider)
        assertEquals(due, released.nextAttemptAt)
        assertEquals(OutboxStatus.PUBLISHED, released.status)
        assertNull(released.processingLeaseUntil)
        assertEquals(
            "SERVER",
            jdbc.queryForObject("SELECT failure_kind FROM coordination_attempts WHERE id=?", String::class.java, first.attempt.id),
        )
        assertNotNull(
            jdbc.queryForObject(
                "SELECT finished_at FROM coordination_attempts WHERE id=?",
                java.sql.Timestamp::class.java,
                first.attempt.id,
            ),
        )
    }

    @Test
    fun `SR08 stale Outbox claim rolls back attempt completion and invocation scheduling`() {
        val first = admit()
        val stale = publishedClaim(leaseSeconds = 1)
        clock.set(F.selectedAt.plusSeconds(1))
        val current = assertNotNull(outbox.claimProcessing(stale.event.id, clock.instant(), clock.instant().plusSeconds(120)))
        val before = continuationRows()

        assertFailsWith<IllegalStateException> {
            service.scheduleDelivery(
                first.binding,
                first.attempt,
                ParserFailureKind.SERVER,
                AnalysisProvider.GEMINI,
                Duration.ofSeconds(1),
                null,
                stale,
            )
        }

        assertEquals(before, continuationRows())
        assertEquals(current.event, outbox.findById(stale.event.id))
    }

    @Test
    fun `SR08 database rejection during Outbox release rolls back the entire continuation`() {
        val first = admit()
        val claim = publishedClaim()
        val before = continuationRows()
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT synthetic_reject_retry_schedule CHECK (next_attempt_at IS NULL)")
        try {
            assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
                service.scheduleDelivery(
                    first.binding,
                    first.attempt,
                    ParserFailureKind.SERVER,
                    AnalysisProvider.GEMINI,
                    Duration.ofSeconds(1),
                    null,
                    claim,
                )
            }
            assertEquals(before, continuationRows())
            assertNull(assertNotNull(outbox.findById(claim.event.id)).nextAttemptAt)
            assertNotNull(assertNotNull(outbox.findById(claim.event.id)).processingLeaseUntil)
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT synthetic_reject_retry_schedule")
        }
    }

    private fun continuationRows() =
        listOf("analysis_invocations", "coordination_attempts", "outbox_events").associateWith { F.rows(jdbc, it) }

    private fun publishedClaim(leaseSeconds: Long = 120): OutboxProcessingClaim {
        val eventId = OutboxEventId(UUID.randomUUID())
        val event =
            OutboxEvent(
                eventId,
                "CoordinationRun",
                F.ir.run,
                "SubmissionBatchStructuringRequested",
                """{"event_id":"${eventId.value}","submission_batch_id":"${F.ir.batch}"}""",
                OutboxStatus.PUBLISHED,
                F.selectedAt.minusSeconds(5),
                F.selectedAt.minusSeconds(1),
            )
        outbox.insert(event)
        return assertNotNull(outbox.claimProcessing(event.id, clock.instant(), clock.instant().plusSeconds(leaseSeconds)))
    }

    private fun admit(): SelectedScheduleDslInvocation {
        val run = assertNotNull(service.start(SubmissionBatchId(F.ir.batch)))
        return assertNotNull(service.admitQueued(run, UUID.randomUUID(), Duration.ofSeconds(60)))
    }

    private fun count(table: String) = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    class RetryClock : Clock() {
        @Volatile private var value = F.selectedAt

        fun set(value: Instant) {
            this.value = value
        }

        override fun instant(): Instant = value

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfiguration {
        @Bean @Primary
        fun streamRetryClock() = RetryClock()
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
        }

        @JvmStatic @AfterAll
        fun stopDatabase() = postgres.stop()
    }
}
