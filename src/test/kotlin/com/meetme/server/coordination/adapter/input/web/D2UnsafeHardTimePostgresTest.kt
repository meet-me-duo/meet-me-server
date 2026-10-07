package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.adapter.output.integration.NaturalLanguageProviderContract
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue #99 review: the actual provider validator must carry failed hard-time safety into PostgreSQL matching. */
class D2UnsafeHardTimePostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var recommendationsRepository: RecommendationRepository

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "UNAVAILABLE"])
    fun `malformed hard time stays unsafe through provider validation DB restore and matching`(polarity: String) {
        val fixture = matchingRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status='MATCHING',candidate_quality=NULL,version=version+1 WHERE id=?",
            fixture.sourceRun,
        )
        val run = assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        val originalInputs = jdbc.queryForList("SELECT id,raw_text,created_at FROM submission_versions ORDER BY id")
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                DATE,
                DATE.plusDays(5),
                run.batch.submissionVersionIds.mapIndexed { index, version ->
                    NaturalLanguageInput(version.value.toString(), "synthetic hard time input $index", Locale.KOREAN, DATE)
                },
            )
        val wire =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        request.inputs.mapIndexed { index, input ->
                            mapOf(
                                "input_ref" to input.inputRef,
                                "conditions" to
                                    if (index == 0) {
                                        listOf(
                                            // A provider reversed an explicit hard restriction. It cannot be silently ignored.
                                            timeCondition("TIME_WINDOW", polarity, "22:00", "18:00"),
                                            timeCondition("PREFERRED_TIME_WINDOW", null, "20:00", "21:00"),
                                            placeCondition(),
                                        ) +
                                            if (polarity == "UNAVAILABLE") {
                                                // A valid AVAILABLE statement does not make a discarded explicit prohibition safe.
                                                listOf(timeCondition("TIME_WINDOW", "AVAILABLE", "18:00", "22:00"))
                                            } else {
                                                emptyList()
                                            }
                                    } else {
                                        listOf(timeCondition("TIME_WINDOW", "AVAILABLE", "19:00", "22:00"), placeCondition())
                                    },
                                "rejection_code" to null,
                            )
                        },
                ),
            )
        val parsed = NaturalLanguageProviderContract(mapper).parseProviderResponse(wire, request, strict = true)
        structured.replaceForBatch(run.batch.id, parsed, Instant.parse("2026-10-07T00:00:00Z"))

        process(fixture.sourceRun)

        val completed = assertNotNull(runs.findById(run.id))
        assertEquals(CandidateQuality.PARTIAL, completed.quality)
        assertEquals(run.batch, completed.batch)
        assertEquals(originalInputs, jdbc.queryForList("SELECT id,raw_text,created_at FROM submission_versions ORDER BY id"))
        assertTrue(
            completed.candidates.isEmpty(),
            "One safely available attendee cannot turn a discarded hard time restriction into two attendees",
        )
        val analysis = assertNotNull(recommendationsRepository.find(fixture.sourceRun))
        assertTrue(analysis.options.isEmpty(), "Preference-only remnants cannot invent attendance or a confirmable recommendation")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recommendation_selections", Int::class.java))
        val restored = structured.findByBatch(run.batch.id)
        assertEquals(parsed.toSet(), restored.toSet())
        val unsafe = restored.single { it.submissionVersionId == run.batch.submissionVersionIds.first() }
        assertEquals(
            "AMBIGUOUS_TIME_CONSTRAINT",
            unsafe.rejectionCode,
            "The unsafe hard-time metadata must survive provider validation and DB roundtrip",
        )
        assertTrue(unsafe.conditions.any { it is StructuredCondition.PreferredTimeWindow })
        assertTrue(unsafe.conditions.any { it is StructuredCondition.SpecificPlace })
    }

    private fun timeCondition(
        type: String,
        polarity: String?,
        start: String,
        end: String,
    ): Map<String, Any?> =
        mapOf(
            "type" to type,
            "polarity" to polarity,
            "date" to DATE.toString(),
            "day_of_week" to null,
            "start_time" to start,
            "end_time" to end,
        )

    private fun placeCondition(): Map<String, Any?> =
        mapOf(
            "type" to "SPECIFIC_PLACE",
            "query" to "synthetic common area",
            "area_key" to "AREA_1",
            "area_name" to "synthetic common area",
        )

    companion object {
        private val DATE = LocalDate.of(2026, 10, 7)
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
