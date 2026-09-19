package com.meetme.server.submission.domain

import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.shared.domain.time.SearchRangeSource
import com.meetme.server.shared.domain.time.WeeklyTimeRange
import java.time.LocalTime

object SubmissionRules {
    const val MAX_ROOM_PARTICIPANTS = 50
    const val MAX_RAW_TEXT_CODE_POINTS = 500
    const val MAX_BATCH_TEXT_CODE_POINTS = 10_000

    fun normalizeAvailability(
        availability: List<ManualAvailability>,
        searchRange: SearchDateRange,
    ): List<ManualAvailability> {
        require(availability.size <= 256) { "Too many manual availability intervals" }
        return when (searchRange.source) {
            SearchRangeSource.HOST_SPECIFIED -> normalizeDated(availability, searchRange)
            SearchRangeSource.DEFAULTED -> normalizeWeekly(availability)
        }.also { require(it.size <= 128) { "Too many normalized manual availability intervals" } }
    }

    private fun normalizeDated(
        availability: List<ManualAvailability>,
        searchRange: SearchDateRange,
    ): List<ManualAvailability> {
        val ranges =
            availability.map {
                require(it is ManualAvailability.Dated) { "Explicit search ranges require dated availability" }
                require(it.range.date >= searchRange.startInclusive && it.range.date < searchRange.endExclusive) {
                    "Dated availability must be inside the room search range"
                }
                it.range
            }
        return ranges
            .groupBy(DatedTimeRange::date)
            .toSortedMap()
            .flatMap { (date, datedRanges) ->
                merge(datedRanges.map { it.time }).map { ManualAvailability.Dated(DatedTimeRange(date, it)) }
            }
    }

    private fun normalizeWeekly(availability: List<ManualAvailability>): List<ManualAvailability> {
        val ranges =
            availability.map {
                require(it is ManualAvailability.Weekly) { "Default search ranges require weekly availability" }
                it.range
            }
        return ranges
            .groupBy(WeeklyTimeRange::dayOfWeek)
            .toSortedMap(compareBy { it.value })
            .flatMap { (day, weeklyRanges) ->
                merge(weeklyRanges.map { it.time }).map { ManualAvailability.Weekly(WeeklyTimeRange(day, it)) }
            }
    }

    private fun merge(ranges: List<LocalTimeRange>): List<LocalTimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedWith(compareBy<LocalTimeRange> { it.startInclusive }.thenBy { it.endExclusive })
        val merged = mutableListOf<Pair<LocalTime, LocalTime>>()
        sorted.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous == null || range.startInclusive > previous.second) {
                merged += range.startInclusive to range.endExclusive
            } else if (range.endExclusive > previous.second) {
                merged[merged.lastIndex] = previous.first to range.endExclusive
            }
        }
        return merged.map { (start, end) -> LocalTimeRange.of(start, end) }
    }
}
