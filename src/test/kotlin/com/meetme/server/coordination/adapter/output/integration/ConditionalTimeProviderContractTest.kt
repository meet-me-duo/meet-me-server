package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.ConditionalTimeActivation
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canned provider JSON only: these tests do not claim actual model extraction accuracy. */
class ConditionalTimeProviderContractTest {
    private val mapper = JsonMapper.builder().build()
    private val gemini = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)
    private val luna = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper)
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 9),
            listOf(
                NaturalLanguageInput(
                    UUID(0, 1).toString(),
                    "수요일 오후 2~6시나 금요일 오전 10~12시가 좋아요. 안 되면 토요일 오전 11시부터 오후 3시도 가능해요",
                    Locale.KOREAN,
                    LocalDate.of(2026, 11, 2),
                ),
            ),
        )
    private val primary = time("TIME_WINDOW", "WEDNESDAY", "14:00", "18:00", "AVAILABLE")
    private val preferred = time("PREFERRED_TIME_WINDOW", "WEDNESDAY", "14:00", "18:00", null)
    private val fallback =
        mapOf<String, Any?>(
            "type" to "CONDITIONAL_FALLBACK_TIME_WINDOW",
            "activation" to "WHOLE_ROOM_PRIMARY_UNAVAILABLE",
            "date" to null,
            "day_of_week" to "SATURDAY",
            "start_time" to "11:00",
            "end_time" to "15:00",
        )

    @Test
    fun `both providers preserve primary availability preference and fallback independently`() {
        val friday = time("TIME_WINDOW", "FRIDAY", "10:00", "12:00", "AVAILABLE")
        val fridayPreferred = time("PREFERRED_TIME_WINDOW", "FRIDAY", "10:00", "12:00", null)
        val response = response(listOf(primary, friday, preferred, fridayPreferred, fallback))
        val geminiResult = gemini.parseProviderResponse(response, request).single()
        val lunaResult = luna.parseProviderResponse(response, request).single()
        assertEquals(geminiResult, lunaResult)
        assertNull(geminiResult.rejectionCode)
        val conditions = geminiResult.conditions
        assertEquals(2, conditions.filterIsInstance<StructuredCondition.TimeWindow>().count { it.polarity == TimePolarity.AVAILABLE })
        assertEquals(2, conditions.filterIsInstance<StructuredCondition.PreferredTimeWindow>().size)
        assertEquals(
            StructuredCondition.ConditionalFallbackTimeWindow(
                ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
                null,
                DayOfWeek.SATURDAY,
                LocalTime.of(11, 0),
                LocalTime.of(15, 0),
            ),
            conditions.filterIsInstance<StructuredCondition.ConditionalFallbackTimeWindow>().single(),
        )
    }

    @Test
    fun `shared additive v3 schema requires the exact conditional fields and explicit activation`() {
        assertEquals(GeminiNaturalLanguageParserAdapter.RESPONSE_SCHEMA, OpenAiLunaNaturalLanguageParserAdapter.RESPONSE_SCHEMA)
        assertEquals(gemini.prompt(request), luna.prompt(request))
        val prompt = gemini.prompt(request)
        assertTrue(prompt.contains("CONDITIONAL_FALLBACK_TIME_WINDOW"))
        assertTrue(prompt.contains("WHOLE_ROOM_PRIMARY_UNAVAILABLE"))
        val schema = mapper.valueToTree<JsonNode>(OpenAiLunaNaturalLanguageParserAdapter.RESPONSE_SCHEMA)
        assertEquals(listOf("3"), schema["properties"]["schema_version"]["enum"].asSequence().map { it.asText() }.toList())
        val variants =
            nodes(schema)
                .filter {
                    it.isObject &&
                        it["properties"]?.get("type")?.get("enum")?.any { type ->
                            type.asText() == "CONDITIONAL_FALLBACK_TIME_WINDOW"
                        } == true
                }.toList()
        assertTrue(variants.isNotEmpty(), "The common schema must advertise the conditional representation")
        for (variant in variants) {
            val fields = setOf("type", "activation", "date", "day_of_week", "start_time", "end_time")
            assertEquals(fields, variant["properties"].propertyNames().toSet())
            assertEquals(fields, variant["required"].asSequence().map { it.asText() }.toSet())
            assertEquals(false, variant["additionalProperties"].asBoolean())
            assertEquals(
                listOf("WHOLE_ROOM_PRIMARY_UNAVAILABLE"),
                variant["properties"]["activation"]["enum"].asSequence().map { it.asText() }.toList(),
            )
        }
    }

    @Test
    fun `malformed conditional windows reject safely on both provider surfaces without becoming flat availability`() {
        val invalid =
            listOf(
                fallback - "activation",
                fallback + ("activation" to null),
                fallback + ("activation" to "SUBSET_PRIMARY_UNAVAILABLE"),
                fallback + ("polarity" to "AVAILABLE"),
                fallback + ("date" to "2026-11-07"),
                fallback + ("date" to "2026-02-30") + ("day_of_week" to null),
                fallback + ("day_of_week" to null),
                fallback + ("start_time" to "24:00"),
                fallback + ("end_time" to "10:00"),
                fallback + ("end_time" to "invalid"),
            )
        for (malformed in invalid) {
            for (results in listOf(
                gemini.parseProviderResponse(response(listOf(primary, preferred, malformed)), request),
                luna.parseProviderResponse(response(listOf(primary, preferred, malformed)), request),
            )) {
                assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", results.single().rejectionCode, malformed.toString())
                assertTrue(
                    results.single().conditions.filterIsInstance<StructuredCondition.TimeWindow>().none {
                        it.dayOfWeek == DayOfWeek.SATURDAY && it.polarity == TimePolarity.AVAILABLE
                    },
                    "Invalid fallback must not become unconditional Saturday availability",
                )
            }
        }
    }

    @Test
    fun `fallback without explicit primary availability cannot treat preferences or unavailable windows as primary`() {
        for (conditions in listOf(
            listOf(fallback),
            listOf(preferred, fallback),
            listOf(primary + ("polarity" to "UNAVAILABLE"), fallback),
        )) {
            val json = response(conditions)
            assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", gemini.parseProviderResponse(json, request).single().rejectionCode)
            assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", luna.parseProviderResponse(json, request).single().rejectionCode)
        }
    }

    @Test
    fun `valid dated fallback and midnight end remain explicit rather than becoming a recurring flat window`() {
        val dated = fallback + mapOf("date" to "2026-11-07", "day_of_week" to null, "start_time" to "22:00", "end_time" to "24:00")
        val json = response(listOf(primary, dated))
        for (result in listOf(gemini.parseProviderResponse(json, request).single(), luna.parseProviderResponse(json, request).single())) {
            assertNull(result.rejectionCode)
            val condition = result.conditions.filterIsInstance<StructuredCondition.ConditionalFallbackTimeWindow>().single()
            assertEquals(LocalDate.of(2026, 11, 7), condition.date)
            assertNull(condition.dayOfWeek)
            assertEquals(LocalTime.MIDNIGHT, condition.endTime)
            assertTrue(condition.endsAtNextDayStart)
        }
    }

    @Test
    fun `existing strict v3 and legacy v1 v2 flat windows stay valid without conditional fields`() {
        val json = response(listOf(primary))
        val expected =
            listOf(
                StructuredCondition.TimeWindow(
                    TimePolarity.AVAILABLE,
                    null,
                    DayOfWeek.WEDNESDAY,
                    LocalTime.of(14, 0),
                    LocalTime.of(18, 0),
                ),
            )
        assertEquals(expected, luna.parseProviderResponse(json, request).single().conditions)
        for (version in listOf("1", "2", "3")) {
            val result =
                gemini
                    .parseProviderResponse(
                        json.replace("\"schema_version\":\"3\"", "\"schema_version\":\"$version\""),
                        request,
                    ).single()
            assertNull(result.rejectionCode)
            assertEquals(expected, result.conditions)
        }
    }

    private fun response(conditions: List<Map<String, Any?>>): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "3",
                "results" to
                    listOf(mapOf("input_ref" to request.inputs.single().inputRef, "rejection_code" to null, "conditions" to conditions)),
            ),
        )

    private fun time(
        type: String,
        day: String,
        start: String,
        end: String,
        polarity: String?,
    ) = mapOf(
        "type" to type,
        "polarity" to polarity,
        "date" to null,
        "day_of_week" to day,
        "start_time" to start,
        "end_time" to end,
    )

    private fun nodes(node: JsonNode): Sequence<JsonNode> =
        sequence {
            yield(node)
            if (node.isObject || node.isArray) {
                for (child in node) yieldAll(nodes(child))
            }
        }
}
