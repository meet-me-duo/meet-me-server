package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent approved EDIT guidance contract. Synthetic envelopes only; no model or network calls. */
class PrecheckEditGuidanceContractTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = PrecheckProviderContract(mapper)
    private val original = "수요일은 수업이 끝난 뒤부터 가능해요."
    private val guidance = "수요일에 수업이 끝나는 시각을 적어 주세요."
    private val excerpt = "수업이 끝난 뒤"
    private val request =
        PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000532"),
            original,
            Instant.parse("2026-10-28T06:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 28),
            LocalDate.of(2026, 11, 15),
            Duration.ofSeconds(2),
        )

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG01 EDIT carries one concrete plain guidance and exact source without invented choices`(kind: String) {
        for (choices in listOf(null, emptyList<Any>())) {
            val result = parse(kind, payload(choices = choices))
            assertEquals(PrecheckVerdict.NEEDS_EDIT, result.verdict)
            assertEquals(guidance, result.prompt)
            assertEquals(excerpt, result.sourceExcerpt)
            assertTrue(result.choices.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG02 legacy EDIT without guidance remains readable`(kind: String) {
        for (body in listOf(mapOf("verdict" to "NEEDS_EDIT"), payload(prompt = null, source = null))) {
            val result = parse(kind, body)
            assertEquals(PrecheckVerdict.NEEDS_EDIT, result.verdict)
            assertNull(result.prompt)
            assertNull(result.sourceExcerpt)
            assertTrue(result.choices.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG03 guidance and excerpt are atomic and must be nonblank exact bounded text`(kind: String) {
        val invalid =
            listOf(
                payload(prompt = null),
                payload(source = null),
                payload(prompt = ""),
                payload(prompt = "   "),
                payload(source = ""),
                payload(source = "수업이 끝난 이후"),
                payload(prompt = "가".repeat(161)),
                payload(source = "🙂".repeat(201)),
                payload(choices = listOf(mapOf("id" to "A", "label" to "오전", "proposedText" to "오전 가능"))),
            )
        for (body in invalid) assertInvalid(kind, body, request.copy(rawText = original + "🙂".repeat(201)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG04 prompt and source limits count Unicode codepoints rather than UTF16 units`(kind: String) {
        val prompt = "가".repeat(159) + "🙂"
        val source = "🙂".repeat(200)
        val result = parse(kind, payload(prompt = prompt, source = source), request.copy(rawText = source))
        assertEquals(prompt, result.prompt)
        assertEquals(source, result.sourceExcerpt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG05 edit guidance is single line without control or bidi rendering instructions`(kind: String) {
        val forbidden = listOf('\u0000', '\u0001', '\t', '\n', '\r', '\u001f', '\u007f', '\u0085', '\u2028', '\u2029', '\u202e', '\u2066')
        for (character in forbidden) assertInvalid(kind, payload(prompt = "끝나는${character}시각을 적어 주세요."))
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG06 guidance cannot carry active markup links or navigation schemes`(kind: String) {
        for (prompt in listOf(
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "[확인](https://example.invalid)",
            "https://example.invalid 에서 입력하세요",
            "HTTP://example.invalid",
            "javascript:alert(1)",
            "data:text/html,x",
        )) {
            assertInvalid(kind, payload(prompt = prompt))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG07 source excerpt remains exact untrusted participant text rather than generated markup`(kind: String) {
        val source = "수업 <끝난 뒤>"
        val result = parse(kind, payload(source = source), request.copy(rawText = "$source 가능해요."))
        assertEquals(source, result.sourceExcerpt)
        assertEquals(guidance, result.prompt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `EG08 CLEAR cannot smuggle edit guidance or choices`(kind: String) {
        assertInvalid(kind, payload(verdict = "CLEAR"))
        val clear = parse(kind, payload(verdict = "CLEAR", prompt = null, source = null))
        assertEquals(PrecheckVerdict.CLEAR, clear.verdict)
        assertNull(clear.prompt)
        assertNull(clear.sourceExcerpt)
    }

    private fun payload(
        verdict: String = "NEEDS_EDIT",
        prompt: String? = guidance,
        source: String? = excerpt,
        choices: Any? = null,
    ): Map<String, Any?> = mapOf("verdict" to verdict, "prompt" to prompt, "sourceExcerpt" to source, "choices" to choices)

    private fun assertInvalid(
        kind: String,
        body: Map<String, Any?>,
        input: PrecheckProviderRequest = request,
    ) {
        assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { parse(kind, body, input) }.failureCode)
    }

    private fun parse(
        kind: String,
        body: Map<String, Any?>,
        input: PrecheckProviderRequest = request,
    ): PrecheckProviderResult {
        val content = mapper.writeValueAsString(body)
        return if (kind == "LUNA") {
            contract.parseLuna(
                mapper.writeValueAsBytes(
                    mapOf(
                        "status" to "completed",
                        "output" to
                            listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to content)))),
                        "usage" to
                            mapOf("input_tokens" to 128, "output_tokens" to 64, "output_tokens_details" to mapOf("reasoning_tokens" to 0)),
                    ),
                ),
                input,
            )
        } else {
            contract.parse(
                mapper.writeValueAsBytes(
                    mapOf(
                        "candidates" to
                            listOf(mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("text" to content))))),
                    ),
                ),
                input,
            )
        }
    }
}
