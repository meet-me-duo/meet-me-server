package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #91: a schema-valid provider success cannot erase unsafe text in the supported context family.
// Existing RED fixtures remain frozen; only synthetic provider results and real matching are used here.
class GeminiTimeContextProviderSafetyTest {
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

    @Test
    fun `ordinary explicit time outside the context family remains provider validated`() {
        val request = request("월요일 오후 7시부터 오후 9시까지 가능해요.")
        val result = adapter.parseProviderResponse(response(request, POSITIVE_WINDOW), request).single()
        val window = result.conditions.single() as StructuredCondition.TimeWindow

        assertNull(result.rejectionCode)
        assertEquals(LocalTime.of(19, 0), window.startTime)
        assertEquals(LocalTime.of(21, 0), window.endTime)
    }

    @Test
    fun `ordinary independent time and place outside the context family remain unchanged`() {
        val request = request("월요일 오후 7시부터 오후 9시까지 가능해요. 장소는 강남역이 좋아요.")
        val result = adapter.parseProviderResponse(response(request, "$POSITIVE_WINDOW,$PLACE"), request).single()

        assertNull(result.rejectionCode)
        assertEquals(2, result.conditions.size)
        assertEquals("강남역", (result.conditions.last() as StructuredCondition.SpecificPlace).query)
        assertEquals(LocalTime.of(19, 0), (result.conditions.first() as StructuredCondition.TimeWindow).startTime)
    }

    @Test
    fun `broad negative word heuristics do not reject an unrelated ordinary place name`() {
        val request = request("월요일 오후 7시부터 오후 9시까지 가능해요. 장소는 안양역이 좋아요.")
        val result =
            adapter
                .parseProviderResponse(response(request, "$POSITIVE_WINDOW,${PLACE.replace("강남역", "안양역")}"), request)
                .single()

        assertNull(result.rejectionCode)
        assertEquals("안양역", (result.conditions.last() as StructuredCondition.SpecificPlace).query)
    }

    private fun assertUnsafe(
        raw: String,
        reason: String = "AMBIGUOUS_TIME_CONSTRAINT",
        providerConditions: String = POSITIVE_WINDOW,
    ) {
        val fixture = NaturalLanguageOnlyFixture(listOf(raw, "월요일 00:00부터 23:00까지 가능해요."))
        val request =
            NaturalLanguageBatchRequest(
                fixture.room.timeZone.value,
                fixture.room.searchRange.startInclusive,
                fixture.room.searchRange.endExclusive,
                listOf(
                    NaturalLanguageInput(
                        fixture.submissions
                            .first()
                            .latest.id.value
                            .toString(),
                        raw,
                        Locale.KOREAN,
                        fixture.room.searchRange.startInclusive,
                    ),
                ),
            )
        val parsed = adapter.parseProviderResponse(response(request, providerConditions), request).single()

        assertEquals(reason, parsed.rejectionCode, raw)
        assertEquals(emptyList(), parsed.conditions, raw)
        fixture.results(parsed.conditions, listOf(fixture.window(0, 23)))
        fixture.structured = fixture.structured.mapIndexed { index, result -> if (index == 0) parsed else result }
        fixture.process()
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality, raw)
        assertTrue(fixture.run.candidates.isEmpty(), "Unsafe input cannot be treated as an attending participant: $raw")
    }

    private fun request(raw: String) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 9, 21),
            LocalDate.of(2026, 9, 23),
            listOf(NaturalLanguageInput(UUID.randomUUID().toString(), raw, Locale.KOREAN, LocalDate.of(2026, 9, 21))),
        )

    private fun response(
        request: NaturalLanguageBatchRequest,
        conditions: String,
    ) = """
        {"schema_version":"2","results":[
          {"input_ref":"${request.inputs.single().inputRef}","conditions":[$conditions]}
        ]}
        """.trimIndent()

    companion object {
        private const val CANONICAL =
            "평일은 7시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 2시부터 7시까지 가능해요."
        private val POSITIVE_WINDOW =
            """
            {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"MONDAY",
             "start_time":"19:00","end_time":"21:00"}
            """.trimIndent()
        private val PLACE =
            """
            {"type":"SPECIFIC_PLACE","query":"강남역","area_key":"AREA_1","area_name":"강남"}
            """.trimIndent()
    }
}
