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

class GeminiPlaceGroupingContractTest {
    @Test
    fun `서로 다른 인접 장소가 같은 Gemini 호환 그룹으로 역직렬화된다`() {
        val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 28),
                listOf(
                    input("봉천역에서 가능"),
                    input("서울대입구역 근처 가능"),
                ),
            )
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs[0].inputRef}","conditions":[
                {"type":"SPECIFIC_PLACE","query":"봉천역","area_key":"AREA_1","area_name":"관악구 북부"}
              ]},
              {"input_ref":"${request.inputs[1].inputRef}","conditions":[
                {"type":"SPECIFIC_PLACE","query":"서울대입구역","area_key":"AREA_1","area_name":"관악구 북부"}
              ]}
            ]}
            """.trimIndent()

        val places =
            adapter
                .parseProviderResponse(response, request)
                .map { it.conditions.single() as StructuredCondition.SpecificPlace }

        assertEquals(listOf("봉천역", "서울대입구역"), places.map { it.query })
        assertEquals(listOf("AREA_1", "AREA_1"), places.map { it.areaKey })
        assertEquals(listOf("관악구 북부", "관악구 북부"), places.map { it.areaName })
    }

    @Test
    fun `같은 호환 그룹의 대표 지역명이 다르면 배치 전체를 거부한다`() {
        val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 28),
                listOf(input("봉천역에서 가능"), input("서울대입구역 근처 가능")),
            )
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs[0].inputRef}","conditions":[
                {"type":"SPECIFIC_PLACE","query":"봉천역","area_key":"AREA_1","area_name":"관악구 북부"}
              ]},
              {"input_ref":"${request.inputs[1].inputRef}","conditions":[
                {"type":"SPECIFIC_PLACE","query":"서울대입구역","area_key":"AREA_1","area_name":"서울대입구역 일대"}
              ]}
            ]}
            """.trimIndent()

        assertThrows<NaturalLanguageParserException> { adapter.parseProviderResponse(response, request) }
    }

    private fun input(rawText: String) = NaturalLanguageInput(UUID.randomUUID().toString(), rawText, Locale.forLanguageTag("ko-KR"))
}
