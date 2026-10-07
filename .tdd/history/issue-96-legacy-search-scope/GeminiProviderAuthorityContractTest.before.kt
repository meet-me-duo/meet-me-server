package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.matching.CompatiblePlaceArea
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.ParticipantAllowedArea
import com.meetme.server.coordination.domain.matching.ParticipantMatchInput
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #91 revised contract: Gemini owns language interpretation; the server validates structure and matches it.
// Synthetic wire fixtures only. No real provider calls and no lexical recovery from provider rejection.
class GeminiProviderAuthorityContractTest {
    private val mapper = JsonMapper.builder().build()
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)

    @Test
    fun `weekday six to nine provider success reaches two participant matching unchanged`() {
        assertSuccess(
            "평일은 6시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 2시부터 7시까지 가능해요.",
            scopedWindows(18, 21, 20, 14, 19),
            fiveRanges(18, 21, 20, 14, 19),
        )
    }

    @Test
    fun `weekday seven to ten provider success preserves the inherited twenty two end`() {
        assertSuccess(
            "평일은 7시부터 10시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 2시부터 7시까지 가능해요.",
            scopedWindows(19, 22, 20, 14, 19),
            fiveRanges(19, 22, 20, 14, 19),
        )
    }

    @Test
    fun `weekend one to six provider success is not restricted to the old two to seven template`() {
        assertSuccess(
            "평일은 7시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 1시부터 6시까지 가능해요.",
            scopedWindows(19, 21, 20, 13, 18),
            fiveRanges(19, 21, 20, 13, 18),
        )
    }

    @Test
    fun `explicit morning parent and arbitrary afternoon weekend remain provider authoritative`() {
        assertSuccess(
            "평일은 오전 6시부터 9시까지 가능해요. 이번주는 목요일만 오전 8시부터 돼요. " +
                "주말은 오후 1시부터 6시까지 가능해요.",
            scopedWindows(6, 9, 8, 13, 18),
            fiveRanges(6, 9, 8, 13, 18),
        )
    }

    @Test
    fun `explicit noon and midnight provider successes retain their exact time boundaries`() {
        assertSuccess(
            "평일은 정오부터 오후 2시까지 가능해요. 이번주는 목요일만 오후 1시부터 돼요. " +
                "주말은 오후 2시부터 오후 7시까지 가능해요.",
            scopedWindows(12, 14, 13, 14, 19),
            fiveRanges(12, 14, 13, 14, 19),
        )
        assertSuccess(
            "평일은 밤 12시부터 오후 2시까지 가능해요. 이번주는 목요일만 오후 1시부터 돼요. " +
                "주말은 오후 2시부터 오후 7시까지 가능해요.",
            scopedWindows(0, 14, 13, 14, 19),
            fiveRanges(0, 14, 13, 14, 19),
        )
    }

    @Test
    fun `valid dated provider windows are preserved instead of rewritten as recurring lexical windows`() {
        val dated =
            listOf(
                datedWindow(0, 19, 21),
                datedWindow(1, 20, 21),
                datedWindow(2, 19, 21),
                datedWindow(3, 14, 19),
                datedWindow(4, 14, 19),
            )

        assertSuccess(CANONICAL, dated, fiveRanges(19, 21, 20, 14, 19))
    }

    @Test
    fun `natural correction and polite suffix do not discard a valid complete provider success`() {
        assertSuccess(
            "음 평일은 저녁 여섯 시부터 아홉 시까지 조율할 수 있어요. " +
                "아 이번주는 목요일만 8시부터 돼요. 주말에는 한 시에서 여섯 시 사이면 좋겠습니다.",
            scopedWindows(18, 21, 20, 13, 18),
            fiveRanges(18, 21, 20, 13, 18),
        )
    }

    @Test
    fun `independent additional place survives language interpretation and real offline matching`() {
        val place = StructuredCondition.SpecificPlace("안양역", areaKey = "AREA_1", areaName = "안양역 일대")

        assertSuccess(
            "$CANONICAL 장소는 안양역이면 좋아요.",
            scopedWindows(19, 21, 20, 14, 19) + place,
            fiveRanges(19, 21, 20, 14, 19),
            MeetingMode.IN_PERSON,
        )
    }

    @Test
    fun `ambiguous provider rejection remains empty even when the old canonical regex could recover it`() {
        val fixture = NaturalLanguageOnlyFixture(listOf(CANONICAL, "매일 오후 7시부터 오후 9시까지 가능해요."))
        val input =
            NaturalLanguageInput(
                fixture.submissions
                    .first()
                    .latest.id.value
                    .toString(),
                CANONICAL,
                Locale.KOREAN,
                fixture.room.searchRange.startInclusive,
            )
        val request =
            NaturalLanguageBatchRequest(
                fixture.room.timeZone.value,
                fixture.room.searchRange.startInclusive,
                fixture.room.searchRange.endExclusive,
                listOf(input),
            )
        val parsed = adapter.parseProviderResponse(response(request, emptyList(), "AMBIGUOUS_TIME_CONSTRAINT"), request).single()

        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", parsed.rejectionCode)
        assertTrue(parsed.conditions.isEmpty())
        fixture.results(parsed.conditions, listOf(fixture.window(19, 21)))
        fixture.structured = fixture.structured.mapIndexed { index, result -> if (index == 0) parsed else result }
        fixture.process()
        assertEquals(CandidateQuality.PARTIAL, fixture.run.quality)
        assertTrue(fixture.run.candidates.isEmpty(), "A provider-rejected participant cannot acquire invented availability")
    }

    @Test
    fun `unsupported conditional provider rejection remains authoritative`() {
        val request = request(CANONICAL)
        val parsed =
            adapter.parseProviderResponse(response(request, emptyList(), "UNSUPPORTED_CONDITIONAL_CONSTRAINT"), request).single()

        assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", parsed.rejectionCode)
        assertTrue(parsed.conditions.isEmpty())
    }

    @Test
    fun `provider authority still rejects missing duplicate and foreign batch references`() {
        val request = request(CANONICAL)
        val valid = providerResult(request.inputs.single().inputRef, scopedWindows(19, 21, 20, 14, 19), null)
        val badResults =
            listOf(
                emptyList(),
                listOf(valid, valid),
                listOf(valid + ("input_ref" to UUID.randomUUID().toString())),
            )

        for (results in badResults) {
            val wire = mapper.writeValueAsString(mapOf("schema_version" to "2", "results" to results))
            val failure = assertFailsWith<NaturalLanguageParserException> { adapter.parseProviderResponse(wire, request) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        }
    }

    @Test
    fun `out of range and contradictory date weekday metadata cannot become provider success`() {
        val request = request(CANONICAL)
        val valid = wireCondition(datedWindow(1, 20, 21))
        val invalid =
            listOf(
                valid + ("date" to DATE.plusDays(5).toString()),
                valid + ("day_of_week" to DayOfWeek.FRIDAY.name),
                valid + ("coordinates" to listOf(37.1, 127.1)),
            )

        for (condition in invalid) {
            val wire =
                mapper.writeValueAsString(
                    mapOf(
                        "schema_version" to "2",
                        "results" to listOf(mapOf("input_ref" to request.inputs.single().inputRef, "conditions" to listOf(condition))),
                    ),
                )
            val parsed = adapter.parseProviderResponse(wire, request).single()
            assertEquals("CONDITION_VALIDATION_FAILED", parsed.rejectionCode)
            assertTrue(parsed.conditions.isEmpty())
        }
    }

    @Test
    fun `incompatible shared provider area names remain a whole batch validation failure`() {
        val first = request("$CANONICAL 장소는 안양역이면 좋아요.")
        val second = NaturalLanguageInput(UUID.randomUUID().toString(), "장소는 수원역이 좋아요.", Locale.KOREAN, DATE)
        val request = first.copy(inputs = first.inputs + second)
        val wire =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "2",
                    "results" to
                        listOf(
                            providerResult(
                                request.inputs.first().inputRef,
                                scopedWindows(19, 21, 20, 14, 19) +
                                    StructuredCondition.SpecificPlace("안양역", areaKey = "AREA_1", areaName = "안양역 일대"),
                                null,
                            ),
                            providerResult(
                                second.inputRef,
                                listOf(StructuredCondition.SpecificPlace("수원역", areaKey = "AREA_1", areaName = "수원역 일대")),
                                null,
                            ),
                        ),
                ),
            )

        val failure = assertFailsWith<NaturalLanguageParserException> { adapter.parseProviderResponse(wire, request) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Test
    fun `thirty two provider conditions are preserved and thirty three remain a validation failure`() {
        val request = request("수요일 19:00부터 21:00까지 가능해요.")
        val conditions = List(32) { datedWindow(0, 19, 21) }
        val parsed = adapter.parseProviderResponse(response(request, conditions, null), request).single()

        assertNull(parsed.rejectionCode)
        assertEquals(conditions, parsed.conditions)
        val failure =
            assertFailsWith<NaturalLanguageParserException> {
                adapter.parseProviderResponse(response(request, conditions + datedWindow(0, 19, 21), null), request)
            }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Test
    fun `prompt carries each immutable date and a general compatible parent inheritance instruction`() {
        val first = request(CANONICAL)
        val second = NaturalLanguageInput(UUID.randomUUID().toString(), "평일은 6~9시 가능해요.", Locale.KOREAN, DATE.plusDays(7))
        val prompt = adapter.prompt(first.copy(inputs = first.inputs + second))

        for (input in first.inputs + second) {
            assertTrue(prompt.lines().any { input.inputRef in it && "reference_date=${input.referenceDate}" in it })
        }
        val lower = prompt.lowercase(Locale.ROOT)
        assertTrue("monday" in lower)
        assertTrue("inherit" in lower && "parent" in lower && "compatible" in lower)
        assertTrue("start-only" in lower && "end" in lower)
    }

    private fun assertSuccess(
        raw: String,
        conditions: List<StructuredCondition>,
        expected: List<InstantTimeRange>,
        mode: MeetingMode = MeetingMode.REMOTE,
    ) {
        val request = request(raw)
        val parsed = adapter.parseProviderResponse(response(request, conditions, null), request).single()

        assertNull(parsed.rejectionCode, raw)
        assertEquals(conditions, parsed.conditions, "Adapter must preserve the validated provider structure: $raw")
        val available =
            TimeRangeMatcher.calculateAvailability(
                parsed.conditions.filterIsInstance<StructuredCondition.TimeWindow>(),
                emptyList(),
                emptyList(),
                emptyList(),
                SearchDateRange.explicit(DATE, DATE.plusDays(5)),
                MeetingTimeZone.of("Asia/Seoul"),
            )
        assertEquals(expected, available, raw)
        val area =
            parsed.conditions.filterIsInstance<StructuredCondition.SpecificPlace>().singleOrNull()?.let {
                ParticipantAllowedArea(listOf(CompatiblePlaceArea(requireNotNull(it.areaKey), requireNotNull(it.areaName))))
            }
        val participants = listOf(ParticipantId(UUID(0, 1)), ParticipantId(UUID(0, 2)))
        val broad = listOf(InstantTimeRange(DATE.atStartOfDay(ZONE).toInstant(), DATE.plusDays(5).atStartOfDay(ZONE).toInstant()))
        val matched =
            DeterministicCandidateMatcher.generate(
                mode,
                listOf(
                    ParticipantMatchInput(participants[0], available, offlineArea = area),
                    ParticipantMatchInput(participants[1], broad, offlineArea = area),
                ),
            )
        val candidate = matched.candidates.single()
        assertEquals(expected, candidate.timeRanges, raw)
        assertEquals(participants, candidate.participantIds)
        assertEquals(mode, candidate.meetingMode)
        assertEquals(if (mode == MeetingMode.IN_PERSON) PlanType.A else PlanType.B, candidate.planType)
    }

    private fun request(raw: String) =
        NaturalLanguageBatchRequest(
            ZONE,
            DATE,
            DATE.plusDays(5),
            listOf(NaturalLanguageInput(UUID.randomUUID().toString(), raw, Locale.KOREAN, DATE)),
        )

    private fun response(
        request: NaturalLanguageBatchRequest,
        conditions: List<StructuredCondition>,
        rejection: String?,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "2",
                "results" to listOf(providerResult(request.inputs.single().inputRef, conditions, rejection)),
            ),
        )

    private fun providerResult(
        ref: String,
        conditions: List<StructuredCondition>,
        rejection: String?,
    ): Map<String, Any?> = mapOf("input_ref" to ref, "conditions" to conditions.map(::wireCondition), "rejection_code" to rejection)

    private fun wireCondition(condition: StructuredCondition): Map<String, Any?> =
        when (condition) {
            is StructuredCondition.TimeWindow ->
                mapOf(
                    "type" to "TIME_WINDOW",
                    "polarity" to condition.polarity.name,
                    "date" to condition.date?.toString(),
                    "day_of_week" to condition.dayOfWeek?.name,
                    "start_time" to condition.startTime.toString(),
                    "end_time" to if (condition.endsAtNextDayStart) "24:00" else condition.endTime.toString(),
                )
            is StructuredCondition.SpecificPlace ->
                mapOf(
                    "type" to "SPECIFIC_PLACE",
                    "query" to condition.query,
                    "area_key" to condition.areaKey,
                    "area_name" to condition.areaName,
                )
            else -> error("Only time and place fixtures belong to this contract")
        }

    private fun scopedWindows(
        weekdayStart: Int,
        weekdayEnd: Int,
        childStart: Int,
        weekendStart: Int,
        weekendEnd: Int,
    ): List<StructuredCondition> =
        DayOfWeek.entries.map { day ->
            val start = if (day.value <= 5) weekdayStart else weekendStart
            val end = if (day.value <= 5) weekdayEnd else weekendEnd
            StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, null, day, LocalTime.of(start, 0), LocalTime.of(end, 0))
        } +
            StructuredCondition.TimeWindow(
                TimePolarity.UNAVAILABLE,
                DATE.plusDays(1),
                null,
                LocalTime.of(weekdayStart, 0),
                LocalTime.of(childStart, 0),
            )

    private fun datedWindow(
        offset: Long,
        start: Int,
        end: Int,
    ) = StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE.plusDays(offset), null, LocalTime.of(start, 0), LocalTime.of(end, 0))

    private fun fiveRanges(
        weekdayStart: Int,
        weekdayEnd: Int,
        childStart: Int,
        weekendStart: Int,
        weekendEnd: Int,
    ) = listOf(
        utc(0, weekdayStart, weekdayEnd),
        utc(1, childStart, weekdayEnd),
        utc(2, weekdayStart, weekdayEnd),
        utc(3, weekendStart, weekendEnd),
        utc(4, weekendStart, weekendEnd),
    )

    private fun utc(
        offset: Long,
        start: Int,
        end: Int,
    ) = InstantTimeRange(
        DATE.plusDays(offset).atTime(start, 0).toInstant(ZoneOffset.ofHours(9)),
        DATE.plusDays(offset).atTime(end, 0).toInstant(ZoneOffset.ofHours(9)),
    )

    companion object {
        private val DATE: LocalDate = LocalDate.of(2026, 10, 7)
        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private const val CANONICAL = "평일은 7시부터 9시까지 가능해요. 이번주는 목요일만 8시부터 돼요. 주말은 2시부터 7시까지 가능해요."
    }
}
