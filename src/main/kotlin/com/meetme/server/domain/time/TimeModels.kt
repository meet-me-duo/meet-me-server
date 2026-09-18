package com.meetme.server.domain.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@JvmInline
value class MeetingDuration private constructor(
    val value: Duration,
) {
    companion object {
        fun ofMinutes(minutes: Long): MeetingDuration {
            require(minutes > 0) { "Meeting duration must be positive" }
            return MeetingDuration(Duration.ofMinutes(minutes))
        }
    }
}

@JvmInline
value class MeetingTimeZone private constructor(
    val value: ZoneId,
) {
    companion object {
        fun of(zoneId: String): MeetingTimeZone {
            require(zoneId.isNotBlank()) { "Time zone must not be blank" }
            return MeetingTimeZone(ZoneId.of(zoneId))
        }
    }
}

enum class SearchRangeSource {
    HOST_SPECIFIED,
    DEFAULTED,
}

data class SearchDateRange private constructor(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
    val source: SearchRangeSource,
) {
    companion object {
        fun explicit(
            startInclusive: LocalDate,
            endExclusive: LocalDate,
        ): SearchDateRange {
            val days = ChronoUnit.DAYS.between(startInclusive, endExclusive)
            require(days in 1..31) { "Search range must contain between 1 and 31 days" }
            return SearchDateRange(startInclusive, endExclusive, SearchRangeSource.HOST_SPECIFIED)
        }

        fun defaultFrom(
            createdAt: Instant,
            zone: MeetingTimeZone,
        ): SearchDateRange {
            val start = createdAt.atZone(zone.value).toLocalDate()
            return SearchDateRange(start, start.plusDays(14), SearchRangeSource.DEFAULTED)
        }

        fun restore(
            startInclusive: LocalDate,
            endExclusive: LocalDate,
            source: SearchRangeSource,
        ): SearchDateRange {
            val range = explicit(startInclusive, endExclusive)
            return range.copy(source = source)
        }
    }
}

data class LocalTimeRange private constructor(
    val startInclusive: LocalTime,
    val endExclusive: LocalTime,
) {
    companion object {
        fun of(
            startInclusive: LocalTime,
            endExclusive: LocalTime,
        ): LocalTimeRange {
            require(startInclusive < endExclusive) { "Local time range must be non-empty and cannot cross midnight" }
            return LocalTimeRange(startInclusive, endExclusive)
        }
    }
}

data class InstantTimeRange(
    val startInclusive: Instant,
    val endExclusive: Instant,
)

data class DatedTimeRange(
    val date: LocalDate,
    val time: LocalTimeRange,
)

data class WeeklyTimeRange(
    val dayOfWeek: DayOfWeek,
    val time: LocalTimeRange,
)

fun LocalTimeRange.resolveOn(
    date: LocalDate,
    zone: MeetingTimeZone,
): InstantTimeRange {
    val start = resolveBoundary(date, startInclusive, zone, Boundary.START)
    val end = resolveBoundary(date, endExclusive, zone, Boundary.END)
    require(start < end) { "Resolved instant range must be non-empty" }
    return InstantTimeRange(start, end)
}

private enum class Boundary {
    START,
    END,
}

private fun resolveBoundary(
    date: LocalDate,
    time: LocalTime,
    zone: MeetingTimeZone,
    boundary: Boundary,
): Instant {
    val localDateTime = date.atTime(time)
    val rules = zone.value.rules
    val validOffsets = rules.getValidOffsets(localDateTime)
    val offset =
        when (validOffsets.size) {
            0 -> return requireNotNull(rules.getTransition(localDateTime)).instant
            1 -> validOffsets.single()
            else -> validOffsets.offsetFor(boundary)
        }
    return localDateTime.toInstant(offset)
}

private fun List<ZoneOffset>.offsetFor(boundary: Boundary): ZoneOffset =
    when (boundary) {
        Boundary.START -> maxBy { it.totalSeconds }
        Boundary.END -> minBy { it.totalSeconds }
    }
