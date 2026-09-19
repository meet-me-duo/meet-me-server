package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
