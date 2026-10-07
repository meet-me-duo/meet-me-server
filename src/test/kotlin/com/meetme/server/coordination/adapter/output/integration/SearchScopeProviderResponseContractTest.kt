package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96: synthetic JSON plus the separately identified stored Luna evaluation fixture; no live provider calls. */
class SearchScopeProviderResponseContractTest {
    private val mapper = JsonMapper.builder().build()
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)

    @Test
    fun `stored Luna evaluation response preserves all valid conditions and COMPLETE three evening ranges`() {
        // Exact supplied evidence from evaluation session 01a114c3-02d3-7011-ab8f-19706bdda089; no live call here.
        val stored = requireNotNull(javaClass.getResourceAsStream("/issue-96/luna-search-scope-observed.json")).use { mapper.readTree(it) }
        assertEquals("1ddbd26747936a56a0f6f2f380d1ef066fb41ed7", stored["sourceHead"].asText())
        assertEquals("gpt-6-luna", stored["model"].asText())
        assertEquals("2", stored["schemaVersion"].asText())
        val original = stored["request"]
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of(original["timeZone"].asText()),
                LocalDate.parse(original["searchStartDate"].asText()),
                LocalDate.parse(original["searchEndDate"].asText()),
                original["inputs"]
                    .asSequence()
                    .map {
                        NaturalLanguageInput(
                            it["inputRef"].asText(),
                            it["rawText"].asText(),
                            Locale.forLanguageTag(it["locale"].asText()),
                            LocalDate.parse(it["referenceDate"].asText()),
                        )
                    }.toList(),
            )
        assertEquals(REFERENCE_DATE, request.searchStartDate)
        assertEquals(LocalDate.of(2026, 10, 12), request.searchEndDate)
        assertEquals(listOf(REFERENCE_DATE, REFERENCE_DATE), request.inputs.map { it.referenceDate })
        val parsed = adapter.parseProviderResponse(mapper.writeValueAsString(stored["providerResponse"]), request)
        assertEquals(listOf(4, 5), parsed.map { it.conditions.size })
        parsed.forEach { assertNull(it.rejectionCode) }
        val fixture = fixture()
        // Fixture repository identities differ; condition bytes and actual request used above are untouched.
        fixture.structured = parsed.mapIndexed { index, result -> result.copy(submissionVersionId = fixture.submissions[index].latest.id) }

        fixture.process()

        assertEquals(CandidateQuality.COMPLETE, fixture.run.quality)
        assertEquals("B", fixture.singleCandidate().planType.name)
        assertEquals(
            listOf("07", "08", "09").map {
                InstantTimeRange(Instant.parse("2026-10-${it}T10:00:00Z"), Instant.parse("2026-10-${it}T12:00:00Z"))
            },
            fixture.singleCandidate().timeRanges,
        )
    }

    @Test
    fun `valid exception outside search preserves COMPLETE weekday intersections`() {
        val fixture = fixture()
        val request = request(fixture)
        val p1 =
            weekdays.map { weekly(it, "19:00", "21:00") } +
                dated("2026-10-15", "19:00", "20:00", "UNAVAILABLE")
        val p2 = allDays.map { weekly(it, "18:00", "22:00") }

        fixture.structured = adapter.parseProviderResponse(response(request, listOf(p1, p2)), request)

        assertEquals(listOf(6, 7), fixture.structured.map { it.conditions.size })
        fixture.structured.forEach { assertNull(it.rejectionCode) }
        assertTrue(
            fixture.structured.first().conditions.filterIsInstance<StructuredCondition.TimeWindow>().any {
                it.date == LocalDate.of(2026, 10, 15)
            },
        )
        fixture.process()

        assertEquals(CoordinationStatus.COMPLETED, fixture.run.status)
        assertEquals(CandidateQuality.COMPLETE, fixture.run.quality)
        assertEquals(
            listOf("07", "08", "09").map {
                InstantTimeRange(Instant.parse("2026-10-${it}T10:00:00Z"), Instant.parse("2026-10-${it}T12:00:00Z"))
            },
            fixture.singleCandidate().timeRanges,
        )
    }

    @Test
    fun `AVAILABLE-only outside search remains empty availability and COMPLETE NO_MATCH`() {
        val fixture = fixture()
        val request = request(fixture)
        val outside = listOf(dated("2026-10-15", "19:00", "21:00", "AVAILABLE"))
        val inside = allDays.map { weekly(it, "18:00", "22:00") }

        fixture.structured = adapter.parseProviderResponse(response(request, listOf(outside, inside)), request)

        assertNull(fixture.structured.first().rejectionCode)
        assertEquals(
            1,
            fixture.structured
                .first()
                .conditions.size,
        )
        fixture.process()

        assertEquals(CoordinationStatus.COMPLETED, fixture.run.status)
        assertEquals(CandidateQuality.COMPLETE, fixture.run.quality)
        assertTrue(fixture.run.candidates.isEmpty(), "Dropping out-of-range AVAILABLE must not turn the input into all-day availability")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"type\":\"TIME_WINDOW\",\"polarity\":\"UNAVAILABLE\",\"date\":\"2026-02-30\",\"day_of_week\":null,\"start_time\":\"19:00\",\"end_time\":\"20:00\"}",
            "{\"type\":\"TIME_WINDOW\",\"polarity\":\"UNAVAILABLE\",\"date\":\"2026-10-15\",\"day_of_week\":\"FRIDAY\",\"start_time\":\"19:00\",\"end_time\":\"20:00\"}",
            "{\"type\":\"TIME_WINDOW\",\"polarity\":\"UNAVAILABLE\",\"date\":\"2026-10-15\",\"day_of_week\":null,\"start_time\":\"25:00\",\"end_time\":\"26:00\"}",
            "{\"type\":\"TIME_WINDOW\",\"polarity\":\"UNKNOWN\",\"date\":\"2026-10-15\",\"day_of_week\":null,\"start_time\":\"19:00\",\"end_time\":\"20:00\"}",
        ],
    )
    fun `scope separation still rejects invalid date weekday clock and polarity`(invalid: String) {
        val fixture = fixture()
        val request = request(fixture)
        val valid = weekly("WEDNESDAY", "19:00", "21:00")
        val results =
            adapter.parseProviderResponse(
                """{"schema_version":"2","results":[{"input_ref":"${request.inputs[0].inputRef}","conditions":[$valid,$invalid],"rejection_code":null},{"input_ref":"${request.inputs[1].inputRef}","conditions":[$valid],"rejection_code":null}]}""",
                request,
            )

        assertEquals("CONDITION_VALIDATION_FAILED", results.first().rejectionCode)
        assertEquals(1, results.first().conditions.size)
        assertNull(results.last().rejectionCode)
    }

    private fun fixture(): NaturalLanguageOnlyFixture {
        val fixture =
            NaturalLanguageOnlyFixture(
                listOf("평일 오후 7–9, 다음주 목요일만 오후 8시부터", "매일 오후 6–10"),
            )
        val previous = fixture.room
        fixture.room =
            MeetingRoom.restore(
                previous.id,
                previous.inviteCode,
                previous.purpose,
                previous.mode,
                previous.timeZone,
                SearchDateRange.explicit(REFERENCE_DATE, LocalDate.of(2026, 10, 12)),
                previous.closurePolicy,
                previous.collectionStatus,
                previous.closureReason,
                previous.closedAt,
                previous.createdAt,
                previous.version,
                previous.activeRunId,
            )
        `when`(fixture.roomRepository.findById(fixture.room.id)).thenReturn(fixture.room)
        `when`(fixture.roomRepository.findByIdForUpdate(fixture.room.id)).thenReturn(fixture.room)
        `when`(fixture.roomRepository.findByInviteCode(fixture.room.inviteCode)).thenReturn(fixture.room)
        return fixture
    }

    private fun request(fixture: NaturalLanguageOnlyFixture) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            REFERENCE_DATE,
            LocalDate.of(2026, 10, 12),
            fixture.submissions.map {
                NaturalLanguageInput(
                    it.latest.id.value
                        .toString(),
                    requireNotNull(it.latest.rawText),
                    Locale.KOREAN,
                    REFERENCE_DATE,
                )
            },
        )

    private fun response(
        request: NaturalLanguageBatchRequest,
        conditions: List<List<String>>,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "2",
                "results" to
                    conditions.mapIndexed { index, values ->
                        mapOf(
                            "input_ref" to request.inputs[index].inputRef,
                            "conditions" to values.map { mapper.readTree(it) },
                            "rejection_code" to null,
                        )
                    },
            ),
        )

    private fun weekly(
        day: String,
        start: String,
        end: String,
    ) = """{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"$day","start_time":"$start","end_time":"$end"}"""

    private fun dated(
        date: String,
        start: String,
        end: String,
        polarity: String,
    ) = """{"type":"TIME_WINDOW","polarity":"$polarity","date":"$date","day_of_week":null,"start_time":"$start","end_time":"$end"}"""

    companion object {
        private val REFERENCE_DATE = LocalDate.of(2026, 10, 7)
        private val weekdays = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")
        private val allDays = weekdays + listOf("SATURDAY", "SUNDAY")
    }
}
