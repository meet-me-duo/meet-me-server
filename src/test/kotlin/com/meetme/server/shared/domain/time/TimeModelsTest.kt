package com.meetme.server.shared.domain.time

import com.meetme.server.coordination.domain.location.GeoCoordinate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals

class TimeModelsTest {
    @Test
    fun `좌표의 유효 범위를 검증한다`() {
        assertEquals(BigDecimal("37.5665"), GeoCoordinate.of(BigDecimal("37.5665"), BigDecimal("126.9780")).latitude)
        assertThrows<IllegalArgumentException> { GeoCoordinate.of(BigDecimal("90.0001"), BigDecimal.ZERO) }
    }

    @Test
    fun `기본 탐색 범위는 방 지역 생성일부터 14일 반개구간이다`() {
        val range =
            SearchDateRange.defaultFrom(
                Instant.parse("2026-09-18T16:00:00Z"),
                MeetingTimeZone.of("Asia/Seoul"),
            )

        assertEquals(LocalDate.of(2026, 9, 19), range.startInclusive)
        assertEquals(LocalDate.of(2026, 10, 3), range.endExclusive)
        assertEquals(SearchRangeSource.DEFAULTED, range.source)
    }

    @Test
    fun `명시 탐색 범위는 최대 31일이다`() {
        val start = LocalDate.of(2026, 9, 1)
        assertEquals(start.plusDays(31), SearchDateRange.explicit(start, start.plusDays(31)).endExclusive)
        assertThrows<IllegalArgumentException> { SearchDateRange.explicit(start, start.plusDays(32)) }
        assertThrows<IllegalArgumentException> { SearchDateRange.explicit(start, start) }
    }

    @Test
    fun `DST gap의 존재하지 않는 시작 시각을 다음 유효 시각으로 이동한다`() {
        val resolved =
            LocalTimeRange
                .of(LocalTime.of(2, 15), LocalTime.of(3, 30))
                .resolveOn(LocalDate.of(2026, 3, 8), MeetingTimeZone.of("America/New_York"))

        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), resolved.startInclusive)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), resolved.endExclusive)
    }

    @Test
    fun `DST overlap은 이른 시작 offset부터 늦은 종료 offset까지 보존한다`() {
        val resolved =
            LocalTimeRange
                .of(LocalTime.of(1, 30), LocalTime.of(1, 45))
                .resolveOn(LocalDate.of(2026, 11, 1), MeetingTimeZone.of("America/New_York"))

        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), resolved.startInclusive)
        assertEquals(Instant.parse("2026-11-01T06:45:00Z"), resolved.endExclusive)
    }
}
