package com.meetme.server.submission.domain.dsl

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.meetme.server.support.TravelPlaceTestFixture as F

/** Unsupported numeric tokens must not acquire authority through a supported suffix or dropped unit. */
class TravelPlaceDurationBoundaryTest {
    @ParameterizedTest
    @CsvSource(
        "-45분,45",
        "+45분,45",
        "1.5시간,90",
        "스물한시간,60",
        "마흔한시간,60",
        "45분30초,45",
        "1시간30초,60",
    )
    fun `TPD08 unsupported signed fractional compound and larger native numbers cannot be partially accepted`(
        duration: String,
        proposedMinutes: Int,
    ) {
        val expression = "${F.NAME}에서 $duration 이내인 곳."
        val raw = "${F.TIME} $expression"
        val declaration = F.constraint(raw, expression).copy(maxMinutes = proposedMinutes)
        assertFailsWith<IllegalArgumentException> {
            ScheduleDslPlaceConstraintPolicy.validate(listOf(declaration), F.base(raw), F.snapshot(raw))
        }
        val unresolved = declaration.copy(kind = SchedulePlaceConstraintKind.UNRESOLVED, maxMinutes = null)
        assertEquals(
            listOf(unresolved),
            ScheduleDslPlaceConstraintPolicy.validate(listOf(unresolved), F.base(raw), F.snapshot(raw)),
        )
    }

    @ParameterizedTest
    @CsvSource("45분,45", "한시간,60", "1시간30분,90")
    fun `TPD09 complete supported duration tokens retain exact minute conversion`(
        duration: String,
        expectedMinutes: Int,
    ) {
        val expression = "${F.NAME}에서 $duration 이내인 곳."
        val raw = "${F.TIME} $expression"
        val declaration = F.constraint(raw, expression).copy(maxMinutes = expectedMinutes)
        assertEquals(
            listOf(declaration),
            ScheduleDslPlaceConstraintPolicy.validate(listOf(declaration), F.base(raw), F.snapshot(raw)),
        )
    }
}
