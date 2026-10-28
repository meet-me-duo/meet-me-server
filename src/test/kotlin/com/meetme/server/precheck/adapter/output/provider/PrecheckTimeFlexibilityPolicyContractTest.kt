package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-053/059 policy transmission only. Synthetic transport replies are not semantic evidence. */
class PrecheckTimeFlexibilityPolicyContractTest {
    private val mapper = JsonMapper.builder().build()
    private val input =
        PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000531"),
            "목요일 오후 6시부터 9시까지 가능해요.",
            Instant.parse("2026-10-28T06:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 28),
            LocalDate.of(2026, 11, 15),
            Duration.ofSeconds(2),
        )

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `TFP01 generation wire distinguishes intentional breadth and scoped meridiem from missing private facts`(kind: String) {
        val capture = Capture(kind)
        capture.provider.check(input)
        val body = capture.generation()
        val instructions =
            if (kind == "LUNA") {
                body.path("instructions").asText()
            } else {
                body
                    .path("systemInstruction")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText()
            }
        // These policy anchors protect transmission, not the model's interpretation of them.
        for (
        policy in
        listOf(
            "Broad availability such as an entire afternoon",
            "An explicit AM/PM qualifier applies to both endpoints of the same continuous range",
            "Do not carry that qualifier into a separate alternative or unrelated clause",
            "Do not invent private event times",
        )
        ) {
            assertTrue(instructions.contains(policy), "Missing transmitted policy: $policy")
        }
        assertTrue(instructions.contains("NEEDS_CHOICE"))
        assertTrue(instructions.contains("NEEDS_EDIT"))
        assertTrue(instructions.contains("without inventing facts or permissions"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `TFP02 generation receives every synthetic oracle original without normalization or narrowing`(kind: String) {
        val oracle = mapper.readTree(Files.readString(Path.of("docs/evaluation/precheck-time-flexibility-oracle.json")))
        assertEquals(12, oracle.path("cases").size())
        for (case in oracle.path("cases").values()) {
            val capture = Capture(kind)
            val original = case.path("rawText").asText()
            capture.provider.check(input.copy(rawText = original))
            val body = capture.generation()
            val data =
                mapper.readTree(
                    if (kind == "LUNA") {
                        body.path("input").asText()
                    } else {
                        body
                            .path("contents")
                            .get(0)
                            .path("parts")
                            .get(0)
                            .path("text")
                            .asText()
                    },
                )
            assertEquals(original, data.path("raw_text").asText(), case.path("id").asText())
            assertEquals(input.referenceAt.toString(), data.path("reference_at").asText())
            assertEquals(input.timeZone.id, data.path("time_zone").asText())
            assertEquals(input.searchStartDate.toString(), data.path("search_start_date").asText())
            assertEquals(input.searchEndDate.toString(), data.path("search_end_date").asText())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LUNA", "GEMINI"])
    fun `TFP03 generation wire preserves weekday and recurrence scope in clarification proposals`(kind: String) {
        val capture = Capture(kind)
        capture.provider.check(input.copy(rawText = "매주 금요일 6시부터 8시까지 가능해요."))
        val body = capture.generation()
        val instructions =
            if (kind == "LUNA") {
                body.path("instructions").asText()
            } else {
                body
                    .path("systemInstruction")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText()
            }
        assertTrue(
            instructions.contains("Preserve weekday and recurrence scope"),
            "Clarification must not narrow a weekday pattern to a guessed single calendar date",
        )
    }

    private inner class Capture(
        private val kind: String,
    ) {
        private val generations = mutableListOf<JsonNode>()
        private val transport =
            PrecheckProviderHttpTransport(
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val buffer = okio.Buffer()
                        request.body!!.writeTo(buffer)
                        val count = request.url.encodedPath.endsWith(":countTokens")
                        if (!count) generations.add(mapper.readTree(buffer.readUtf8()))
                        val body =
                            when {
                                count -> """{"totalTokens":128}"""
                                kind == "LUNA" ->
                                    """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"verdict\":\"CLEAR\",\"prompt\":null,\"sourceExcerpt\":null,\"choices\":null}"}]}],"usage":{"input_tokens":128,"output_tokens":24,"output_tokens_details":{"reasoning_tokens":0}}}"""
                                else ->
                                    """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"verdict\":\"CLEAR\"}"}]}}],"usageMetadata":{"promptTokenCount":128,"candidatesTokenCount":24,"thoughtsTokenCount":0}}"""
                            }
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("synthetic transport sentinel; no live model")
                            .body(body.toResponseBody())
                            .build()
                    }.build(),
            )
        val provider: PreSubmissionCheckProvider =
            if (kind == "LUNA") {
                LunaPreSubmissionCheckProvider.runtime(
                    OpenAiProperties(apiKey = "synthetic-time-policy-key"),
                    mapper,
                    PrecheckProviderRuntimeProperties(enabled = true),
                    transport,
                )
            } else {
                GeminiPreSubmissionCheckProvider.runtime(
                    GeminiProperties(apiKey = "synthetic-time-policy-key"),
                    mapper,
                    PrecheckProviderRuntimeProperties(enabled = true),
                    transport,
                )
            }

        fun generation(): JsonNode = generations.single()
    }
}
