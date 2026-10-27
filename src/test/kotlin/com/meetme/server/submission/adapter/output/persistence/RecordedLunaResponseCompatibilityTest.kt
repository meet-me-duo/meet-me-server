package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Immutable recorded provider output, never a claim about the modified prompt's extraction quality. */
class RecordedLunaResponseCompatibilityTest {
    @Test
    fun `recorded v26 empty rejection and three valid results survive both parsers and stored condition JSON`() {
        val mapper = JsonMapper.builder().build()
        val fixture = mapper.readTree(resource("original-four-regression.json"))
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 9),
                fixture["inputs"]
                    .asSequence()
                    .map {
                        NaturalLanguageInput(it["inputRef"].asText(), it["rawText"].asText(), Locale.KOREAN, LocalDate.of(2026, 11, 2))
                    }.toList(),
            )
        val recorded = resource("recorded-luna-v26-response.json")
        val luna = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper).parseProviderResponse(recorded, request)
        val gemini = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper).parseProviderResponse(recorded, request)
        assertEquals(luna, gemini)
        assertEquals(4, luna.size)
        assertTrue(luna.take(3).all { it.rejectionCode == null && it.conditions.isNotEmpty() })
        assertEquals("CONDITION_VALIDATION_FAILED", luna.last().rejectionCode)
        assertTrue(luna.last().conditions.isEmpty())
        assertNull(luna.last().clarification)
        for (result in luna) {
            val stored = mapper.writeValueAsString(result.conditions.map(StructuredConditionJsonMapper::toMap))
            val decoded = mapper.readValue(stored, object : TypeReference<List<Map<String, Any?>>>() {})
            assertEquals(result, result.copy(conditions = decoded.map(StructuredConditionJsonMapper::fromMap)))
        }
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/features/conditional-time/$name")).bufferedReader().use { it.readText() }
}
