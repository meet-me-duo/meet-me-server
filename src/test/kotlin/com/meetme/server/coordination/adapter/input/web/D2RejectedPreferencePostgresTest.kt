package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue #99 review regression: rejected preference dimensions cannot silently award an AND score. */
class D2RejectedPreferencePostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var recommendationsRepository: RecommendationRepository

    @Test
    fun `rejected time preference with retained place preference preserves hard availability but awards no preference score`() {
        assertRejectedPreference(
            StructuredCondition.PreferredPlace("synthetic common area", "AREA_1", "synthetic common area"),
            preservePreferredTimeWindow = false,
        )
    }

    @Test
    fun `rejected place preference retains hard availability and time subwindow without awarding a preference score`() {
        assertRejectedPreference(
            StructuredCondition.PreferredTimeWindow(DATE, null, LocalTime.of(20, 0), LocalTime.of(21, 0)),
            preservePreferredTimeWindow = true,
        )
    }

    private fun assertRejectedPreference(
        retainedPreference: StructuredCondition,
        preservePreferredTimeWindow: Boolean,
    ) {
        val fixture = matchingRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status='MATCHING',candidate_quality=NULL,version=version+1 WHERE id=?",
            fixture.sourceRun,
        )
        val run = assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        val storedResults =
            run.batch.submissionVersionIds.map { version ->
                StructuredSubmissionResult(
                    version,
                    listOf(
                        StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0)),
                        StructuredCondition.SpecificPlace("synthetic common area", areaKey = "AREA_1", areaName = "synthetic common area"),
                        retainedPreference,
                    ),
                    "CONDITION_VALIDATION_FAILED",
                )
            }
        structured.replaceForBatch(run.batch.id, storedResults, Instant.parse("2026-10-07T00:00:00Z"))

        process(fixture.sourceRun)

        assertEquals(storedResults.toSet(), structured.findByBatch(run.batch.id).toSet())
        assertEquals(CandidateQuality.PARTIAL, runs.findById(run.id)?.quality)
        assertEquals(run.batch, runs.findById(run.id)?.batch)
        val analysis = assertNotNull(recommendationsRepository.find(fixture.sourceRun))
        assertTrue(analysis.options.any { it.option.window == AVAILABLE }, "Rejected preferences cannot erase hard availability")
        if (preservePreferredTimeWindow) {
            assertTrue(
                analysis.options.any {
                    it.option.window == PREFERRED
                },
                "Retained valid time preference still defines a meaningful possible subwindow",
            )
        }
        assertTrue(analysis.options.flatMap { it.option.variants }.isNotEmpty())
        assertTrue(
            analysis.options.flatMap { it.option.variants }.all { it.preferenceCount == 0 },
            "A partially rejected preference cannot claim every explicitly supplied dimension was satisfied",
        )
    }

    companion object {
        private val DATE = LocalDate.of(2026, 10, 7)
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
