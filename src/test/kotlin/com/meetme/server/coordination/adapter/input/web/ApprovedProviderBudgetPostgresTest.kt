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
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.CoordinationWorker
import com.meetme.server.coordination.application.service.DeadLetterPersistence
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.RecommendationAnalysis
import com.meetme.server.coordination.domain.ResumeStage
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approved PR100 follow-up: Gemini stage 30s, Luna up to 27s, completion reserve 3s, durable total 60s. No paid calls. */
@Import(ControlledTime::class)
class ApprovedProviderBudgetPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var outbox: OutboxRepository

    @Autowired private lateinit var matching: MatchingProcessor

    @Autowired private lateinit var time: DurableClock

    @MockitoSpyBean private lateinit var persistence: GeminiProcessingPersistenceService

    @MockitoSpyBean private lateinit var recommendationsRepository: RecommendationRepository

    @BeforeEach
    fun resetTime() {
        time.now = NOW
    }

    @Test
    fun `actual Gemini admission allows just before second 30 and rejects at second 30 without consuming budget`() {
        val run = assertNotNull(persistence.start(queuedRoom()))
        val invocation = assertNotNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        time.now = NOW.plusMillis(29_999)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 1)))
        time.now = NOW.plusSeconds(30)

        assertFalse(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 2)))

        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertEquals(1, stored.geminiAttempts)
        assertEquals(0, stored.lunaAttempts)
        assertEquals(NOW.plusSeconds(60), stored.deadlineAt)
        assertEquals(invocation.ownerToken, stored.ownerToken)
        assertEquals(1, attempts.countByRun(run.id))
    }

    @Test
    fun `actual invocation retains one owner sixty second deadline and four plus one admissions across stale snapshots`() {
        val run = assertNotNull(persistence.start(queuedRoom()))
        val invocation = assertNotNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        time.now = NOW.plusSeconds(20)
        repeat(4) { index ->
            assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, index + 1)))
        }
        assertFalse(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt(run, invocation, 5)))
        time.now = NOW.plusSeconds(31)
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.OPENAI, attempt(run, invocation, 5, AnalysisProvider.OPENAI)))
        assertFalse(
            persistence.claimAttempt(run, invocation, AnalysisProvider.OPENAI, attempt(run, invocation, 6, AnalysisProvider.OPENAI)),
        )
        assertNull(persistence.claimInvocation(run, UUID.randomUUID(), Duration.ofSeconds(60)))
        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertEquals(4, stored.geminiAttempts)
        assertEquals(1, stored.lunaAttempts)
        assertEquals(invocation.id, stored.id)
        assertEquals(invocation.ownerToken, stored.ownerToken)
        assertEquals(NOW, stored.startedAt)
        assertEquals(NOW.plusSeconds(60), stored.deadlineAt)
        assertFalse(
            persistence.claimAttempt(
                run,
                invocation.copy(ownerToken = UUID.randomUUID()),
                AnalysisProvider.OPENAI,
                attempt(run, invocation, 6, AnalysisProvider.OPENAI),
            ),
        )
        assertEquals(stored, invocations.findLatestByRun(run.id))
        assertEquals(5, attempts.countByRun(run.id))
    }

    @Test
    fun `actual admission delay leaves Gemini one hundred milliseconds before its second 30 stage cutoff`() {
        val run = queuedRoom()
        var geminiClaims = 0
        interceptAdmission(run) { provider, before ->
            if (before && provider == AnalysisProvider.GEMINI && ++geminiClaims == 2) time.now = NOW.plusMillis(29_900)
        }
        val timeouts = mutableListOf<Duration>()
        val gemini =
            NaturalLanguageParserPort {
                timeouts += it.callTimeout
                if (timeouts.size == 1) throw NaturalLanguageParserException(ParserFailureKind.SERVER)
                result(run)
            }

        processor(
            gemini,
            NaturalLanguageParserPort { error("Successful Gemini within its stage must not call Luna") },
        ).process(run.batch.id)

        assertEquals(listOf(Duration.ofSeconds(15), Duration.ofMillis(100)), timeouts)
        assertEquals(CoordinationStatus.COMPLETED, runs.findById(run.id)?.status)
        assertNotNull(recommendationsRepository.find(run.id.value))
    }

    @Test
    fun `Luna admitted at second 31 gets twenty six seconds and twenty second result completes recommendations atomically`() {
        val run = queuedRoom()
        val inputs = inputSnapshot()
        interceptAdmission(run) { provider, before ->
            if (before && provider == AnalysisProvider.OPENAI) time.now = NOW.plusSeconds(31)
        }
        val geminiCalls = AtomicInteger()
        var lunaTimeout: Duration? = null
        val lunaCalls = AtomicInteger()
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        val processor =
            processor(
                unavailableGemini(geminiCalls),
                NaturalLanguageParserPort {
                    lunaCalls.incrementAndGet()
                    lunaTimeout = it.callTimeout
                    time.now = time.now.plusSeconds(20)
                    result(run)
                },
            )

        worker(processor, queue).process(message)

        assertEquals(Duration.ofSeconds(26), lunaTimeout)
        assertEquals(NOW.plusSeconds(51), time.now)
        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(CoordinationStatus.COMPLETED, runs.findById(run.id)?.status)
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(message.eventId)?.status)
        val completed = assertNotNull(runs.findById(run.id))
        val analysis = assertNotNull(recommendationsRepository.find(run.id.value))
        assertTrue(completed.candidates.isNotEmpty())
        assertTrue(analysis.options.isNotEmpty())
        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertEquals(NOW.plusSeconds(60), stored.deadlineAt)
        assertEquals(4, stored.geminiAttempts)
        assertEquals(1, stored.lunaAttempts)
        val winner = assertNotNull(stored.winnerAttemptId)
        assertEquals("OPENAI", jdbc.queryForMap("SELECT provider FROM coordination_attempts WHERE id=?", winner)["provider"])
        assertNull(jdbc.queryForMap("SELECT failure_kind FROM coordination_attempts WHERE id=?", winner)["failure_kind"])
        assertEquals(run.batch, completed.batch)
        assertEquals(inputs, inputSnapshot())
        assertEquals(result(run).results.toSet(), structured.findByBatch(run.batch.id).toSet())
        worker(processor, queue).process(message.copy(streamRecordId = "synthetic-budget-completed-redelivery"))
        assertEquals(4, geminiCalls.get())
        assertEquals(1, lunaCalls.get())
        assertEquals(stored, invocations.findLatestByRun(run.id))
        assertEquals(analysis, recommendationsRepository.find(run.id.value))
    }

    @ParameterizedTest
    @ValueSource(longs = [57, 60])
    fun `Luna admission at cutoff cannot start HTTP and ends delayed ACK with frozen input intact`(second: Long) {
        val run = queuedRoom()
        val inputs = inputSnapshot()
        interceptAdmission(run) { provider, before ->
            if (before && provider == AnalysisProvider.OPENAI) time.now = NOW.plusSeconds(second)
        }
        val geminiCalls = AtomicInteger()
        val lunaCalls = AtomicInteger()
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        val processor =
            processor(
                unavailableGemini(geminiCalls),
                NaturalLanguageParserPort {
                    lunaCalls.incrementAndGet()
                    result(run)
                },
            )

        worker(processor, queue).process(message)

        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(message.eventId)?.status)
        assertEquals(0, lunaCalls.get())
        assertDelayed(run, inputs, ResumeStage.STRUCTURING)
        assertEquals(emptyList(), structured.findByBatch(run.batch.id))
        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertEquals(NOW.plusSeconds(60), stored.deadlineAt)
        assertEquals(4, stored.geminiAttempts)
        assertEquals(0, stored.lunaAttempts)
        assertNull(stored.winnerAttemptId)
        assertNotNull(stored.finishedAt)
        worker(processor, queue).process(message.copy(streamRecordId = "synthetic-late-admission-redelivery"))
        assertEquals(4, geminiCalls.get())
        assertEquals(0, lunaCalls.get())
        assertEquals(stored, invocations.findLatestByRun(run.id))
    }

    @ParameterizedTest
    @ValueSource(longs = [57, 60])
    fun `time exhausted after durable Luna admission never starts a physical call or publishes candidates`(second: Long) {
        val run = queuedRoom()
        val inputs = inputSnapshot()
        interceptAdmission(run) { provider, before ->
            if (!before && provider == AnalysisProvider.OPENAI) time.now = NOW.plusSeconds(second)
        }
        val lunaCalls = AtomicInteger()
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        val processor =
            processor(
                unavailableGemini(AtomicInteger()),
                NaturalLanguageParserPort {
                    lunaCalls.incrementAndGet()
                    result(run)
                },
            )

        worker(processor, queue).process(message)

        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(0, lunaCalls.get())
        assertDelayed(run, inputs, ResumeStage.STRUCTURING)
        val stored = assertNotNull(invocations.findLatestByRun(run.id))
        assertEquals(1, stored.lunaAttempts, "The already admitted cost unit remains durable even though no HTTP is allowed")
        assertNull(stored.winnerAttemptId)
        assertEquals(
            "TIMEOUT",
            jdbc.queryForMap(
                "SELECT failure_kind FROM coordination_attempts WHERE invocation_id=? AND provider='OPENAI'",
                stored.id,
            )["failure_kind"],
        )
    }

    @Test
    fun `twenty second Luna winner reaching total deadline during projection rolls back candidates and recommendations then ACKs`() {
        val run = queuedRoom()
        val inputs = inputSnapshot()
        interceptAdmission(run) { provider, before ->
            if (before && provider == AnalysisProvider.OPENAI) time.now = NOW.plusSeconds(31)
        }
        val projections = AtomicInteger()
        doAnswer { call ->
            call.callRealMethod().also {
                projections.incrementAndGet()
                assertEquals(1, count("recommendation_analyses"))
                assertTrue(count("candidates") > 0)
                time.now = NOW.plusSeconds(60)
            }
        }.`when`(recommendationsRepository).publish(anyAnalysis())
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        val processor =
            processor(
                unavailableGemini(AtomicInteger()),
                NaturalLanguageParserPort {
                    time.now = time.now.plusSeconds(20)
                    result(run)
                },
            )

        worker(processor, queue).process(message)

        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(1, projections.get(), "The successful twenty second Luna result must reach actual projection storage")
        assertDelayed(run, inputs, ResumeStage.MATCHING)
        val invocation = assertNotNull(invocations.findLatestByRun(run.id))
        assertNotNull(invocation.winnerAttemptId)
        assertEquals(NOW.plusSeconds(60), invocation.deadlineAt)
        assertEquals(result(run).results.toSet(), structured.findByBatch(run.batch.id).toSet())
    }

    private fun interceptAdmission(
        run: CoordinationRun,
        advance: (AnalysisProvider, Boolean) -> Unit,
    ) {
        val placeholder = AnalysisInvocation(UUID.randomUUID(), run.id, run.version, NOW, NOW.plusSeconds(60), UUID.randomUUID())
        doAnswer { call ->
            val provider = call.getArgument<AnalysisProvider>(2)
            advance(provider, true)
            call.callRealMethod().also { advance(provider, false) }
        }.`when`(persistence).claimAttempt(
            Mockito.any(CoordinationRun::class.java) ?: run,
            Mockito.any(AnalysisInvocation::class.java) ?: placeholder,
            Mockito.any(AnalysisProvider::class.java) ?: AnalysisProvider.GEMINI,
            Mockito.any(CoordinationAttempt::class.java) ?: attempt(run, placeholder, 1),
        )
    }

    private fun queuedRoom(): CoordinationRun {
        val fixture = completedRoom()
        jdbc.update("UPDATE coordination_runs SET status='QUEUED',candidate_quality=NULL,version=version+1 WHERE id=?", fixture.sourceRun)
        return assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
    }

    private fun attempt(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
        number: Int,
        provider: AnalysisProvider = AnalysisProvider.GEMINI,
    ) = CoordinationAttempt(
        UUID.randomUUID(),
        run.id,
        number,
        time.now,
        null,
        null,
        null,
        null,
        null,
        null,
        provider = provider,
        model = if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna",
        policyVersion = AnalysisInvocation.POLICY_VERSION,
        invocationId = invocation.id,
    )

    private fun result(run: CoordinationRun) =
        NaturalLanguageBatchResult(
            run.batch.submissionVersionIds.map { version ->
                StructuredSubmissionResult(
                    version,
                    listOf(
                        StructuredCondition.TimeWindow(
                            TimePolarity.AVAILABLE,
                            LocalDate.of(2026, 10, 7),
                            null,
                            LocalTime.of(18, 0),
                            LocalTime.of(22, 0),
                        ),
                        StructuredCondition.PreferredTimeWindow(LocalDate.of(2026, 10, 7), null, LocalTime.of(20, 0), LocalTime.of(21, 0)),
                    ),
                    null,
                )
            },
            ParserUsage(100, 20, 80),
        )

    private fun unavailableGemini(calls: AtomicInteger) =
        NaturalLanguageParserPort {
            calls.incrementAndGet()
            throw NaturalLanguageParserException(ParserFailureKind.SERVER)
        }

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
        DeadLetterPersistence { _, _, _ -> error("Approved provider budget exhaustion must delay and ACK") },
        time,
    )

    private fun publishedMessage(run: CoordinationRun): CoordinationWorkMessage {
        val id =
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM outbox_events WHERE aggregate_id=? ORDER BY occurred_at DESC LIMIT 1",
                    UUID::class.java,
                    run.id.value,
                ),
            )
        jdbc.update(
            "UPDATE outbox_events SET event_type=?,status='PUBLISHED',published_at=?,processed_at=NULL,processing_lease_until=NULL WHERE id=?",
            CollectionClosureService.STRUCTURING_REQUESTED,
            time.now.atOffset(java.time.ZoneOffset.UTC),
            id,
        )
        return CoordinationWorkMessage(
            "synthetic-approved-budget-$id",
            com.meetme.server.shared.domain
                .OutboxEventId(id),
            run.batch.id,
            CollectionClosureService.STRUCTURING_REQUESTED,
        )
    }

    private fun assertDelayed(
        run: CoordinationRun,
        inputs: List<Map<String, Any?>>,
        stage: ResumeStage,
    ) {
        val delayed = assertNotNull(runs.findById(run.id))
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, delayed.status)
        assertEquals(stage, delayed.resumeStage)
        assertEquals(run.batch, delayed.batch)
        assertEquals(inputs, inputSnapshot())
        listOf(
            "candidates",
            "normalized_places",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
        ).forEach {
            assertEquals(0, count(it), "Exhausted completion budget cannot publish $it")
        }
    }

    private fun anyAnalysis(): RecommendationAnalysis =
        Mockito.any(RecommendationAnalysis::class.java) ?: RecommendationAnalysis(UUID(0, 0), UUID(0, 0), emptyList())

    private fun inputSnapshot(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT id,submission_id,revision,raw_text,created_at FROM submission_versions ORDER BY id")

    private fun count(table: String): Int = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
