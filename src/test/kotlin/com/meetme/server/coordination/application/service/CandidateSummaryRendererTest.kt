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
    fun `연속 날짜의 동일 시간 구간을 매일 표현으로 압축한다`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T10:00:00Z", "2026-09-21T13:00:00Z"),
                            range("2026-09-22T10:00:00Z", "2026-09-22T13:00:00Z"),
                            range("2026-09-23T10:00:00Z", "2026-09-23T13:00:00Z"),
                            range("2026-09-24T10:00:00Z", "2026-09-24T13:00:00Z"),
                            range("2026-09-25T10:00:00Z", "2026-09-25T13:00:00Z"),
                            range("2026-09-26T04:00:00Z", "2026-09-26T08:00:00Z"),
                        ),
                ),
            )

        assertEquals(
            "9월 21일부터 25일까지 매일 19:00~22:00, 9월 26일 13:00~17:00",
            summary.text,
        )
    }

    @Test
    fun `비연속 날짜의 동일 시간 구간은 날짜를 묶고 시간을 한 번만 표시한다`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T10:00:00Z", "2026-09-21T13:00:00Z"),
                            range("2026-09-23T10:00:00Z", "2026-09-23T13:00:00Z"),
                            range("2026-09-26T10:00:00Z", "2026-09-26T13:00:00Z"),
                        ),
                ),
            )

        assertEquals("9월 21일·23일·26일 19:00~22:00", summary.text)
    }

    @Test
    fun `같은 날짜의 여러 시간 구간은 또는으로 결합한다`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T04:00:00Z", "2026-09-21T06:00:00Z"),
                            range("2026-09-21T10:00:00Z", "2026-09-21T13:00:00Z"),
                        ),
                ),
            )

        assertEquals("9월 21일 13:00~15:00 또는 19:00~22:00", summary.text)
    }

    @Test
    fun `날짜별 시간 구간 목록이 같으면 복수 시간도 함께 묶는다`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T04:00:00Z", "2026-09-21T06:00:00Z"),
                            range("2026-09-21T10:00:00Z", "2026-09-21T13:00:00Z"),
                            range("2026-09-23T04:00:00Z", "2026-09-23T06:00:00Z"),
                            range("2026-09-23T10:00:00Z", "2026-09-23T13:00:00Z"),
                        ),
                ),
            )

        assertEquals("9월 21일·23일 13:00~15:00 또는 19:00~22:00", summary.text)
    }

    @Test
    fun `날짜별 시간 구간 목록이 일부만 같으면 서로 묶지 않는다`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-21T04:00:00Z", "2026-09-21T06:00:00Z"),
                            range("2026-09-21T10:00:00Z", "2026-09-21T13:00:00Z"),
                            range("2026-09-23T04:00:00Z", "2026-09-23T06:00:00Z"),
                            range("2026-09-23T11:00:00Z", "2026-09-23T13:00:00Z"),
                        ),
                ),
            )

        assertEquals(
            "9월 21일 13:00~15:00 또는 19:00~22:00, 9월 23일 13:00~15:00 또는 20:00~22:00",
            summary.text,
        )
    }

    @Test
    fun `월 경계를 넘는 연속 날짜는 양쪽 월을 표시한다`() {
        val summary =
            CandidateSummaryRenderer.render(
                request(
                    occurrences =
                        listOf(
                            range("2026-09-30T10:00:00Z", "2026-09-30T13:00:00Z"),
                            range("2026-10-01T10:00:00Z", "2026-10-01T13:00:00Z"),
                            range("2026-10-02T10:00:00Z", "2026-10-02T13:00:00Z"),
                        ),
                    endDate = LocalDate.of(2026, 10, 4),
                ),
            )

        assertEquals("9월 30일부터 10월 2일까지 매일 19:00~22:00", summary.text)
    }

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
