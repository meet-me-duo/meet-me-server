package com.meetme.server.submission.domain.time

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** User-approved table only; no free-text classifier, model accuracy or owner confirmation claim. */
class ApprovedKoreanTimeTermFullTableTest {
    @ParameterizedTest
    @CsvSource(
        "AM,2027-01-08T00:00,2027-01-08T12:00,오전",
        "PM,2027-01-08T12:00,2027-01-09T00:00,오후",
        "DAWN,2027-01-08T00:00,2027-01-08T06:00,새벽",
        "MORNING,2027-01-08T06:00,2027-01-08T11:00,아침",
        "LUNCH,2027-01-08T11:00,2027-01-08T15:00,점심",
        "DAYTIME,2027-01-08T09:00,2027-01-08T18:00,낮",
        "MIDDAY,2027-01-08T11:00,2027-01-08T15:00,한낮",
        "EVENING,2027-01-08T17:00,2027-01-08T22:00,저녁",
        "NIGHT,2027-01-08T20:00,2027-01-09T06:00,밤",
        "LATE_NIGHT,2027-01-08T22:00,2027-01-09T04:00,심야",
        "ALL_DAY,2027-01-08T00:00,2027-01-09T00:00,하루종일",
    )
    fun `ATT01 exact eleven local half open intervals`(
        term: ApprovedKoreanTimeTerm,
        start: String,
        end: String,
        lexeme: String,
    ) {
        assertEquals(
            IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end)),
            ApprovedKoreanTimeTermPolicy.resolve(term, LocalDate.of(2027, 1, 8)),
        )
        assertEquals(
            if (term == ApprovedKoreanTimeTerm.LATE_NIGHT) setOf("심야", "한밤중") else setOf(lexeme),
            ApprovedKoreanTimeTermPolicy.lexemes(term),
        )
    }

    @Test
    fun `ATT02 late night rolls over leap day and year end by calendar date`() {
        assertEquals(
            LocalDateTime.parse("2028-03-01T04:00"),
            ApprovedKoreanTimeTermPolicy.resolve(ApprovedKoreanTimeTerm.LATE_NIGHT, LocalDate.of(2028, 2, 29)).end,
        )
        assertEquals(
            LocalDateTime.parse("2028-01-01T06:00"),
            ApprovedKoreanTimeTermPolicy.resolve(ApprovedKoreanTimeTerm.NIGHT, LocalDate.of(2027, 12, 31)).end,
        )
        assertFailsWith<DateTimeException> {
            ApprovedKoreanTimeTermPolicy.resolve(ApprovedKoreanTimeTerm.NIGHT, LocalDate.MAX)
        }
    }
}
