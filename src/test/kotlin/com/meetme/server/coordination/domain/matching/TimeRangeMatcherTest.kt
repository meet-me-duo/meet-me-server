package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.shared.domain.time.WeeklyTimeRange
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class TimeRangeMatcherTest {
    @Test
    fun `겹치거나 인접한 반개구간을 하나로 정규화한다`() {
        val ranges =
            listOf(
                range("2026-09-21T11:00:00Z", "2026-09-21T12:00:00Z"),
                range("2026-09-21T09:00:00Z", "2026-09-21T10:00:00Z"),
                range("2026-09-21T09:30:00Z", "2026-09-21T11:00:00Z"),
            )

        assertEquals(
            listOf(range("2026-09-21T09:00:00Z", "2026-09-21T12:00:00Z")),
            TimeRangeMatcher.normalize(ranges),
        )
    }

    @Test
    fun `불가 구간을 차감하면 양쪽의 남은 연속 구간을 보존한다`() {
        val available = listOf(range("2026-09-21T09:00:00Z", "2026-09-21T13:00:00Z"))
        val blocked = listOf(range("2026-09-21T10:00:00Z", "2026-09-21T11:00:00Z"))

        assertEquals(
            listOf(
                range("2026-09-21T09:00:00Z", "2026-09-21T10:00:00Z"),
                range("2026-09-21T11:00:00Z", "2026-09-21T13:00:00Z"),
            ),
            TimeRangeMatcher.subtract(available, blocked),
        )
    }

    @Test
    fun `두 가능 시간 목록의 실제 교집합만 반환한다`() {
        val left = listOf(range("2026-09-21T09:00:00Z", "2026-09-21T12:00:00Z"))
        val right = listOf(range("2026-09-21T10:00:00Z", "2026-09-21T13:00:00Z"))

        assertEquals(
            listOf(range("2026-09-21T10:00:00Z", "2026-09-21T12:00:00Z")),
            TimeRangeMatcher.intersect(left, right),
        )
    }

    @Test
    fun `주간 자연어 조건을 탐색 범위 안의 실제 날짜로 확장한다`() {
        val mondayEvening =
            StructuredCondition.TimeWindow(
                polarity = TimePolarity.AVAILABLE,
                date = null,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = LocalTime.of(18, 0),
                endTime = LocalTime.of(20, 0),
            )

        assertEquals(
            listOf(
                range("2026-09-21T09:00:00Z", "2026-09-21T11:00:00Z"),
                range("2026-09-28T09:00:00Z", "2026-09-28T11:00:00Z"),
            ),
            TimeRangeMatcher.expandNaturalWindows(
                listOf(mondayEvening),
                SearchDateRange.explicit(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 1)),
                MeetingTimeZone.of("Asia/Seoul"),
            ),
        )
    }

    @Test
    fun `수동 가능 시간이 있으면 자연어 가능 시간과 교차한다`() {
        val natural = datedWindow(LocalDate.of(2026, 9, 21), 9, 0, 13, 0)
        val manual = DatedTimeRange(LocalDate.of(2026, 9, 21), LocalTimeRange.of(LocalTime.of(10, 0), LocalTime.of(12, 0)))

        assertEquals(
            listOf(range("2026-09-21T01:00:00Z", "2026-09-21T03:00:00Z")),
            calculate(naturalWindows = listOf(natural), datedManualAvailability = listOf(manual)),
        )
    }

    @Test
    fun `빈 수동 가능 시간은 자연어 기준 구간을 제한하지 않는다`() {
        val natural = datedWindow(LocalDate.of(2026, 9, 21), 9, 0, 11, 0)

        assertEquals(
            listOf(range("2026-09-21T00:00:00Z", "2026-09-21T02:00:00Z")),
            calculate(naturalWindows = listOf(natural)),
        )
    }

    @Test
    fun `탐색 범위를 벗어난 구간은 후보 가능 시간에서 제거한다`() {
        val beforeRange = datedWindow(LocalDate.of(2026, 9, 20), 9, 0, 11, 0)
        val insideRange = datedWindow(LocalDate.of(2026, 9, 21), 9, 0, 11, 0)

        assertEquals(
            listOf(range("2026-09-21T00:00:00Z", "2026-09-21T02:00:00Z")),
            calculate(naturalWindows = listOf(beforeRange, insideRange)),
        )
    }

    @Test
    fun `가능 조건이 모두 탐색 범위 밖이면 중립 전체 범위로 되돌리지 않는다`() {
        val outside = datedWindow(LocalDate.of(2026, 9, 20), 9, 0, 11, 0)

        assertEquals(emptyList(), calculate(naturalWindows = listOf(outside)))
    }

    @Test
    fun `차감 후 남은 모든 연속 가능 구간을 보존한다`() {
        val natural = datedWindow(LocalDate.of(2026, 9, 21), 9, 0, 12, 0)
        val blocked = listOf(range("2026-09-21T00:40:00Z", "2026-09-21T01:30:00Z"))

        assertEquals(
            listOf(
                range("2026-09-21T00:00:00Z", "2026-09-21T00:40:00Z"),
                range("2026-09-21T01:30:00Z", "2026-09-21T03:00:00Z"),
            ),
            calculate(naturalWindows = listOf(natural), blocked = blocked),
        )
    }

    @Test
    fun `DST gap과 overlap 정책을 실제 날짜 확장에도 유지한다`() {
        val gap = datedWindow(LocalDate.of(2026, 3, 8), 2, 15, 3, 30)
        val overlap = datedWindow(LocalDate.of(2026, 11, 1), 1, 30, 1, 45)

        assertEquals(
            listOf(
                range("2026-03-08T07:00:00Z", "2026-03-08T07:30:00Z"),
                range("2026-11-01T05:30:00Z", "2026-11-01T06:45:00Z"),
            ),
            TimeRangeMatcher.expandNaturalWindows(
                listOf(gap),
                SearchDateRange.explicit(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 9)),
                MeetingTimeZone.of("America/New_York"),
            ) +
                TimeRangeMatcher.expandNaturalWindows(
                    listOf(overlap),
                    SearchDateRange.explicit(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2)),
                    MeetingTimeZone.of("America/New_York"),
                ),
        )
    }

    private fun calculate(
        naturalWindows: List<StructuredCondition.TimeWindow>,
        datedManualAvailability: List<DatedTimeRange> = emptyList(),
        blocked: List<InstantTimeRange> = emptyList(),
    ): List<InstantTimeRange> =
        TimeRangeMatcher.calculateAvailability(
            naturalWindows = naturalWindows,
            datedManualAvailability = datedManualAvailability,
            weeklyManualAvailability = emptyList<WeeklyTimeRange>(),
            blocked = blocked,
            searchRange = SearchDateRange.explicit(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 23)),
            zone = MeetingTimeZone.of("Asia/Seoul"),
        )

    private fun datedWindow(
        date: LocalDate,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
    ) = StructuredCondition.TimeWindow(
        polarity = TimePolarity.AVAILABLE,
        date = date,
        dayOfWeek = null,
        startTime = LocalTime.of(startHour, startMinute),
        endTime = LocalTime.of(endHour, endMinute),
    )

    private fun range(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))
}
