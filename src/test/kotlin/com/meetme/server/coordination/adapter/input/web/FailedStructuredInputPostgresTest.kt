package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
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
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-008B/FR-011: real DB restoration, both publications and HOST diagnostics; no provider calls. */
class FailedStructuredInputPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var structuredResults: StructuredSubmissionRepository

    @Autowired private lateinit var coordinationRuns: CoordinationRunRepository

    @Autowired private lateinit var storedRecommendations: RecommendationRepository

    @Autowired private lateinit var matchingProcessor: MatchingProcessor

    @Test
    fun `actual v26 empty failure cannot attend after either adapter response is restored from PostgreSQL`() {
        val recorded = resource("recorded-luna-v26-response.json")
        val fixtureMapper = JsonMapper.builder().build()
        val originalInputs = fixtureMapper.readTree(resource("original-four-regression.json"))["inputs"]
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 9),
                originalInputs
                    .asSequence()
                    .map {
                        NaturalLanguageInput(it["inputRef"].asText(), it["rawText"].asText(), Locale.KOREAN, LocalDate.of(2026, 11, 2))
                    }.toList(),
            )
        val parsedResponses =
            listOf(
                GeminiNaturalLanguageParserAdapter(GeminiProperties(), fixtureMapper).parseProviderResponse(recorded, request),
                OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), fixtureMapper).parseProviderResponse(recorded, request),
            )
        assertEquals(parsedResponses.first(), parsedResponses.last())
        for (parsed in parsedResponses) {
            val fixture = matchingRoom()
            prepareMatching(fixture.sourceRun)
            val run = assertNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
            // Replay original 4's recurring valid hours and original 48's failure against synthetic frozen owners.
            // The recorded response is not edited; only input references map to this synthetic room's versions.
            val restored =
                listOf(
                    parsed.first().copy(submissionVersionId = versionForRole(fixture, "HOST")),
                    parsed.last().copy(submissionVersionId = versionForRole(fixture, "MEMBER")),
                )
            assertEquals(run.batch.submissionVersionIds.toSet(), restored.map { it.submissionVersionId }.toSet())
            structuredResults.replaceForBatch(run.batch.id, restored, PROCESSED_AT)
            assertEquals(restored.toSet(), structuredResults.findByBatch(run.batch.id).toSet())

            process(fixture.sourceRun)

            assertNoAttendance(fixture, CandidateQuality.PARTIAL)
            assertCounts(fixture, applied = 1, unapplied = 1)
            assertDiagnostic(fixture, "CONDITION_VALIDATION_FAILED")
            assertEquals(restored.toSet(), structuredResults.findByBatch(run.batch.id).toSet())
        }
    }

    @Test
    fun `missing frozen result is persisted as a failure and cannot supply a second attendee`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = assertNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val host = StructuredSubmissionResult(versionForRole(fixture, "HOST"), listOf(window()), null)
        structuredResults.replaceForBatch(run.batch.id, listOf(host), PROCESSED_AT)

        process(fixture.sourceRun)

        assertNoAttendance(fixture, CandidateQuality.PARTIAL)
        assertCounts(fixture, applied = 1, unapplied = 1)
        assertDiagnostic(fixture, "STRUCTURED_RESULT_MISSING")
        val restored = structuredResults.findByBatch(run.batch.id)
        assertEquals(2, restored.size)
        assertEquals(host, restored.single { it.submissionVersionId == host.submissionVersionId })
        val missing = restored.single { it.submissionVersionId == versionForRole(fixture, "MEMBER") }
        assertEquals("STRUCTURED_RESULT_MISSING", missing.rejectionCode)
        assertTrue(missing.conditions.isEmpty())
        assertEquals(run.batch, coordinationRuns.findById(run.id)?.batch)
    }

    @Test
    fun `cancellation after missing result diagnostic write rolls back diagnostics and both publications`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = assertNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val original = listOf(StructuredSubmissionResult(versionForRole(fixture, "HOST"), listOf(window()), null))
        structuredResults.replaceForBatch(run.batch.id, original, PROCESSED_AT)
        var observedDiagnosticWrite = false

        matchingProcessor.processBounded(run.batch.id) {
            val diagnosticWritten = structuredResults.findByBatch(run.batch.id).any { it.rejectionCode == "STRUCTURED_RESULT_MISSING" }
            if (diagnosticWritten) observedDiagnosticWrite = true
            !diagnosticWritten
        }

        assertTrue(observedDiagnosticWrite, "Cancellation must observe the real diagnostic insert within the publication transaction")
        assertEquals(original, structuredResults.findByBatch(run.batch.id))
        val delayed = assertNotNull(coordinationRuns.findById(run.id))
        assertEquals(CoordinationStatus.ANALYSIS_DELAYED, delayed.status)
        assertTrue(delayed.candidates.isEmpty())
        assertNull(storedRecommendations.find(fixture.sourceRun))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM normalized_places WHERE batch_id = ?", Int::class.java, run.batch.id.value),
        )
    }

    @Test
    fun `registered participant without a submission is outside frozen membership and is not a missing parser result`() {
        val fixture = matchingRoom()
        prepareMatching(fixture.sourceRun)
        val run = assertNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        val unsubmitted = UUID.randomUUID()
        val account = UUID.randomUUID()
        jdbc.update("INSERT INTO account_users(id,display_name,created_at) VALUES (?,'unsubmitted',now())", account)
        jdbc.update(
            "INSERT INTO participants(id,room_id,user_id,role,joined_at,display_name) VALUES (?,?,?,'MEMBER',?,'unsubmitted')",
            unsubmitted,
            fixture.roomId,
            account,
            java.sql.Timestamp.from(PROCESSED_AT),
        )
        val results = run.batch.submissionVersionIds.map { StructuredSubmissionResult(it, listOf(window()), null) }
        structuredResults.replaceForBatch(run.batch.id, results, PROCESSED_AT)

        process(fixture.sourceRun)

        val completed = assertNotNull(coordinationRuns.findById(run.id))
        assertEquals(CandidateQuality.COMPLETE, completed.quality)
        assertTrue(completed.candidates.isNotEmpty())
        assertTrue(completed.candidates.all { it.totalParticipants == 2 && it.participantIds.none { id -> id.value == unsubmitted } })
        val modern = assertNotNull(storedRecommendations.find(fixture.sourceRun))
        assertTrue(modern.options.isNotEmpty())
        assertTrue(
            modern.options.flatMap { it.option.variants }.all {
                it.participantIds.size == 2 &&
                    it.participantIds.none { id -> id.value == unsubmitted }
            },
        )
        val publishedVariants = items(recommendations(fixture.host), "options").flatMap { items(it, "variants") }
        assertTrue(publishedVariants.isNotEmpty())
        assertTrue(publishedVariants.all { (it.getValue("total_participants") as Number).toInt() == 2 })
        assertCounts(fixture, applied = 2, unapplied = 0)
        assertEquals(run.batch, completed.batch)
        assertEquals(results.toSet(), structuredResults.findByBatch(run.batch.id).toSet())
    }

    @Test
    fun `successful unrestricted place only and explicit anytime remain distinct from explicit no attendance`() {
        val acceptedInputs =
            listOf(
                listOf(StructuredCondition.SpecificPlace("synthetic station")),
                fullSearch(TimePolarity.AVAILABLE),
                fullSearch(TimePolarity.UNAVAILABLE),
            )
        for ((index, conditions) in acceptedInputs.withIndex()) {
            val fixture = matchingRoom()
            prepareMatching(fixture.sourceRun)
            val run = assertNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
            val results =
                listOf(
                    StructuredSubmissionResult(versionForRole(fixture, "HOST"), listOf(window()), null),
                    StructuredSubmissionResult(versionForRole(fixture, "MEMBER"), conditions, null),
                )
            structuredResults.replaceForBatch(run.batch.id, results, PROCESSED_AT)

            process(fixture.sourceRun)

            assertCounts(fixture, applied = 2, unapplied = 0)
            assertEquals(CandidateQuality.COMPLETE, coordinationRuns.findById(run.id)?.quality)
            if (index == 2) {
                assertNoAttendance(fixture, CandidateQuality.COMPLETE)
            } else {
                assertTrue(
                    coordinationRuns
                        .findById(run.id)
                        ?.candidates
                        .orEmpty()
                        .isNotEmpty(),
                )
                assertTrue(assertNotNull(storedRecommendations.find(fixture.sourceRun)).options.isNotEmpty())
            }
            mvc
                .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(0))
            assertEquals(results.toSet(), structuredResults.findByBatch(run.batch.id).toSet())
        }
    }

    private fun assertNoAttendance(
        fixture: CompletedCorrectionFixture,
        quality: CandidateQuality,
    ) {
        val completed = assertNotNull(coordinationRuns.findById(CoordinationRunId(fixture.sourceRun)))
        assertEquals(CoordinationStatus.COMPLETED, completed.status)
        assertEquals(quality, completed.quality)
        assertTrue(completed.candidates.isEmpty(), "A failed or explicitly absent participant cannot supply the required second attendee")
        assertTrue(assertNotNull(storedRecommendations.find(fixture.sourceRun)).options.isEmpty())
        assertEquals("NO_MATCH", room(fixture.host)["public_status"])
    }

    private fun assertCounts(
        fixture: CompletedCorrectionFixture,
        applied: Int,
        unapplied: Int,
    ) {
        mvc
            .perform(get("/api/rooms/{code}/candidates", fixture.host.code).cookie(fixture.host.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total_submissions").value(2))
            .andExpect(jsonPath("$.applied_submissions").value(applied))
            .andExpect(jsonPath("$.unapplied_inputs").value(unapplied))
    }

    private fun assertDiagnostic(
        fixture: CompletedCorrectionFixture,
        reason: String,
    ) {
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].participant_display_name").value("member"))
            .andExpect(jsonPath("$[0].raw_text").value("M0 original"))
            .andExpect(jsonPath("$[0].reason").value(reason))
        mvc
            .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.member.code).cookie(fixture.member.cookie()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("HOST_PERMISSION_REQUIRED"))
    }

    private fun versionForRole(
        fixture: CompletedCorrectionFixture,
        role: String,
    ): SubmissionVersionId =
        SubmissionVersionId(
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT h.latest_version_id FROM submission_heads h JOIN participants p ON p.id=h.participant_id " +
                        "WHERE p.room_id=? AND p.role=?",
                    UUID::class.java,
                    fixture.roomId,
                    role,
                ),
            ),
        )

    private fun window() = StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(10, 0), LocalTime.of(12, 0))

    private fun fullSearch(polarity: TimePolarity): List<StructuredCondition> =
        (0L..4L).map {
            StructuredCondition.TimeWindow(
                polarity,
                DATE.plusDays(it),
                null,
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                endsAtNextDayStart = true,
            )
        }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/features/conditional-time/$name")).bufferedReader().use { it.readText() }

    companion object {
        private val DATE = LocalDate.of(2026, 10, 7)
        private val PROCESSED_AT = Instant.parse("2026-10-07T05:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
