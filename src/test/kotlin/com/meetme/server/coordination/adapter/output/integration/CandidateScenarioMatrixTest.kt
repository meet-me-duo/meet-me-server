package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.input.web.MatchingResultController
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.adapter.input.web.GuestCookie
import com.meetme.server.submission.domain.StructuredCondition
import jakarta.servlet.http.Cookie
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FR-011/011A, ADR-038/043/045: synthetic provider output, real parser/matcher/result HTTP path. */
class CandidateScenarioMatrixTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    fun `trace synthetic natural language scenarios through result HTTP`(scenario: Scenario) {
        val fixture = NaturalLanguageOnlyFixture(scenario.texts, scenario.mode)
        val mapper = JsonMapper.builder().build()
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                NaturalLanguageOnlyFixture.DATE,
                NaturalLanguageOnlyFixture.DATE.plusDays(2),
                fixture.submissions.mapIndexed { index, submission ->
                    NaturalLanguageInput(
                        submission.latest.id.value
                            .toString(),
                        scenario.texts[index],
                        Locale.KOREAN,
                    )
                },
            )
        val provider =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "2",
                    "results" to
                        scenario.conditions.mapIndexed { index, conditions ->
                            mapOf(
                                "input_ref" to request.inputs[index].inputRef,
                                "conditions" to conditions,
                                "rejection_code" to if (index in scenario.rejected) "AMBIGUOUS_INPUT" else null,
                            )
                        },
                ),
            )
        fixture.structured = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper).parseProviderResponse(provider, request)
        val availability =
            fixture.structured.map {
                TimeRangeMatcher.calculateAvailability(
                    it.conditions.filterIsInstance<StructuredCondition.TimeWindow>(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    fixture.room.searchRange,
                    fixture.room.timeZone,
                )
            }
        val commonTimes = availability.reduce(TimeRangeMatcher::intersect)
        val areas =
            fixture.structured.map { result ->
                result.conditions
                    .filterIsInstance<StructuredCondition.SpecificPlace>()
                    .map { requireNotNull(it.areaKey) }
                    .toSet()
            }
        fixture.process()
        assertEquals(scenario.plans, fixture.run.candidates.map { it.planType.name })
        assertEquals(scenario.quality, fixture.run.quality)
        fixture.run.candidates.forEach { candidate ->
            assertEquals(scenario.ranges, candidate.timeRanges.map { "${it.startInclusive}/${it.endExclusive}" })
            assertEquals(scenario.attendance ?: scenario.texts.size, candidate.participantIds.size)
            assertEquals(if (candidate.meetingMode == MeetingMode.IN_PERSON) scenario.place else null, candidate.place?.displayName)
            candidate.participantIds.forEach { participant ->
                val index = fixture.submissions.indexOfFirst { it.participantId == participant }
                assertEquals(candidate.timeRanges, TimeRangeMatcher.intersect(candidate.timeRanges, availability[index]))
            }
        }
        val service = fixture.resultService()
        val mvc = MockMvcBuilders.standaloneSetup(MatchingResultController(service, service, service, service)).build()
        val body =
            mvc
                .perform(
                    get("/api/rooms/{code}/candidates", fixture.room.inviteCode.value)
                        .cookie(Cookie(GuestCookie.NAME, "host"))
                        .header("Accept-Language", "ko"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val response = mapper.readTree(body)
        assertEquals(scenario.plans.size, response["candidates"].size())
        assertFalse(body.contains("raw_text"))
        assertFalse(body.contains("participant_ids"))
        response["candidates"].forEachIndexed { index, candidate ->
            assertEquals(scenario.plans[index], candidate["plan_type"].asText())
            assertEquals(scenario.ranges.size, candidate["time_ranges"].size())
            assertTrue(candidate["summary"].asText().isNotBlank())
        }
        println(
            "SCENARIO_TRACE " +
                mapper.writeValueAsString(
                    mapOf(
                        "id" to scenario.id,
                        "mode" to scenario.mode.name,
                        "path" to "SYNTHETIC_PROVIDER_REAL_PARSER_MATCHER_MOCKMVC_MOCK_REPOSITORIES",
                        "raw_inputs" to scenario.texts,
                        "provider_conditions" to scenario.conditions,
                        "parsed_condition_counts" to fixture.structured.map { it.conditions.size },
                        "rejections" to fixture.structured.map { it.rejectionCode },
                        "availability" to availability.map { ranges -> ranges.map { "${it.startInclusive}/${it.endExclusive}" } },
                        "all_participant_intersection" to commonTimes.map { "${it.startInclusive}/${it.endExclusive}" },
                        "place_groups" to areas,
                        "common_place_groups" to areas.reduce { left, right -> left.intersect(right) },
                        "http_result" to mapper.readValue(body, Map::class.java),
                    ),
                ),
        )
    }

    data class Scenario(
        val id: String,
        val mode: MeetingMode,
        val texts: List<String>,
        val conditions: List<List<Map<String, String>>>,
        val plans: List<String>,
        val ranges: List<String>,
        val place: String? = null,
        val attendance: Int? = null,
        val rejected: Set<Int> = emptySet(),
        val quality: CandidateQuality = CandidateQuality.COMPLETE,
    ) {
        override fun toString(): String = id
    }

    companion object {
        private fun time(
            start: String,
            end: String,
            day: String = "21",
            polarity: String = "AVAILABLE",
        ) = mapOf("type" to "TIME_WINDOW", "polarity" to polarity, "date" to "2026-09-$day", "start_time" to start, "end_time" to end)

        private fun place(
            key: String = "AREA_1",
            name: String = "강남역 일대",
            query: String = "강남역",
        ) = mapOf("type" to "SPECIFIC_PLACE", "query" to query, "area_key" to key, "area_name" to name)

        private fun range(
            start: String,
            end: String,
            day: String = "21",
            endDay: String = day,
        ) = "2026-09-${day}T$start:00Z/2026-09-${endDay}T$end:00Z"

        @JvmStatic
        fun scenarios(): List<Scenario> {
            val morning = time("09:00", "11:00")
            val afternoon = time("14:00", "16:00")
            val evening = time("19:00", "21:00")
            val multiple = listOf(morning, afternoon, evening, place())
            val threeRanges = listOf(range("00:00", "02:00"), range("05:00", "07:00"), range("10:00", "12:00"))
            val one = listOf(evening, place())
            val oneRange = listOf(range("10:00", "12:00"))
            val remote = MeetingMode.REMOTE
            val offline = MeetingMode.IN_PERSON
            val either = MeetingMode.EITHER
            return listOf(
                Scenario(
                    "two-many-times-remote",
                    remote,
                    List(2) {
                        "9월 21일 9~11시, 14~16시, 19~21시 강남역 가능"
                    },
                    List(2) { multiple },
                    listOf("B"),
                    threeRanges,
                ),
                Scenario(
                    "three-many-times-either",
                    either,
                    List(3) {
                        "9월 21일 9~11시, 14~16시, 19~21시 강남역 가능"
                    },
                    List(3) { multiple },
                    listOf("A", "B"),
                    threeRanges,
                    "강남역 일대",
                ),
                Scenario(
                    "two-one-time-offline",
                    offline,
                    List(2) { "9월 21일 19~21시 강남역만 가능" },
                    List(2) { one },
                    listOf("A"),
                    oneRange,
                    "강남역 일대",
                ),
                Scenario(
                    "two-no-time",
                    remote,
                    listOf("9월 21일 오전 9~11시 강남역", "9월 21일 오후 14~16시 강남역"),
                    listOf(listOf(morning, place()), listOf(afternoon, place())),
                    emptyList(),
                    emptyList(),
                ),
                Scenario(
                    "three-partial-time",
                    remote,
                    listOf("9월 21일 19~21시 강남역", "9월 21일 19~21시 강남역", "9월 21일 9~11시 강남역"),
                    listOf(one, one, listOf(morning, place())),
                    listOf("C"),
                    oneRange,
                    attendance = 2,
                ),
                Scenario(
                    "three-no-pair",
                    remote,
                    listOf("9월 21일 9~11시 강남역", "9월 21일 14~16시 강남역", "9월 21일 19~21시 강남역"),
                    listOf(listOf(morning, place()), listOf(afternoon, place()), one),
                    emptyList(),
                    emptyList(),
                ),
                Scenario(
                    "two-different-preferences",
                    remote,
                    listOf(
                        "9월 21일 9~11시 또는 19~21시 강남역, 아침 선호",
                        "9월 21일 9~11시 또는 19~21시 강남역, 저녁 선호",
                    ),
                    List(2) {
                        listOf(morning, evening, place())
                    },
                    listOf("B"),
                    listOf(threeRanges[0], threeRanges[2]),
                ),
                Scenario(
                    "two-hard-exclusion",
                    remote,
                    listOf("9월 21일 9~16시 강남역 가능, 11~14시 절대 불가", "9월 21일 9~16시 강남역 가능"),
                    listOf(
                        listOf(time("09:00", "16:00"), time("11:00", "14:00", polarity = "UNAVAILABLE"), place()),
                        listOf(time("09:00", "16:00"), place()),
                    ),
                    listOf("B"),
                    listOf(threeRanges[0], threeRanges[1]),
                ),
                Scenario(
                    "two-wide-time",
                    remote,
                    listOf("9월 21일 9~18시 강남역 가능", "9월 21일 10~13시 강남역 가능"),
                    listOf(listOf(time("09:00", "18:00"), place()), listOf(time("10:00", "13:00"), place())),
                    listOf("B"),
                    listOf(range("01:00", "04:00")),
                ),
                Scenario(
                    "two-ambiguous-partial",
                    remote,
                    listOf("적당한 시간에 학교 근처", "9월 21일 19~21시 강남역"),
                    listOf(emptyList(), one),
                    listOf("B"),
                    oneRange,
                    rejected = setOf(0),
                    quality = CandidateQuality.PARTIAL,
                ),
                Scenario(
                    "two-midnight-boundary",
                    remote,
                    List(2) {
                        "9월 21일 밤 23시부터 자정까지 강남역"
                    },
                    List(2) { listOf(time("23:00", "24:00"), place()) },
                    listOf("B"),
                    listOf(range("14:00", "15:00")),
                ),
                Scenario(
                    "two-cross-date",
                    remote,
                    listOf("9월 21일 23시부터 22일 새벽 2시까지 강남역", "9월 22일 0~2시 강남역"),
                    listOf(
                        listOf(time("23:00", "24:00"), time("00:00", "02:00", "22"), place()),
                        listOf(time("00:00", "02:00", "22"), place()),
                    ),
                    listOf("B"),
                    listOf(range("15:00", "17:00")),
                ),
                Scenario(
                    "two-nearby-stations",
                    offline,
                    listOf("9월 21일 19~21시 봉천역 가능", "9월 21일 19~21시 서울대입구역 가능"),
                    listOf(
                        listOf(evening, place(name = "관악구 북부", query = "봉천역")),
                        listOf(evening, place(name = "관악구 북부", query = "서울대입구역")),
                    ),
                    listOf("A"),
                    oneRange,
                    "관악구 북부",
                ),
                Scenario(
                    "two-separated-stations",
                    either,
                    listOf("9월 21일 19~21시 강남역", "9월 21일 19~21시 홍대입구역"),
                    listOf(one, listOf(evening, place("AREA_2", "홍대 일대", "홍대입구역"))),
                    listOf("B"),
                    oneRange,
                ),
                Scenario(
                    "two-place-unspecified",
                    offline,
                    listOf("9월 21일 19~21시 가능", "9월 21일 19~21시 강남역"),
                    listOf(listOf(evening), one),
                    emptyList(),
                    emptyList(),
                ),
                Scenario(
                    "two-multiple-common-stations",
                    offline,
                    List(2) {
                        "9월 21일 19~21시 강남역 또는 홍대입구역 가능"
                    },
                    List(2) { listOf(evening, place(), place("AREA_2", "홍대 일대", "홍대입구역")) },
                    listOf("A"),
                    oneRange,
                    "강남역 일대",
                ),
                Scenario(
                    "two-same-time-different-places-either",
                    either,
                    List(2) {
                        "9월 21일 19~21시 강남역 또는 홍대입구역, 온라인도 가능"
                    },
                    List(2) { listOf(evening, place(), place("AREA_2", "홍대 일대", "홍대입구역")) },
                    listOf("A", "B"),
                    oneRange,
                    "강남역 일대",
                ),
                Scenario(
                    "two-duplicate-conditions",
                    offline,
                    List(2) {
                        "9월 21일 19~21시 강남역. 같은 날 19~21시 강남역 가능"
                    },
                    List(2) { listOf(evening, evening, place(), place()) },
                    listOf("A"),
                    oneRange,
                    "강남역 일대",
                ),
                Scenario(
                    "three-place-only-fallback",
                    offline,
                    listOf("9월 21일 19~21시 강남역", "9월 21일 19~21시 강남역", "9월 21일 19~21시 홍대입구역만"),
                    listOf(one, one, listOf(evening, place("AREA_2", "홍대 일대", "홍대입구역"))),
                    listOf("C"),
                    oneRange,
                    "강남역 일대",
                    attendance = 2,
                ),
                Scenario(
                    "two-unresolved-place",
                    either,
                    listOf("9월 21일 19~21시 중앙역", "9월 21일 19~21시 강남역"),
                    listOf(
                        listOf(
                            evening,
                            mapOf(
                                "type" to "UNRESOLVED_PLACE",
                                "query" to "중앙역",
                            ),
                        ),
                        one,
                    ),
                    listOf("B"),
                    oneRange,
                    quality = CandidateQuality.PARTIAL,
                ),
                Scenario(
                    "two-travel-constraint",
                    either,
                    listOf("9월 21일 19~21시, 강남역에서 30분 이내만", "9월 21일 19~21시 강남역"),
                    listOf(
                        listOf(
                            evening,
                            mapOf(
                                "type" to "TRAVEL_CONSTRAINT",
                                "expression" to "강남역에서 30분 이내",
                            ),
                        ),
                        one,
                    ),
                    listOf("B"),
                    oneRange,
                ),
                Scenario(
                    "two-only-exclusions",
                    remote,
                    listOf("9월 21일 0~9시는 불가, 장소 상관없음", "9월 21일 10~13시 강남역 가능"),
                    listOf(listOf(time("00:00", "09:00", polarity = "UNAVAILABLE")), listOf(time("10:00", "13:00"), place())),
                    listOf("B"),
                    listOf(range("01:00", "04:00")),
                ),
                Scenario(
                    "two-touching-boundaries",
                    remote,
                    listOf("9월 21일 9~11시 강남역", "9월 21일 11~14시 강남역"),
                    listOf(listOf(morning, place()), listOf(time("11:00", "14:00"), place())),
                    emptyList(),
                    emptyList(),
                ),
            )
        }
    }
}
