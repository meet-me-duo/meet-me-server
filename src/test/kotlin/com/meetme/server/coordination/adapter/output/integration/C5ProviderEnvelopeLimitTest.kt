package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Synthetic API framing is independent of the original model JSON's unchanged UTF-8 byte limit. */
class C5ProviderEnvelopeLimitTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val budget = Mockito.mock(DurableAiBudgetPort::class.java)
    private val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
    private val dslLimit = 256 * 1024
    private val envelopeLimit = 1024 * 1024
    private val model = "gpt-6-luna"
    private val modelJson = " \n{\"synthetic\":\"가상 🙂\",\"conditions\":[]}\n "

    private fun transport(candidate: Boolean) =
        C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true, placeConstraintsCandidate = candidate)

    @Test
    fun `CEL01 place candidate admits a larger schema echo envelope while preserving original small model JSON and usage`() {
        val envelope = envelope(modelJson, targetSize = 300 * 1024)
        val frozen = envelope.copyOf()
        assertTrue(envelope.size > dslLimit && envelope.size < envelopeLimit)
        val result = transport(true).decodeCollectedResponse(envelope, AnalysisProvider.OPENAI, model)
        assertContentEquals(modelJson.toByteArray(Charsets.UTF_8), result.responseJson.toByteArray(Charsets.UTF_8))
        assertContentEquals(frozen, envelope)
        assertEquals(AnalysisProvider.OPENAI, result.provider)
        assertEquals(model, result.model)
        assertEquals(envelope.size, result.usage.responseBytes)
        assertEquals(11L, result.usage.inputTokens)
        assertEquals(13L, result.usage.outputTokens)
        assertFailsWith<IllegalArgumentException> { transport(false).decodeCollectedResponse(envelope, AnalysisProvider.OPENAI, model) }
        assertFailsWith<IllegalArgumentException> {
            C5EvaluationSelectedHttpTransport(mapper, gate).decodeCollectedResponse(envelope, AnalysisProvider.OPENAI, model)
        }
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `CEL02 larger API framing allowance never admits model JSON beyond the existing UTF8 DSL limit`() {
        val tooLargeModelJson = "{\"synthetic\":\"" + "가".repeat(90 * 1024) + "\"}"
        assertTrue(tooLargeModelJson.length < dslLimit)
        assertTrue(tooLargeModelJson.toByteArray(Charsets.UTF_8).size > dslLimit)
        val envelope = envelope(tooLargeModelJson)
        assertTrue(envelope.size < envelopeLimit)
        assertFailsWith<IllegalArgumentException> { transport(true).decodeCollectedResponse(envelope, AnalysisProvider.OPENAI, model) }
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `CEL03 place candidate rejects one byte beyond the one MiB API envelope even with tiny model JSON`() {
        val envelope = envelope(modelJson, targetSize = envelopeLimit + 1)
        val frozen = envelope.copyOf()
        assertFailsWith<IllegalArgumentException> { transport(true).decodeCollectedResponse(envelope, AnalysisProvider.OPENAI, model) }
        assertContentEquals(frozen, envelope)
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `CEL04 explicit framing boundaries retain one MiB opt in and original 256 KiB prototype admission`() {
        val maximalCandidate = envelope(modelJson, targetSize = envelopeLimit)
        assertEquals(modelJson, transport(true).decodeCollectedResponse(maximalCandidate, AnalysisProvider.OPENAI, model).responseJson)
        val maximalPrototype = envelope(modelJson, targetSize = dslLimit)
        assertEquals(modelJson, transport(false).decodeCollectedResponse(maximalPrototype, AnalysisProvider.OPENAI, model).responseJson)
        val oversizedPrototype = envelope(modelJson, targetSize = dslLimit + 1)
        assertFailsWith<IllegalArgumentException> {
            transport(
                false,
            ).decodeCollectedResponse(oversizedPrototype, AnalysisProvider.OPENAI, model)
        }
        Mockito.verifyNoInteractions(budget)
    }

    private fun envelope(
        originalModelText: String,
        targetSize: Int? = null,
    ): ByteArray {
        fun encoded(padding: Int): ByteArray =
            mapper.writeValueAsBytes(
                linkedMapOf(
                    "status" to "completed",
                    "error" to null,
                    "incomplete_details" to null,
                    "model" to model,
                    "text" to
                        mapOf(
                            "format" to
                                mapOf(
                                    "type" to "json_schema",
                                    "name" to "synthetic-response",
                                    "schema" to mapOf("type" to "object", "description" to "x".repeat(padding)),
                                ),
                        ),
                    "output" to
                        listOf(
                            mapOf(
                                "type" to "message",
                                "role" to "assistant",
                                "status" to "completed",
                                "content" to listOf(mapOf("type" to "output_text", "text" to originalModelText, "refusal" to null)),
                            ),
                        ),
                    "usage" to mapOf("input_tokens" to 11, "output_tokens" to 13),
                ),
            )
        val unpadded = encoded(0)
        if (targetSize == null) return unpadded
        require(targetSize >= unpadded.size)
        return encoded(targetSize - unpadded.size).also { assertEquals(targetSize, it.size) }
    }
}
