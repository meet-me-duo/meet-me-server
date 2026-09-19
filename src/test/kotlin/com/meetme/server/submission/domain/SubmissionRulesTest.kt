package com.meetme.server.submission.domain

import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.shared.domain.time.WeeklyTimeRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class SubmissionRulesTest {
    @Test
    fun `명시 날짜 범위의 인접 가능 시간을 하나로 병합한다`() {
        val date = LocalDate.of(2026, 9, 21)
        val normalized =
            SubmissionRules.normalizeAvailability(
                listOf(
                    dated(date, 18, 19),
                    dated(date, 19, 20),
                    dated(date, 17, 18),
                ),
                SearchDateRange.explicit(date, date.plusDays(7)),
            )

        assertEquals(listOf(dated(date, 17, 20)), normalized)
    }

    @Test
    fun `명시 날짜 범위에서는 주간 반복 입력을 거부한다`() {
        val start = LocalDate.of(2026, 9, 21)
        assertThrows<IllegalArgumentException> {
            SubmissionRules.normalizeAvailability(
                listOf(weekly(DayOfWeek.MONDAY, 18, 20)),
                SearchDateRange.explicit(start, start.plusDays(7)),
            )
        }
    }

    @Test
    fun `기본 범위에서는 실제 날짜 입력을 거부하고 주간 반복을 병합한다`() {
        val createdAt = java.time.Instant.parse("2026-09-19T00:00:00Z")
        val range =
            SearchDateRange.defaultFrom(
                createdAt,
                com.meetme.server.shared.domain.time.MeetingTimeZone
                    .of("Asia/Seoul"),
            )
        assertThrows<IllegalArgumentException> {
            SubmissionRules.normalizeAvailability(
                listOf(dated(range.startInclusive, 18, 20)),
                range,
            )
        }
        assertEquals(
            listOf(weekly(DayOfWeek.MONDAY, 18, 21)),
            SubmissionRules.normalizeAvailability(
                listOf(weekly(DayOfWeek.MONDAY, 19, 21), weekly(DayOfWeek.MONDAY, 18, 20)),
                range,
            ),
        )
    }

    @Test
    fun `탐색 범위 밖 실제 날짜를 거부한다`() {
        val start = LocalDate.of(2026, 9, 21)
        assertThrows<IllegalArgumentException> {
            SubmissionRules.normalizeAvailability(
                listOf(dated(start.minusDays(1), 18, 20)),
                SearchDateRange.explicit(start, start.plusDays(7)),
            )
        }
    }

    private fun dated(
        date: LocalDate,
        startHour: Int,
        endHour: Int,
    ) = ManualAvailability.Dated(
        DatedTimeRange(
            date,
            LocalTimeRange.of(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)),
        ),
    )

    private fun weekly(
        day: DayOfWeek,
        startHour: Int,
        endHour: Int,
    ) = ManualAvailability.Weekly(
        WeeklyTimeRange(
            day,
            LocalTimeRange.of(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)),
        ),
    )
}
