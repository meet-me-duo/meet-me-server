package com.meetme.server.domain.matching

import com.meetme.server.domain.submission.StructuredCondition
import com.meetme.server.domain.time.DatedTimeRange
import com.meetme.server.domain.time.InstantTimeRange
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
import com.meetme.server.domain.time.WeeklyTimeRange
import com.meetme.server.domain.time.resolveEndOfDayBoundary
import com.meetme.server.domain.time.resolveOn
import com.meetme.server.domain.time.resolveStartOfDay
import java.time.LocalDate

object TimeRangeMatcher {
    fun normalize(ranges: List<InstantTimeRange>): List<InstantTimeRange> {
        if (ranges.isEmpty()) return emptyList()

        val sorted =
            ranges
                .onEach { require(it.startInclusive < it.endExclusive) { "Time range must be non-empty" } }
                .sortedWith(compareBy(InstantTimeRange::startInclusive, InstantTimeRange::endExclusive))
        val normalized = mutableListOf<InstantTimeRange>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.startInclusive <= current.endExclusive) {
                current = InstantTimeRange(current.startInclusive, maxOf(current.endExclusive, next.endExclusive))
            } else {
                normalized += current
                current = next
            }
        }
        normalized += current
        return normalized
    }

    fun intersect(
        left: List<InstantTimeRange>,
        right: List<InstantTimeRange>,
    ): List<InstantTimeRange> {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        val result = mutableListOf<InstantTimeRange>()
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < normalizedLeft.size && rightIndex < normalizedRight.size) {
            val leftRange = normalizedLeft[leftIndex]
            val rightRange = normalizedRight[rightIndex]
            val start = maxOf(leftRange.startInclusive, rightRange.startInclusive)
            val end = minOf(leftRange.endExclusive, rightRange.endExclusive)
            if (start < end) result += InstantTimeRange(start, end)

            if (leftRange.endExclusive <= rightRange.endExclusive) leftIndex++ else rightIndex++
        }
        return normalize(result)
    }

    fun subtract(
        available: List<InstantTimeRange>,
        blocked: List<InstantTimeRange>,
    ): List<InstantTimeRange> {
        val normalizedBlocked = normalize(blocked)
        if (normalizedBlocked.isEmpty()) return normalize(available)

        return normalize(available).flatMap { availableRange ->
            val remaining = mutableListOf<InstantTimeRange>()
            var cursor = availableRange.startInclusive
            for (blockedRange in normalizedBlocked) {
                if (blockedRange.endExclusive <= cursor) continue
                if (blockedRange.startInclusive >= availableRange.endExclusive) break
                if (blockedRange.startInclusive > cursor) {
                    remaining += InstantTimeRange(cursor, minOf(blockedRange.startInclusive, availableRange.endExclusive))
                }
                cursor = maxOf(cursor, blockedRange.endExclusive)
                if (cursor >= availableRange.endExclusive) break
            }
            if (cursor < availableRange.endExclusive) {
                remaining += InstantTimeRange(cursor, availableRange.endExclusive)
            }
            remaining
        }
    }

    fun expandNaturalWindows(
        windows: List<StructuredCondition.TimeWindow>,
        searchRange: SearchDateRange,
        zone: MeetingTimeZone,
    ): List<InstantTimeRange> =
        normalize(
            windows.flatMap { window ->
                datesFor(window.date, window.dayOfWeek, searchRange).map { date ->
                    com.meetme.server.domain.time.LocalTimeRange
                        .of(window.startTime, window.endTime)
                        .resolveOn(date, zone)
                }
            },
        )

    fun calculateAvailability(
        naturalWindows: List<StructuredCondition.TimeWindow>,
        datedManualAvailability: List<DatedTimeRange>,
        weeklyManualAvailability: List<WeeklyTimeRange>,
        blocked: List<InstantTimeRange>,
        searchRange: SearchDateRange,
        zone: MeetingTimeZone,
        duration: MeetingDuration,
    ): List<InstantTimeRange> {
        val availableConditions =
            naturalWindows.filter { it.polarity == com.meetme.server.domain.submission.TimePolarity.AVAILABLE }
        val naturalAvailable =
            expandNaturalWindows(
                availableConditions,
                searchRange,
                zone,
            )
        val naturalUnavailable =
            expandNaturalWindows(
                naturalWindows.filter { it.polarity == com.meetme.server.domain.submission.TimePolarity.UNAVAILABLE },
                searchRange,
                zone,
            )
        val searchBoundary = searchBoundary(searchRange, zone)
        var available = if (availableConditions.isEmpty()) listOf(searchBoundary) else naturalAvailable

        val manual =
            normalize(
                datedManualAvailability
                    .filter { it.date >= searchRange.startInclusive && it.date < searchRange.endExclusive }
                    .map { it.time.resolveOn(it.date, zone) } +
                    weeklyManualAvailability.flatMap { weekly ->
                        datesFor(null, weekly.dayOfWeek, searchRange).map { weekly.time.resolveOn(it, zone) }
                    },
            )
        if (datedManualAvailability.isNotEmpty() || weeklyManualAvailability.isNotEmpty()) {
            available = intersect(available, manual)
        }

        return subtract(intersect(available, listOf(searchBoundary)), naturalUnavailable + blocked)
            .filter { java.time.Duration.between(it.startInclusive, it.endExclusive) >= duration.value }
    }

    private fun datesFor(
        date: LocalDate?,
        dayOfWeek: java.time.DayOfWeek?,
        searchRange: SearchDateRange,
    ): List<LocalDate> {
        if (date != null) {
            return if (date >= searchRange.startInclusive && date < searchRange.endExclusive) listOf(date) else emptyList()
        }
        return generateSequence(searchRange.startInclusive) { it.plusDays(1) }
            .takeWhile { it < searchRange.endExclusive }
            .filter { it.dayOfWeek == dayOfWeek }
            .toList()
    }

    private fun searchBoundary(
        searchRange: SearchDateRange,
        zone: MeetingTimeZone,
    ): InstantTimeRange =
        InstantTimeRange(
            searchRange.startInclusive.resolveStartOfDay(zone),
            searchRange.endExclusive.resolveEndOfDayBoundary(zone),
        )
}
