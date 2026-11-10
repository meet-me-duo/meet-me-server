package com.meetme.server.submission.domain.dsl

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.meetme.server.support.TravelPlaceTestFixture as F

/** A huge native duration cannot wrap into a valid small public travel limit. */
class TravelPlaceDurationOverflowTest {
    @ParameterizedTest
    @CsvSource(
        "71582789시간,44",
        "71582790시간,104",
        "307445734561825861시간,44",
        "999999999999999999999999999999999999999999999999999분,45",
    )
    fun `TPD10 whole native duration must fit the supported minute range before numeric narrowing`(
        duration: String,
        forgedMinutes: Int,
    ) {
        val expression = "${F.NAME}에서 $duration 이내인 곳."
        val raw = "${F.TIME} $expression"
        val declaration = F.constraint(raw, expression).copy(maxMinutes = forgedMinutes)
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslPlaceConstraintPolicy.validate(listOf(declaration), F.base(raw), F.snapshot(raw))
        }
        val unresolved = declaration.copy(kind = SchedulePlaceConstraintKind.UNRESOLVED, maxMinutes = null)
        assertEquals(
            listOf(unresolved),
            ScheduleDslPlaceConstraintPolicy.validate(listOf(unresolved), F.base(raw), F.snapshot(raw)),
        )
    }
}
