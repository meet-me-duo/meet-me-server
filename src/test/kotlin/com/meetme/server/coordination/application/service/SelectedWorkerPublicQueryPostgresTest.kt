package com.meetme.server.coordination.application.service

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.integration.SelectedApprovedTermsProviderPostgresTest
import com.meetme.server.coordination.application.port.input.GetCandidatesUseCase
import com.meetme.server.coordination.application.port.input.GetUnappliedInputsUseCase
import com.meetme.server.coordination.application.port.input.MatchingResultErrorCode
import com.meetme.server.coordination.application.port.input.MatchingResultException
import com.meetme.server.coordination.application.port.input.RecommendationUseCase
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslStoredCalculation
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.testsupport.AccountPrincipalTestFixture
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Real PostgreSQL + worker/application public queries + existing guest auth; no HTTP or provider dispatch proof. */
@SpringBootTest
@Import(
    SelectedApprovedTermsProviderPostgresTest.ApprovedPublicationConfig::class,
    SelectedScheduleDslMatchingPostgresTest.Configuration::class,
)
class SelectedWorkerPublicQueryPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var envelopes: ScheduleDslRepository

    @Autowired private lateinit var approved: ScheduleDslApprovedTermsRepository

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var publication: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var credentials: GuestCredentialPort

    @Autowired private lateinit var recommendations: RecommendationUseCase

    @Autowired private lateinit var candidates: GetCandidatesUseCase

    @Autowired private lateinit var unapplied: GetUnappliedInputsUseCase

    @MockitoBean private lateinit var currentAccount: CurrentAccountPort
    private lateinit var hostCookie: Cookie

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Autowired private lateinit var recommendationWrites: SelectedScheduleDslMatchingPostgresTest.AfterRecommendationWrite

    @Autowired
    @Qualifier("selectedMatchingWriter")
    private lateinit var matchingPersistence: MatchingProcessingPersistenceService
    private var providerCalls = 0
    private var calculationReads = 0
    private lateinit var flat: List<String>

    @BeforeEach
    fun acceptedNativeInputAndRealGuestAuthentication() {
        RequestContextHolder.resetRequestAttributes()
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        recommendationWrites.afterPublish = {}
        providerCalls = 0
        calculationReads = 0
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val capsule = ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
        routes.freeze(
            F.ir.run,
            1,
            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", input.policy.version),
                input.policy,
                capsule.snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            ),
        )
        // Existing business-test account fixture supplies principal context; DB resolver still requires this room's host.
        val accounts = AccountPrincipalTestFixture(jdbc, currentAccount)
        accounts.install()
        hostCookie = accounts.newAccount("synthetic-host")
        val hostId = UUID.fromString(hostCookie.value.removePrefix(AccountPrincipalTestFixture.PREFIX))
        jdbc.update("UPDATE participants SET guest_session_id=NULL,user_id=? WHERE id=?", hostId, F.ir.firstParticipant)
        // Member public reads use real guest digest/session/proof/ownership, independent of mocked account context.
        jdbc.update(
            "UPDATE guest_browser_sessions SET credential_digest=? WHERE id=?",
            credentials.digest(RAW_GUEST),
            UUID(0, 502),
        )
        flat = F.rows(jdbc, "structured_submission_results")
    }

    @Test
    fun `SWPQ01 selected worker reaches completion and real authenticated public queries use stored nineteen to twenty one`() {
        worker().process(SubmissionBatchId(F.ir.batch))
        assertEquals(CoordinationStatus.COMPLETED, currentRun().status)
        assertEquals(1, providerCalls)
        assertEquals(1, calculationReads)
        val beforeReads = storedRows()
        assertPublicWindow()
        assertPublicWindow()
        val denied = assertFailsWith<MatchingResultException> { recommendations.primary(INVITE, null, Locale.KOREAN) }
        assertEquals(MatchingResultErrorCode.GUEST_SESSION_REQUIRED, denied.code)
        assertEquals(beforeReads, storedRows())
        assertEquals(1, providerCalls)
        assertEquals(1, calculationReads)
        assertEquals(flat, F.rows(jdbc, "structured_submission_results"))
    }

    @Test
    fun `SWPQ02 matching redelivery resumes original winner and deadline without another provider attempt`() {
        val admitted = publishToMatching()
        val before = upstreamRows()
        worker().process(SubmissionBatchId(F.ir.batch))
        assertEquals(CoordinationStatus.COMPLETED, currentRun().status)
        assertEquals(0, providerCalls)
        assertEquals(1, calculationReads)
        assertEquals(before, upstreamRows())
        assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        assertEquals(1, count("coordination_attempts"))
        assertPublicWindow()
    }

    @Test
    fun `SWPQ03 expired original matching invocation cannot be renewed by worker redelivery`() {
        val admitted = publishToMatching()
        val before = upstreamRows()
        clock.set(
            admitted.binding.invocation.deadlineAt
                .plusNanos(1),
        )
        worker().process(SubmissionBatchId(F.ir.batch))
        assertNotEquals(CoordinationStatus.COMPLETED, currentRun().status)
        assertTrue(currentRun().candidates.isEmpty())
        assertEquals(0, count("recommendation_analyses"))
        assertEquals(0, providerCalls)
        assertEquals(1, count("coordination_attempts"))
        assertEquals(before, upstreamRows())
        val notReady = assertFailsWith<MatchingResultException> { recommendations.primary(INVITE, RAW_GUEST, Locale.KOREAN) }
        assertEquals(MatchingResultErrorCode.CANDIDATES_NOT_READY, notReady.code)
    }

    @Test
    fun `SWPQ04 completed worker redelivery preserves original public result and identifiers`() {
        val worker = worker()
        worker.process(SubmissionBatchId(F.ir.batch))
        assertEquals(CoordinationStatus.COMPLETED, currentRun().status)
        val before = storedRows()
        val primary = recommendations.primary(INVITE, RAW_GUEST, Locale.KOREAN)
        val candidateList = candidates.getCandidates(INVITE, RAW_GUEST, Locale.KOREAN)
        worker.process(SubmissionBatchId(F.ir.batch))
        assertEquals(before, storedRows())
        assertEquals(primary, recommendations.primary(INVITE, RAW_GUEST, Locale.KOREAN))
        assertEquals(candidateList, candidates.getCandidates(INVITE, RAW_GUEST, Locale.KOREAN))
        assertEquals(1, providerCalls)
        assertEquals(1, calculationReads)
    }

    private fun assertPublicWindow() {
        val primary = recommendations.primary(INVITE, RAW_GUEST, Locale.KOREAN)
        assertEquals(F.ir.run, primary.analysisId)
        assertEquals(CandidateQuality.COMPLETE, primary.quality)
        assertEquals(listOf(expectedWindow()), primary.options.map { it.timeRange })
        assertTrue(primary.options.flatMap { it.variants }.all { it.meetingMode == MeetingMode.REMOTE && it.attendanceCount == 2 })
        val list = candidates.getCandidates(INVITE, RAW_GUEST, Locale.KOREAN)
        assertEquals(F.ir.run, list.analysisId)
        assertEquals(CandidateQuality.COMPLETE, list.quality)
        assertEquals(2, list.appliedSubmissions)
        assertEquals(2, list.totalSubmissions)
        assertEquals(0, list.unappliedInputs)
        assertEquals(emptyList(), asHost { unapplied.getUnappliedInputs(INVITE, null) })
        assertEquals(listOf(expectedWindow()), list.candidates.single().timeRanges)
        assertEquals(MeetingMode.REMOTE, list.candidates.single().meetingMode)
    }

    private fun <T> asHost(action: () -> T): T {
        val previous = RequestContextHolder.getRequestAttributes()
        val request = MockHttpServletRequest().apply { setCookies(hostCookie) }
        try {
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
            return action()
        } finally {
            RequestContextHolder.resetRequestAttributes()
            previous?.let { RequestContextHolder.setRequestAttributes(it) }
        }
    }

    private fun worker(): GeminiBatchProcessor {
        assertTrue(AopUtils.isAopProxy(reader))
        val countedReader =
            object : ScheduleDslStoredReadService(runs, rooms, submissions, routes, envelopes, approved) {
                override fun readStored(
                    runId: CoordinationRunId,
                    expectedCurrentRunVersion: Long,
                    expectedSelectionFingerprint: String,
                ): ScheduleDslStoredCalculation {
                    calculationReads++
                    return reader.readStored(runId, expectedCurrentRunVersion, expectedSelectionFingerprint)
                }
            }
        val matching =
            SelectedScheduleDslMatchingService(
                runs,
                rooms,
                submissions,
                routes,
                invocations,
                attempts,
                countedReader,
                matchingPersistence,
                IdGenerator { UUID.randomUUID() },
                clock,
            )
        val selected =
            SelectedScheduleDslBatchProcessor(
                publication,
                SelectedScheduleDslProviderPort {
                    providerCalls++
                    response3()
                },
                SelectedScheduleDslProviderPort { error("No fallback expected in synthetic success actor") },
                RetryDelayPort { error("No retry expected in synthetic success actor") },
                JitterPort { 0 },
                MonotonicTimePort(System::nanoTime),
            )
        val dispatcher =
            SelectedFinalAnalysisDispatchService(
                runs,
                routes,
                selected,
                invocations = invocations,
                matching = matching,
                clock = clock,
                monotonicTime = MonotonicTimePort(System::nanoTime),
            )
        return GeminiBatchProcessor(
            runs,
            submissions,
            structured,
            attempts,
            NaturalLanguageParserPort { error("Typed selection must not invoke legacy parser") },
            IdGenerator { error("Typed selection must not allocate a legacy attempt") },
            RetryDelayPort { error("Typed selection must not enter legacy retry") },
            JitterPort { error("Typed selection must not enter legacy jitter") },
            MonotonicTimePort(System::nanoTime),
            clock,
            mock(GeminiProcessingPersistenceService::class.java),
            selectedDispatcher = dispatcher,
        )
    }

    private fun publishToMatching(): SelectedScheduleDslInvocation {
        val run = assertNotNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(publication.admit(run, UUID(0, 9917), Duration.ofSeconds(60)))
        assertTrue(publication.completeDsl(admitted.binding, admitted.attempt, response3()) { true })
        assertEquals(CoordinationStatus.MATCHING, currentRun().status)
        return admitted
    }

    @Suppress("UNCHECKED_CAST")
    private fun response3(): ScheduleDslProviderResponse {
        val document = mapper.readValue(F.q.wire.json(), MutableMap::class.java) as MutableMap<String, Any?>
        document["semantic_version"] = "schedule-ir-product-4"
        document["vocabulary_version"] = "ko-time-terms-1"
        (document["results"] as List<MutableMap<String, Any?>>).forEach { it["approved_terms"] = emptyList<Any>() }
        return ScheduleDslProviderResponse(
            mapper.writeValueAsString(document),
            ParserUsage(17, 23, 100),
            AnalysisProvider.GEMINI,
            "gemini-3.8-flash",
        )
    }

    private fun currentRun() = assertNotNull(runs.findById(CoordinationRunId(F.ir.run)))

    private fun expectedWindow() = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun upstreamRows() =
        listOf("schedule_ir_envelopes", "schedule_ir_inputs", "analysis_invocations", "coordination_attempts").associateWith {
            F.rows(jdbc, it)
        }

    private fun storedRows() =
        upstreamRows() +
            listOf(
                "coordination_runs",
                "meeting_rooms",
                "recommendation_analyses",
                "recommendation_options",
                "recommendation_variants",
            ).associateWith { F.rows(jdbc, it) }

    companion object {
        private const val INVITE = "route-synthetic-000001"
        private const val RAW_GUEST = "synthetic-selected-query-credential"
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
        fun stopDatabase() {
            postgres.stop()
        }
    }
}
