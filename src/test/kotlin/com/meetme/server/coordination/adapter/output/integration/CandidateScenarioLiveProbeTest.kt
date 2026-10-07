package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.service.NaturalLanguageOnlyFixture
import com.meetme.server.meetingroom.domain.MeetingMode
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals

/** Opt-in, three calls, pinned existing model, no retries/production persistence or key output. */
class CandidateScenarioLiveProbeTest {
    @Test
    fun `bounded live Gemini semantic probe`() {
        assumeTrue(System.getenv("MEETME_CANDIDATE_LIVE_PROBE") == "true")
        val key = System.getenv("GEMINI_API_KEY")
        assumeTrue(!key.isNullOrBlank())
        val mapper = JsonMapper.builder().build()
        val parser = GeminiNaturalLanguageParserAdapter(GeminiProperties(apiKey = requireNotNull(key)), mapper)
        val probes =
            listOf(
                "many-common-times" to List(3) { "2026년 9월 21일 오전 9~11시, 오후 2~4시, 저녁 7~9시 강남역 또는 홍대입구역에서 가능해요." },
                "hard-exclusion-midnight" to
                    listOf(
                        "2026년 9월 21일 강남역에서 19시부터 자정까지 가능하지만 20~21시는 절대 안 돼요.",
                        "2026년 9월 21일 강남역에서 19시부터 자정까지 가능해요.",
                    ),
                "coupled-place-time" to
                    listOf(
                        "2026년 9월 21일 9~11시는 강남역에서만, 19~21시는 홍대입구역에서만 가능해요.",
                        "2026년 9월 21일 9~11시는 홍대입구역에서만, 19~21시는 강남역에서만 가능해요.",
                    ),
            )
        probes.forEach { (id, texts) ->
            val fixture = NaturalLanguageOnlyFixture(texts, MeetingMode.IN_PERSON)
            val request =
                NaturalLanguageBatchRequest(
                    ZoneId.of("Asia/Seoul"),
                    NaturalLanguageOnlyFixture.DATE,
                    NaturalLanguageOnlyFixture.DATE.plusDays(2),
                    fixture.submissions.mapIndexed { index, submission ->
                        NaturalLanguageInput(
                            submission.latest.id.value
                                .toString(),
                            texts[index],
                            Locale.forLanguageTag("ko-KR"),
                            submission.latest.createdAt
                                .atZone(ZoneId.of("Asia/Seoul"))
                                .toLocalDate(),
                        )
                    },
                )
            try {
                val parsed = parser.parse(request)
                fixture.structured = parsed.results
                fixture.process()
                val view = fixture.resultService().getCandidates(fixture.room.inviteCode.value, "host", Locale.KOREAN)
                println(
                    "LIVE_SCENARIO_TRACE " +
                        mapper.writeValueAsString(
                            mapOf(
                                "id" to id,
                                "path" to "LIVE_GEMINI_REAL_PARSER_MATCHER_RESULT_SERVICE_MOCK_REPOSITORIES",
                                "raw_inputs" to texts,
                                "usage" to mapOf("input_tokens" to parsed.usage.inputTokens, "output_tokens" to parsed.usage.outputTokens),
                                "conditions" to parsed.results.map { result -> result.conditions.map { it.toString() } },
                                "rejections" to parsed.results.map { it.rejectionCode },
                                "quality" to fixture.run.quality?.name,
                                "candidates" to
                                    view.candidates.map { candidate ->
                                        mapOf(
                                            "plan" to candidate.planType.name,
                                            "place" to candidate.place?.displayName,
                                            "summary" to candidate.summary,
                                            "ranges" to candidate.timeRanges.map { "${it.startInclusive}/${it.endExclusive}" },
                                        )
                                    },
                            ),
                        ),
                )
                assertEquals(texts.size, parsed.results.size)
            } catch (exception: NaturalLanguageParserException) {
                // Only failure classification is printed; exception messages can contain provider context.
                println("LIVE_SCENARIO_FAILURE $id ${exception.kind.name}")
            }
        }
    }
}
