package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.ConditionalTimeActivation
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real PostgreSQL JSON restore and both matching publications; provider requests are never executed. */
class ConditionalTimePostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var structuredResults: StructuredSubmissionRepository

    @Autowired private lateinit var coordinationRuns: CoordinationRunRepository

    @Autowired private lateinit var storedRecommendations: RecommendationRepository

    @Autowired private lateinit var matchingProcessor: MatchingProcessor

    @Autowired private lateinit var matchingPersistence: MatchingProcessingPersistenceService

    @Test
    fun `primary match keeps fallback closed after JSON restore in legacy and modern publications`() {
        verifyPublication(primaryMatches = true)
    }

    @Test
    fun `fallback opens after JSON restore with exclusions and no artificial minimum duration`() {
        verifyPublication(primaryMatches = false)
    }

    private fun verifyPublication(primaryMatches: Boolean) {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = requireNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val primary = window(7, "14:00", "18:00")
        val fallback = fallback()
        val preferences = StructuredCondition.PreferredTimeWindow(primary.date, null, primary.startTime, primary.endTime)
        val exclusion = window(10, "11:05", "11:10", TimePolarity.UNAVAILABLE)
        val first = listOf(primary, preferences, fallback, exclusion)
        val second = listOfNotNull(if (primaryMatches) window(7, "14:00", "14:30") else null, window(10, "11:00", "11:20"))
        val results =
            listOf(
                StructuredSubmissionResult(run.batch.submissionVersionIds[0], first, null),
                StructuredSubmissionResult(run.batch.submissionVersionIds[1], second, null),
            )
        structuredResults.replaceForBatch(run.batch.id, results, Instant.parse("2026-10-07T05:00:00Z"))
        assertEquals(results.toSet(), structuredResults.findByBatch(run.batch.id).toSet())

        process(fixture.sourceRun)

        val completed = requireNotNull(coordinationRuns.findById(run.id))
        val expected =
            if (primaryMatches) {
                listOf(
                    utc(7, "14:00", "14:30"),
                )
            } else {
                listOf(utc(10, "11:00", "11:05"), utc(10, "11:10", "11:20"))
            }
        assertEquals(CandidateQuality.COMPLETE, completed.quality)
        assertEquals(expected, completed.candidates.single().timeRanges)
        val modern = requireNotNull(storedRecommendations.find(fixture.sourceRun))
        assertEquals(expected, modern.options.map { it.option.window })
        assertTrue(modern.options.all { option -> option.option.variants.all { it.preferenceCount == if (primaryMatches) 1 else 0 } })
        assertFalse(structuredResults.findByBatch(run.batch.id).any { it.rejectionCode != null })
        assertTrue(structuredResults.findByBatch(run.batch.id).flatMap { it.conditions }.any { it == fallback })
        assertEquals(expected.size, (recommendations(fixture.host).getValue("total_options") as Number).toInt())
    }

    @Test
    fun `multiple conditional participants persist unsupported reasons and expose a partial result`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = requireNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        structuredResults.replaceForBatch(
            run.batch.id,
            run.batch.submissionVersionIds.mapIndexed { index, id ->
                StructuredSubmissionResult(id, listOf(window(7 + index, "14:00", "18:00"), fallback()), null)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )

        process(fixture.sourceRun)

        assertEquals(CandidateQuality.PARTIAL, coordinationRuns.findById(run.id)?.quality)
        assertTrue(
            coordinationRuns
                .findById(run.id)
                ?.candidates
                .orEmpty()
                .isEmpty(),
        )
        val restored = structuredResults.findByBatch(run.batch.id)
        assertEquals(2, restored.size)
        assertTrue(restored.all { it.rejectionCode == "UNSUPPORTED_CONDITIONAL_CONSTRAINT" })
        assertTrue(restored.all { it.conditions.any { condition -> condition is StructuredCondition.ConditionalFallbackTimeWindow } })
        assertEquals("NO_MATCH", room(fixture.host)["public_status"])
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].reason").value("UNSUPPORTED_CONDITIONAL_CONSTRAINT"))
            .andExpect(jsonPath("$[1].reason").value("UNSUPPORTED_CONDITIONAL_CONSTRAINT"))
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.member.code).cookie(fixture.member.cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
    }

    @Test
    fun `cancellation after conditional reason rewrite rolls back reasons and all published rows`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = requireNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val results =
            run.batch.submissionVersionIds.mapIndexed { index, id ->
                StructuredSubmissionResult(id, listOf(window(7 + index, "14:00", "18:00"), fallback()), null)
            }
        structuredResults.replaceForBatch(run.batch.id, results, Instant.parse("2026-10-07T05:00:00Z"))
        var observedConditionalWrite = false

        matchingProcessor.processBounded(run.batch.id) {
            val stillBeforeWrite = structuredResults.findByBatch(run.batch.id).none { it.rejectionCode != null }
            if (!stillBeforeWrite) observedConditionalWrite = true
            stillBeforeWrite
        }

        assertTrue(observedConditionalWrite, "Cancellation must happen after the real DB rejection rewrite")
        assertEquals(results.toSet(), structuredResults.findByBatch(run.batch.id).toSet())
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, coordinationRuns.findById(run.id)?.status)
        assertTrue(
            coordinationRuns
                .findById(run.id)
                ?.candidates
                .orEmpty()
                .isEmpty(),
        )
        assertEquals(null, storedRecommendations.find(fixture.sourceRun))
    }

    @Test
    fun `stale matching run cannot execute the conditional rejection rewrite`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = requireNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val completed = run.complete(CandidateQuality.PARTIAL, emptyList())
        jdbc.update("UPDATE coordination_runs SET version = version + 1 WHERE id = ?", fixture.sourceRun)
        var writes = 0

        assertFalse(matchingPersistence.completeBounded(completed, emptyList(), persistInputDiagnostics = { writes++ }) { true })

        assertEquals(0, writes)
        assertEquals(CoordinationStatus.MATCHING, coordinationRuns.findById(run.id)?.status)
    }

    private fun window(
        day: Int,
        start: String,
        end: String,
        polarity: TimePolarity = TimePolarity.AVAILABLE,
    ) = StructuredCondition.TimeWindow(polarity, LocalDate.of(2026, 10, day), null, LocalTime.parse(start), LocalTime.parse(end))

    private fun fallback() =
        StructuredCondition.ConditionalFallbackTimeWindow(
            ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
            LocalDate.of(2026, 10, 10),
            null,
            LocalTime.of(11, 0),
            LocalTime.of(15, 0),
        )

    private fun utc(
        day: Int,
        start: String,
        end: String,
    ) = com.meetme.server.shared.domain.time.InstantTimeRange(
        LocalDate.of(2026, 10, day).atTime(LocalTime.parse(start)).toInstant(java.time.ZoneOffset.ofHours(9)),
        LocalDate.of(2026, 10, day).atTime(LocalTime.parse(end)).toInstant(java.time.ZoneOffset.ofHours(9)),
    )

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
