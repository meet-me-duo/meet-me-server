package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.domain.MeetingMode
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// FR-008B, FR-011/011A and the requested hard-constraint invariant:
// known unrepresentable time restrictions must never become unrestricted availability.
class MatchingUnrepresentableConstraintSafetyTest {
    @Test
    fun `two remote participants cannot match when one has an unsupported conditional restriction`() {
        val f = fixture("UNSUPPORTED_CONDITIONAL_CONSTRAINT", MeetingMode.REMOTE)

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        val view = f.resultService().getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(2, view.totalSubmissions)
        assertEquals(1, view.appliedSubmissions)
        assertEquals(1, view.unappliedInputs)
    }

    @Test
    fun `ambiguous time restriction cannot become all day availability in either mode`() {
        val f = fixture("AMBIGUOUS_TIME_CONSTRAINT", MeetingMode.EITHER)

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
    }

    @Test
    fun `three participants retain only the two valid participants in a partial Plan C`() {
        listOf("UNSUPPORTED_CONDITIONAL_CONSTRAINT", "AMBIGUOUS_TIME_CONSTRAINT").forEach { reason ->
            val f = NaturalLanguageOnlyFixture(listOf("월요일 9~12시", "월요일 10~13시", "시간 하드 조건 표현 불가"))
            f.results(listOf(f.window(9, 12)), listOf(f.window(10, 13)), emptyList())
            f.structured = f.structured.mapIndexed { index, result -> if (index == 2) result.copy(rejectionCode = reason) else result }

            f.process()

            val candidate = f.run.candidates.single()
            assertEquals(PlanType.C, candidate.planType, reason)
            assertEquals(3, candidate.totalParticipants)
            assertEquals(
                f.submissions
                    .take(2)
                    .map { it.participantId }
                    .toSet(),
                candidate.participantIds.toSet(),
            )
            assertEquals(listOf(f.utc(10, 12)), candidate.timeRanges)
            assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        }
    }

    @Test
    fun `generic invalid condition retains the existing neutral time policy`() {
        val f = fixture("INVALID_CONDITION", MeetingMode.REMOTE)

        f.process()

        val candidate = f.run.candidates.single()
        assertEquals(PlanType.B, candidate.planType)
        assertEquals(2, candidate.participantIds.size)
        assertEquals(listOf(f.utc(10, 13)), candidate.timeRanges)
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
    }

    private fun fixture(
        reason: String,
        mode: MeetingMode,
    ): NaturalLanguageOnlyFixture {
        val f = NaturalLanguageOnlyFixture(listOf("시간 하드 조건 표현 불가", "월요일 10~13시"), mode)
        f.results(emptyList(), listOf(f.window(10, 13)))
        f.structured = f.structured.mapIndexed { index, result -> if (index == 0) result.copy(rejectionCode = reason) else result }
        return f
    }
}
