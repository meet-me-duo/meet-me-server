package com.meetme.server.coordination.application.service

import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

enum class CandidateSummaryStrategy {
    PATTERN_WITH_EXCEPTIONS,
    EXPLICIT_OCCURRENCES,
}

data class RecurringCandidatePattern(
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

data class CandidateSummaryRequest(
    val occurrences: List<InstantTimeRange>,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val timeZone: ZoneId,
    val locale: Locale,
    val recurringPattern: RecurringCandidatePattern? = null,
)

data class CandidateSummary(
    val strategy: CandidateSummaryStrategy,
    val text: String,
)

object CandidateSummaryRenderer {
    fun render(request: CandidateSummaryRequest): CandidateSummary {
        val occurrences =
            request.occurrences
                .sortedBy { it.startInclusive }
                .map {
                    LocalOccurrence(
                        it.startInclusive.atZone(request.timeZone).toLocalDate(),
                        it.startInclusive.atZone(request.timeZone).toLocalTime(),
                        it.endExclusive.atZone(request.timeZone).toLocalTime(),
                    )
                }
        val pattern = request.recurringPattern
        if (pattern == null) {
            return CandidateSummary(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, explicitText(occurrences, emptyList()))
        }

        val expectedDates =
            generateSequence(request.searchStartDate) { it.plusDays(1) }
                .takeWhile { it < request.searchEndDate }
                .filter { it.dayOfWeek == pattern.dayOfWeek }
                .toList()
        val byDate = occurrences.groupBy { it.date }
        val exceptions =
            expectedDates.mapNotNull { date ->
                val actual = byDate[date].orEmpty()
                when {
                    actual.size == 1 && actual.single().start == pattern.startTime && actual.single().end == pattern.endTime -> null
                    actual.isEmpty() -> SummaryException(date, null)
                    else -> SummaryException(date, actual)
                }
            }
        val actualDateReferences = occurrences.map { it.date }.distinct().size
        return if (exceptions.size < actualDateReferences) {
            CandidateSummary(
                CandidateSummaryStrategy.PATTERN_WITH_EXCEPTIONS,
                buildString {
                    append(pattern.dayOfWeek.koreanName())
                    append(' ')
                    append(pattern.startTime.hhmm())
                    append('~')
                    append(pattern.endTime.hhmm())
                    if (exceptions.isNotEmpty()) {
                        append(", 예외: ")
                        append(exceptions.joinToString(", ") { it.text() })
                    }
                },
            )
        } else {
            CandidateSummary(CandidateSummaryStrategy.EXPLICIT_OCCURRENCES, explicitText(occurrences, exceptions))
        }
    }

    private fun explicitText(
        occurrences: List<LocalOccurrence>,
        exceptions: List<SummaryException>,
    ): String {
        val occurrenceText = compactOccurrences(occurrences)
        val excluded = exceptions.filter { it.occurrences == null }.joinToString(", ") { "${it.date.koreanDate()} 제외" }
        return listOf(occurrenceText, excluded).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun compactOccurrences(occurrences: List<LocalOccurrence>): String {
        val schedules =
            occurrences
                .groupBy { it.date }
                .map { (date, dateOccurrences) ->
                    DailySchedule(
                        date,
                        dateOccurrences
                            .map { TimeSlot(it.start, it.end) }
                            .distinct()
                            .sortedWith(compareBy(TimeSlot::start, TimeSlot::end)),
                    )
                }
        val segments =
            schedules
                .groupBy { it.timeSlots }
                .flatMap { (timeSlots, sameScheduleDates) ->
                    val dates = sameScheduleDates.map { it.date }.sorted()
                    val runs = consecutiveRuns(dates)
                    if (runs.all { it.size == 1 }) {
                        listOf(SummarySegment(dates.first(), dates.dateListText(), timeSlots))
                    } else {
                        runs.map { run ->
                            SummarySegment(
                                run.first(),
                                if (run.size == 1) run.single().koreanDate() else run.dateRangeText(),
                                timeSlots,
                            )
                        }
                    }
                }.sortedWith(compareBy(SummarySegment::firstDate, { it.timeSlots.firstOrNull()?.start }))
        return segments.joinToString(", ") { segment ->
            "${segment.dateText} ${segment.timeSlots.joinToString(" 또는 ") { "${it.start.hhmm()}~${it.end.hhmm()}" }}"
        }
    }

    private fun consecutiveRuns(dates: List<LocalDate>): List<List<LocalDate>> {
        if (dates.isEmpty()) return emptyList()
        val runs = mutableListOf<MutableList<LocalDate>>()
        dates.forEach { date ->
            val current = runs.lastOrNull()
            if (current == null || current.last().plusDays(1) != date) {
                runs += mutableListOf(date)
            } else {
                current += date
            }
        }
        return runs
    }

    private data class LocalOccurrence(
        val date: LocalDate,
        val start: LocalTime,
        val end: LocalTime,
    )

    private data class TimeSlot(
        val start: LocalTime,
        val end: LocalTime,
    )

    private data class DailySchedule(
        val date: LocalDate,
        val timeSlots: List<TimeSlot>,
    )

    private data class SummarySegment(
        val firstDate: LocalDate,
        val dateText: String,
        val timeSlots: List<TimeSlot>,
    )

    private data class SummaryException(
        val date: LocalDate,
        val occurrences: List<LocalOccurrence>?,
    ) {
        fun text(): String =
            occurrences?.joinToString("/") { "${date.koreanDate()} ${it.start.hhmm()}~${it.end.hhmm()}" }
                ?: "${date.koreanDate()} 제외"
    }

    private fun LocalDate.koreanDate(): String = "${monthValue}월 ${dayOfMonth}일"

    private fun List<LocalDate>.dateListText(): String =
        if (map { it.year to it.month }.distinct().size == 1) {
            "${first().monthValue}월 ${joinToString("일·") { it.dayOfMonth.toString() }}일"
        } else {
            joinToString("·") { it.koreanDate() }
        }

    private fun List<LocalDate>.dateRangeText(): String {
        val start = first()
        val end = last()
        val endText = if (start.year == end.year && start.month == end.month) "${end.dayOfMonth}일" else end.koreanDate()
        return "${start.koreanDate()}부터 ${endText}까지 매일"
    }

    private fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)

    private fun DayOfWeek.koreanName(): String =
        when (this) {
            DayOfWeek.MONDAY -> "월요일"
            DayOfWeek.TUESDAY -> "화요일"
            DayOfWeek.WEDNESDAY -> "수요일"
            DayOfWeek.THURSDAY -> "목요일"
            DayOfWeek.FRIDAY -> "금요일"
            DayOfWeek.SATURDAY -> "토요일"
            DayOfWeek.SUNDAY -> "일요일"
        }
}
