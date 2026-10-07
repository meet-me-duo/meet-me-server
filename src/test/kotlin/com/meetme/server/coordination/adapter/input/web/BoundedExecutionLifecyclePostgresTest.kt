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
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.CoordinationWorker
import com.meetme.server.coordination.application.service.DeadLetterPersistence
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.ResumeStage
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96 completion deadline and physical-call bounds across fresh processor/worker instances. */
@Import(ControlledTime::class)
class BoundedExecutionLifecyclePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var outbox: OutboxRepository

    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var matching: MatchingProcessor

    @Autowired private lateinit var matchingPersistence: MatchingProcessingPersistenceService

    @Autowired private lateinit var time: DurableClock

    @BeforeEach
    fun resetTime() {
        time.now = NOW
    }

    @Test
    fun `bounded matching completes before deadline even when provider invocation already finished`() {
        val (run, invocation) = readyToMatch()
        assertNotNull(invocation.finishedAt)

        matching.processBounded(run.batch.id) { true }

        val completed = assertNotNull(runs.findById(run.id))
        assertEquals(CoordinationStatus.COMPLETED, completed.status)
        assertEquals(CandidateQuality.COMPLETE, completed.quality)
        assertEquals(1, completed.candidates.size)
        assertTrue(
            completed.candidates
                .single()
                .timeRanges
                .isNotEmpty(),
        )
    }

    @Test
    fun `bounded matching at original deadline delays without publishing candidates`() {
        val (run, invocation) = readyToMatch()
        time.now = invocation.deadlineAt

        matching.processBounded(run.batch.id) { true }

        val delayed = assertNotNull(runs.findById(run.id))
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, delayed.status)
        assertEquals(ResumeStage.MATCHING, delayed.resumeStage)
        assertEquals(run.batch, delayed.batch)
        assertTrue(delayed.candidates.isEmpty())
        assertNull(delayed.quality)
        assertEquals(0, count("candidates"))
    }

    @Test
    fun `cancellation immediately before matching publication cannot write completed candidates`() {
        val run = readyToMatch().first

        matching.processBounded(run.batch.id) { false }

        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, runs.findById(run.id)?.status)
        assertEquals(0, count("candidates"))
        assertEquals(0, count("normalized_places"))
    }

    @Test
    fun `matching writer rechecks original deadline after acquiring actual room lock`() {
        val (run, invocation) = readyToMatch()
        val completed = run.complete(CandidateQuality.COMPLETE, emptyList())
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        val holder =
            executor.submit {
                TransactionTemplate(transactionManager).execute {
                    jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE", UUID::class.java, run.roomId.value)
                    locked.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                }
            }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val worker =
                executor.submit<Boolean> {
                    requireNotNull(
                        TransactionTemplate(transactionManager).execute {
                            pid.set(requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                            started.countDown()
                            matchingPersistence.completeBounded(completed, emptyList()) { true }
                        },
                    )
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertBlocked(pid.get())
            time.now = invocation.deadlineAt
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            assertFalse(worker.get(10, TimeUnit.SECONDS))
            assertEquals(CoordinationStatus.MATCHING, runs.findById(run.id)?.status)
            assertEquals(0, count("candidates"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `crashed physical call is not repeated by fresh worker before or after original deadline`() {
        val (_, run) = queuedRoom()
        val versions = versionIds()
        val calls = AtomicInteger()
        val crash =
            NaturalLanguageParserPort {
                calls.incrementAndGet()
                throw IllegalStateException("synthetic processor interruption after admitted physical call")
            }
        val luna = NaturalLanguageParserPort { error("Luna must not be reached after interrupted call") }
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        worker(processor(crash, luna), queue).process(message)
        val original = assertNotNull(invocations.findLatestByRun(run.id))
        assertEquals(1, calls.get())
        assertEquals(1, original.geminiAttempts)
        assertNull(original.finishedAt)
        assertEquals(OutboxStatus.PUBLISHED, outbox.findById(message.eventId)?.status)
        verify(queue, never()).acknowledge(message.streamRecordId)

        time.now = NOW.plusSeconds(20)
        worker(processor(crash, luna), queue).process(message.copy(streamRecordId = "synthetic-restart"))
        verify(queue).acknowledge("synthetic-restart")
        assertEquals(1, calls.get())
        assertEquals(original, invocations.findLatestByRun(run.id))
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(message.eventId)?.status)

        time.now = original.deadlineAt
        invocations.findExpired(time.now, 10).forEach(persistence::expire)
        processor(crash, luna).process(run.batch.id)
        worker(processor(crash, luna), queue).process(message.copy(streamRecordId = "synthetic-after-deadline"))
        verify(queue).acknowledge("synthetic-after-deadline")
        assertEquals(1, calls.get())
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, runs.findById(run.id)?.status)
        assertEquals(run.batch, runs.findById(run.id)?.batch)
        assertEquals(versions, versionIds())
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertEquals(0, count("candidates"))
    }

    @Test
    fun `failed providers ACK once with max five physical calls then host retry owns a separate cost unit`() {
        val (fixture, run) = queuedRoom()
        val versions = versionIds()
        val calls = mutableListOf<AnalysisProvider>()
        val gemini =
            NaturalLanguageParserPort {
                calls += AnalysisProvider.GEMINI
                throw NaturalLanguageParserException(ParserFailureKind.SERVER)
            }
        var lunaSucceeds = false
        val luna =
            NaturalLanguageParserPort { request ->
                calls += AnalysisProvider.OPENAI
                if (!lunaSucceeds) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                NaturalLanguageBatchResult(
                    request.inputs.map { result(SubmissionVersionId(UUID.fromString(it.inputRef))) },
                    ParserUsage(100, 20, 80),
                )
            }
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val firstMessage = publishedMessage(run)
        worker(processor(gemini, luna), queue).process(firstMessage)
        verify(queue).acknowledge(firstMessage.streamRecordId)
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(firstMessage.eventId)?.status)
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, runs.findById(run.id)?.status)
        assertEquals(List(4) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, calls)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        assertEquals(0, count("candidates"))
        val original = assertNotNull(invocations.findLatestByRun(run.id))
        processor(gemini, luna).process(run.batch.id)
        worker(processor(gemini, luna), queue).process(firstMessage.copy(streamRecordId = "synthetic-duplicate"))
        assertEquals(5, calls.size)

        time.now = NOW.plusSeconds(10)
        mvc
            .perform(post("/api/rooms/{code}/analysis/retry", fixture.host.code).header("Origin", ORIGIN).cookie(fixture.host.cookie()))
            .andExpect(status().isAccepted)
        lunaSucceeds = true
        val retried = assertNotNull(runs.findById(run.id))
        val nextMessage = publishedMessage(retried)
        worker(processor(gemini, luna), queue).process(nextMessage)
        verify(queue).acknowledge(nextMessage.streamRecordId)
        assertEquals(10, calls.size)
        assertEquals(8, calls.count { it == AnalysisProvider.GEMINI })
        assertEquals(2, calls.count { it == AnalysisProvider.OPENAI })
        val next = assertNotNull(invocations.findLatestByRun(run.id))
        assertNotEquals(original.id, next.id)
        assertTrue(next.runVersion > original.runVersion)
        assertEquals(4, next.geminiAttempts)
        assertEquals(1, next.lunaAttempts)
        assertEquals(CoordinationStatus.COMPLETED, runs.findById(run.id)?.status)
        assertEquals(run.batch, runs.findById(run.id)?.batch)
        assertEquals(versions, versionIds())
        assertEquals(2, count("analysis_invocations"))
        val usage =
            jdbc.queryForMap(
                "SELECT input_tokens, output_tokens, estimated_cost_usd FROM coordination_attempts " +
                    "WHERE invocation_id = ? AND provider = 'OPENAI'",
                next.id,
            )
        assertEquals(100L, usage["input_tokens"])
        assertEquals(20L, usage["output_tokens"])
        assertNull(usage["estimated_cost_usd"], "Luna usage must not use Gemini pricing")
    }

    private fun queuedRoom(): Pair<CompletedCorrectionFixture, CoordinationRun> {
        val fixture = completedRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status = 'QUEUED', candidate_quality = NULL, version = version + 1 WHERE id = ?",
            fixture.sourceRun,
        )
        return fixture to assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
    }

    private fun readyToMatch(): Pair<CoordinationRun, AnalysisInvocation> {
        val run = assertNotNull(persistence.start(queuedRoom().second))
        val invocation = assertNotNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        val attempt =
            CoordinationAttempt(
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
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt))
        assertTrue(
            persistence.completeBounded(
                run,
                run.batch.submissionVersionIds.map(::result),
                attempt.copy(finishedAt = NOW),
                invocation,
            ) {
                true
            },
        )
        return assertNotNull(runs.findById(run.id)) to assertNotNull(invocations.findLatestByRun(run.id))
    }

    private fun result(id: SubmissionVersionId) =
        StructuredSubmissionResult(
            id,
            listOf(
                StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, null, DayOfWeek.WEDNESDAY, LocalTime.of(19, 0), LocalTime.of(21, 0)),
            ),
            null,
        )

    private fun processor(
        gemini: NaturalLanguageParserPort,
        luna: NaturalLanguageParserPort,
    ) = GeminiBatchProcessor(
        runs,
        submissions,
        structured,
        attempts,
        gemini,
        IdGenerator { UUID.randomUUID() },
        RetryDelayPort { time.now = time.now.plus(it) },
        JitterPort { 0 },
        MonotonicTimePort { Duration.between(NOW, time.now).toNanos() },
        time,
        persistence,
        matchingProcessor = matching,
        fallbackParser = luna,
        invocationRepository = invocations,
    )

    private fun worker(
        processor: GeminiBatchProcessor,
        queue: CoordinationWorkQueuePort,
    ) = CoordinationWorker(
        outbox,
        queue,
        CoordinationEventProcessor { _, batch -> processor.process(batch) },
        DeadLetterPersistence { _, _, _ -> error("bounded provider failure must not enter DLQ") },
        time,
    )

    private fun publishedMessage(run: CoordinationRun): CoordinationWorkMessage {
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
            time.now.atOffset(java.time.ZoneOffset.UTC),
            id,
        )
        return CoordinationWorkMessage("synthetic-$id", OutboxEventId(id), run.batch.id, CollectionClosureService.STRUCTURING_REQUESTED)
    }

    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun assertBlocked(pid: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, pid) == true) return
            Thread.sleep(15)
        } while (System.nanoTime() < until)
        assertTrue(false, "Matching publication never waited on the PostgreSQL room lock")
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
