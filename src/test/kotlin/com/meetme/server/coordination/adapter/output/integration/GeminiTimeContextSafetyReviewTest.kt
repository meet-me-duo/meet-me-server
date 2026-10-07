package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Independent review regressions for issue #91. Existing RED test fingerprints remain unchanged.
class GeminiTimeContextSafetyReviewTest {
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

    @Test
    fun `valid provider availability union cannot preserve the superseded Thursday hour`() {
        val request =
            request("평일은 7시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 2시부터 7시까지 가능해요.", 5)
        val conditions =
            listOf(
                window("MONDAY", 19, 21),
                window("TUESDAY", 19, 21),
                window("WEDNESDAY", 19, 21),
                window("THURSDAY", 19, 21),
                window("FRIDAY", 19, 21),
                window("SATURDAY", 14, 19),
                window("SUNDAY", 14, 19),
                """
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-08",
                 "day_of_week":null,"start_time":"20:00","end_time":"21:00"}
                """.trimIndent(),
            ).joinToString(",")
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[$conditions]}
            ]}
            """.trimIndent()
        val result = adapter.parseProviderResponse(response, request).single()

        assertNull(result.rejectionCode)
        assertEquals(
            listOf(utc(0, 19, 21), utc(1, 20, 21), utc(2, 19, 21), utc(3, 14, 19), utc(4, 14, 19)),
            availability(result.conditions, request),
        )
    }

    @Test
    fun `exact user filler text inherits context without dropping any clause`() {
        val request =
            request("음~ 평일은 7시부터 9시까지 돼요 아 이번주는 목요일만 8시부터 가능합니다 ㅜ 주말에는 2시부터 7시까지 되어요", 5)
        val result = adapter.parseProviderResponse(ambiguous(request), request).single()

        assertNull(result.rejectionCode)
        assertEquals(
            listOf(utc(0, 19, 21), utc(1, 20, 21), utc(2, 19, 21), utc(3, 14, 19), utc(4, 14, 19)),
            availability(result.conditions, request),
        )
    }

    @Test
    fun `explicit morning start supplies the unstated end period before child inheritance`() {
        val request = request("평일은 오전 7시부터 9시까지 가능해요. 이번주는 목요일만 오전 8시부터 돼요.", 3)
        val result = adapter.parseProviderResponse(ambiguous(request), request).single()

        assertNull(result.rejectionCode)
        assertEquals(listOf(utc(0, 7, 9), utc(1, 8, 9), utc(2, 7, 9)), availability(result.conditions, request))
    }

    @Test
    fun `explicit afternoon child outside inherited morning parent remains rejected`() {
        val request = request("평일은 오전 7시부터 9시까지 가능해요. 이번주는 목요일만 오후 8시부터 돼요.", 3)
        val result = adapter.parseProviderResponse(ambiguous(request), request).single()

        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", result.rejectionCode)
        assertEquals(emptyList(), result.conditions)
    }

    private fun request(
        text: String,
        days: Long,
    ) = NaturalLanguageBatchRequest(
        ZoneId.of("Asia/Seoul"),
        DATE,
        DATE.plusDays(days),
        listOf(NaturalLanguageInput(UUID.randomUUID().toString(), text, Locale.KOREAN, DATE)),
    )

    private fun ambiguous(request: NaturalLanguageBatchRequest) =
        """
        {"schema_version":"2","results":[
          {"input_ref":"${request.inputs.single().inputRef}","conditions":[],
           "rejection_code":"AMBIGUOUS_TIME_CONSTRAINT"}
        ]}
        """.trimIndent()

    private fun availability(
        conditions: List<StructuredCondition>,
        request: NaturalLanguageBatchRequest,
    ) = TimeRangeMatcher.calculateAvailability(
        conditions.filterIsInstance<StructuredCondition.TimeWindow>(),
        emptyList(),
        emptyList(),
        emptyList(),
        SearchDateRange.explicit(request.searchStartDate, request.searchEndDate),
        MeetingTimeZone.of(request.timeZone.id),
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
