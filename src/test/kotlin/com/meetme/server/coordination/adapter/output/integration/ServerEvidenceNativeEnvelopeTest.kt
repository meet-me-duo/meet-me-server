package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.DispatchBudget
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as R
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Real main transport and loopback HTTP framing; no provider, credentials, DB authority, or language oracle. */
class ServerEvidenceNativeEnvelopeTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val rawLimit = 262_144
    private val envelopeLimit = 1_048_576
    private val model = "gpt-6-luna"
    private val modelJson = " \n{\"synthetic\":\"가상 🙂\",\"conditions\":[]}\n "

    @Test
    fun `SNE01 native catalogue route admits a larger API envelope while preserving original small model JSON and usage`() {
        val bytes = envelope(modelJson, 350 * 1024)
        val frozen = bytes.copyOf()
        assertTrue(bytes.size > rawLimit && bytes.size < envelopeLimit)
        val result = exchange(catalog = true, bytes)
        assertContentEquals(modelJson.toByteArray(Charsets.UTF_8), result.responseJson.toByteArray(Charsets.UTF_8))
        assertContentEquals(frozen, bytes)
        assertEquals(bytes.size, result.usage.responseBytes)
        assertEquals(11L, result.usage.inputTokens)
        assertEquals(13L, result.usage.outputTokens)
        assertEquals(AnalysisProvider.OPENAI, result.provider)
        assertEquals(model, result.model)
    }

    @Test
    fun `SNE02 old frozen OpenAI route retains its 256 KiB envelope rejection for the same large framing`() {
        invalid { exchange(catalog = false, envelope(modelJson, 350 * 1024)) }
    }

    private fun exchange(
        catalog: Boolean,
        bytes: ByteArray,
    ): ScheduleDslProviderResponse =
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(okio.Buffer().write(bytes)),
            )
            val budget = DispatchBudget().apply { permitDeadline = R.selectedAt.plusSeconds(40) }
            val gate = AiHttpDispatchGate(AiDispatchGateProperties(true), budget, Clock.fixed(R.selectedAt, ZoneOffset.UTC))
            val transport = SelectedScheduleDslHttpTransport(mapper, gate)
            try {
                transport.generate(
                    request(catalog),
                    AnalysisProvider.OPENAI,
                    model,
                    "synthetic-wire-only",
                    URI("http://127.0.0.1:${server.port}"),
                )
            } finally {
                assertEquals(1, server.requestCount)
                assertEquals(1, budget.reserves.size)
                assertEquals(1, budget.claims.size)
            }
        }

    private fun request(catalog: Boolean): SelectedScheduleDslProviderRequest {
        val input = N.input()
        val capsule = N.capsule(input)
        val responseContract = if (catalog) SERVER_EVIDENCE_RESPONSE_CONTRACT else SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
        val identity = IrExecutionIdentity(responseContract, SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION, input.policy.version)
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                identity,
                input.policy,
                capsule.snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        val invocation = R.invocation()
        val attempt =
            CoordinationAttempt(
                UUID(0, 25001),
                invocation.runId,
                1,
                R.selectedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                AnalysisProvider.OPENAI,
                model,
                invocation.policyVersion,
                invocation.id,
            )
        val zone = ZoneId.of(input.timeZone)
        val inputs =
            input.inputs.sortedBy { it.participantId }.map {
                AiDispatchInputSnapshot(
                    it.submissionVersionId.toString(),
                    it.rawText,
                    it.locale,
                    requireNotNull(it.evidence).referenceAt.atZone(zone).toLocalDate(),
                    it.evidence,
                )
            }
        val execution =
            AiBudgetExecution(
                input.roomId,
                AiBudgetExecutionKind.FINAL_ANALYSIS,
                invocation.id,
                invocation.runVersion,
                invocation.ownerToken,
                invocation.runVersion,
                AiDispatchFingerprints.batchSource(inputs),
                AiDispatchFingerprints.batchContext(zone, input.searchStart, input.searchEnd, inputs),
            )
        return SelectedScheduleDslProviderRequest(
            FinalAnalysisAttemptBinding(R.route(choice), invocation),
            attempt,
            capsule,
            inputs,
            AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(60).toNanos(), attempt.id, 1),
            Duration.ofSeconds(15),
        )
    }

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    private fun envelope(
        text: String,
        targetSize: Int? = null,
    ): ByteArray {
        fun encoded(padding: Int) =
            mapper.writeValueAsBytes(
                mapOf(
                    "status" to "completed",
                    "error" to null,
                    "incomplete_details" to null,
                    "model" to model,
                    "text" to mapOf("format" to mapOf("schema" to mapOf("description" to "x".repeat(padding)))),
                    "output" to
                        listOf(
                            mapOf(
                                "type" to "message",
                                "role" to "assistant",
                                "status" to "completed",
                                "content" to listOf(mapOf("type" to "output_text", "text" to text, "refusal" to null)),
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
