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

/** Approved vocabulary mapping only: no raw-text interpretation, effect selection or participant confirmation. */
class ApprovedKoreanHalfDayPolicyTest {
    @ParameterizedTest
    @CsvSource(
        "AM,2027-01-08,2027-01-08T00:00,2027-01-08T12:00",
        "PM,2027-01-08,2027-01-08T12:00,2027-01-09T00:00",
        "AM,2027-01-09,2027-01-09T00:00,2027-01-09T12:00",
        "PM,2027-01-09,2027-01-09T12:00,2027-01-10T00:00",
        "AM,2028-02-29,2028-02-29T00:00,2028-02-29T12:00",
        "PM,2028-02-29,2028-02-29T12:00,2028-03-01T00:00",
        "PM,2027-12-31,2027-12-31T12:00,2028-01-01T00:00",
        "AM,2027-03-14,2027-03-14T00:00,2027-03-14T12:00",
    )
    fun `AHD01 approved terms use exact local calendar boundaries`(
        term: ApprovedKoreanTimeTerm,
        date: String,
        start: String,
        end: String,
    ) {
        assertEquals(
            IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end)),
            ApprovedKoreanTimeTermPolicy.resolve(term, LocalDate.parse(date)),
        )
    }

    @Test
    fun `AHD02 the closed vocabulary contains exactly the user approved eleven terms`() {
        assertEquals(
            listOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY"),
            ApprovedKoreanTimeTerm.entries.map { it.name },
        )
        assertEquals(setOf("오전"), ApprovedKoreanTimeTermPolicy.lexemes(ApprovedKoreanTimeTerm.AM))
        assertEquals(setOf("오후"), ApprovedKoreanTimeTermPolicy.lexemes(ApprovedKoreanTimeTerm.PM))
        assertEquals("ko-time-terms-1", APPROVED_KOREAN_TIME_TERM_VERSION)
    }

    @Test
    fun `AHD03 adjacent half days meet at noon without overlapping calendar endpoints`() {
        val date = LocalDate.of(2027, 1, 8)
        val am = ApprovedKoreanTimeTermPolicy.resolve(ApprovedKoreanTimeTerm.AM, date)
        val pm = ApprovedKoreanTimeTermPolicy.resolve(ApprovedKoreanTimeTerm.PM, date)
        assertEquals(am.end, pm.start)
        assertEquals(date.atStartOfDay(), am.start)
        assertEquals(date.plusDays(1).atStartOfDay(), pm.end)
    }

    @Test
    fun `AHD04 unrepresentable next midnight is rejected rather than wrapped or clipped`() {
        assertFailsWith<DateTimeException> {
            ApprovedKoreanTimeTermPolicy.resolve(ApprovedKoreanTimeTerm.PM, LocalDate.MAX)
        }
    }
}
