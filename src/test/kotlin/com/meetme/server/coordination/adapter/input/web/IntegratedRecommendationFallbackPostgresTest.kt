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
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #99 integration: frozen D2 input, bounded fallback, recommendation atomicity, and typed confirmation. Paid calls: zero. */
@Import(ControlledTime::class)
class IntegratedRecommendationFallbackPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var outbox: OutboxRepository

    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var matching: MatchingProcessor

    @Autowired private lateinit var time: DurableClock

    @MockitoSpyBean private lateinit var recommendationsRepository: RecommendationRepository

    @BeforeEach
    fun resetTime() {
        time.now = NOW
    }

    @Test
    fun `Luna fallback persists frozen preferences publishes subwindow and confirms actual selection without repeating calls`() {
        val (fixture, run) = queuedRoom()
        val inputs = inputSnapshot()
        val calls = mutableListOf<AnalysisProvider>()
        val gemini =
            NaturalLanguageParserPort {
                calls += AnalysisProvider.GEMINI
                throw NaturalLanguageParserException(ParserFailureKind.SERVER)
            }
        val luna =
            NaturalLanguageParserPort { request ->
                calls += AnalysisProvider.OPENAI
                NaturalLanguageBatchResult(
                    request.inputs.map {
                        preferredResult(SubmissionVersionId(UUID.fromString(it.inputRef)))
                    },
                    ParserUsage(123, 45, 678),
                )
            }
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        val processor = processor(gemini, luna)

        worker(processor, queue).process(message)

        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(message.eventId)?.status)
        assertEquals(List(4) { AnalysisProvider.GEMINI } + AnalysisProvider.OPENAI, calls)
        assertEquals(CoordinationStatus.COMPLETED, runs.findById(run.id)?.status)
        assertEquals(inputs, inputSnapshot())
        assertEquals(run.batch, runs.findById(run.id)?.batch)
        assertEquals(
            run.batch.submissionVersionIds
                .map(::preferredResult)
                .toSet(),
            structured.findByBatch(run.batch.id).toSet(),
        )
        val stored =
            assertNotNull(recommendationsRepository.find(run.id.value), "Bounded completion must publish the recommendation protocol")
        val preferred = stored.options.first { it.primaryRank == 1 }
        assertEquals(PREFERRED, preferred.option.window)
        assertEquals(
            2,
            preferred.option.variants
                .first()
                .preferenceCount,
        )
        assertTrue(stored.options.any { it.option.window == AVAILABLE }, "Hard availability must survive preference projection")
        val response = recommendations(fixture.host)
        assertEquals("diverse-time-v1", response["protocol"])
        val option = items(response, "options").first()
        val publicText = mapper.writeValueAsString(response)
        listOf(
            "preference_count",
            "raw_text",
            "H0 original",
            "M0 original",
            "PREFERRED_TIME_WINDOW",
            "conditions",
            "submission_version_id",
        ).forEach {
            assertTrue(!publicText.contains(it), "Public recommendation response must not expose $it")
        }
        val selectedStart = "2026-10-07T11:15:00Z"
        val selectedEnd = "2026-10-07T11:45:00Z"
        mvc.perform(selection(fixture, option, start = selectedStart, end = selectedEnd)).andExpect(status().isOk)
        mvc.perform(selection(fixture, option, start = selectedStart, end = selectedEnd)).andExpect(status().isOk)
        assertEquals(
            InstantTimeRange(Instant.parse(selectedStart), Instant.parse(selectedEnd)),
            recommendationsRepository.findSelection(run.id.value)?.selectedWindow,
        )
        assertEquals(1, count("recommendation_selections"))
        assertNull(child(result(fixture.host), "candidate")["candidate_id"])
        assertEquals(selectedStart, child(result(fixture.host), "selection")["start_at"])
        mvc.perform(reopen(fixture)).andExpect(status().isConflict)
        worker(processor, queue).process(message.copy(streamRecordId = "synthetic-confirmed-duplicate"))
        assertEquals(5, calls.size)
        assertEquals(stored, recommendationsRepository.find(run.id.value))
        assertEquals(inputs, inputSnapshot())
    }

    @Test
    fun `concurrent duplicate processors share one physical fallback budget and one recommendation publication`() {
        val (_, run) = queuedRoom()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val geminiCalls = AtomicInteger()
        val lunaCalls = AtomicInteger()
        val gemini =
            NaturalLanguageParserPort {
                geminiCalls.incrementAndGet()
                throw NaturalLanguageParserException(ParserFailureKind.SERVER)
            }
        val luna =
            NaturalLanguageParserPort { request ->
                lunaCalls.incrementAndGet()
                entered.countDown()
                check(release.await(8, TimeUnit.SECONDS))
                NaturalLanguageBatchResult(
                    request.inputs.map {
                        preferredResult(SubmissionVersionId(UUID.fromString(it.inputRef)))
                    },
                    ParserUsage(1, 1, 100),
                )
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit { processor(gemini, luna).process(run.batch.id) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val original = assertNotNull(invocations.findLatestByRun(run.id))
            executor.submit { processor(gemini, luna).process(run.batch.id) }.get(5, TimeUnit.SECONDS)
            assertEquals(original, invocations.findLatestByRun(run.id), "Redelivery cannot reset ownership, counters, or deadline")
            release.countDown()
            owner.get(10, TimeUnit.SECONDS)
            assertEquals(4, geminiCalls.get())
            assertEquals(1, lunaCalls.get())
            assertEquals(1, count("analysis_invocations"))
            assertEquals(1, count("recommendation_analyses"))
            assertNotNull(recommendationsRepository.find(run.id.value)?.options?.firstOrNull { it.option.window == PREFERRED })
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cancellation before projection preserves frozen structured input and delays without any published result`() {
        val run = readyToMatch()
        val inputs = inputSnapshot()

        matching.processBounded(run.batch.id) { false }

        assertDelayedWithoutPublication(run, inputs)
    }

    @Test
    fun `cancellation after actual projection rolls back legacy candidates projection and room transition`() {
        val run = readyToMatch()
        val inputs = inputSnapshot()
        val publishedInsideTransaction = AtomicInteger()
        doAnswer { call ->
            call.callRealMethod().also {
                publishedInsideTransaction.incrementAndGet()
                assertTrue(count("candidates") > 0)
                assertEquals(1, count("recommendation_analyses"))
            }
        }.`when`(recommendationsRepository).publish(anyAnalysis())

        matching.processBounded(run.batch.id) { publishedInsideTransaction.get() == 0 }

        assertEquals(1, publishedInsideTransaction.get(), "The fixture must reach actual projection publication before cancellation")
        assertDelayedWithoutPublication(run, inputs)
    }

    @Test
    fun `durable invocation deadline crossed after actual projection rolls back every recommendation and candidate row`() {
        val run = readyToMatch()
        val inputs = inputSnapshot()
        val invocation = assertNotNull(invocations.findLatestByRun(run.id))
        val publishedInsideTransaction = AtomicInteger()
        doAnswer { call ->
            call.callRealMethod().also {
                publishedInsideTransaction.incrementAndGet()
                assertEquals(1, count("recommendation_analyses"))
                time.now = invocation.deadlineAt
            }
        }.`when`(recommendationsRepository).publish(anyAnalysis())

        matching.processBounded(run.batch.id) { true }

        assertEquals(1, publishedInsideTransaction.get(), "The deadline must cross after actual recommendation rows were written")
        assertDelayedWithoutPublication(run, inputs)
        assertEquals(
            invocation,
            invocations.findLatestByRun(run.id),
            "Matching cancellation cannot erase the provider winner or restart its deadline",
        )
    }

    @Test
    fun `stored hard exclusion is subtracted before preferred subwindow and cannot be confirmed through preference`() {
        val (fixture, queued) = queuedRoom()
        val run = assertNotNull(persistence.start(queued))
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
        val excluded =
            run.batch.submissionVersionIds.map { id ->
                val preferred = preferredResult(id)
                preferred.copy(
                    conditions =
                        preferred.conditions +
                            StructuredCondition.TimeWindow(
                                TimePolarity.UNAVAILABLE,
                                LocalDate.of(2026, 10, 7),
                                null,
                                LocalTime.of(20, 15),
                                LocalTime.of(20, 45),
                            ),
                )
            }
        assertTrue(persistence.claimAttempt(run, invocation, AnalysisProvider.GEMINI, attempt))
        assertTrue(persistence.completeBounded(run, excluded, attempt.copy(finishedAt = NOW), invocation) { true })

        matching.processBounded(run.batch.id) { true }

        assertEquals(excluded.toSet(), structured.findByBatch(run.batch.id).toSet())
        val stored = assertNotNull(recommendationsRepository.find(run.id.value))
        val gapStart = Instant.parse("2026-10-07T11:15:00Z")
        val gapEnd = Instant.parse("2026-10-07T11:45:00Z")
        assertTrue(stored.options.isNotEmpty())
        assertTrue(stored.options.none { it.option.window.startInclusive < gapEnd && it.option.window.endExclusive > gapStart })
        assertTrue(
            stored.options.any {
                it.option.window == InstantTimeRange(PREFERRED.startInclusive, gapStart) &&
                    it.option.variants
                        .first()
                        .preferenceCount == 2
            },
        )
        assertTrue(
            stored.options.any {
                it.option.window == InstantTimeRange(gapEnd, PREFERRED.endExclusive) &&
                    it.option.variants
                        .first()
                        .preferenceCount == 2
            },
        )
        val option = items(recommendations(fixture.host), "options").first()
        mvc.perform(selection(fixture, option, start = gapStart.toString(), end = gapEnd.toString())).andExpect(status().isBadRequest)
        assertEquals(0, count("recommendation_selections"))
    }

    @Test
    fun `eight frozen participants and five preference windows roll back at original deadline and ACK without repeating providers`() {
        val (fixture, original) = queuedRoom()
        repeat(6) { addFrozenParticipant(fixture.roomId, original.batch.id.value) }
        val run = assertNotNull(runs.findById(original.id))
        assertEquals(8, run.batch.submissionVersionIds.size)
        val inputs = inputSnapshot()
        val geminiCalls = AtomicInteger()
        val lunaCalls = AtomicInteger()
        val actualPublication = AtomicInteger()
        val expected = run.batch.submissionVersionIds.map(::manyWindowResult)
        doAnswer { call ->
            call.callRealMethod().also {
                val stored = call.getArgument<RecommendationAnalysis>(0)
                assertTrue(stored.options.size >= 10, "All five hard windows and five meaningful preference subwindows must reach storage")
                assertTrue(stored.options.flatMap { it.option.variants }.any { it.participantIds.size == 8 && it.preferenceCount == 8 })
                actualPublication.incrementAndGet()
                time.now = assertNotNull(invocations.findLatestByRun(run.id)).deadlineAt
            }
        }.`when`(recommendationsRepository).publish(anyAnalysis())
        val gemini =
            NaturalLanguageParserPort {
                geminiCalls.incrementAndGet()
                throw NaturalLanguageParserException(ParserFailureKind.SERVER)
            }
        val luna =
            NaturalLanguageParserPort {
                lunaCalls.incrementAndGet()
                NaturalLanguageBatchResult(expected, ParserUsage(10, 10, 100))
            }
        val queue = mock(CoordinationWorkQueuePort::class.java)
        val message = publishedMessage(run)
        val processor = processor(gemini, luna)

        worker(processor, queue).process(message)

        verify(queue).acknowledge(message.streamRecordId)
        assertEquals(1, actualPublication.get(), "Actual large projection must be inside the original completion budget")
        assertEquals(OutboxStatus.PROCESSED, outbox.findById(message.eventId)?.status)
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, runs.findById(run.id)?.status)
        assertEquals(ResumeStage.MATCHING, runs.findById(run.id)?.resumeStage)
        assertEquals(expected.toSet(), structured.findByBatch(run.batch.id).toSet())
        assertEquals(inputs, inputSnapshot())
        assertEquals(run.batch, runs.findById(run.id)?.batch)
        listOf(
            "candidates",
            "normalized_places",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
        ).forEach {
            assertEquals(0, count(it), "Original deadline must roll back $it even for larger projections")
        }
        worker(processor, queue).process(message.copy(streamRecordId = "synthetic-large-deadline-redelivery"))
        assertEquals(4, geminiCalls.get())
        assertEquals(1, lunaCalls.get())
        assertEquals(1, actualPublication.get())
    }

    private fun manyWindowResult(id: SubmissionVersionId) =
        StructuredSubmissionResult(
            id,
            (0 until 5).flatMap { day ->
                val date = LocalDate.of(2026, 10, 7).plusDays(day.toLong())
                listOf(
                    StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, date, null, LocalTime.of(18, 0), LocalTime.of(22, 0)),
                    StructuredCondition.PreferredTimeWindow(date, null, LocalTime.of(20, 0), LocalTime.of(21, 0)),
                )
            },
            null,
        )

    private fun addFrozenParticipant(
        room: UUID,
        batch: UUID,
    ) {
        val session = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val submission = UUID.randomUUID()
        val version = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,'2026-11-01T00:00:00Z','2026-10-07T00:00:00Z')",
            session,
            UUID.randomUUID().toString(),
        )
        jdbc.update(
            "INSERT INTO participants(id,room_id,guest_session_id,role,joined_at,display_name) VALUES (?,?,?,'MEMBER','2026-10-07T00:00:00Z','synthetic member')",
            participant,
            room,
            session,
        )
        jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", submission, room, participant)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,1,'synthetic multiwindow','ko-KR','2026-10-07T00:00:00.123456Z')",
            version,
            submission,
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, submission)
        jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", batch, version)
    }

    private fun assertDelayedWithoutPublication(
        run: CoordinationRun,
        inputs: List<Map<String, Any?>>,
    ) {
        val delayed = assertNotNull(runs.findById(run.id))
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, delayed.status)
        assertEquals(ResumeStage.MATCHING, delayed.resumeStage)
        assertEquals(run.batch, delayed.batch)
        assertEquals(inputs, inputSnapshot())
        assertEquals(
            run.batch.submissionVersionIds
                .map(::preferredResult)
                .toSet(),
            structured.findByBatch(run.batch.id).toSet(),
        )
        listOf(
            "candidates",
            "normalized_places",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_variant_participants",
            "recommendation_selections",
        ).forEach {
            assertEquals(0, count(it), "Deadline/cancellation must roll back $it")
        }
        assertNull(delayed.quality)
        assertNull(recommendationsRepository.find(run.id.value))
        assertEquals(
            run.id.value,
            jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms WHERE id=?", UUID::class.java, run.roomId.value),
        )
    }

    private fun queuedRoom(): Pair<CompletedCorrectionFixture, CoordinationRun> {
        val fixture = completedRoom()
        jdbc.update("UPDATE coordination_runs SET status='QUEUED',candidate_quality=NULL,version=version+1 WHERE id=?", fixture.sourceRun)
        return fixture to assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
    }

    private fun readyToMatch(): CoordinationRun {
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
                run.batch.submissionVersionIds.map(::preferredResult),
                attempt.copy(finishedAt = NOW),
                invocation,
            ) {
                true
            },
        )
        return assertNotNull(runs.findById(run.id))
    }

    private fun preferredResult(id: SubmissionVersionId) =
        StructuredSubmissionResult(
            id,
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
        DeadLetterPersistence { _, _, _ -> error("Bounded deadline/fallback must not become a poison message") },
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
        return CoordinationWorkMessage("synthetic-$id", OutboxEventId(id), run.batch.id, CollectionClosureService.STRUCTURING_REQUESTED)
    }

    private fun anyAnalysis(): RecommendationAnalysis =
        org.mockito.ArgumentMatchers.any(RecommendationAnalysis::class.java)
            ?: RecommendationAnalysis(UUID(0, 0), UUID(0, 0), emptyList())

    private fun inputSnapshot(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT id,submission_id,revision,raw_text,created_at FROM submission_versions ORDER BY id")

    private fun count(table: String): Int = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")
        private val AVAILABLE = InstantTimeRange(Instant.parse("2026-10-07T09:00:00Z"), Instant.parse("2026-10-07T13:00:00Z"))
        private val PREFERRED = InstantTimeRange(Instant.parse("2026-10-07T11:00:00Z"), Instant.parse("2026-10-07T12:00:00Z"))
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
