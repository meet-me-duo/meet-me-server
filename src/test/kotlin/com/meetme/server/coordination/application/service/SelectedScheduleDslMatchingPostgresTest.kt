package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.integration.SelectedApprovedTermsProviderPostgresTest
import com.meetme.server.coordination.adapter.output.persistence.JdbcFinalAnalysisRouteRegistry
import com.meetme.server.coordination.adapter.output.persistence.JdbcRecommendationRepository
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.RecommendationAnalysis
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslStoredCalculation
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Three real DB actors; typed synthetic response, not provider/model or language-accuracy proof. */
@SpringBootTest
@Import(
    SelectedApprovedTermsProviderPostgresTest.ApprovedPublicationConfig::class,
    SelectedScheduleDslMatchingPostgresTest.Configuration::class,
)
class SelectedScheduleDslMatchingPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var envelopes: ScheduleDslRepository

    @Autowired private lateinit var approved: ScheduleDslApprovedTermsRepository

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var publication: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var clock: SelectedScheduleDslPublicationPostgresTest.PublicationClock

    @Autowired private lateinit var recommendationWrites: AfterRecommendationWrite

    @Autowired
    @Qualifier("selectedMatchingWriter")
    private lateinit var persistence: MatchingProcessingPersistenceService
    private lateinit var binding: FinalAnalysisAttemptBinding
    private lateinit var winner: UUID
    private var matchingVersion = 0L
    private lateinit var upstreamEnvelopes: List<String>
    private lateinit var upstreamInputs: List<String>
    private lateinit var upstreamInvocations: List<String>
    private lateinit var flat: List<String>

    @BeforeEach
    fun actualC3PublicationFromAcceptedNativeRows() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
        recommendationWrites.afterPublish = {}
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val snapshot = ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
        val choice =
            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", input.policy.version),
                input.policy,
                snapshot.snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        routes.freeze(F.ir.run, 1, choice)
        val run = assertNotNull(publication.start(SubmissionBatchId(F.ir.batch)))
        val admitted = assertNotNull(publication.admit(run, UUID(0, 9831), Duration.ofSeconds(60)))
        binding = admitted.binding
        winner = admitted.attempt.id
        val response = ScheduleDslProviderResponse(response3(), ParserUsage(17, 23, 100), AnalysisProvider.GEMINI, "gemini-3.8-flash")
        assertTrue(publication.completeDsl(binding, admitted.attempt, response) { true })
        matchingVersion = binding.invocation.runVersion + 1
        assertEquals(CoordinationStatus.MATCHING, assertNotNull(runs.findById(CoordinationRunId(F.ir.run))).status)
        upstreamEnvelopes = F.rows(jdbc, "schedule_ir_envelopes")
        upstreamInputs = F.rows(jdbc, "schedule_ir_inputs")
        upstreamInvocations = F.rows(jdbc, "analysis_invocations")
        flat = F.rows(jdbc, "structured_submission_results")
    }

    @Test
    fun `SFM01 final recommendation uses stored C3 calculation and publishes complete nineteen to twenty one remote`() {
        var reads = 0
        val observedReader = wrappingReader { reads++ }
        assertTrue(service(observedReader).processBounded(binding, winner, matchingVersion) { true })
        assertEquals(1, reads)
        val completed = assertNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        assertEquals(CoordinationStatus.COMPLETED, completed.status)
        assertEquals(matchingVersion + 1, completed.version)
        assertEquals(CandidateQuality.COMPLETE, completed.quality)
        assertEquals(listOf(expectedWindow()), completed.candidates.single().timeRanges)
        assertEquals(MeetingMode.REMOTE, completed.candidates.single().meetingMode)
        val recommendation = assertNotNull(recommendationWrites.find(F.ir.run))
        assertEquals(listOf(expectedWindow()), recommendation.options.map { it.option.window })
        assertTrue(recommendation.options.flatMap { it.option.variants }.all { it.meetingMode == MeetingMode.REMOTE })
        assertUpstreamPreserved()
    }

    @Test
    fun `SFM02 source changed after repeatable read is rejected by fresh writer fence`() {
        var reads = 0
        val racingReader =
            wrappingReader {
                reads++
                jdbc.update("UPDATE submission_versions SET raw_text=raw_text || ' synthetic source change' WHERE id=?", F.ir.first)
            }
        assertFalse(service(racingReader).processBounded(binding, winner, matchingVersion) { true })
        assertEquals(1, reads)
        assertNoMatchingPublication()
        assertUpstreamPreserved()
    }

    @Test
    fun `SFM03 deadline crossing after actual recommendation write rolls back matching publication`() {
        var didWrite = false
        recommendationWrites.afterPublish = {
            didWrite = true
            assertEquals(1, count("recommendation_analyses"))
            clock.set(binding.invocation.deadlineAt.plusNanos(1))
        }
        assertFalse(service(reader).processBounded(binding, winner, matchingVersion) { true })
        assertTrue(didWrite)
        assertNoMatchingPublication()
        assertUpstreamPreserved()
    }

    private fun service(read: ScheduleDslStoredReadService) =
        SelectedScheduleDslMatchingService(
            runs,
            rooms,
            submissions,
            routes,
            invocations,
            attempts,
            read,
            persistence,
            IdGenerator { UUID.randomUUID() },
            clock,
        )

    /** Raw wrapper is intentionally not a Spring bean: delegate finishes its RR transaction before hook. */
    private fun wrappingReader(afterRead: () -> Unit): ScheduleDslStoredReadService {
        assertTrue(AopUtils.isAopProxy(reader))
        return object : ScheduleDslStoredReadService(runs, rooms, submissions, routes, envelopes, approved) {
            override fun readStored(
                runId: CoordinationRunId,
                expectedCurrentRunVersion: Long,
                expectedSelectionFingerprint: String,
            ): ScheduleDslStoredCalculation =
                reader.readStored(runId, expectedCurrentRunVersion, expectedSelectionFingerprint).also { afterRead() }
        }
    }

    private fun assertNoMatchingPublication() {
        val run = assertNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        assertNotEquals(CoordinationStatus.COMPLETED, run.status)
        assertTrue(run.candidates.isEmpty())
        assertEquals(0, count("recommendation_analyses"))
        assertEquals(0, count("recommendation_options"))
        assertEquals(0, count("recommendation_variants"))
        assertEquals(0, count("normalized_places"))
    }

    private fun assertUpstreamPreserved() {
        assertEquals(upstreamEnvelopes, F.rows(jdbc, "schedule_ir_envelopes"))
        assertEquals(upstreamInputs, F.rows(jdbc, "schedule_ir_inputs"))
        assertEquals(upstreamInvocations, F.rows(jdbc, "analysis_invocations"))
        assertEquals(winner, jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java))
        assertEquals(flat, F.rows(jdbc, "structured_submission_results"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun response3(): String {
        val document = mapper.readValue(F.q.wire.json(), MutableMap::class.java) as MutableMap<String, Any?>
        document["semantic_version"] = "schedule-ir-product-4"
        document["vocabulary_version"] = "ko-time-terms-1"
        (document["results"] as List<MutableMap<String, Any?>>).forEach { it["approved_terms"] = emptyList<Any>() }
        return mapper.writeValueAsString(document)
    }

    private fun expectedWindow() = InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    class AfterRecommendationWrite(
        private val delegate: RecommendationRepository,
    ) : RecommendationRepository by delegate {
        var afterPublish: () -> Unit = {}

        override fun publish(analysis: RecommendationAnalysis) {
            delegate.publish(analysis)
            afterPublish()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun matchingPublicationClock() = SelectedScheduleDslPublicationPostgresTest.PublicationClock()

        @Bean fun matchingRouteRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
            clock: Clock,
        ): FinalAnalysisRouteRegistry = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, clock)

        @Bean @Primary
        fun afterRecommendationWrite(jdbc: JdbcTemplate) = AfterRecommendationWrite(JdbcRecommendationRepository(jdbc))

        @Bean("selectedMatchingWriter")
        @Primary
        fun selectedMatchingWriter(
            rooms: MeetingRoomRepository,
            runs: CoordinationRunRepository,
            places: NormalizedPlaceRepository,
            recommendations: AfterRecommendationWrite,
            invocations: AnalysisInvocationRepository,
            clock: Clock,
        ) = MatchingProcessingPersistenceService(rooms, runs, places, recommendations, invocations, clock)
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
        fun stopDatabase() {
            postgres.stop()
        }
    }
}
