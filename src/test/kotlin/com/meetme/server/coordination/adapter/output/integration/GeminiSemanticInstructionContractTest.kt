package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// These tests verify submitted instructions and response handling, not actual Gemini interpretation.
// FR-006/006A, FR-008B, FR-011A and ADR-043/045 require safe, input-faithful structuring.
class GeminiSemanticInstructionContractTest {
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), JsonMapper.builder().build())

    @Test
    fun `hard time exclusions and soft preferences have distinct instructions`() {
        val instructions = instructions()

        assertTrue("AVAILABLE" in instructions && "UNAVAILABLE" in instructions)
        assertTrue(Regex("(?i)hard.{0,40}(exclusion|constraint)|exclusion.{0,40}hard").containsMatchIn(instructions))
        assertTrue(Regex("(?i)soft.{0,40}prefer|prefer.{0,40}soft").containsMatchIn(instructions))
        assertTrue(Regex("(?i)(preserve|keep|remove).{0,80}(alternative|feasible)").containsMatchIn(instructions))
    }

    @Test
    fun `overnight windows and vague bounds are covered without fabricating times`() {
        val instructions = instructions()

        assertTrue(Regex("(?i)overnight|cross.{0,20}midnight").containsMatchIn(instructions))
        assertTrue("24:00" in instructions && "00:00" in instructions)
        assertTrue(Regex("(?i)vague|ambiguous|unclear").containsMatchIn(instructions))
        assertTrue(
            Regex(
                "(?i)(invent|fabricat|guess).{0,60}(bound|time)|(?:bound|time).{0,60}(invent|fabricat|guess)",
            ).containsMatchIn(instructions),
        )
    }

    @Test
    fun `coupled time and place constraints cannot silently become a Cartesian product`() {
        val instructions = instructions()

        assertTrue("UNSUPPORTED_CONDITIONAL_CONSTRAINT" in instructions)
        assertTrue(Regex("(?i)conditional|coupled|association").containsMatchIn(instructions))
        assertTrue(Regex("(?i)cartesian").containsMatchIn(instructions))
        assertTrue(Regex("(?i)empty.{0,40}condition|condition.{0,40}empty|conditions\\s*=\\s*\\[\\]").containsMatchIn(instructions))
    }

    @Test
    fun `forbidden locations and untrusted input have explicit boundaries`() {
        val instructions = instructions()

        assertTrue(Regex("(?i)forbidden|prohibited|excluded place|location exclusion").containsMatchIn(instructions))
        assertTrue("SPECIFIC_PLACE" in instructions)
        assertTrue(Regex("(?i)untrusted|input.{0,30}data|data.{0,30}instruction").containsMatchIn(instructions))
        assertTrue(Regex("(?i)never.{0,50}(plan|score)|do not.{0,50}(plan|score)").containsMatchIn(instructions))
    }

    @Test
    fun `prompt preserves the raw input reference room zone and search boundaries`() {
        val rawText = "월요일은 강남역 18~20시, 화요일은 홍대입구역 19~21시. 조건을 모두 유지해 줘."
        val request = request(rawText)
        val prompt = adapter.prompt(request)

        assertTrue("${request.inputs.single().inputRef}\tko-KR\t$rawText" in prompt)
        assertTrue("Asia/Seoul" in prompt)
        assertTrue("2026-09-20" in prompt && "2026-09-28" in prompt && "exclusive" in prompt)
    }

    @Test
    fun `unsupported coupled response is preserved as a rejection without invented conditions`() {
        val request = request("월요일 강남역 또는 화요일 홍대입구역에서만 가능")
        val response =
            """
            {"schema_version":"2","results":[
              {"input_ref":"${request.inputs.single().inputRef}","conditions":[],
               "rejection_code":"UNSUPPORTED_CONDITIONAL_CONSTRAINT"}
            ]}
            """.trimIndent()

        val result = adapter.parseProviderResponse(response, request).single()

        assertEquals(request.inputs.single().inputRef, result.submissionVersionId.value.toString())
        assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", result.rejectionCode)
        assertEquals(emptyList<StructuredCondition>(), result.conditions)
    }

    private fun instructions(): String = adapter.prompt(request("명시 시간과 장소")).substringBefore("Inputs:")

    private fun request(rawText: String) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 9, 20),
            LocalDate.of(2026, 9, 28),
            listOf(NaturalLanguageInput(UUID.randomUUID().toString(), rawText, Locale.forLanguageTag("ko-KR"), LocalDate.of(2026, 9, 20))),
        )
}
