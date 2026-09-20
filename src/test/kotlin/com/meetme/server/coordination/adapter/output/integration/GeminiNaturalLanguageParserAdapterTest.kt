package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiNaturalLanguageParserAdapterTest {
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

    @Test
    fun `고정 모델 응답과 호출 상한을 사용한다`() {
        val properties = GeminiProperties()

        assertEquals("gemini-3.8-flash", properties.model)
        assertEquals(262_144, properties.maxResponseBytes)
        assertEquals(15_000, GeminiNaturalLanguageParserAdapter.CALL_TIMEOUT_MILLIS)
        assertEquals(32_768, GeminiNaturalLanguageParserAdapter.MAX_OUTPUT_TOKENS)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `중첩 배열 상한은 Gemini schema가 아니라 응답 검증에서 적용한다`() {
        val properties = GeminiNaturalLanguageParserAdapter.RESPONSE_SCHEMA.getValue("properties") as Map<String, Any>
        val results = properties.getValue("results") as Map<String, Any>
        val resultItem = results.getValue("items") as Map<String, Any>
        val resultProperties = resultItem.getValue("properties") as Map<String, Any>
        val conditions = resultProperties.getValue("conditions") as Map<String, Any>

        assertFalse(results.containsKey("maxItems"))
        assertFalse(conditions.containsKey("maxItems"))

        val request = request(1)
        val tooManyConditions =
            List(33) { """{"type":"TRAVEL_CONSTRAINT","expression":"학교 근처"}""" }.joinToString(",")
        val response =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[$tooManyConditions]}
            ]}
            """.trimIndent()

        assertThrows<NaturalLanguageParserException> { adapter.parseProviderResponse(response, request) }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `타입별 필수 조건과 offset 없는 지역 시각 schema를 제공한다`() {
        val rootProperties = GeminiNaturalLanguageParserAdapter.RESPONSE_SCHEMA.getValue("properties") as Map<String, Any>
        val results = rootProperties.getValue("results") as Map<String, Any>
        val resultItem = results.getValue("items") as Map<String, Any>
        val resultProperties = resultItem.getValue("properties") as Map<String, Any>
        val conditions = resultProperties.getValue("conditions") as Map<String, Any>
        val conditionItem = conditions.getValue("items") as Map<String, Any>
        val variants = conditionItem.getValue("anyOf") as List<Map<String, Any>>

        assertEquals(5, variants.size)
        val timeVariants =
            variants.filter { variant ->
                val properties = variant.getValue("properties") as Map<String, Any>
                val type = properties.getValue("type") as Map<String, Any>
                type["enum"] == listOf("TIME_WINDOW")
            }
        assertEquals(2, timeVariants.size)

        timeVariants.forEach { variant ->
            assertEquals(
                setOf("type", "polarity", "date", "day_of_week", "start_time", "end_time"),
                (variant.getValue("required") as List<String>).toSet(),
            )
            val properties = variant.getValue("properties") as Map<String, Any>
            listOf("start_time", "end_time").forEach { field ->
                val time = properties.getValue(field) as Map<String, Any>
                assertFalse(time.containsKey("format"))
                assertTrue(time.getValue("description").toString().contains("HH:mm"))
                assertTrue(time.getValue("description").toString().contains("without a UTC offset"))
            }
        }

        assertTrue(adapter.prompt(request(1)).contains("HH:mm room-local wall-clock time without a UTC offset"))
    }

    @Test
    fun `입력 참조가 누락되거나 추가되면 배치 전체를 거부한다`() {
        val request = request(2)
        val onlyFirst =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.first().inputRef}","conditions":[
                {"type":"TRAVEL_CONSTRAINT","expression":"학교 근처"}
              ]}
            ]}
            """.trimIndent()

        assertThrows<NaturalLanguageParserException> { adapter.parseProviderResponse(onlyFirst, request) }
    }

    @Test
    fun `LLM 좌표와 잘못된 조건만 제외하고 유효한 조건을 보존한다`() {
        val request = request(1)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"SPECIFIC_PLACE","query":"봉천역","radius_meters":1000,"latitude":37.0},
                {"type":"TRAVEL_CONSTRAINT","expression":"학교 근처"},
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-09-21","day_of_week":null,"start_time":"18:00","end_time":"20:00"}
              ]}
            ]}
            """.trimIndent()

        val result = adapter.parseProviderResponse(json, request).single()

        assertEquals("CONDITION_VALIDATION_FAILED", result.rejectionCode)
        assertEquals(2, result.conditions.size)
        assertIs<StructuredCondition.TravelConstraint>(result.conditions[0])
        assertIs<StructuredCondition.TimeWindow>(result.conditions[1])
    }

    @Test
    fun `관련 없는 null 필드와 날짜에 일치하는 요일을 명시 날짜 조건으로 정규화한다`() {
        val request = request(1)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-09-21","day_of_week":"MONDAY","start_time":"18:00","end_time":"20:00","query":null,"radius_meters":null,"expression":null}
              ]}
            ]}
            """.trimIndent()

        val result = adapter.parseProviderResponse(json, request).single()
        val condition = assertIs<StructuredCondition.TimeWindow>(result.conditions.single())

        assertNull(result.rejectionCode)
        assertEquals(LocalDate.of(2026, 9, 21), condition.date)
        assertNull(condition.dayOfWeek)
        assertEquals(LocalTime.of(18, 0), condition.startTime)
        assertEquals(LocalTime.of(20, 0), condition.endTime)
    }

    @Test
    fun `날짜와 불일치하는 요일 또는 관련 없는 non-null 필드는 거부한다`() {
        val request = request(2)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs[0].inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-09-21","day_of_week":"TUESDAY","start_time":"18:00","end_time":"20:00","query":null,"radius_meters":null,"expression":null}
              ]},
              {"input_ref":"${request.inputs[1].inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-09-21","day_of_week":"MONDAY","start_time":"18:00","end_time":"20:00","query":"봉천역","radius_meters":null,"expression":null}
              ]}
            ]}
            """.trimIndent()

        val results = adapter.parseProviderResponse(json, request)

        results.forEach {
            assertEquals(emptyList(), it.conditions)
            assertEquals("CONDITION_VALIDATION_FAILED", it.rejectionCode)
        }
    }

    @Test
    fun `탐색 범위 밖 날짜 조건을 제외한다`() {
        val request = request(1)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-21","day_of_week":null,"start_time":"18:00","end_time":"20:00"}
              ]}
            ]}
            """.trimIndent()

        val result = adapter.parseProviderResponse(json, request).single()
        assertEquals(emptyList(), result.conditions)
        assertEquals("CONDITION_VALIDATION_FAILED", result.rejectionCode)
    }

    @Test
    fun `개인 기준 장소와 모호한 지명을 이동 제약과 미확정 장소로 보존한다`() {
        val request = request(1)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"TRAVEL_CONSTRAINT","expression":"집 근처"},
                {"type":"TRAVEL_CONSTRAINT","expression":"회사 근처"},
                {"type":"TRAVEL_CONSTRAINT","expression":"학교 근처"},
                {"type":"UNRESOLVED_PLACE","query":"중앙역"}
              ]}
            ]}
            """.trimIndent()

        val conditions = adapter.parseProviderResponse(json, request).single().conditions

        assertEquals(3, conditions.filterIsInstance<StructuredCondition.TravelConstraint>().size)
        assertEquals("중앙역", conditions.filterIsInstance<StructuredCondition.UnresolvedPlace>().single().query)
    }

    @Test
    fun `24시 종료를 다음 날 자정의 배타적 경계로 확장한다`() {
        val request = request(1)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-09-21","day_of_week":null,"start_time":"18:00","end_time":"24:00"}
              ]}
            ]}
            """.trimIndent()

        val window =
            adapter
                .parseProviderResponse(json, request)
                .single()
                .conditions
                .single() as StructuredCondition.TimeWindow

        assertEquals(
            listOf(
                InstantTimeRange(
                    Instant.parse("2026-09-21T09:00:00Z"),
                    Instant.parse("2026-09-21T15:00:00Z"),
                ),
            ),
            TimeRangeMatcher.expandNaturalWindows(
                listOf(window),
                SearchDateRange.explicit(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 28)),
                MeetingTimeZone.of("Asia/Seoul"),
            ),
        )
    }

    @Test
    fun `잘못된 지역 시각은 해당 조건만 제외하고 유효한 조건을 보존한다`() {
        val request = request(2)
        val json =
            """
            {"schema_version":"1","results":[
              {"input_ref":"${request.inputs[0].inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"MONDAY","start_time":"18:00","end_time":"25:00"},
                {"type":"TRAVEL_CONSTRAINT","expression":"학교 근처"}
              ]},
              {"input_ref":"${request.inputs[1].inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"TUESDAY","start_time":"24:00","end_time":"24:00"},
                {"type":"UNRESOLVED_PLACE","query":"중앙역"}
              ]}
            ]}
            """.trimIndent()

        val results = adapter.parseProviderResponse(json, request)

        assertEquals(listOf(1, 1), results.map { it.conditions.size })
        assertTrue(results[0].conditions.single() is StructuredCondition.TravelConstraint)
        assertTrue(results[1].conditions.single() is StructuredCondition.UnresolvedPlace)
        results.forEach { assertEquals("CONDITION_VALIDATION_FAILED", it.rejectionCode) }
    }

    private fun request(count: Int) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 9, 20),
            LocalDate.of(2026, 9, 28),
            (1..count).map {
                NaturalLanguageInput(UUID.randomUUID().toString(), "월요일 저녁", Locale.forLanguageTag("ko-KR"))
            },
        )
}
