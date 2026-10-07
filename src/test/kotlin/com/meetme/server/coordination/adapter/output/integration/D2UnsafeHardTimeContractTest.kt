package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issue #99: rejecting malformed hard time must not make lost restrictions become neutral full-search availability. */
class D2UnsafeHardTimeContractTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = NaturalLanguageProviderContract(mapper)
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "합성 하드 시간과 선호", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )
    private val hard =
        mapOf(
            "type" to "TIME_WINDOW",
            "polarity" to "AVAILABLE",
            "date" to "2026-10-07",
            "day_of_week" to null,
            "start_time" to "18:00",
            "end_time" to "22:00",
        )
    private val preferred =
        mapOf(
            "type" to "PREFERRED_TIME_WINDOW",
            "polarity" to null,
            "date" to "2026-10-07",
            "day_of_week" to null,
            "start_time" to "20:00",
            "end_time" to "21:00",
        )
    private val expectedHard =
        StructuredCondition.TimeWindow(
            TimePolarity.AVAILABLE,
            LocalDate.of(2026, 10, 7),
            null,
            LocalTime.of(18, 0),
            LocalTime.of(22, 0),
        )
    private val expectedPreferred =
        StructuredCondition.PreferredTimeWindow(
            LocalDate.of(2026, 10, 7),
            null,
            LocalTime.of(20, 0),
            LocalTime.of(21, 0),
        )

    @Test
    fun `valid hard time with explicit preference remains usable`() {
        val result = parse(listOf(hard, preferred))
        assertEquals(listOf(expectedHard, expectedPreferred), result.conditions)
        assertNull(result.rejectionCode)
    }

    @Test
    fun `malformed hard date clock unknown null and missing nullable scope preserve preference but mark time unsafe`() {
        val malformed =
            listOf(
                hard + ("date" to "2026-02-30"),
                hard + ("start_time" to "25:00"),
                hard + ("unknown" to null),
                hard - "day_of_week",
            )
        for (condition in malformed) {
            val result = parse(listOf(condition, preferred))
            assertEquals(listOf(expectedPreferred), result.conditions, "Valid preference bytes must survive: $condition")
            assertEquals(
                "AMBIGUOUS_TIME_CONSTRAINT",
                result.rejectionCode,
                "Lost hard time must never become neutral availability: $condition",
            )
        }
    }

    @Test
    fun `malformed hard exclusion makes participant unsafe despite surviving valid available time and preference`() {
        val invalidExclusion = hard + mapOf("polarity" to "UNAVAILABLE", "start_time" to "25:00", "end_time" to "26:00")
        val result = parse(listOf(hard, invalidExclusion, preferred))
        assertEquals(listOf(expectedHard, expectedPreferred), result.conditions)
        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", result.rejectionCode)
    }

    @Test
    fun `provider generic rejection cannot override unsafe malformed hard time`() {
        for (supplied in listOf("CONDITION_VALIDATION_FAILED", "AMBIGUOUS_INPUT")) {
            val result = parse(listOf(hard + ("date" to "2026-02-30"), preferred), supplied)
            assertEquals(listOf(expectedPreferred), result.conditions)
            assertEquals("AMBIGUOUS_TIME_CONSTRAINT", result.rejectionCode, supplied)
        }
    }

    @Test
    fun `malformed preference alone retains valid hard conditions with generic partial rejection`() {
        val malformed =
            listOf(
                preferred + ("date" to "2026-02-30"),
                preferred + ("start_time" to "25:00"),
                preferred + ("unknown" to null),
                preferred - "day_of_week",
            )
        for (condition in malformed) {
            val result = parse(listOf(hard, condition))
            assertEquals(listOf(expectedHard), result.conditions, condition.toString())
            assertEquals("CONDITION_VALIDATION_FAILED", result.rejectionCode, condition.toString())
        }
    }

    private fun parse(
        conditions: List<Map<String, Any?>>,
        rejection: String? = null,
    ) = contract
        .parseProviderResponse(
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        listOf(
                            mapOf(
                                "input_ref" to request.inputs.single().inputRef,
                                "conditions" to conditions,
                                "rejection_code" to rejection,
                            ),
                        ),
                ),
            ),
            request,
            strict = true,
        ).single()
}
