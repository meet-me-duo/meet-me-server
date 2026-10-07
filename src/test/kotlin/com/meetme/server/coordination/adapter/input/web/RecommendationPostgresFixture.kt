package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// Independent #95 synthetic inputs; real HTTP, matcher, application publishing, and PostgreSQL. No provider calls.
abstract class RecommendationPostgresFixture : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var matching: MatchingProcessor

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    protected fun recommendedRoom(
        days: Int = 5,
        partial: Boolean = false,
    ): CompletedCorrectionFixture {
        val fixture = matchingRoom()
        publish(fixture.sourceRun, days, partial)
        return fixture
    }

    protected fun matchingRoom(): CompletedCorrectionFixture {
        val fixture = completedRoom()
        jdbc.update("UPDATE meeting_rooms SET meeting_mode = 'EITHER' WHERE id = ?", fixture.roomId)
        return fixture
    }

    protected fun publish(
        runId: UUID,
        days: Int = 5,
        partial: Boolean = false,
    ) {
        prepareMatching(runId, days, partial)
        process(runId)
    }

    protected fun prepareMatching(
        runId: UUID,
        days: Int = 5,
        partial: Boolean = false,
    ) {
        jdbc.update(
            "UPDATE coordination_runs SET status = 'MATCHING', candidate_quality = NULL, version = version + 1 WHERE id = ?",
            runId,
        )
        val run = requireNotNull(runs.findById(CoordinationRunId(runId)))
        val windows =
            if (days == 0) {
                // No AVAILABLE condition means full search-range availability; explicit exclusions make zero windows.
                (0 until 5).map { day ->
                    StructuredCondition.TimeWindow(
                        TimePolarity.UNAVAILABLE,
                        LocalDate.of(2026, 10, 7).plusDays(day.toLong()),
                        null,
                        LocalTime.MIDNIGHT,
                        LocalTime.MIDNIGHT,
                        endsAtNextDayStart = true,
                    )
                }
            } else {
                (0 until days).map { day ->
                    StructuredCondition.TimeWindow(
                        TimePolarity.AVAILABLE,
                        LocalDate.of(2026, 10, 7).plusDays(day.toLong()),
                        null,
                        LocalTime.of(18, 0),
                        LocalTime.of(21, 0),
                    )
                }
            }
        val conditions =
            windows + StructuredCondition.SpecificPlace("합성 공통 지역", areaKey = "AREA_1", areaName = "합성 공통 지역")
        structured.replaceForBatch(
            run.batch.id,
            run.batch.submissionVersionIds.mapIndexed { index, id ->
                StructuredSubmissionResult(id, conditions, if (partial && index == 0) "CONDITION_VALIDATION_FAILED" else null)
            },
            Instant.parse("2026-10-07T05:00:00Z"),
        )
    }

    protected fun process(runId: UUID) {
        matching.process(requireNotNull(runs.findById(CoordinationRunId(runId))).batch.id)
    }

    protected fun recommendations(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/{code}/recommendations", actor.code).cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    protected fun alternatives(
        fixture: CompletedCorrectionFixture,
        cursor: String? = null,
        analysis: UUID = fixture.sourceRun,
        limit: Int = 1,
        actor: CorrectionSession = fixture.host,
    ): MockHttpServletRequestBuilder {
        val request =
            get("/api/rooms/{code}/recommendations/alternatives", actor.code)
                .cookie(actor.cookie())
                .param("analysis_id", analysis.toString())
                .param("limit", limit.toString())
        cursor?.let { request.param("cursor", it) }
        return request
    }

    protected fun selection(
        fixture: CompletedCorrectionFixture,
        option: Map<String, Any?>,
        variant: Map<String, Any?> = items(option, "variants").first(),
        analysis: UUID = fixture.sourceRun,
        start: String = child(option, "time_range").getValue("start_at").toString(),
        end: String = child(option, "time_range").getValue("end_at").toString(),
        actor: CorrectionSession = fixture.host,
    ): MockHttpServletRequestBuilder =
        post("/api/rooms/{code}/recommendations/{option}/confirmation", actor.code, option.getValue("option_id"))
            .header("Origin", ORIGIN)
            .cookie(actor.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                mapper.writeValueAsString(
                    mapOf("analysis_id" to analysis, "variant_id" to variant.getValue("variant_id"), "start_at" to start, "end_at" to end),
                ),
            )

    protected fun result(actor: CorrectionSession): Map<String, Any?> =
        document(
            mvc
                .perform(get("/api/rooms/{code}/result", actor.code).cookie(actor.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response,
        )

    @Suppress("UNCHECKED_CAST")
    protected fun items(
        document: Map<String, Any?>,
        field: String,
    ): List<Map<String, Any?>> = document.getValue(field) as List<Map<String, Any?>>

    protected fun frozenVersions(runId: UUID): List<UUID> =
        jdbc
            .queryForList(
                "SELECT i.submission_version_id FROM coordination_runs r JOIN submission_batch_items i ON i.batch_id = r.batch_id " +
                    "WHERE r.id = ? ORDER BY i.submission_version_id",
                UUID::class.java,
                runId,
            ).map { requireNotNull(it) }
}
