package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.MatchingResultErrorCode
import com.meetme.server.coordination.application.port.input.MatchingResultException
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule 2 / ADR-013, 043, 048 + 2026-10-31 user policy: retain time with explicitly unverified place conditions. */
class UnappliedTravelConstraintContractTest {
    @Test
    fun `remote retains independent available alternatives and unavailable subtraction with a place diagnostic`() {
        val f = fixture(MeetingMode.REMOTE, "집에서 도보 10분 이내", "다른 참여자의 시간")
        f.results(
            listOf(f.window(9, 13), f.window(10, 11, TimePolarity.UNAVAILABLE), travel("집에서 도보 10분 이내")),
            listOf(f.window(8, 14)),
        )

        f.process()

        val candidate = f.singleCandidate()
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
        assertEquals(listOf(f.utc(9, 10), f.utc(11, 13)), candidate.timeRanges)
        assertEquals(2, candidate.participantIds.size)
        assertNull(candidate.place)
        assertPartialCounts(f, applied = 1, total = 2, unapplied = 1)
        assertTravelDiagnostic(f, "집에서 도보 10분 이내")
    }

    @Test
    fun `either retains permitted modes without treating an unknown personal origin as verified`() {
        val f = fixture(MeetingMode.EITHER, "회사 근처에서만 가능", "명시 장소에서 가능")
        f.results(
            listOf(f.window(9, 12), travel("회사 근처에서만 가능")),
            listOf(f.window(10, 13), place()),
        )

        f.process()

        assertEquals(
            setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE),
            f.run.candidates
                .map { it.meetingMode }
                .toSet(),
        )
        f.run.candidates.forEach {
            assertEquals(listOf(f.utc(10, 12)), it.timeRanges)
            if (it.meetingMode == MeetingMode.REMOTE) assertNull(it.place)
        }
        assertPartialCounts(f, applied = 1, total = 2, unapplied = 1)
        assertTravelDiagnostic(f, "회사 근처에서만 가능")
    }

    @Test
    fun `in person retains a time candidate with an explicitly unverified travel restriction`() {
        val f = fixture(MeetingMode.IN_PERSON, "학교 근처에서만 가능", "명시 장소에서 가능")
        f.results(listOf(f.window(9, 12), travel("학교 근처에서만 가능")), listOf(f.window(9, 12), place()))

        f.process()

        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        val candidate = f.singleCandidate()
        assertEquals(MeetingMode.IN_PERSON, candidate.meetingMode)
        assertEquals(listOf(f.utc(9, 12)), candidate.timeRanges)
        assertEquals(2, candidate.participantIds.size)
        assertPartialCounts(f, applied = 1, total = 2, unapplied = 1)
        assertTravelDiagnostic(f, "학교 근처에서만 가능")
    }

    @Test
    fun `normal specific places in the same group remain complete with null coordinates`() {
        val f = fixture(MeetingMode.IN_PERSON)
        f.results(listOf(f.window(9, 12), place()), listOf(f.window(10, 13), place()))

        f.process()

        val candidate = f.singleCandidate()
        assertEquals(MeetingMode.IN_PERSON, candidate.meetingMode)
        assertEquals(listOf(f.utc(10, 12)), candidate.timeRanges)
        assertEquals("합성 공통 지역", candidate.place?.displayName)
        val publicPlace =
            f
                .resultService()
                .getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
                .candidates
                .single()
                .place
        assertEquals("합성 공통 지역", publicPlace?.displayName)
        assertNull(publicPlace?.latitude)
        assertNull(publicPlace?.longitude)
        assertCompleteCounts(f)
    }

    @Test
    fun `different supported area groups are a genuine no match without an unsupported input diagnosis`() {
        val f = fixture(MeetingMode.IN_PERSON)
        f.results(
            listOf(f.window(9, 12), place()),
            listOf(f.window(9, 12), place("AREA_2", "다른 합성 지역")),
        )

        f.process()

        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        assertTrue(f.run.candidates.isEmpty())
        assertCompleteCounts(f)
    }

    @Test
    fun `remote with no travel restriction remains complete and does not acquire place failures`() {
        val f = fixture(MeetingMode.REMOTE)
        f.results(listOf(f.window(9, 12)), listOf(f.window(10, 13)))

        f.process()

        assertEquals(listOf(f.utc(10, 12)), f.singleCandidate().timeRanges)
        assertCompleteCounts(f)
    }

    @Test
    fun `an explicit origin and multiple walking limits remain intact without inventing in person feasibility`() {
        val raw = "합성역에서 도보 5분 이내이고 환승 없이 도보 10분 이내"
        val f = fixture(MeetingMode.IN_PERSON, raw, "합성역에서 가능")
        val conditions = listOf(f.window(9, 12), place(), travel("합성역에서 도보 5분 이내"), travel("환승 없이 도보 10분 이내"))
        f.results(conditions, listOf(f.window(9, 12), place()))

        f.process()

        assertEquals(conditions, f.structured.first().conditions)
        val candidate = f.singleCandidate()
        assertEquals(MeetingMode.IN_PERSON, candidate.meetingMode)
        assertEquals(listOf(f.utc(9, 12)), candidate.timeRanges)
        assertEquals("합성 공통 지역", candidate.place?.displayName)
        assertPartialCounts(f, applied = 1, total = 2, unapplied = 1)
        assertTravelDiagnostic(f, raw)
    }

    @Test
    fun `the same explicit origin with different walking limits does not merge the participants restrictions`() {
        val first = "합성역에서 도보 5분 이내"
        val second = "합성역에서 도보 20분 이내"
        val f = fixture(MeetingMode.EITHER, first, second)
        val conditions =
            listOf(
                listOf(f.window(9, 12), place(), travel(first)),
                listOf(f.window(10, 13), place(), travel(second)),
            )
        f.results(*conditions.toTypedArray())

        f.process()

        assertEquals(conditions, f.structured.map { it.conditions })
        assertEquals(
            setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE),
            f.run.candidates
                .map { it.meetingMode }
                .toSet(),
        )
        f.run.candidates.forEach {
            assertEquals(listOf(f.utc(10, 12)), it.timeRanges)
        }
        assertPartialCounts(f, applied = 0, total = 2, unapplied = 2)
        val diagnostics = f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host")
        assertEquals(setOf(first, second), diagnostics.map { it.rawText }.toSet())
        assertEquals(2, diagnostics.size)
        diagnostics.forEach {
            assertEquals(UNSUPPORTED_TRAVEL, it.reason)
            assertEquals("PLACE", it.failureScope)
        }
    }

    @Test
    fun `multiple travel conditions count one partially unapplied participant while keeping all valid online attendees`() {
        val f = fixture(MeetingMode.REMOTE, "집과 회사 기준 이동 제한", "시간 2", "시간 3")
        f.results(
            listOf(f.window(9, 12), travel("집에서 10분 이내"), travel("회사에서 20분 이내")),
            listOf(f.window(9, 12)),
            listOf(f.window(10, 13)),
        )

        f.process()

        assertEquals(3, f.singleCandidate().participantIds.size)
        assertEquals(listOf(f.utc(10, 12)), f.singleCandidate().timeRanges)
        assertPartialCounts(f, applied = 2, total = 3, unapplied = 1)
        assertEquals(1, f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host").size)
    }

    @Test
    fun `a selected unsafe time rejection is not replaced by a secondary unsupported travel reason`() {
        val f = fixture(MeetingMode.REMOTE, "시간 모호성과 이동 제한", "시간 정상")
        f.results(listOf(f.window(9, 12), travel("집 근처")), listOf(f.window(9, 12)))
        f.structured =
            f.structured.mapIndexed { index, result ->
                if (index == 0) result.copy(rejectionCode = "AMBIGUOUS_TIME_CONSTRAINT") else result
            }

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertPartialCounts(f, applied = 1, total = 2, unapplied = 1)
        val diagnostic = f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host").single()
        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", diagnostic.reason)
        assertTrue(diagnostic.failureScope in setOf(null, "TIME"), "A legacy nullable time scope must not be relabeled PLACE")
        assertEquals("시간 모호성과 이동 제한", diagnostic.rawText)
    }

    @Test
    fun `a soft preferred place does not become a required travel restriction or remove a valid hard place`() {
        val f = fixture(MeetingMode.IN_PERSON)
        f.results(
            listOf(f.window(9, 12), place(), StructuredCondition.PreferredPlace("다른 합성역", "AREA_2", "다른 합성 지역")),
            listOf(f.window(10, 13), place()),
        )

        f.process()

        assertEquals(MeetingMode.IN_PERSON, f.singleCandidate().meetingMode)
        assertEquals("합성 공통 지역", f.singleCandidate().place?.displayName)
        assertEquals(listOf(f.utc(10, 12)), f.singleCandidate().timeRanges)
        assertCompleteCounts(f)
    }

    @Test
    fun `only the host can read unsupported travel raw text and public candidate fields remain private`() {
        val raw = "비공개 개인 기준점에서 도보 7분 이내"
        val f = fixture(MeetingMode.REMOTE, "시간 정상", raw)
        f.results(listOf(f.window(9, 12)), listOf(f.window(9, 12), travel(raw)))
        f.process()
        val service = f.resultService()

        val diagnostic = service.getUnappliedInputs(f.room.inviteCode.value, "host").single()
        assertEquals(raw, diagnostic.rawText)
        assertEquals("participant 2", diagnostic.participantDisplayName)
        assertEquals(UNSUPPORTED_TRAVEL, diagnostic.reason)
        assertEquals("PLACE", diagnostic.failureScope)
        assertNull(diagnostic.finalizedAnswer)
        val diagnosticJson = JsonMapper.builder().build().writeValueAsString(diagnostic)
        listOf("question", "prompt", "choice", "sourceExcerpt", "sourceStart", "inputEvidence").forEach {
            assertFalse(diagnosticJson.contains(it, ignoreCase = true), "Host diagnostics must not acquire private $it fields")
        }
        f.viewAsMember()
        val denied = assertThrows<MatchingResultException> { service.getUnappliedInputs(f.room.inviteCode.value, "member") }
        assertEquals(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED, denied.code)
        val publicView = service.getCandidates(f.room.inviteCode.value, "member", Locale.KOREAN)
        assertEquals(CandidateQuality.PARTIAL, publicView.quality)
        assertFalse(publicView.toString().contains(raw))
        assertFalse(publicView.toString().contains("participant 2"))
        publicView.candidates.forEach { assertFalse(it.summary.contains(raw)) }
    }

    @Test
    fun `historical complete travel results retain original counts diagnostics candidates and selection`() {
        listOf(false, true).forEach { confirmed ->
            val f = fixture(MeetingMode.REMOTE, "집에서 도보 10분 이내", "시간 정상")
            f.results(listOf(f.window(9, 12), travel("집에서 도보 10분 이내")), listOf(f.window(10, 13)))
            f.process()
            // Restore the pre-diagnostic stored quality without reinterpreting the frozen conditions.
            val completed =
                CoordinationRun
                    .queued(f.run.id, f.batch)
                    .startMatching()
                    .complete(CandidateQuality.COMPLETE, f.run.candidates)
            f.run =
                if (confirmed) {
                    completed.confirm(completed.candidates.single().id, NaturalLanguageOnlyFixture.NOW.plusSeconds(60))
                } else {
                    completed
                }
            val historical = f.run
            val frozenConditions = f.structured.toList()

            f.process()

            val service = f.resultService()
            val view = service.getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
            assertEquals(CandidateQuality.COMPLETE, view.quality, "confirmed=$confirmed")
            assertEquals(2, view.appliedSubmissions)
            assertEquals(2, view.totalSubmissions)
            assertEquals(0, view.unappliedInputs)
            assertTrue(service.getUnappliedInputs(f.room.inviteCode.value, "host").isEmpty())
            val candidate = view.candidates.single()
            val expectedCandidateId =
                completed.candidates
                    .single()
                    .id.value
            assertEquals(expectedCandidateId, candidate.candidateId)
            assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
            assertEquals(2, candidate.attendanceCount)
            assertEquals(listOf(f.utc(10, 12)), candidate.timeRanges)
            if (confirmed) {
                val selected = service.getConfirmed(f.room.inviteCode.value, "host", Locale.KOREAN)
                assertEquals(candidate, selected.candidate)
                assertEquals(NaturalLanguageOnlyFixture.NOW.plusSeconds(60), selected.confirmedAt)
            }
            assertEquals(historical, f.run, "Reading or processing a completed run must preserve its selection")
            assertEquals(frozenConditions, f.structured)
        }
    }

    private fun fixture(
        mode: MeetingMode,
        vararg texts: String,
    ): NaturalLanguageOnlyFixture {
        val f = NaturalLanguageOnlyFixture(texts.toList().ifEmpty { listOf("정상 입력 1", "정상 입력 2") }, mode)
        `when`(f.normalizedRepository.findByBatch(f.batch.id)).thenReturn(emptyList())
        return f
    }

    private fun travel(expression: String) = StructuredCondition.TravelConstraint(expression)

    private fun place(
        key: String = "AREA_1",
        name: String = "합성 공통 지역",
    ) = StructuredCondition.SpecificPlace("합성역", areaKey = key, areaName = name)

    private fun assertPartialCounts(
        f: NaturalLanguageOnlyFixture,
        applied: Int,
        total: Int,
        unapplied: Int,
    ) {
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        val view = f.resultService().getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN, supportsConditionalAttendance = true)
        assertEquals(CandidateQuality.PARTIAL, view.quality)
        assertEquals(applied, view.appliedSubmissions)
        assertEquals(total, view.totalSubmissions)
        assertEquals(unapplied, view.unappliedInputs)
    }

    private fun assertCompleteCounts(f: NaturalLanguageOnlyFixture) {
        assertEquals(CandidateQuality.COMPLETE, f.run.quality)
        val service = f.resultService()
        val view = service.getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(CandidateQuality.COMPLETE, view.quality)
        assertEquals(2, view.appliedSubmissions)
        assertEquals(2, view.totalSubmissions)
        assertEquals(0, view.unappliedInputs)
        assertTrue(service.getUnappliedInputs(f.room.inviteCode.value, "host").isEmpty())
    }

    private fun assertTravelDiagnostic(
        f: NaturalLanguageOnlyFixture,
        raw: String,
    ) {
        val diagnostic = f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host").single()
        assertEquals(raw, diagnostic.rawText)
        assertEquals(UNSUPPORTED_TRAVEL, diagnostic.reason)
        assertEquals("PLACE", diagnostic.failureScope)
    }

    companion object {
        private const val UNSUPPORTED_TRAVEL = "UNSUPPORTED_TRAVEL_CONSTRAINT"
    }
}
