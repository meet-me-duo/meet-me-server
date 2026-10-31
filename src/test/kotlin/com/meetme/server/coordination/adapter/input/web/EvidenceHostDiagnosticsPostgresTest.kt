package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** EX-PRIVACY/EX-FINAL: frozen final evidence belongs in host diagnostics, never participant responses. */
class EvidenceHostDiagnosticsPostgresTest : RecommendationPostgresFixture() {
    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Test
    fun `EX-PRIVACY V2 host sees final unresolved input and answer without the private question`() {
        val fixture = matchingRoom()
        val memberVersion = prepareEvidence(fixture)
        prepareMatching(fixture.sourceRun)
        val run = assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        structured.replaceForBatch(
            run.batch.id,
            listOf(
                StructuredSubmissionResult(versionForRole(fixture, "HOST"), listOf(window()), null),
                StructuredSubmissionResult(memberVersion, emptyList(), "AMBIGUOUS_TIME_CONSTRAINT"),
            ),
            NOW,
        )
        process(fixture.sourceRun)

        assertHostDiagnostic(fixture, "AMBIGUOUS_TIME_CONSTRAINT", "TIME")
        assertParticipantPrivacy(fixture)
    }

    @Test
    fun `EX-FINAL V2 missing interpretation yields an isolated host diagnostic with frozen answer`() {
        val fixture = matchingRoom()
        prepareEvidence(fixture)
        prepareMatching(fixture.sourceRun)
        val run = assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        structured.replaceForBatch(
            run.batch.id,
            listOf(StructuredSubmissionResult(versionForRole(fixture, "HOST"), listOf(window()), null)),
            NOW,
        )
        process(fixture.sourceRun)

        assertHostDiagnostic(fixture, "STRUCTURED_RESULT_MISSING", "INPUT")
        assertParticipantPrivacy(fixture)
        val saved = structured.findByBatch(run.batch.id)
        assertEquals(2, saved.size)
        assertTrue(saved.single { it.submissionVersionId == versionForRole(fixture, "MEMBER") }.conditions.isEmpty())
        assertEquals(listOf(window()), saved.single { it.submissionVersionId == versionForRole(fixture, "HOST") }.conditions)
    }

    @Test
    fun `EX-PRIVACY V1 ambiguity continues to hide the other participants raw input from the host`() {
        val fixture = matchingRoom()
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V1' WHERE id=?", fixture.roomId)
        prepareMatching(fixture.sourceRun)
        val run = assertNotNull(runs.findById(CoordinationRunId(fixture.sourceRun)))
        structured.replaceForBatch(
            run.batch.id,
            listOf(
                StructuredSubmissionResult(versionForRole(fixture, "HOST"), listOf(window()), null),
                StructuredSubmissionResult(versionForRole(fixture, "MEMBER"), emptyList(), "AMBIGUOUS_TIME_CONSTRAINT"),
            ),
            NOW,
        )
        process(fixture.sourceRun)

        val response =
            mvc
                .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response
        assertEquals(0, mapper.readTree(response.contentAsByteArray).size())
        assertFalse(response.contentAsString.contains("M0 original"))
    }

    private fun assertHostDiagnostic(
        fixture: CompletedCorrectionFixture,
        reason: String,
        scope: String,
    ) {
        val response =
            mvc
                .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.host.code).cookie(fixture.host.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val body = mapper.readTree(response.contentAsByteArray)
        assertEquals(1, body.size())
        val row = body[0]
        assertEquals("member", row.path("participant_display_name").stringValue())
        assertEquals(RAW, row.path("raw_text").stringValue())
        assertEquals(ANSWER, row.path("finalized_answer").stringValue())
        assertEquals(reason, row.path("reason").stringValue())
        assertEquals(scope, row.path("failure_scope").stringValue())
        assertFalse(response.contentAsString.contains(PRIVATE_PROMPT))
        assertFalse(response.contentAsString.contains("source_start_code_point"))
        assertFalse(response.contentAsString.contains("question_id"))
    }

    private fun assertParticipantPrivacy(fixture: CompletedCorrectionFixture) {
        val response =
            mvc
                .perform(get("/api/rooms/{code}/candidates/unapplied-inputs", fixture.member.code).cookie(fixture.member.cookie()))
                .andExpect(status().isForbidden)
                .andReturn()
                .response
        for (secret in listOf(RAW, ANSWER, PRIVATE_PROMPT)) assertFalse(response.contentAsString.contains(secret))
        val publicResponse =
            mvc
                .perform(get("/api/rooms/{code}/candidates", fixture.member.code).cookie(fixture.member.cookie()))
                .andExpect(status().isOk)
                .andReturn()
                .response
        for (secret in listOf(RAW, ANSWER, PRIVATE_PROMPT, "finalized_answer", "input_evidence", "source_excerpt")) {
            assertFalse(publicResponse.contentAsString.contains(secret))
        }
    }

    private fun prepareEvidence(fixture: CompletedCorrectionFixture): SubmissionVersionId {
        jdbc.update("UPDATE meeting_rooms SET submission_protocol='PRE_SUBMIT_V2' WHERE id=?", fixture.roomId)
        val host = versionForRole(fixture, "HOST")
        val member = versionForRole(fixture, "MEMBER")
        val evidence =
            InputEvidence(
                originalRevision = 1,
                referenceAt = NOW,
                supplement =
                    InputSupplement(
                        UUID.fromString("4f7394ca-efaa-40e4-a154-7788d8255ae8"),
                        1,
                        "7시",
                        4,
                        PRIVATE_PROMPT,
                        ANSWER,
                        "PM",
                        NOW.plusSeconds(1),
                        NOW,
                    ),
                interpretation = InputInterpretation.UNRESOLVED,
                unresolvedReason = "TIME_UNRESOLVED",
            )
        jdbc.update(
            "UPDATE submission_versions SET input_evidence=?::jsonb WHERE id=?",
            mapper.writeValueAsString(InputEvidence(1, NOW, interpretation = InputInterpretation.CONFIRMED)),
            host.value,
        )
        jdbc.update(
            "UPDATE submission_versions SET raw_text=?,input_evidence=?::jsonb WHERE id=?",
            RAW,
            mapper.writeValueAsString(evidence),
            member.value,
        )
        return member
    }

    private fun versionForRole(
        fixture: CompletedCorrectionFixture,
        role: String,
    ): SubmissionVersionId =
        SubmissionVersionId(
            assertNotNull(
                jdbc.queryForObject(
                    "SELECT h.latest_version_id FROM submission_heads h JOIN participants p ON p.id=h.participant_id WHERE p.room_id=? AND p.role=?",
                    UUID::class.java,
                    fixture.roomId,
                    role,
                ),
            ),
        )

    private fun window() =
        StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, LocalDate.of(2026, 10, 7), null, LocalTime.of(10, 0), LocalTime.of(12, 0))

    companion object {
        private const val RAW = "목요일 7시 이후 가능. 금요일은 불가."
        private const val ANSWER = "오후"
        private const val PRIVATE_PROMPT = "비공개 개인 보완 질문의 전체 문구"
        private val NOW = Instant.parse("2026-10-07T05:00:00Z")
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
