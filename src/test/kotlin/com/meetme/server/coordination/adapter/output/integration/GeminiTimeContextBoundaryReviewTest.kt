package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Issue #91 independent boundary review. Provider-wide validity precedes contextual rejection.
class GeminiTimeContextBoundaryReviewTest {
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

    @Test
    fun `fully bounded scoped week input does not enter start-only context recovery`() {
        val request = request(listOf("이번주 목요일 오후 8시부터 오후 9시까지 강남역에서 가능해요."))
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-08","day_of_week":null,
                 "start_time":"20:00","end_time":"21:00"},
                {"type":"SPECIFIC_PLACE","query":"강남역","area_key":"AREA_1","area_name":"강남"}
              ]}
            ]}
            """.trimIndent()
        val result = adapter.parseProviderResponse(response, request).single()
        val time = result.conditions.first() as StructuredCondition.TimeWindow

        assertNull(result.rejectionCode)
        assertEquals(2, result.conditions.size)
        assertEquals(DATE.plusDays(1), time.date)
        assertEquals(LocalTime.of(20, 0), time.startTime)
        assertEquals(LocalTime.of(21, 0), time.endTime)
        assertEquals("강남역", (result.conditions.last() as StructuredCondition.SpecificPlace).query)
    }

    @Test
    fun `provider area name conflicts remain invalid before unsafe input conditions are discarded`() {
        val request =
            request(
                listOf(
                    "평일은 7시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. " +
                        "주말은 2시부터 7시까지 가능해요. 목요일에는 강남역에서만 돼요.",
                    "홍대입구역에서 가능해요.",
                ),
            )
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs[0].inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"WEDNESDAY",
                 "start_time":"19:00","end_time":"21:00"},
                {"type":"SPECIFIC_PLACE","query":"강남역","area_key":"AREA_1","area_name":"강남"}
              ]},
              {"input_ref":"${request.inputs[1].inputRef}","conditions":[
                {"type":"SPECIFIC_PLACE","query":"홍대입구역","area_key":"AREA_1","area_name":"홍대"}
              ]}
            ]}
            """.trimIndent()

        val error = assertThrows<NaturalLanguageParserException> { adapter.parseProviderResponse(response, request) }

        assertEquals(ParserFailureKind.INVALID_RESPONSE, error.kind)
    }

    private fun request(texts: List<String>) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            DATE,
            DATE.plusDays(5),
            texts.map { NaturalLanguageInput(UUID.randomUUID().toString(), it, Locale.KOREAN, DATE) },
        )

    private fun window(
        day: String,
        start: Int,
        end: Int,
    ): String {
        val startTime = "${start.toString().padStart(2, '0')}:00"
        val endTime = "${end.toString().padStart(2, '0')}:00"
        return """
            {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"$day",
             "start_time":"$startTime","end_time":"$endTime"}
            """.trimIndent()
    }

    companion object {
        private val DATE: LocalDate = LocalDate.of(2026, 10, 7)

        private fun utc(
            offset: Long,
            start: Int,
            end: Int,
        ) = InstantTimeRange(
            DATE.plusDays(offset).atTime(start, 0).toInstant(ZoneOffset.ofHours(9)),
            DATE.plusDays(offset).atTime(end, 0).toInstant(ZoneOffset.ofHours(9)),
        )
    }
}
