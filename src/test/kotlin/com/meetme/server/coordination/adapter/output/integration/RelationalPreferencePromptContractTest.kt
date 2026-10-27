package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared prompt and canned JSON contract; no live provider call or extraction-accuracy claim. */
class RelationalPreferencePromptContractTest {
    private val mapper = JsonMapper.builder().build()
    private val gemini = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)
    private val luna = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper)

    @Test
    fun `shared prompt distinguishes relational primary preference from isolated soft preference`() {
        val request = request("목요일 오전 9~11시가 좋고 그 시간에 안 되면 일요일 오후 4~6시도 가능해요")
        val prompt = gemini.prompt(request)

        assertEquals(prompt, luna.prompt(request))
        assertTrue(
            prompt.contains(
                "When X is preferred and Y is possible only if X does not work, preserve X as both " +
                    "TIME_WINDOW AVAILABLE and PREFERRED_TIME_WINDOW, and Y as CONDITIONAL_FALLBACK_TIME_WINDOW.",
            ),
            "A clear primary-to-fallback relationship must retain the preferred primary as feasible time",
        )
        assertTrue(
            prompt.contains(
                "An isolated soft preference without an explicit feasible window or primary-to-fallback relationship " +
                    "must not create TIME_WINDOW AVAILABLE.",
            ),
            "The relational rule must not turn every preference into hard availability",
        )
        assertTrue(prompt.contains("preference alone is not primary availability"))
        assertTrue(prompt.contains("Do not evaluate this trigger per participant subset"))
    }

    @Test
    fun `different dated relation retains primary feasible preference and conditional fallback on both provider surfaces`() {
        val request = request("11월 5일 오전 9~11시가 좋고 그때 안 되면 11월 8일 오후 4~6시도 가능해요")
        val response =
            response(
                request,
                listOf(
                    window("TIME_WINDOW", "2026-11-05", "09:00", "11:00", "AVAILABLE"),
                    window("PREFERRED_TIME_WINDOW", "2026-11-05", "09:00", "11:00", null),
                    mapOf(
                        "type" to "CONDITIONAL_FALLBACK_TIME_WINDOW",
                        "activation" to "WHOLE_ROOM_PRIMARY_UNAVAILABLE",
                        "date" to "2026-11-08",
                        "day_of_week" to null,
                        "start_time" to "16:00",
                        "end_time" to "18:00",
                    ),
                ),
            )
        val results = listOf(gemini.parseProviderResponse(response, request), luna.parseProviderResponse(response, request))

        assertEquals(results.first(), results.last())
        for (result in results.map { it.single() }) {
            assertNull(result.rejectionCode)
            val primary = result.conditions.filterIsInstance<StructuredCondition.TimeWindow>().single()
            val preferred = result.conditions.filterIsInstance<StructuredCondition.PreferredTimeWindow>().single()
            val fallback = result.conditions.filterIsInstance<StructuredCondition.ConditionalFallbackTimeWindow>().single()
            assertEquals(TimePolarity.AVAILABLE, primary.polarity)
            assertEquals(LocalDate.of(2026, 11, 5), primary.date)
            assertEquals(primary.date, preferred.date)
            assertEquals(primary.startTime, preferred.startTime)
            assertEquals(primary.endTime, preferred.endTime)
            assertEquals(LocalDate.of(2026, 11, 8), fallback.date)
            assertTrue(result.conditions.filterIsInstance<StructuredCondition.TimeWindow>().none { it.date == fallback.date })
        }
    }

    @Test
    fun `isolated preferred time remains a preference without synthesized available time`() {
        val request = request("11월 5일 오전 9~11시를 선호해요")
        val response = response(request, listOf(window("PREFERRED_TIME_WINDOW", "2026-11-05", "09:00", "11:00", null)))

        for (result in listOf(gemini.parseProviderResponse(response, request), luna.parseProviderResponse(response, request))) {
            val parsed = result.single()
            assertNull(parsed.rejectionCode)
            assertEquals(1, parsed.conditions.filterIsInstance<StructuredCondition.PreferredTimeWindow>().size)
            assertTrue(parsed.conditions.none { it is StructuredCondition.TimeWindow })
            assertTrue(parsed.conditions.none { it is StructuredCondition.ConditionalFallbackTimeWindow })
        }
    }

    private fun request(text: String) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 9),
            listOf(NaturalLanguageInput(UUID(0, 19).toString(), text, Locale.KOREAN, LocalDate.of(2026, 11, 2))),
        )

    private fun response(
        request: NaturalLanguageBatchRequest,
        conditions: List<Map<String, Any?>>,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "3",
                "results" to
                    listOf(
                        mapOf(
                            "input_ref" to request.inputs.single().inputRef,
                            "clarification" to null,
                            "rejection_code" to null,
                            "conditions" to conditions,
                        ),
                    ),
            ),
        )

    private fun window(
        type: String,
        date: String,
        start: String,
        end: String,
        polarity: String?,
    ): Map<String, Any?> =
        mapOf(
            "type" to type,
            "polarity" to polarity,
            "date" to date,
            "day_of_week" to null,
            "start_time" to start,
            "end_time" to end,
        )
}
