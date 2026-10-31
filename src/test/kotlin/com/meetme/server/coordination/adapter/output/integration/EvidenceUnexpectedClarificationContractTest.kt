package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EX-FINAL: a forbidden second question is evidence of unresolved interpretation, not authority to guess. */
class EvidenceUnexpectedClarificationContractTest {
    @Test
    fun `EX-FINAL a second question cannot be silently discarded while applying its guessed conditions`() {
        val mapper = JsonMapper.builder().build()
        val anchor = Instant.parse("2026-10-29T03:00:00Z")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val evidence =
            InputEvidence(
                1,
                anchor,
                InputSupplement(UUID.randomUUID(), 1, "7시", 4, "오전인가요 오후인가요?", "오후", "B", anchor.plusSeconds(30), anchor),
                InputInterpretation.UNRESOLVED,
                "TIME_UNRESOLVED",
            )
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.parse("2026-10-29"),
                LocalDate.parse("2026-11-05"),
                listOf(
                    NaturalLanguageInput(first, "목요일 7시 이후 가능", Locale.KOREAN, LocalDate.parse("2026-10-29"), evidence),
                    NaturalLanguageInput(
                        second,
                        "목요일 19시부터 21시 가능",
                        Locale.KOREAN,
                        LocalDate.parse("2026-10-29"),
                        InputEvidence(1, anchor),
                    ),
                ),
            )
        val window =
            mapOf(
                "type" to "TIME_WINDOW",
                "polarity" to "AVAILABLE",
                "date" to "2026-10-29",
                "day_of_week" to null,
                "start_time" to "19:00",
                "end_time" to "21:00",
            )
        val question =
            mapOf(
                "excerpt" to "7시",
                "question" to "다시 오전인지 오후인지 알려주세요",
                "option_a" to "목요일 오전 7시 이후 가능",
                "option_b" to "목요일 오후 7시 이후 가능",
            )
        val response =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        listOf(
                            mapOf(
                                "input_ref" to first,
                                "conditions" to listOf(window),
                                "rejection_code" to null,
                                "clarification" to question,
                            ),
                            mapOf("input_ref" to second, "conditions" to listOf(window), "rejection_code" to null, "clarification" to null),
                        ),
                ),
            )
        val results =
            listOf(
                GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper).parseProviderResponse(response, request),
                OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper).parseProviderResponse(response, request),
            )

        for (batch in results) {
            assertEquals(2, batch.size)
            val failed = batch.single { it.submissionVersionId.value.toString() == first }
            assertTrue(failed.conditions.isEmpty(), "A result that still asks a question cannot authorize its guessed time")
            assertEquals("AMBIGUOUS_INPUT", failed.rejectionCode)
            assertNull(failed.clarification)
            val valid = batch.single { it.submissionVersionId.value.toString() == second }
            assertEquals(1, valid.conditions.size)
            assertNull(valid.rejectionCode)
            assertNull(valid.clarification)
        }
    }
}
