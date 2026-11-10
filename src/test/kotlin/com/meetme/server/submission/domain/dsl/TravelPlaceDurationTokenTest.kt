package com.meetme.server.submission.domain.dsl

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.meetme.server.support.TravelPlaceTestFixture as F

/** A supported numeric suffix cannot stand in for an unsupported complete native quantity. */
class TravelPlaceDurationTokenTest {
    @ParameterizedTest
    @CsvSource(
        "'1,045분',45",
        "1 345분,345",
        "스물 한시간,60",
    )
    fun `TPD11 separated or compound native quantities cannot be accepted by choosing a smaller suffix`(
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
