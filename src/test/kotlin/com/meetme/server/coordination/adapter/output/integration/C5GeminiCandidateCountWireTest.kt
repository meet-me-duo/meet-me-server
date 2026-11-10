package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.DispatchBudget
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
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
import kotlin.test.assertFalse
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.FinalAnalysisRouteTestFixture as R
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic loopback wire only. In-memory budget fixture does not prove native DB authority or model quality. */
class C5GeminiCandidateCountWireTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `CC01 C5 exact Gemini wire omits redundant candidateCount and preserves every other byte`() {
        val fixture = fixture(5, AnalysisProvider.GEMINI)
        val body = exchange(fixture)
        assertFalse(mapper.readTree(body).path("generationConfig").has("candidateCount"))
        assertContentEquals(mapper.writeValueAsBytes(expectedGemini(fixture)), body)
    }

    @Test
    fun `CC02 C2 C3 C4 Gemini retain complete wire and selected legacy still refuses before HTTP`() {
        for (version in 2..4) {
            val fixture = fixture(version, AnalysisProvider.GEMINI)
            val body = exchange(fixture)
            assertEquals(
                1,
                mapper
                    .readTree(body)
                    .path("generationConfig")
                    .path("candidateCount")
                    .asInt(),
            )
            assertContentEquals(mapper.writeValueAsBytes(expectedGemini(fixture)), body, "C$version")
        }
        val fixture = fixture(5, AnalysisProvider.GEMINI)
        val legacy =
            fixture.copy(
                request =
                    fixture.request.copy(
                        binding =
                            fixture.request.binding.copy(
                                route =
                                    fixture.request.binding.route
                                        .copy(choice = FinalAnalysisRouteChoice.Legacy),
                            ),
                    ),
            )
        exchange(legacy, refused = true)
    }

    @Test
    fun `CC03 C5 Luna trusted instructions low reasoning schema and all generation fields remain exact`() {
        val fixture = fixture(5, AnalysisProvider.OPENAI)
        val neutral = mapper.readValue(fixture.neutral, Map::class.java)
        val expected =
            linkedMapOf(
                "model" to "gpt-6-luna",
                "store" to false,
                "input" to mapper.writeValueAsString(neutral - "prompt"),
                "max_output_tokens" to 32768,
                "text" to
                    mapOf(
                        "format" to
                            mapOf(
                                "type" to "json_schema",
                                "name" to "meet_me_schedule_dsl_v5",
                                "strict" to true,
                                "schema" to fixture.schema,
                            ),
                    ),
                "instructions" to neutral["prompt"],
                "reasoning" to mapOf("effort" to "low"),
            )
        assertContentEquals(mapper.writeValueAsBytes(expected), exchange(fixture))
    }

    private data class Fixture(
        val version: Int,
        val provider: AnalysisProvider,
        val request: SelectedScheduleDslProviderRequest,
        val neutral: ByteArray,
        val schema: Map<String, Any?>,
    )

    private fun expectedGemini(fixture: Fixture): Map<String, Any?> {
        val configuration =
            linkedMapOf<String, Any?>(
                "responseMimeType" to "application/json",
                "responseJsonSchema" to
                    if (fixture.version == 5) GeminiResponseSchemaProjection.project(fixture.schema) else fixture.schema,
            ).apply {
                if (fixture.version != 5) put("candidateCount", 1)
                put("maxOutputTokens", 32768)
                put("thinkingConfig", mapOf("thinkingLevel" to "LOW"))
            }
        return linkedMapOf(
            "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to fixture.neutral.toString(Charsets.UTF_8))))),
            "generationConfig" to configuration,
        )
    }

    private fun fixture(
        version: Int,
        provider: AnalysisProvider,
    ): Fixture {
        val input =
            N.input().copy(
                policy =
                    when (version) {
                        2 -> F.q.wire.f.policy
                        3 -> ApprovedScheduleDslMatchingPolicy.contextual()
                        else -> N.policy
                    },
            )
        val capsule = N.capsule(input)
        val identity = IrExecutionIdentity("schedule-dsl-response-$version", "schedule-ir-product-${version + 1}", input.policy.version)
        val choice =
            when (version) {
                2 -> FinalAnalysisRouteChoice.DslContextV2(identity, input.policy, capsule.snapshot.context, input.meetingMode)
                3 ->
                    FinalAnalysisRouteChoice.DslApprovedTermsV3(
                        identity,
                        input.policy,
                        capsule.snapshot.context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
                4 ->
                    FinalAnalysisRouteChoice.DslContextModeV4(
                        identity,
                        input.policy,
                        capsule.snapshot.context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
                else ->
                    FinalAnalysisRouteChoice.DslStructuralV5(
                        identity,
                        input.policy,
                        capsule.snapshot.context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
            }
        val invocation = R.invocation()
        val model = if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna"
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
                provider,
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
        val request =
            SelectedScheduleDslProviderRequest(
                FinalAnalysisAttemptBinding(R.route(choice), invocation),
                attempt,
                capsule,
                inputs,
                AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(60).toNanos(), attempt.id, 1),
                Duration.ofSeconds(15),
            )
        val (neutral, schema) =
            when (version) {
                2 ->
                    ScheduleDslContextProviderContract(mapper).requestBytes(capsule) to
                        ScheduleDslProviderContract(mapper).responseSchema()
                3 ->
                    ScheduleDslApprovedTermsProviderContract(mapper).let {
                        it.requestBytes(capsule, input.meetingMode) to
                            it.responseSchema()
                    }
                4 ->
                    ScheduleDslContextModeProviderContract(
                        mapper,
                    ).let { it.requestBytes(capsule, input.meetingMode) to it.responseSchema() }
                else ->
                    ScheduleDslStructuralProviderContract(mapper).let {
                        it.requestBytes(capsule, input.meetingMode) to
                            it.responseSchema()
                    }
            }
        return Fixture(version, provider, request, neutral, schema)
    }

    private fun exchange(
        fixture: Fixture,
        refused: Boolean = false,
    ): ByteArray =
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val gemini = fixture.provider == AnalysisProvider.GEMINI
            val response =
                if (gemini) {
                    mapOf(
                        "candidates" to
                            listOf(mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("text" to "{}"))))),
                    )
                } else {
                    mapOf(
                        "status" to "completed",
                        "model" to "gpt-6-luna",
                        "output" to
                            listOf(
                                mapOf(
                                    "type" to "message",
                                    "role" to "assistant",
                                    "status" to "completed",
                                    "content" to listOf(mapOf("type" to "output_text", "text" to "{}")),
                                ),
                            ),
                    )
                }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(mapper.writeValueAsString(response)))
            val budget = DispatchBudget().apply { permitDeadline = R.selectedAt.plusSeconds(40) }
            val gate = AiHttpDispatchGate(AiDispatchGateProperties(true), budget, Clock.fixed(R.selectedAt, ZoneOffset.UTC))
            val transport = SelectedScheduleDslHttpTransport(mapper, gate)
            val endpoint = URI("http://127.0.0.1:${server.port}")
            if (refused) {
                val failure =
                    assertFailsWith<NaturalLanguageParserException> {
                        transport.generate(fixture.request, fixture.provider, "gemini-3.8-flash", "synthetic-wire-only", endpoint)
                    }
                assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
                assertEquals(0, server.requestCount)
                assertEquals(0, budget.reserves.size)
                byteArrayOf()
            } else {
                val model = if (gemini) "gemini-3.8-flash" else "gpt-6-luna"
                assertEquals(
                    "{}",
                    transport.generate(fixture.request, fixture.provider, model, "synthetic-wire-only", endpoint).responseJson,
                )
                val captured = server.takeRequest()
                assertEquals(if (gemini) "/v1beta/models/gemini-3.8-flash:generateContent" else "/v1/responses", captured.path)
                assertEquals(1, server.requestCount)
                assertEquals(1, budget.reserves.size)
                assertEquals(1, budget.claims.size)
                captured.body.readByteArray()
            }
        }
}
