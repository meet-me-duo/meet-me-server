package com.meetme.server.coordination.application.service

import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// FR-011A and ADR-014: an inferred recurring pattern cannot hide irregular actual occurrences.
class CandidateSummaryMixedPatternContractTest {
    @Test
    fun `dominant Monday pattern cannot hide an additional Tuesday occurrence`() {
        val summary =
            render(
                listOf(
                    range("2026-09-21T18:00", "2026-09-21T20:00"),
                    range("2026-09-22T19:00", "2026-09-22T21:00"),
                    range("2026-09-28T18:00", "2026-09-28T20:00"),
                ),
                RecurringCandidatePattern(DayOfWeek.MONDAY, LocalTime.of(18, 0), LocalTime.of(20, 0)),
            )

        assertEquals(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, summary.strategy)
        assertTrue("21일" in summary.text && "28일" in summary.text)
        assertTrue("18:00~20:00" in summary.text)
        assertTrue("9월 22일" in summary.text && "19:00~21:00" in summary.text, "Tuesday must not disappear: ${summary.text}")
    }

    @Test
    fun `recurring overnight occurrence preserves both Monday and Tuesday portions`() {
        val summary =
            render(
                listOf(
                    range("2026-09-21T23:00", "2026-09-22T01:00"),
                    range("2026-09-28T23:00", "2026-09-29T01:00"),
                ),
                RecurringCandidatePattern(DayOfWeek.MONDAY, LocalTime.of(23, 0), LocalTime.of(1, 0)),
            )

        assertEquals(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, summary.strategy)
        assertTrue("21일" in summary.text && "28일" in summary.text)
        assertTrue("22일" in summary.text && "29일" in summary.text, "Both next-day portions must remain visible: ${summary.text}")
        assertTrue("23:00~24:00" in summary.text && "00:00~01:00" in summary.text)
    }

    private fun render(
        occurrences: List<InstantTimeRange>,
        pattern: RecurringCandidatePattern,
    ) = CandidateSummaryRenderer.render(
        CandidateSummaryRequest(
            occurrences = occurrences,
            searchStartDate = LocalDate.of(2026, 9, 20),
            searchEndDate = LocalDate.of(2026, 9, 30),
            timeZone = ZONE,
            locale = Locale.forLanguageTag("ko-KR"),
            recurringPattern = pattern,
        ),
    )

    private fun range(
        localStart: String,
        localEnd: String,
    ) = InstantTimeRange(
        java.time.LocalDateTime
            .parse(localStart)
            .atZone(ZONE)
            .toInstant(),
        java.time.LocalDateTime
            .parse(localEnd)
            .atZone(ZONE)
            .toInstant(),
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
