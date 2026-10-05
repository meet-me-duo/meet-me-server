package com.meetme.server.coordination.application.service

import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// FR-011A, FR-013 and ADR-014: prose must preserve the local extent of every UTC interval.
class CandidateSummaryBoundaryContractTest {
    @Test
    fun `next midnight is displayed as end of day rather than same day midnight`() {
        val text = render(range("2026-09-21T09:00:00Z", "2026-09-21T15:00:00Z"))

        assertTrue("9월 21일" in text && "18:00" in text)
        assertTrue("24:00" in text || "9월 22일" in text || "다음 날" in text || "다음날" in text)
        assertFalse("9월 21일 18:00~00:00" == text, "Same-day-looking end loses the exclusive next-day boundary: $text")
    }

    @Test
    fun `overnight interval preserves the next local date and both clock endpoints`() {
        val text = render(range("2026-09-21T14:00:00Z", "2026-09-21T16:00:00Z"))

        assertTrue("9월 21일" in text && "23:00" in text && "01:00" in text)
        assertTrue("9월 22일" in text || "다음 날" in text || "다음날" in text, "Missing next local date: $text")
        assertFalse("9월 21일 23:00~01:00" == text, "End date must not be discarded")
    }

    @Test
    fun `continuous multi day availability includes every covered local date`() {
        val text = render(range("2026-09-19T15:00:00Z", "2026-09-22T15:00:00Z"))

        assertTrue("9월 20일" in text)
        assertTrue("22일" in text || "23일" in text, "A three-day interval must expose its last covered date or endpoint: $text")
        assertTrue("24:00" in text || "23일" in text, "Full-day availability must preserve the next-midnight endpoint: $text")
        assertFalse("00:00~00:00" in text, "Non-empty multi-day availability cannot look like a zero-length interval: $text")
    }

    @Test
    fun `different end dates with identical local clock values must not collapse`() {
        val text =
            render(
                range("2026-09-20T09:00:00Z", "2026-09-20T11:00:00Z"),
                range("2026-09-21T09:00:00Z", "2026-09-22T11:00:00Z"),
            )

        assertTrue("9월 20일" in text && "18:00" in text && "20:00" in text)
        assertTrue("22일" in text, "Second interval ends a day later and cannot share the first interval's daily schedule: $text")
        assertFalse("9월 20일부터 21일까지 매일 18:00~20:00" == text)
    }

    @Test
    fun `overnight month boundary retains the destination month`() {
        val text = render(range("2026-09-30T14:00:00Z", "2026-09-30T17:00:00Z"))

        assertTrue("9월 30일" in text && "23:00" in text && "02:00" in text)
        assertTrue("10월 1일" in text, "The next month's date must be visible: $text")
    }

    private fun render(vararg occurrences: InstantTimeRange): String =
        CandidateSummaryRenderer
            .render(
                CandidateSummaryRequest(
                    occurrences = occurrences.toList(),
                    searchStartDate = LocalDate.of(2026, 9, 20),
                    searchEndDate = LocalDate.of(2026, 10, 4),
                    timeZone = ZoneId.of("Asia/Seoul"),
                    locale = Locale.forLanguageTag("ko-KR"),
                ),
            ).text

    private fun range(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))
}
