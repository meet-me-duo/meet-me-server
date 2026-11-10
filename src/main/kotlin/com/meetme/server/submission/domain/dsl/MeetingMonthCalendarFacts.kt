package com.meetme.server.submission.domain.dsl

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

const val SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT = "schedule-dsl-evidence-request-3"
const val SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT = "schedule-dsl-evidence-response-3"

data class MeetingMonthCalendarDay(
    val localDate: LocalDate,
    val weekday: DayOfWeek,
    val weekdayOccurrenceInMonth: Int,
    val isLastWeekdayInMonth: Boolean,
    val monthFirstWeekday: DayOfWeek,
    val daysInMonth: Int,
)

object MeetingMonthCalendarFacts {
    const val VERSION = "server-calendar-facts-2"

    fun forDate(date: LocalDate): MeetingMonthCalendarDay =
        MeetingMonthCalendarDay(
            date,
            date.dayOfWeek,
            (date.dayOfMonth - 1) / 7 + 1,
            date.dayOfMonth + 7 > date.lengthOfMonth(),
            date.withDayOfMonth(1).dayOfWeek,
            date.lengthOfMonth(),
        )

    fun forRange(
        startInclusive: LocalDate,
        endExclusive: LocalDate,
    ): List<MeetingMonthCalendarDay> {
        val days = ChronoUnit.DAYS.between(startInclusive, endExclusive)
        require(days in 1..MeetingDateScopePolicy.MAX_SEARCH_DAYS.toLong()) { "Meeting month calendar range limit" }
        return (0 until days).map { forDate(startInclusive.plusDays(it)) }
    }
}
