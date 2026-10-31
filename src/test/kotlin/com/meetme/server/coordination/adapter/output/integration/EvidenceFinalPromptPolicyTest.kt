package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EX-FINAL: V2 must never instruct the final provider to ask again or replace immutable input. */
class EvidenceFinalPromptPolicyTest {
    @Test
    fun `EX-FINAL V2 instructions contain no legacy question or whole-original replacement policy`() {
        val prompt = prompt(InputEvidence(1, Instant.parse("2026-10-29T03:00:00Z"), interpretation = InputInterpretation.CONFIRMED))

        assertFalse(prompt.contains("Return at most one concise clarification"))
        assertFalse(prompt.contains("Each option is a complete replacement input"))
    }

    @Test
    fun `EX-FINAL V1 retains its explicit legacy clarification policy`() {
        val prompt = prompt(null)

        assertTrue(prompt.contains("Return at most one concise clarification"))
        assertTrue(prompt.contains("Each option is a complete replacement input"))
    }

    private fun prompt(evidence: InputEvidence?): String =
        NaturalLanguageProviderContract(JsonMapper.builder().build()).prompt(
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.parse("2026-10-29"),
                LocalDate.parse("2026-11-05"),
                listOf(
                    NaturalLanguageInput(
                        UUID.randomUUID().toString(),
                        "목요일 19시부터 21시 가능",
                        Locale.KOREAN,
                        LocalDate.parse("2026-10-29"),
                        evidence,
                    ),
                ),
            ),
        )
}
