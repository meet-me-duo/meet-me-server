package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-008B/FR-011: failed structure is not evidence of unrestricted attendance. */
class MatchingFailedStructuredInputSafetyTest {
    @Test
    fun `empty rejected input cannot supply the second attendee required for any candidate`() {
        for (code in listOf("CONDITION_VALIDATION_FAILED", "INVALID_CONDITION", "FUTURE_VALIDATION_FAILURE")) {
            val f = NaturalLanguageOnlyFixture(listOf("invalid meeting constraint", "Monday 10 to 13"))
            f.results(emptyList(), listOf(f.window(10, 13)))
            f.structured = f.structured.mapIndexed { index, result -> if (index == 0) result.copy(rejectionCode = code) else result }

            f.process()

            assertTrue(f.run.candidates.isEmpty(), code)
            assertEquals(CoordinationStatus.COMPLETED, f.run.status, code)
            assertEquals(CandidateQuality.PARTIAL, f.run.quality, code)
            assertCounts(f, total = 2, applied = 1, unapplied = 1)
            val diagnostic = f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host").single()
            assertEquals("invalid meeting constraint", diagnostic.rawText, code)
            assertEquals(code, diagnostic.reason)
        }
    }

    @Test
    fun `three submitted participants retain failed input in total while only valid two attend partial plan`() {
        val f = NaturalLanguageOnlyFixture(listOf("Monday 9 to 12", "Monday 10 to 13", "failed constraint"))
        f.results(listOf(f.window(9, 12)), listOf(f.window(10, 13)), emptyList())
        f.structured =
            f.structured.mapIndexed { index, result ->
                if (index == 2) result.copy(rejectionCode = "CONDITION_VALIDATION_FAILED") else result
            }

        f.process()

        val candidate = f.singleCandidate()
        assertEquals(PlanType.C, candidate.planType)
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
        assertCounts(f, total = 3, applied = 2, unapplied = 1)
    }

    @Test
    fun `rejected place preference or unavailable only cannot reopen a neutral full day`() {
        val retainedConditions: List<(NaturalLanguageOnlyFixture) -> List<StructuredCondition>> =
            listOf(
                { listOf(StructuredCondition.SpecificPlace("station")) },
                { f -> listOf(f.window(10, 11, TimePolarity.UNAVAILABLE)) },
                {
                    listOf(
                        StructuredCondition.PreferredTimeWindow(
                            NaturalLanguageOnlyFixture.DATE,
                            null,
                            LocalTime.of(10, 0),
                            LocalTime.of(11, 0),
                        ),
                    )
                },
            )
        for (retained in retainedConditions) {
            val f = NaturalLanguageOnlyFixture(listOf("partially rejected without possible times", "Monday 9 to 12"))
            f.results(retained(f), listOf(f.window(9, 12)))
            f.structured =
                f.structured.mapIndexed { index, result ->
                    if (index == 0) result.copy(rejectionCode = "CONDITION_VALIDATION_FAILED") else result
                }

            f.process()

            assertTrue(f.run.candidates.isEmpty(), retained(f).toString())
            assertEquals(CandidateQuality.PARTIAL, f.run.quality)
            assertCounts(f, total = 2, applied = 1, unapplied = 1)
        }
    }

    @Test
    fun `missing structured result cannot count as attendance and retains a host diagnostic`() {
        val f = NaturalLanguageOnlyFixture(listOf("Monday 9 to 12", "missing structured result"))
        f.results(listOf(f.window(9, 12)))

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertCounts(f, total = 2, applied = 1, unapplied = 1)
        val diagnostic = f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host").single()
        assertEquals("missing structured result", diagnostic.rawText)
        assertEquals("STRUCTURED_RESULT_MISSING", diagnostic.reason)
    }

    @Test
    fun `valid available times and exclusions survive a generic partial condition rejection`() {
        val f = NaturalLanguageOnlyFixture(listOf("retained possible interval with rejected preference", "Monday 8 to 14"))
        f.results(listOf(f.window(9, 12), f.window(10, 11, TimePolarity.UNAVAILABLE)), listOf(f.window(8, 14)))
        f.structured =
            f.structured.mapIndexed { index, result ->
                if (index == 0) result.copy(rejectionCode = "CONDITION_VALIDATION_FAILED") else result
            }

        f.process()

        assertEquals(listOf(f.utc(9, 10), f.utc(11, 12)), f.singleCandidate().timeRanges)
        assertEquals(f.submissions.map { it.participantId }.toSet(), f.singleCandidate().participantIds.toSet())
        assertEquals(CandidateQuality.PARTIAL, f.run.quality)
        assertCounts(f, total = 2, applied = 1, unapplied = 1)
    }

    @Test
    fun `known unsafe rejection still excludes a participant even with a retained available interval`() {
        for (code in listOf("AMBIGUOUS_TIME_CONSTRAINT", "UNSUPPORTED_CONDITIONAL_CONSTRAINT")) {
            val f = NaturalLanguageOnlyFixture(listOf("unsafe hard constraint", "Monday 10 to 13"))
            f.results(listOf(f.window(9, 12)), listOf(f.window(10, 13)))
            f.structured = f.structured.mapIndexed { index, result -> if (index == 0) result.copy(rejectionCode = code) else result }

            f.process()

            assertTrue(f.run.candidates.isEmpty(), code)
            assertEquals(CandidateQuality.PARTIAL, f.run.quality, code)
        }
    }

    @Test
    fun `successful place only input remains unrestricted while successful unavailable only subtracts exclusions`() {
        val placeOnly = NaturalLanguageOnlyFixture(listOf("station", "Monday 9 to 12"))
        placeOnly.results(listOf(StructuredCondition.SpecificPlace("station")), listOf(placeOnly.window(9, 12)))
        placeOnly.process()
        assertEquals(listOf(placeOnly.utc(9, 12)), placeOnly.singleCandidate().timeRanges)
        assertEquals(CandidateQuality.COMPLETE, placeOnly.run.quality)
        assertCounts(placeOnly, total = 2, applied = 2, unapplied = 0)

        val excludedHour = NaturalLanguageOnlyFixture(listOf("unavailable 10 to 11", "Monday 9 to 12"))
        excludedHour.results(listOf(excludedHour.window(10, 11, TimePolarity.UNAVAILABLE)), listOf(excludedHour.window(9, 12)))
        excludedHour.process()
        assertEquals(listOf(excludedHour.utc(9, 10), excludedHour.utc(11, 12)), excludedHour.singleCandidate().timeRanges)
        assertEquals(CandidateQuality.COMPLETE, excludedHour.run.quality)
        assertCounts(excludedHour, total = 2, applied = 2, unapplied = 0)
    }

    @Test
    fun `successful explicit no attendance produces no match without becoming a failed input`() {
        val f = NaturalLanguageOnlyFixture(listOf("cannot attend throughout search period", "Monday 9 to 12"))
        val fullSearchExclusions =
            (0L..1L).map { offset ->
                StructuredCondition.TimeWindow(
                    TimePolarity.UNAVAILABLE,
                    NaturalLanguageOnlyFixture.DATE.plusDays(offset),
                    null,
                    LocalTime.MIDNIGHT,
                    LocalTime.MIDNIGHT,
                    endsAtNextDayStart = true,
                )
            }
        f.results(fullSearchExclusions, listOf(f.window(9, 12)))

        f.process()

        assertTrue(f.run.candidates.isEmpty())
        assertEquals(CoordinationStatus.COMPLETED, f.run.status)
        assertEquals(CandidateQuality.COMPLETE, f.run.quality)
        assertCounts(f, total = 2, applied = 2, unapplied = 0)
        assertTrue(f.resultService().getUnappliedInputs(f.room.inviteCode.value, "host").isEmpty())
    }

    private fun assertCounts(
        f: NaturalLanguageOnlyFixture,
        total: Int,
        applied: Int,
        unapplied: Int,
    ) {
        val view = f.resultService().getCandidates(f.room.inviteCode.value, "host", Locale.KOREAN)
        assertEquals(total, view.totalSubmissions)
        assertEquals(applied, view.appliedSubmissions)
        assertEquals(unapplied, view.unappliedInputs)
    }
}
