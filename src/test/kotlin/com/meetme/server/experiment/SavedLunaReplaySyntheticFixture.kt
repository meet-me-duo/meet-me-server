package com.meetme.server.experiment

import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** New public synthetic data. Never copied from a provider reply, private pilot, or blind evaluation gold. */
internal data class SavedLunaReplayData(
    val originalInputs: List<NaturalLanguageInput>,
    val question: JsonNode,
    val sourceInput: JsonNode,
    val responseBytes: ByteArray,
    val origin: String,
)

internal object SavedLunaReplaySyntheticFixture {
    const val EXCERPT = "퇴근 뒤"
    const val PROMPT = "12월 3일 퇴근 뒤 가능한 시작 시각을 알려주세요."
    const val ANSWER = "12월 3일 오후 6시 30분부터 가능해요."
    const val RAW_FIRST = "2026년 12월 3일 퇴근 뒤부터 오후 9시까지, 12월 4일 오후 8시부터 10시까지 가능해요. 합성 중앙역에서 도보 12분 이내여야 해요."
    const val RAW_SECOND = "2026년 12월 3일 오후 7시부터 9시까지 또는 12월 4일 오후 8시부터 9시 30분까지 가능하고 장소 제한은 없어요."
    const val RAW_THIRD = "2026년 12월 3일 오후 7시 30분부터 9시까지, 12월 4일 오후 7시부터 9시 30분까지 가능해요. 출발지를 정하지 못했지만 대중교통 45분 이내여야 해요."

    fun data(json: ObjectMapper): SavedLunaReplayData {
        val reference = Instant.parse("2026-10-31T00:00:00Z")
        val answered = reference.plusSeconds(60)
        val texts = listOf(RAW_FIRST, RAW_SECOND, RAW_THIRD)
        val inputs =
            texts.mapIndexed { index, raw ->
                val evidence =
                    if (index == 0) {
                        InputEvidence(
                            originalRevision = 1,
                            referenceAt = reference,
                            supplement =
                                InputSupplement(
                                    questionId = UUID(0, 100),
                                    sourceRevision = 1,
                                    sourceExcerpt = EXCERPT,
                                    sourceStartCodePoint = raw.codePointCount(0, raw.indexOf(EXCERPT)),
                                    prompt = PROMPT,
                                    answerText = ANSWER,
                                    choiceId = null,
                                    answeredAt = answered,
                                    referenceAt = answered,
                                ),
                            interpretation = InputInterpretation.UNRESOLVED,
                            unresolvedReason = "SYNTHETIC_REVIEW_PENDING",
                        )
                    } else {
                        InputEvidence(originalRevision = 1, referenceAt = reference, interpretation = InputInterpretation.CONFIRMED)
                    }
                evidence.validateAgainst(raw)
                NaturalLanguageInput(UUID(0, (index + 1).toLong()).toString(), raw, Locale.KOREAN, LocalDate.of(2026, 10, 31), evidence)
            }
        val conditions =
            listOf(
                listOf(
                    window("2026-12-03", "18:30", "21:00"),
                    window("2026-12-04", "20:00", "22:00"),
                    mapOf("type" to "TRAVEL_CONSTRAINT", "expression" to "합성 중앙역에서 도보 12분 이내"),
                ),
                listOf(window("2026-12-03", "19:00", "21:00"), window("2026-12-04", "20:00", "21:30")),
                listOf(
                    window("2026-12-03", "19:30", "21:00"),
                    window("2026-12-04", "19:00", "21:30"),
                    mapOf("type" to "TRAVEL_CONSTRAINT", "expression" to "출발지 미정인 대중교통 45분 이내"),
                ),
            )
        val payload =
            mapOf(
                "schema_version" to "3",
                "results" to
                    inputs.mapIndexed { index, input ->
                        mapOf(
                            "input_ref" to input.inputRef,
                            "rejection_code" to null,
                            "clarification" to null,
                            "conditions" to conditions[index],
                        )
                    },
            )
        val response =
            mapOf(
                "status" to "completed",
                "output" to
                    listOf(
                        mapOf(
                            "type" to "message",
                            "content" to listOf(mapOf("type" to "output_text", "text" to json.writeValueAsString(payload))),
                        ),
                    ),
                // Parser-shape fixture only; these values are not measured model usage or billing.
                "usage" to mapOf("input_tokens" to 100, "output_tokens" to 50),
            )
        return SavedLunaReplayData(
            inputs,
            json.valueToTree(mapOf("text" to PROMPT, "source_excerpt" to EXCERPT)),
            json.valueToTree(
                mapOf(
                    "title" to "Independent synthetic two-day time and travel replay",
                    "startDate" to "2026-12-03",
                    "endDate" to "2026-12-05",
                    "participants" to texts.mapIndexed { index, raw -> mapOf("id" to "p${index + 1}", "rawInput" to raw) },
                ),
            ),
            json.writeValueAsBytes(response),
            "SYNTHETIC",
        )
    }

    private fun window(
        date: String,
        start: String,
        end: String,
    ): Map<String, String?> =
        mapOf(
            "type" to "TIME_WINDOW",
            "polarity" to "AVAILABLE",
            "date" to date,
            "day_of_week" to null,
            "start_time" to start,
            "end_time" to end,
        )
}
