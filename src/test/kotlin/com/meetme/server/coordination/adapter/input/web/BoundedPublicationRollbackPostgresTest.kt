package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.input.web.DurableAnalysisInvocationPostgresTest.ControlledTime
import com.meetme.server.coordination.adapter.input.web.DurableAnalysisInvocationPostgresTest.DurableClock
import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.CoordinationWorker
import com.meetme.server.coordination.application.service.DeadLetterPersistence
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96: deadline crossing during actual PostgreSQL publication rolls back and ends as delayed, with zero paid calls. */
@Import(ControlledTime::class)
class BoundedPublicationRollbackPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var outbox: OutboxRepository

    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var matching: MatchingProcessor

    @Autowired private lateinit var time: DurableClock

    @MockitoSpyBean private lateinit var structured: StructuredSubmissionRepository

    @MockitoSpyBean private lateinit var places: NormalizedPlaceRepository

    @BeforeEach
    fun resetTime() {
        time.now = NOW
    }

    @Test
    fun `deadline crossing after structured writes throws typed cancellation and rolls back all publication`() {
        val run = assertNotNull(persistence.start(queuedRun()))
        val invocation = assertNotNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        val attempt = attempt(run, invocation)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt))
        val checks = AtomicInteger()

        val failure =
            assertThrows<RuntimeException> {
                persistence.completeBounded(run, results(run), attempt.copy(finishedAt = NOW), invocation) {
                    checks.incrementAndGet() == 1
                }
            }

        assertEquals("AnalysisDeadlineExceededException", failure::class.simpleName)
        assertTrue(checks.get() >= 2, "Publication must recheck cancellation after writing structured results")
        assertEquals(CoordinationStatus.STRUCTURING, runs.findById(run.id)?.status)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertNull(stored.winnerAttemptId)
        assertNull(stored.finishedAt)
        assertNull(jdbc.queryForMap("SELECT finished_at FROM coordination_attempts WHERE id = ?", attempt.id)["finished_at"])
    }

    @Test
    fun `processor converts publication deadline rollback into delayed ACK without another physical call`() {
        val run = queuedRun()
        val versions = run.batch.submissionVersionIds
        val calls = AtomicInteger()
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = message(run)
        doAnswer { call ->
            call.callRealMethod().also {
                time.now = assertNotNull(invocations.findLatestByRun(run.id)).deadlineAt
            }
        }.`when`(structured).replaceForBatch(run.batch.id, results(run), NOW)
        val processor =
            GeminiBatchProcessor(
                runs,
                submissions,
                structured,
                attempts,
                NaturalLanguageParserPort {
                    calls.incrementAndGet()
                    NaturalLanguageBatchResult(results(run), ParserUsage(10, 10, 200))
                },
                IdGenerator { UUID.randomUUID() },
                RetryDelayPort { error("No provider retry is authorized after successful structure") },
                JitterPort { 0 },
                MonotonicTimePort { Duration.between(NOW, time.now).toNanos() },
                time,
                persistence,
                fallbackParser = NaturalLanguageParserPort { error("No fallback is authorized after publication cancellation") },
                invocationRepository = invocations,
            )

        CoordinationWorker(
            outbox,
            queue,
            CoordinationEventProcessor { _, batch -> processor.process(batch) },
            DeadLetterPersistence { _, _, _ -> error("Deadline cancellation must not become poison or DLQ") },
            time,
        ).process(message)

        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(message.eventId)?.status)
        assertEquals(1, calls.get())
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, runs.findById(run.id)?.status)
        assertEquals(versions, runs.findById(run.id)?.batch?.submissionVersionIds)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM candidates", Int::class.java))
        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertNull(stored.winnerAttemptId)
        assertNotNull(stored.finishedAt)
    }

    @Test
    fun `matching storage deadline rollback ends delayed in a fresh transaction with no candidates`() {
        val run = assertNotNull(persistence.start(queuedRun()))
        val invocation = assertNotNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        val attempt = attempt(run, invocation)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt))
        assertTrue(persistence.completeBounded(run, results(run), attempt.copy(finishedAt = NOW), invocation) { true })
        doAnswer { call ->
            call.callRealMethod().also { time.now = invocation.deadlineAt }
        }.`when`(places).replaceForBatch(run.batch.id, emptyList())

        matching.processBounded(run.batch.id) { true }

        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, runs.findById(run.id)?.status)
        assertEquals(results(run).toSet(), structured.findByBatch(run.batch.id).toSet())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM candidates", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM normalized_places", Int::class.java))
        assertNotNull(invocations.findLatestByRun(run.id)?.winnerAttemptId)
    }

    private fun queuedRun(): CoordinationRun {
        val fixture = completedRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status = 'QUEUED', candidate_quality = NULL, version = version + 1 WHERE id = ?",
            fixture.sourceRun,
        )
        return assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
    }

    private fun attempt(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
    ) = CoordinationAttempt(
        UUID.randomUUID(),
        run.id,
        1,
        NOW,
        null,
        null,
        null,
        null,
        null,
        null,
        policyVersion = AnalysisInvocation.POLICY_VERSION,
        invocationId = invocation.id,
    )

    private fun results(run: CoordinationRun) =
        run.batch.submissionVersionIds.map {
            StructuredSubmissionResult(
                it,
                listOf(
                    StructuredCondition.TimeWindow(
                        TimePolarity.AVAILABLE,
                        null,
                        DayOfWeek.WEDNESDAY,
                        LocalTime.of(19, 0),
                        LocalTime.of(21, 0),
                    ),
                ),
                null,
            )
        }

    private fun message(run: CoordinationRun): CoordinationWorkMessage {
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM outbox_events WHERE aggregate_id = ? ORDER BY occurred_at DESC LIMIT 1",
                    UUID::class.java,
                    run.id.value,
                ),
            )
        jdbc.update(
            "UPDATE outbox_events SET event_type = ?, status = 'PUBLISHED', published_at = ?, processed_at = NULL, " +
                "processing_lease_until = NULL WHERE id = ?",
            CollectionClosureService.STRUCTURING_REQUESTED,
            NOW.atOffset(java.time.ZoneOffset.UTC),
            id,
        )
        return CoordinationWorkMessage("synthetic-$id", OutboxEventId(id), run.batch.id, CollectionClosureService.STRUCTURING_REQUESTED)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
