package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline product wire reuse; no HTTP, Spring, PostgreSQL, SDK, environment keys or real model. */
class ScheduleDslGeminiWireTest {
    private val w = ScheduleDslWireFixture
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `DSLWIRE01 Gemini transport wraps exact neutral prompt and the agreed schema and generation config`() {
        val snapshot = w.f.ir.snapshot()
        val bytes = ScheduleDslGeminiWire.requestBytes(mapper, snapshot, "gemini-3.8-flash")
        val request = mapper.readTree(bytes)
        val content = request.path("contents").single()
        assertEquals("user", content.path("role").asString())
        val text = content.path("parts").single().path("text")
        assertTrue(text.isString)
        assertContentEquals(
            ScheduleDslProviderContract(
                mapper,
            ).requestBytes(snapshot, SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION),
            requireNotNull(text.asString()).toByteArray(Charsets.UTF_8),
        )
        val config = request.path("generationConfig")
        assertEquals("application/json", config.path("responseMimeType").asString())
        assertEquals(
            mapper.readTree(mapper.writeValueAsBytes(ScheduleDslProviderContract(mapper).responseSchema())),
            config.path("responseJsonSchema"),
        )
        assertEquals(1, config.path("candidateCount").asInt())
        assertEquals(32768, config.path("maxOutputTokens").asInt())
        assertEquals("LOW", config.path("thinkingConfig").path("thinkingLevel").asString())
    }

    @Test
    fun `DSLWIRE02 real Gemini response envelope retains exact DSL text and usage for the same strict parser`() {
        val json = w.json()
        val bytes = envelope(json).toByteArray(Charsets.UTF_8)
        val response = ScheduleDslGeminiWire.response(mapper, bytes)
        assertEquals(json, response.responseJson)
        assertEquals(AnalysisProvider.GEMINI, response.provider)
        assertEquals("gemini-3.8-flash", response.model)
        assertEquals(10L, response.usage.inputTokens)
        assertEquals(20L, response.usage.outputTokens)
        assertEquals(bytes.size, response.usage.responseBytes)
        assertEquals(
            w.f.envelope(w.claims, w.program),
            ScheduleDslProviderContract(
                mapper,
            ).parseProviderResponse(response.responseJson, w.f.ir.snapshot(), SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["malformed", "missing_candidates", "missing_text", "truncated_generation"])
    fun `DSLWIRE03 invalid transport response cannot be accepted as an empty successful DSL response`(defect: String) {
        val raw =
            when (defect) {
                "malformed" -> "{SYNTHETIC_PRIVATE_WIRE_MARKER:not JSON}"
                "missing_candidates" -> "{}"
                "missing_text" ->
                    w.encode(
                        mapOf(
                            "candidates" to listOf(mapOf("content" to mapOf("parts" to emptyList<String>()), "finishReason" to "STOP")),
                        ),
                    )
                else -> envelope(w.json(), "MAX_TOKENS")
            }
        val failure = assertFailsWith<RuntimeException> { ScheduleDslGeminiWire.response(mapper, raw.toByteArray(Charsets.UTF_8)) }
        assertFalse(failure.toString().contains("SYNTHETIC_PRIVATE_WIRE_MARKER"))
        assertFalse(failure.toString().contains(w.f.ir.FIRST_TEXT))
    }

    private fun envelope(
        json: String,
        finish: String = "STOP",
    ) = w.encode(
        mapOf(
            "candidates" to
                listOf(mapOf("content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to json))), "finishReason" to finish)),
            "usageMetadata" to mapOf("promptTokenCount" to 10, "candidatesTokenCount" to 20, "totalTokenCount" to 30),
            "modelVersion" to "gemini-3.8-flash",
        ),
    )
}
