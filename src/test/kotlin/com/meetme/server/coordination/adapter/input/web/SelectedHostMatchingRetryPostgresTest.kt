package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.integration.SelectedApprovedTermsProviderPostgresTest
import com.meetme.server.coordination.application.port.input.RetryAnalysisUseCase
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.service.CoordinationEventDispatcher
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.SelectedScheduleDslMatchingPostgresTest
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.application.service.SelectedScheduleDslPublicationPostgresTest
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Actual PG/host application CTA/outbox/event/matcher; existing business account context, not JWT or paid-call proof. */
@Import(
    SelectedApprovedTermsProviderPostgresTest.ApprovedPublicationConfig::class,
    SelectedScheduleDslMatchingPostgresTest.Configuration::class,
    SelectedHostMatchingRetryPostgresTest.ForbiddenProviders::class,
)
class SelectedHostMatchingRetryPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var outbox: OutboxRepository

    @Autowired private lateinit var publication: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var retry: RetryAnalysisUseCase

    @Autowired private lateinit var events: CoordinationEventDispatcher

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Autowired private lateinit var recommendationWrites: SelectedScheduleDslMatchingPostgresTest.AfterRecommendationWrite

    @Autowired
    @Qualifier("selectedMatchingWriter")
    private lateinit var matchingPersistence: MatchingProcessingPersistenceService

    @Autowired private lateinit var forbidden: ProviderCounter
    private lateinit var host: CorrectionSession
    private lateinit var original: FinalAnalysisAttemptBinding
    private lateinit var winner: UUID
    private lateinit var originalHistory: Map<String, List<String>>

    @BeforeEach
    fun actualPublishedWinnerThenDelayedMatchingAfterOriginalProviderDeadline() {
        clock.set(F.selectedAt)
        recommendationWrites.afterPublish = {}
        forbidden.calls.set(0)
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val cookie = accounts.newAccount("host")
        val account = UUID.fromString(cookie.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.update("UPDATE participants SET guest_session_id=NULL,user_id=? WHERE id=?", account, F.ir.firstParticipant)
        host = CorrectionSession(INVITE, cookie.value)
        val choice =
            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", input.policy.version),
                input.policy,
                ScheduleDslOfflinePipeline.snapshotApprovedTerms(input).snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        routes.freeze(F.ir.run, 1, choice)
        val started = assertNotNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(publication.admit(started, UUID(0, 9981), Duration.ofSeconds(60)))
        original = admitted.binding
        winner = admitted.attempt.id
        assertTrue(publication.completeDsl(original, admitted.attempt, response3()) { true })
        matchingPersistence.delay(current())
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, current().status)
        clock.set(original.invocation.deadlineAt.plusSeconds(1))
        originalHistory = historyRows()
    }

    @Test
    fun `HMR01 explicit host CTA publishes new pure compute authority after original provider expiry without changing history`() {
        val event = hostRetryPublished()
        val payload = payload(event)
        assertTrue(clock.instant() > original.invocation.deadlineAt)
        assertEquals(event.id.value.toString(), payload["event_id"])
        assertEquals(F.ir.batch.toString(), payload["submission_batch_id"])
        assertEquals(current().version, (payload.getValue("matching_run_version") as Number).toLong())
        assertEquals(original.invocation.id.toString(), payload["source_invocation_id"])
        assertEquals(winner.toString(), payload["source_winner_attempt_id"])
        assertEquals(original.route.fingerprint(), payload["route_fingerprint"])
        clock.set(event.occurredAt.plusSeconds(59))
        dispatch()
        assertEquals(CoordinationStatus.COMPLETED, current().status)
        assertEquals(CandidateQuality.COMPLETE, current().quality)
        assertEquals(listOf(expectedWindow()), current().candidates.single().timeRanges)
        assertEquals(1, count("recommendation_analyses"))
        assertEquals(originalHistory, historyRows())
        assertEquals(0, forbidden.calls.get())
        val complete = finalRows()
        dispatch()
        assertEquals(complete, finalRows())
        assertEquals(0, forbidden.calls.get())
    }

    @Test
    fun `HMR02 published event expiry and republishing cannot restart its original sixty second computation window`() {
        val event = hostRetryPublished()
        clock.set(event.occurredAt.plusSeconds(60).plusNanos(1))
        outbox.markRepublished(event.id, clock.instant())
        rejectOrDelay()
        assertNoPublication()
        assertEquals(event.occurredAt, assertNotNull(outbox.findById(event.id)).occurredAt)
        clock.set(clock.instant().plusSeconds(30))
        outbox.markRepublished(event.id, clock.instant())
        rejectOrDelay()
        assertNoPublication()
        assertEquals(originalHistory, historyRows())
        assertEquals(0, forbidden.calls.get())
    }

    @Test
    fun `HMR03 malformed actual DB authority cannot authorize matching publication`() {
        val event = hostRetryPublished()
        val malformed = payload(event).toMutableMap().apply { put("source_winner_attempt_id", "not-a-uuid") }
        jdbc.update("UPDATE outbox_events SET payload=CAST(? AS jsonb) WHERE id=?", mapper.writeValueAsString(malformed), event.id.value)
        rejectOrDelay()
        assertNoPublication()
        assertEquals(originalHistory, historyRows())
        assertEquals(0, forbidden.calls.get())
    }

    @Test
    fun `HMR04 duplicate same version DB retry authorities are rejected rather than choosing a convenient winner`() {
        val event = hostRetryPublished()
        val secondId = OutboxEventId(UUID(0, 9984))
        val duplicatePayload = payload(event).toMutableMap().apply { put("event_id", secondId.value.toString()) }
        outbox.insert(
            event.copy(
                id = secondId,
                payload = mapper.writeValueAsString(duplicatePayload),
                status = OutboxStatus.PENDING,
                publishedAt = null,
            ),
        )
        outbox.markPublished(secondId, clock.instant())
        assertEquals(2, count("outbox_events"))
        rejectOrDelay()
        assertNoPublication()
        assertEquals(originalHistory, historyRows())
        assertEquals(0, forbidden.calls.get())
    }

    @Test
    fun `HMR05 new host deadline crossing after actual recommendation write rolls back every matching write`() {
        val event = hostRetryPublished()
        var actualWrite = false
        recommendationWrites.afterPublish = {
            actualWrite = true
            assertEquals(1, count("recommendation_analyses"))
            clock.set(event.occurredAt.plusSeconds(60).plusNanos(1))
        }
        dispatch()
        assertTrue(actualWrite)
        assertNoPublication()
        assertEquals(originalHistory, historyRows())
        assertEquals(0, forbidden.calls.get())
    }

    private fun hostRetryPublished(): OutboxEvent {
        asHost { retry.retry(INVITE, null) }
        assertEquals(CoordinationStatus.MATCHING, current().status)
        val id = assertNotNull(jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id=?", UUID::class.java, F.ir.run))
        val pending = assertNotNull(outbox.findById(OutboxEventId(id)))
        assertEquals(OutboxStatus.PENDING, pending.status)
        assertEquals(CollectionClosureService.MATCHING_REQUESTED, pending.eventType)
        assertEquals("CoordinationRun", pending.aggregateType)
        assertEquals(F.ir.run, pending.aggregateId)
        assertEquals(
            setOf(
                "event_id",
                "submission_batch_id",
                "matching_run_version",
                "source_invocation_id",
                "source_winner_attempt_id",
                "route_fingerprint",
            ),
            payload(pending).keys,
        )
        outbox.markPublished(pending.id, clock.instant())
        return assertNotNull(outbox.findById(pending.id)).also { assertEquals(OutboxStatus.PUBLISHED, it.status) }
    }

    /** Invalid authority may surface existing typed CONFIGURATION or an explicit delayed/dead-lettered state. */
    private fun rejectOrDelay() {
        val failure = runCatching { dispatch() }.exceptionOrNull()
        if (failure != null) {
            assertTrue(failure is NaturalLanguageParserException)
            assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
        } else {
            assertTrue(current().status in setOf(CoordinationStatus.ANALYSIS_DELAYED, CoordinationStatus.DEAD_LETTERED))
        }
    }

    private fun assertNoPublication() {
        assertTrue(current().status != CoordinationStatus.COMPLETED)
        assertTrue(current().candidates.isEmpty())
        assertEquals(0, count("recommendation_analyses"))
        assertEquals(0, count("recommendation_options"))
        assertEquals(0, count("recommendation_variants"))
        assertEquals(0, count("normalized_places"))
    }

    private fun dispatch() = events.process(CollectionClosureService.MATCHING_REQUESTED, SubmissionBatchId(F.ir.batch))

    private fun current() = assertNotNull(runs.findById(CoordinationRunId(F.ir.run)))

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    @Suppress("UNCHECKED_CAST")
    private fun payload(event: OutboxEvent) = mapper.readValue(event.payload, Map::class.java) as Map<String, Any?>

    private fun historyRows() =
        listOf(
            "analysis_invocations",
            "coordination_attempts",
            "ai_room_budgets",
            "ai_call_reservations",
            "ai_call_observations",
            "final_analysis_routes",
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
            "structured_submission_results",
        ).associateWith { F.rows(jdbc, it) }

    private fun finalRows() =
        historyRows() +
            listOf(
                "coordination_runs",
                "meeting_rooms",
                "recommendation_analyses",
                "recommendation_options",
                "recommendation_variants",
            ).associateWith {
                F.rows(jdbc, it)
            }

    private fun expectedWindow() = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))

    private fun <T> asHost(action: () -> T): T {
        val previous = RequestContextHolder.getRequestAttributes()
        val request = MockHttpServletRequest().apply { setCookies(host.cookie()) }
        try {
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
            return action()
        } finally {
            RequestContextHolder.resetRequestAttributes()
            previous?.let { RequestContextHolder.setRequestAttributes(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun response3(): ScheduleDslProviderResponse {
        val root = mapper.readValue(F.q.wire.json(), MutableMap::class.java) as MutableMap<String, Any?>
        root["semantic_version"] = "schedule-ir-product-4"
        root["vocabulary_version"] = "ko-time-terms-1"
        (root["results"] as List<MutableMap<String, Any?>>).forEach { it["approved_terms"] = emptyList<Any>() }
        return ScheduleDslProviderResponse(
            mapper.writeValueAsString(root),
            ParserUsage(17, 23, 100),
            AnalysisProvider.GEMINI,
            "gemini-3.8-flash",
        )
    }

    class ProviderCounter {
        val calls = AtomicInteger()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ForbiddenProviders {
        // Explicit test clock prevents real DB/JIT latency from consuming the final one-second wall fixture window.
        @Bean @Primary
        fun hostRetryMonotonicTime() = MonotonicTimePort { 0L }

        @Bean fun hostRetryProviderCounter() = ProviderCounter()

        @Bean("selectedScheduleDslGeminiProvider")
        fun forbiddenGemini(counter: ProviderCounter) =
            SelectedScheduleDslProviderPort {
                counter.calls.incrementAndGet()
                throw AssertionError("Host matching retry must not dispatch a provider")
            }

        @Bean("selectedScheduleDslLunaProvider")
        fun forbiddenLuna(counter: ProviderCounter) =
            SelectedScheduleDslProviderPort {
                counter.calls.incrementAndGet()
                throw AssertionError("Host matching retry must not dispatch a provider")
            }
    }

    companion object {
        private const val INVITE = "route-synthetic-000001"
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.openai.api-key") { "" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
