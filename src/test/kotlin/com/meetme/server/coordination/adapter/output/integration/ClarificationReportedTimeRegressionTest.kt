package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reported input oracle from the user, not parser implementation. Synthetic responses only; no inference accuracy claim. */
class ClarificationReportedTimeRegressionTest {
    private val mapper = JsonMapper.builder().build()
    private val gemini = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)
    private val luna = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper)
    private val reference = LocalDate.of(2026, 10, 17)

    @Test
    fun `reported exact hyphen and approximate expression preserves semantic ambiguity rather than technical failure`() {
        val request = request("7-8시쯤")
        val proposal =
            mapOf(
                "excerpt" to "7-8시쯤",
                "question" to "오전인가요, 오후인가요? 대략적인 표현은 유지돼요.",
                "option_a" to "오전 7-8시쯤",
                "option_b" to "오후 7-8시쯤",
            )
        listOf("AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT").forEach { reason ->
            parseBoth(response(request, emptyList(), reason, proposal), request).forEach { result ->
                assertEquals(reason, result.rejectionCode)
                assertTrue(result.conditions.isEmpty(), "Ambiguity must not invent availability or a numeric tolerance")
                val clarification = assertNotNull(result.clarification)
                assertEquals("7-8시쯤", clarification.excerpt)
                assertEquals("오전 7-8시쯤", clarification.optionA)
                assertEquals("오후 7-8시쯤", clarification.optionB)
            }
        }
    }

    @Test
    fun `bare short time range and unavailable polarity remain unresolved without assumed period`() {
        listOf("7~8시" to "", "7~8시 불가" to " 불가").forEach { (raw, suffix) ->
            val request = request(raw)
            val proposal =
                mapOf(
                    "excerpt" to "7~8시",
                    "question" to "오전 7~8시인가요, 오후 7~8시인가요?",
                    "option_a" to "오전 7~8시$suffix",
                    "option_b" to "오후 7~8시$suffix",
                )
            parseBoth(response(request, emptyList(), "AMBIGUOUS_TIME_CONSTRAINT", proposal), request).forEach {
                assertEquals("AMBIGUOUS_TIME_CONSTRAINT", it.rejectionCode)
                assertTrue(it.conditions.isEmpty())
                assertEquals(proposal.getValue("option_a"), assertNotNull(it.clarification).optionA)
                assertEquals(proposal.getValue("option_b"), assertNotNull(it.clarification).optionB)
            }
        }
    }

    @Test
    fun `explicit morning evening 24hour and complete context preserve exact provider times without a question`() {
        val cases =
            listOf(
                Triple("오전 7~8시", "07:00", "08:00"),
                Triple("저녁 7~8시", "19:00", "20:00"),
                Triple("19~20시", "19:00", "20:00"),
                Triple("저녁에만 가능해요. 7~8시요", "19:00", "20:00"),
            )
        cases.forEach { (raw, start, end) ->
            val request = request(raw)
            parseBoth(response(request, listOf(window(reference, start, end))), request).forEach {
                assertNull(it.rejectionCode)
                assertNull(it.clarification)
                val time = it.conditions.single() as StructuredCondition.TimeWindow
                assertEquals(LocalTime.parse(start), time.startTime)
                assertEquals(LocalTime.parse(end), time.endTime)
                assertEquals(TimePolarity.AVAILABLE, time.polarity)
            }
        }
    }

    @Test
    fun `today tomorrow and explicit nextweek Monday retain dates from immutable reference`() {
        val cases =
            listOf(
                "오늘 19~20시 가능" to reference,
                "내일 19~20시 가능" to reference.plusDays(1),
                "다음주 월요일 저녁 7~8시 가능" to LocalDate.of(2026, 10, 19),
            )
        cases.forEach { (raw, expectedDate) ->
            val request = request(raw)
            val prompt = gemini.prompt(request)
            assertTrue("reference_date=2026-10-17" in prompt)
            assertTrue(raw in prompt)
            parseBoth(response(request, listOf(window(expectedDate, "19:00", "20:00"))), request).forEach {
                assertNull(it.clarification)
                assertNull(it.rejectionCode)
                assertEquals(expectedDate, (it.conditions.single() as StructuredCondition.TimeWindow).date)
            }
        }
    }

    @Test
    fun `explicit midnight boundaries preserve exclusive next day end and following day start`() {
        val request = request("오늘 23시부터 내일 0시까지 가능하고 내일 0~1시도 가능")
        val conditions = listOf(window(reference, "23:00", "24:00"), window(reference.plusDays(1), "00:00", "01:00"))
        parseBoth(response(request, conditions), request).forEach {
            assertNull(it.clarification)
            assertNull(it.rejectionCode)
            val times = it.conditions.filterIsInstance<StructuredCondition.TimeWindow>()
            assertEquals(2, times.size)
            assertEquals(reference, times[0].date)
            assertEquals(LocalTime.of(23, 0), times[0].startTime)
            assertEquals(LocalTime.MIDNIGHT, times[0].endTime)
            assertTrue(times[0].endsAtNextDayStart)
            assertEquals(reference.plusDays(1), times[1].date)
            assertEquals(LocalTime.MIDNIGHT, times[1].startTime)
            assertEquals(LocalTime.of(1, 0), times[1].endTime)
            assertEquals(false, times[1].endsAtNextDayStart)
        }
    }

    @Test
    fun `explicit unavailable range keeps polarity and does not become available when no question is needed`() {
        val request = request("저녁 7~8시 불가")
        parseBoth(response(request, listOf(window(reference, "19:00", "20:00", "UNAVAILABLE"))), request).forEach {
            assertNull(it.clarification)
            assertNull(it.rejectionCode)
            val time = it.conditions.single() as StructuredCondition.TimeWindow
            assertEquals(TimePolarity.UNAVAILABLE, time.polarity)
            assertEquals(LocalTime.of(19, 0), time.startTime)
            assertEquals(LocalTime.of(20, 0), time.endTime)
        }
    }

    @Test
    fun `reversed equal and impossible hard clock ranges are safely rejected without swap or overnight inference`() {
        val cases =
            listOf(
                Triple("오전 8~7시 가능", "08:00", "07:00"),
                Triple("20~19시 불가", "20:00", "19:00"),
                Triple("오전 7~7시 가능", "07:00", "07:00"),
                Triple("25~26시 가능", "25:00", "26:00"),
            )
        cases.forEach { (raw, start, end) ->
            val request = request(raw)
            val polarity = if (raw.endsWith("불가")) "UNAVAILABLE" else "AVAILABLE"
            parseBoth(response(request, listOf(window(reference, start, end, polarity))), request).forEach {
                assertEquals("AMBIGUOUS_TIME_CONSTRAINT", it.rejectionCode)
                assertTrue(it.conditions.isEmpty(), "Invalid hard time cannot be swapped, expanded overnight or silently made available")
                assertNull(it.clarification, "A validator cannot invent two semantic interpretations")
            }
        }
    }

    @Test
    fun `no safe complete options leaves approximation and missing contextual period unresolved`() {
        listOf("오전 7-8시쯤 가능", "다음주 7~8시 가능", "오전이나 저녁 중 아직 정하지 못했어요. 7~8시요").forEach { raw ->
            val request = request(raw)
            parseBoth(response(request, emptyList(), "AMBIGUOUS_TIME_CONSTRAINT"), request).forEach {
                assertEquals("AMBIGUOUS_TIME_CONSTRAINT", it.rejectionCode)
                assertTrue(it.conditions.isEmpty())
                assertNull(it.clarification)
            }
        }
    }

    private fun parseBoth(
        wire: String,
        request: NaturalLanguageBatchRequest,
    ): List<StructuredSubmissionResult> =
        listOf(gemini.parseProviderResponse(wire, request).single(), luna.parseProviderResponse(wire, request).single())

    private fun request(raw: String) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            reference,
            reference.plusDays(14),
            listOf(NaturalLanguageInput(UUID(0, 1078).toString(), raw, Locale.forLanguageTag("ko-KR"), reference)),
        )

    private fun response(
        request: NaturalLanguageBatchRequest,
        conditions: List<Map<String, Any?>>,
        reason: String? = null,
        clarification: Map<String, String>? = null,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "3",
                "results" to
                    listOf(
                        mapOf(
                            "input_ref" to request.inputs.single().inputRef,
                            "conditions" to conditions,
                            "rejection_code" to reason,
                            "clarification" to clarification,
                        ),
                    ),
            ),
        )

    private fun window(
        date: LocalDate,
        start: String,
        end: String,
        polarity: String = "AVAILABLE",
    ): Map<String, Any?> =
        mapOf(
            "type" to "TIME_WINDOW",
            "date" to date.toString(),
            "day_of_week" to null,
            "start_time" to start,
            "end_time" to end,
            "polarity" to polarity,
        )
}
