package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.ParticipantMatchInput
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
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
import kotlin.test.assertTrue

// Issue #91: explicit night is never converted to daytime, and 밤12 means midnight.
class GeminiTimeContextNightBoundaryTest {
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

    @Test
    fun `early night inheritance remains ambiguous and cannot create afternoon attendance`() {
        val raw = "평일은 밤 1시부터 밤 3시까지 가능해요. 이번주는 목요일만 밤 2시부터 돼요."
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
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[
                {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"MONDAY",
                 "start_time":"13:00","end_time":"15:00"}
              ]}
            ]}
            """.trimIndent()
        val parsed = adapter.parseProviderResponse(response, request).single()

        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", parsed.rejectionCode)
        assertEquals(emptyList(), parsed.conditions)
        fixture.results(parsed.conditions, listOf(fixture.window(0, 23)))
        fixture.structured = fixture.structured.mapIndexed { index, result -> if (index == 0) parsed else result }
        fixture.process()
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        assertTrue(fixture.run.candidates.isEmpty(), "Ambiguous night cannot become an attending afternoon participant")
    }

    @Test
    fun `night twelve is midnight before inherited afternoon end and real two person matching`() {
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                DATE,
                DATE.plusDays(3),
                listOf(
                    NaturalLanguageInput(
                        UUID.randomUUID().toString(),
                        "평일은 밤 12시부터 오후 2시까지 가능해요. 이번주는 목요일만 오후 1시부터 돼요.",
                        Locale.KOREAN,
                        DATE,
                    ),
                ),
            )
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[],
               "rejection_code":"AMBIGUOUS_TIME_CONSTRAINT"}
            ]}
            """.trimIndent()
        val parsed = adapter.parseProviderResponse(response, request).single()
        val times =
            TimeRangeMatcher.calculateAvailability(
                parsed.conditions.filterIsInstance<StructuredCondition.TimeWindow>(),
                emptyList(),
                emptyList(),
                emptyList(),
                SearchDateRange.explicit(DATE, DATE.plusDays(3)),
                MeetingTimeZone.of("Asia/Seoul"),
            )
        val matched =
            DeterministicCandidateMatcher.generate(
                MeetingMode.REMOTE,
                listOf(
                    ParticipantMatchInput(ParticipantId(UUID.randomUUID()), times),
                    ParticipantMatchInput(
                        ParticipantId(UUID.randomUUID()),
                        listOf(
                            InstantTimeRange(
                                DATE.atStartOfDay().toInstant(ZoneOffset.ofHours(9)),
                                DATE.plusDays(3).atStartOfDay().toInstant(ZoneOffset.ofHours(9)),
                            ),
                        ),
                    ),
                ),
            )

        assertNull(parsed.rejectionCode)
        assertEquals(
            2,
            matched.candidates
                .single()
                .participantIds.size,
        )
        assertEquals(listOf(utc(0, 0, 14), utc(1, 13, 14), utc(2, 0, 14)), matched.candidates.single().timeRanges)
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
