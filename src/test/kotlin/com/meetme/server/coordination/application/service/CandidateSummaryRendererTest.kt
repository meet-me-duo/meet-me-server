package com.meetme.server.coordination.application.service

import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateSummaryRendererTest {
    @Test
    fun `explicit ko KR summary represents the same local dates and times as structured occurrences`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T09:00:00Z", "2026-09-21T11:00:00Z"),
                            range("2026-09-24T10:00:00Z", "2026-09-24T12:00:00Z"),
                        ),
                ),
            )

        assertEquals(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, summary.strategy)
        assertTrue("9월 21일" in summary.text && "18:00" in summary.text && "20:00" in summary.text)
        assertTrue("9월 24일" in summary.text && "19:00" in summary.text && "21:00" in summary.text)
    }

    @Test
    fun `excluded or shortened date is never described as fully available`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T09:00:00Z", "2026-09-21T11:00:00Z"),
                            range("2026-09-28T10:00:00Z", "2026-09-28T11:00:00Z"),
                        ),
                    endDate = LocalDate.of(2026, 10, 6),
                    pattern = mondayEvening,
                ),
            )

        assertTrue("9월 28일" in summary.text && "19:00" in summary.text && "20:00" in summary.text)
        assertTrue("10월 5일" in summary.text)
        assertFalse(summary.text.contains("9월 28일 18:00~20:00"))
        assertFalse(summary.text.contains("10월 5일 18:00~20:00"))
    }

    @Test
    fun `uses pattern with exceptions only when exception references are fewer than actual date references`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T09:00:00Z", "2026-09-21T11:00:00Z"),
                            range("2026-09-28T09:00:00Z", "2026-09-28T11:00:00Z"),
                            range("2026-10-05T10:00:00Z", "2026-10-05T11:00:00Z"),
                        ),
                    endDate = LocalDate.of(2026, 10, 13),
                    pattern = mondayEvening,
                ),
            )

        assertEquals(CandidateSummaryStrategy.PATTERN_WITH_EXCEPTIONS, summary.strategy)
        assertTrue("월요일" in summary.text)
        assertTrue("10월 5일" in summary.text && "19:00" in summary.text && "20:00" in summary.text)
    }

    @Test
    fun `uses explicit occurrences when exception and actual date references tie`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences = listOf(range("2026-09-21T09:00:00Z", "2026-09-21T11:00:00Z")),
                    endDate = LocalDate.of(2026, 10, 6),
                    pattern = mondayEvening,
                ),
            )

        assertEquals(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, summary.strategy)
    }

    @Test
    fun `uses explicit occurrences for irregular dates without a recurring pattern`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-22T09:00:00Z", "2026-09-22T10:00:00Z"),
                            range("2026-09-25T11:00:00Z", "2026-09-25T12:30:00Z"),
                        ),
                ),
            )

        assertEquals(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, summary.strategy)
    }

    private fun request(
        occurrences: List<InstantTimeRange>,
        endDate: LocalDate = LocalDate.of(2026, 9, 28),
        pattern: RecurringCandidatePattern? = null,
    ) = CandidateSummaryRequest(
        occurrences = occurrences,
        searchStartDate = LocalDate.of(2026, 9, 20),
        searchEndDate = endDate,
        timeZone = ZoneId.of("Asia/Seoul"),
        locale = Locale.forLanguageTag("ko-KR"),
        recurringPattern = pattern,
    )

    private fun range(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    companion object {
        private val mondayEvening =
            RecurringCandidatePattern(
                DayOfWeek.MONDAY,
                LocalTime.of(18, 0),
                LocalTime.of(20, 0),
            )
    }
}
