package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issue #99: strict v3 schemas apply to hard conditions as well as preferences; legacy fixtures stay nonstrict. */
class D2HardConditionShapeContractTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = NaturalLanguageProviderContract(mapper)
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "합성 조건", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )
    private val sentinel =
        mapOf(
            "type" to "TIME_WINDOW",
            "polarity" to "AVAILABLE",
            "date" to "2026-10-07",
            "day_of_week" to null,
            "start_time" to "18:00",
            "end_time" to "22:00",
        )
    private val expectedSentinel =
        StructuredCondition.TimeWindow(
            TimePolarity.AVAILABLE,
            LocalDate.of(2026, 10, 7),
            null,
            LocalTime.of(18, 0),
            LocalTime.of(22, 0),
        )

    @Test
    fun `strict dated hard window rejects unknown null keys and requires its nullable weekday field`() {
        assertAccepted(sentinel, expectedSentinel)
        assertRejectedSeparately(sentinel + ("unknown" to null), "AMBIGUOUS_TIME_CONSTRAINT")
        assertRejectedSeparately(sentinel - "day_of_week", "AMBIGUOUS_TIME_CONSTRAINT")
        assertRejectedSeparately(sentinel + ("query" to null), "AMBIGUOUS_TIME_CONSTRAINT")
    }

    @Test
    fun `strict recurring hard window requires its nullable date field`() {
        val recurring = sentinel + mapOf("date" to null, "day_of_week" to "WEDNESDAY")
        val expected = expectedSentinel.copy(date = null, dayOfWeek = DayOfWeek.WEDNESDAY)
        assertAccepted(recurring, expected)
        assertRejectedSeparately(recurring - "date", "AMBIGUOUS_TIME_CONSTRAINT")
        assertRejectedSeparately(recurring + ("unknown" to null), "AMBIGUOUS_TIME_CONSTRAINT")
    }

    @Test
    fun `strict specific place rejects undeclared null radius and coordinate fields`() {
        val place =
            mapOf(
                "type" to "SPECIFIC_PLACE",
                "query" to "강남역",
                "area_key" to "AREA_1",
                "area_name" to "강남",
            )
        assertAccepted(place, StructuredCondition.SpecificPlace("강남역", areaKey = "AREA_1", areaName = "강남"))
        assertRejectedSeparately(place + ("unknown" to null))
        assertRejectedSeparately(place + ("radius_meters" to null))
        assertRejectedSeparately(place + ("coordinates" to null))
        assertRejectedSeparately(place - "area_name")
    }

    @Test
    fun `strict travel and unresolved place also reject undeclared null fields`() {
        val travel = mapOf("type" to "TRAVEL_CONSTRAINT", "expression" to "집에서 30분 이내")
        val unresolved = mapOf("type" to "UNRESOLVED_PLACE", "query" to "회사 근처")
        assertAccepted(travel, StructuredCondition.TravelConstraint("집에서 30분 이내"))
        assertAccepted(unresolved, StructuredCondition.UnresolvedPlace("회사 근처"))
        assertRejectedSeparately(travel + ("unknown" to null))
        assertRejectedSeparately(unresolved + ("unknown" to null))
    }

    @Test
    fun `legacy v1 and v2 retain nonstrict restoration of omitted nullable scope and null metadata`() {
        val legacyCondition = (sentinel - "day_of_week") + ("unknown" to null)
        for (version in listOf("1", "2")) {
            val result = contract.parseProviderResponse(wire(listOf(legacyCondition), version), request, strict = false).single()
            assertEquals(listOf(expectedSentinel), result.conditions)
            assertNull(result.rejectionCode)
        }
    }

    private fun assertAccepted(
        condition: Map<String, Any?>,
        expected: StructuredCondition,
    ) {
        val result = contract.parseProviderResponse(wire(listOf(condition)), request, strict = true).single()
        assertEquals(listOf(expected), result.conditions, condition.toString())
        assertNull(result.rejectionCode)
    }

    private fun assertRejectedSeparately(
        condition: Map<String, Any?>,
        rejectionCode: String = "CONDITION_VALIDATION_FAILED",
    ) {
        val result = contract.parseProviderResponse(wire(listOf(sentinel, condition)), request, strict = true).single()
        assertEquals(listOf(expectedSentinel), result.conditions, "Invalid hard shape must not erase a valid hard condition: $condition")
        assertEquals(rejectionCode, result.rejectionCode, "Invalid hard shape must be reported: $condition")
    }

    private fun wire(
        conditions: List<Map<String, Any?>>,
        version: String = "3",
    ) = mapper.writeValueAsString(
        mapOf(
            "schema_version" to version,
            "results" to
                listOf(
                    mapOf(
                        "input_ref" to request.inputs.single().inputRef,
                        "conditions" to conditions,
                        "rejection_code" to null,
                    ),
                ),
        ),
    )
}
