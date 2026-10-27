package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.Participant
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import com.meetme.server.submission.application.port.output.ClarificationOwnerPort
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.domain.ClarificationProposal
import com.meetme.server.submission.domain.StructuredSubmissionResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Feature port delegation only. Actual account/Bearer parsing and production ownership wiring belong to #109.
class ClarificationOwnerDelegationPostgresTest : CorrectionRoundPostgresFixture() {
    @MockitoBean private lateinit var ownership: ClarificationOwnerPort

    @Autowired private lateinit var participants: ParticipantRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Test
    fun `common ownership port can authorize query without legacy guest cookie`() {
        val fixture = seededRoom()
        val participant = member(fixture)
        doReturn(participant).`when`(ownership).requireOwner(MeetingRoomId(fixture.roomId), null)

        mvc
            .perform(get("/api/rooms/{code}/submission/clarifications", fixture.member.code))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.questions.length()").value(1))
            .andExpect(jsonPath("$.questions[0].question").value(QUESTION))
            .andExpect(jsonPath("$.questions[0].source_revision").value(1))
        verify(ownership).requireOwner(MeetingRoomId(fixture.roomId), null)
        verifyNoMoreInteractions(ownership)
    }

    @Test
    fun `common ownership unauthorized result cannot fall back to otherwise valid guest cookie`() {
        val fixture = seededRoom()
        doThrow(RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_INVALID))
            .`when`(ownership)
            .requireOwner(MeetingRoomId(fixture.roomId), fixture.member.credential)
        val versions = versionIds()
        val counts = workCounts()

        val response =
            mvc
                .perform(get("/api/rooms/{code}/submission/clarifications", fixture.member.code).cookie(fixture.member.cookie()))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("GUEST_SESSION_INVALID"))
                .andReturn()
                .response.contentAsString
        assertPrivate(response)
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        verify(ownership).requireOwner(MeetingRoomId(fixture.roomId), fixture.member.credential)
        verifyNoMoreInteractions(ownership)
    }

    @Test
    fun `common ownership forbidden result cannot use cookie participant or reveal question`() {
        val fixture = seededRoom()
        doThrow(SubmissionException(SubmissionErrorCode.PARTICIPANT_REQUIRED))
            .`when`(ownership)
            .requireOwner(MeetingRoomId(fixture.roomId), fixture.member.credential)
        val versions = versionIds()
        val counts = workCounts()

        val response =
            mvc
                .perform(get("/api/rooms/{code}/submission/clarifications", fixture.member.code).cookie(fixture.member.cookie()))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("PARTICIPANT_REQUIRED"))
                .andReturn()
                .response.contentAsString
        assertPrivate(response)
        assertEquals(versions, versionIds())
        assertEquals(counts, workCounts())
        verify(ownership).requireOwner(MeetingRoomId(fixture.roomId), fixture.member.credential)
        verifyNoMoreInteractions(ownership)
    }

    private fun assertPrivate(response: String) {
        for (secret in listOf("M0 original", QUESTION, OPTION_A, OPTION_B, "AMBIGUOUS_TIME_CONSTRAINT")) {
            assertTrue(
                !response.contains(secret),
                "Rejected ownership must not reveal a source, question, answer option or ambiguity reason",
            )
        }
    }

    private fun seededRoom(): CompletedCorrectionFixture {
        val fixture = completedRoom()
        val batch =
            requireNotNull(jdbc.queryForObject("SELECT batch_id FROM coordination_runs WHERE id = ?", UUID::class.java, fixture.sourceRun))
        val source =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT h.latest_version_id FROM submission_heads h JOIN participants p ON p.id = h.participant_id " +
                        "WHERE p.room_id = ? AND p.display_name = 'member'",
                    UUID::class.java,
                    fixture.roomId,
                ),
            )
        structured.replaceForBatch(
            SubmissionBatchId(batch),
            listOf(
                StructuredSubmissionResult(
                    SubmissionVersionId(source),
                    emptyList(),
                    "AMBIGUOUS_TIME_CONSTRAINT",
                    ClarificationProposal("3시", QUESTION, OPTION_A, OPTION_B),
                ),
            ),
            Instant.parse("2026-10-07T05:00:00Z"),
        )
        return fixture
    }

    private fun member(fixture: CompletedCorrectionFixture): Participant {
        val id =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM participants WHERE room_id = ? AND display_name = 'member'",
                    UUID::class.java,
                    fixture.roomId,
                ),
            )
        return requireNotNull(participants.findById(ParticipantId(id)))
    }

    companion object {
        private const val QUESTION = "오전인가요 오후인가요?"
        private const val OPTION_A = "2026-10-09 오전 3~5시 가능"
        private const val OPTION_B = "2026-10-09 오후 3~5시 가능"
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrectionDatabase(registry, postgres)

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
