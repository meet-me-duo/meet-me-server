package com.meetme.server.submission.domain.dsl

import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent Gregorian calendar facts; neither native text nor availability interpretations enter this API. */
class MeetingMonthCalendarFactsTest {
    @Test
    fun `MCF01 calendar facts expose a version and objective first day attributes`() {
        val day = LocalDate.of(2044, 9, 1)
        val actual = MeetingMonthCalendarFacts.forDate(day)
        assertEquals("server-calendar-facts-2", MeetingMonthCalendarFacts.VERSION)
        assertEquals(day, actual.localDate)
        assertEquals(DayOfWeek.THURSDAY, actual.weekday)
        assertEquals(1, actual.weekdayOccurrenceInMonth)
        assertFalse(actual.isLastWeekdayInMonth)
        assertEquals(DayOfWeek.THURSDAY, actual.monthFirstWeekday)
        assertEquals(30, actual.daysInMonth)
    }

    @Test
    fun `MCF02 a five occurrence weekday is counted over its complete month`() {
        val fridays = listOf(2, 9, 16, 23, 30)
        fridays.forEachIndexed { index, dayOfMonth ->
            val actual = MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, dayOfMonth))
            assertEquals(DayOfWeek.FRIDAY, actual.weekday)
            assertEquals(index + 1, actual.weekdayOccurrenceInMonth)
            assertEquals(index == 4, actual.isLastWeekdayInMonth)
            assertEquals(DayOfWeek.THURSDAY, actual.monthFirstWeekday)
            assertEquals(30, actual.daysInMonth)
        }
    }

    @Test
    fun `MCF03 the last occurrence of each weekday includes weekends and need not be the month final day`() {
        (24..30).forEach { day ->
            assertTrue(MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, day)).isLastWeekdayInMonth)
        }
        (17..23).forEach { day ->
            assertFalse(MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, day)).isLastWeekdayInMonth)
        }
        val saturday = MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, 24))
        assertEquals(DayOfWeek.SATURDAY, saturday.weekday)
        assertEquals(4, saturday.weekdayOccurrenceInMonth)
        val sunday = MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, 25))
        assertEquals(DayOfWeek.SUNDAY, sunday.weekday)
        assertEquals(4, sunday.weekdayOccurrenceInMonth)
    }

    @Test
    fun `MCF04 weekday occurrence is not the calendar row or a product week number`() {
        // September starts Thursday: its first Monday falls in the second calendar row.
        val firstMonday = MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, 5))
        val thirdMonday = MeetingMonthCalendarFacts.forDate(LocalDate.of(2044, 9, 19))
        assertEquals(1, firstMonday.weekdayOccurrenceInMonth)
        assertEquals(3, thirdMonday.weekdayOccurrenceInMonth)
        assertEquals(DayOfWeek.MONDAY, thirdMonday.weekday)
        assertEquals(DayOfWeek.THURSDAY, thirdMonday.monthFirstWeekday)
        assertFalse(thirdMonday.isLastWeekdayInMonth)
    }

    @Test
    fun `MCF05 a truncated search range cannot restart monthly counts or create a false last occurrence`() {
        val actual = MeetingMonthCalendarFacts.forRange(LocalDate.of(2044, 9, 17), LocalDate.of(2044, 9, 22))
        assertEquals((17..21).map { LocalDate.of(2044, 9, it) }, actual.map { it.localDate })
        assertEquals(
            listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
            actual.map {
                it.weekday
            },
        )
        assertEquals(listOf(3, 3, 3, 3, 3), actual.map { it.weekdayOccurrenceInMonth })
        assertTrue(actual.none { it.isLastWeekdayInMonth })
        assertTrue(actual.all { it.monthFirstWeekday == DayOfWeek.THURSDAY && it.daysInMonth == 30 })
    }

    @Test
    fun `MCF06 crossing December and January resets only the calendar month facts`() {
        val actual = MeetingMonthCalendarFacts.forRange(LocalDate.of(2047, 12, 30), LocalDate.of(2048, 1, 3))
        assertEquals(
            listOf(LocalDate.of(2047, 12, 30), LocalDate.of(2047, 12, 31), LocalDate.of(2048, 1, 1), LocalDate.of(2048, 1, 2)),
            actual.map { it.localDate },
        )
        assertEquals(listOf(5, 5, 1, 1), actual.map { it.weekdayOccurrenceInMonth })
        assertEquals(listOf(true, true, false, false), actual.map { it.isLastWeekdayInMonth })
        assertEquals(
            listOf(DayOfWeek.SUNDAY, DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY, DayOfWeek.WEDNESDAY),
            actual.map { it.monthFirstWeekday },
        )
        assertEquals(listOf(31, 31, 31, 31), actual.map { it.daysInMonth })
    }

    @Test
    fun `MCF07 leap February can contain a fifth last weekday while common February cannot`() {
        val leap = MeetingMonthCalendarFacts.forDate(LocalDate.of(2040, 2, 29))
        assertEquals(DayOfWeek.WEDNESDAY, leap.weekday)
        assertEquals(5, leap.weekdayOccurrenceInMonth)
        assertTrue(leap.isLastWeekdayInMonth)
        assertEquals(DayOfWeek.WEDNESDAY, leap.monthFirstWeekday)
        assertEquals(29, leap.daysInMonth)
        assertFalse(MeetingMonthCalendarFacts.forDate(LocalDate.of(2040, 2, 22)).isLastWeekdayInMonth)
        val common = MeetingMonthCalendarFacts.forDate(LocalDate.of(2045, 2, 28))
        assertEquals(DayOfWeek.TUESDAY, common.weekday)
        assertEquals(4, common.weekdayOccurrenceInMonth)
        assertTrue(common.isLastWeekdayInMonth)
        assertEquals(28, common.daysInMonth)
    }

    @Test
    fun `MCF08 Gregorian century rules distinguish 1900 and 2000 month lengths`() {
        val commonCentury = MeetingMonthCalendarFacts.forDate(LocalDate.of(1900, 2, 28))
        val leapCentury = MeetingMonthCalendarFacts.forDate(LocalDate.of(2000, 2, 29))
        assertEquals(28, commonCentury.daysInMonth)
        assertEquals(29, leapCentury.daysInMonth)
        assertEquals(DayOfWeek.WEDNESDAY, commonCentury.weekday)
        assertEquals(DayOfWeek.TUESDAY, leapCentury.weekday)
        assertEquals(4, commonCentury.weekdayOccurrenceInMonth)
        assertEquals(5, leapCentury.weekdayOccurrenceInMonth)
        assertTrue(commonCentury.isLastWeekdayInMonth)
        assertTrue(leapCentury.isLastWeekdayInMonth)
    }

    @Test
    fun `MCF09 empty and reversed ranges fail explicitly instead of returning a misleading calendar`() {
        val day = LocalDate.of(2044, 9, 19)
        assertFailsWith<IllegalArgumentException> { MeetingMonthCalendarFacts.forRange(day, day) }
        assertFailsWith<IllegalArgumentException> { MeetingMonthCalendarFacts.forRange(day.plusDays(1), day) }
    }

    @Test
    fun `MCF10 the bounded range accepts 366 days and rejects 367 without silently truncating`() {
        val start = LocalDate.of(2040, 1, 1)
        val actual = MeetingMonthCalendarFacts.forRange(start, start.plusDays(366))
        assertEquals(366, actual.size)
        assertEquals(start, actual.first().localDate)
        assertEquals(LocalDate.of(2040, 12, 31), actual.last().localDate)
        assertFailsWith<IllegalArgumentException> { MeetingMonthCalendarFacts.forRange(start, start.plusDays(367)) }
    }

    @Test
    fun `MCF11 one search day includes its start and excludes its end without calling itself the last monthly occurrence`() {
        val firstFriday = LocalDate.of(2044, 9, 2)
        val actual = MeetingMonthCalendarFacts.forRange(firstFriday, firstFriday.plusDays(1))
        assertEquals(1, actual.size)
        assertEquals(firstFriday, actual.single().localDate)
        assertEquals(1, actual.single().weekdayOccurrenceInMonth)
        assertFalse(actual.single().isLastWeekdayInMonth)
    }
}
