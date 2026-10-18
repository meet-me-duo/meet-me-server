package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierOptions
import com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier
import com.meetme.server.precheck.adapter.output.provider.GeminiPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.application.port.output.AiCallBudgetRequest
import com.meetme.server.shared.application.port.output.AiCallBudgetReservation
import com.meetme.server.shared.application.port.output.AiCallObservation
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Actual A/count/B transport chain, not fake verdict-to-method assertions. */
class Issue123TwoStagePhysicalDispatchTest {
    private val raw = "  synthetic unchanged text  "
    private val date = LocalDate.of(2026, 10, 18)
    private val mapper = JsonMapper.builder().build()
    private val legacy =
        object : AiCallBudgetPort {
            override fun reserve(request: AiCallBudgetRequest): AiCallBudgetReservation? =
                error("Physical mode must not reserve old outer budget")

            override fun record(
                reservation: AiCallBudgetReservation,
                observation: AiCallObservation,
            ) {
                error("Physical mode must not record old outer bill")
            }
        }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["CLEAR", "NEEDS_REVIEW", "LOW_CONFIDENCE", "MODEL_UNCERTAIN"])
    fun `AD04 real two stage pipeline charges only actual physical children and never old outer reservation`(assessment: String) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { path, _ -> 200 to response(path, assessment) }).use { server ->
            val result = provider(server, budget).check(request())
            assertEquals(PrecheckVerdict.CLEAR, result.verdict)
            val expected =
                if (assessment == "CLEAR") {
                    listOf(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                } else {
                    listOf(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        AiPhysicalOperation.PRECHECK_COUNT,
                        AiPhysicalOperation.PRECHECK_GENERATION,
                    )
                }
            assertEquals(expected, budget.reserves.flatMap { it.calls }.map { it.operation })
            assertEquals(expected.size, server.requests.size)
            assertEquals(expected.size, budget.claims.size)
            assertEquals(expected.size, budget.observations.size)
            assertEquals(if (assessment == "CLEAR") 1 else 2, budget.reserves.size, "Count/generation child group is reserved once")
            assertTrue(budget.reserves.all { it.execution.roomId == UUID(0, 123) && it.execution.generation == 7L })
        }
    }

    @Test
    fun `AD02 physical default deny never falls back to legacy stage or generator`() {
        val budget = DispatchBudget().apply { denyReserve = true }
        DispatchEndpoint(response = { path, _ -> 200 to response(path, "NEEDS_REVIEW") }).use { server ->
            assertEquals(
                "CONFIGURATION",
                assertFailsWith<PrecheckProviderException> { provider(server, budget).check(request()) }.failureCode,
            )
            assertEquals(1, budget.reserves.size)
            assertTrue(server.requests.isEmpty())
        }
    }

    private fun request(): PrecheckProviderRequest {
        val context =
            dispatchContext().let { original ->
                original.copy(
                    execution =
                        original.execution.copy(
                            sourceFingerprint = dispatchFramedHash(raw),
                            contextFingerprint =
                                dispatchFramedHash(
                                    dispatchNow.toString(),
                                    "Asia/Seoul",
                                    "ko-KR",
                                    date.toString(),
                                    date.plusDays(5).toString(),
                                ),
                        ),
                )
            }
        return PrecheckProviderRequest(
            UUID(0, 124),
            raw,
            dispatchNow,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(5),
            Duration.ofSeconds(3),
            roomId = UUID(0, 123),
            executionGeneration = 7,
            dispatchContext = context,
        )
    }

    private fun provider(
        server: DispatchEndpoint,
        budget: DispatchBudget,
    ): TwoStagePreSubmissionCheckProvider {
        val gate = gate(budget)
        val classifier =
            OpenAiAmbiguityClassifier(
                "synthetic-key",
                mapper,
                AmbiguityClassifierOptions(server.uri("/v1/decisions"), true, 0.8),
                dispatchGate = gate,
            )
        val generator =
            GeminiPreSubmissionCheckProvider.runtime(
                GeminiProperties(apiKey = "synthetic-key"),
                mapper,
                PrecheckProviderRuntimeProperties(true),
                endpoint = server.origin,
                dispatchGate = gate,
            )
        return TwoStagePreSubmissionCheckProvider(classifier, generator, legacy, physicalDispatch = gate)
    }

    private fun response(
        path: String,
        assessment: String,
    ): String {
        if (path.endsWith(":countTokens")) return "{\"totalTokens\":256}"
        if (path.endsWith(":generateContent")) {
            return mapper.writeValueAsString(
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "finishReason" to "STOP",
                                "content" to mapOf("parts" to listOf(mapOf("text" to "{\"verdict\":\"CLEAR\"}"))),
                            ),
                        ),
                    "usageMetadata" to mapOf("promptTokenCount" to 256, "candidatesTokenCount" to 7, "thoughtsTokenCount" to 0),
                ),
            )
        }
        val choice =
            when (assessment) {
                "LOW_CONFIDENCE" -> "CLEAR"
                "MODEL_UNCERTAIN" -> "INDETERMINATE"
                else -> assessment
            }
        val confidence = if (assessment == "LOW_CONFIDENCE") 0.6 else 0.9
        val probabilities =
            listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").map {
                mapOf(
                    "value" to it,
                    "probability" to if (it == choice) 0.8 else 0.1,
                )
            }
        return mapper.writeValueAsString(
            mapOf(
                "model" to "gpt-6-luna",
                "answers" to
                    listOf(
                        mapOf(
                            "type" to "choice",
                            "name" to "material_ambiguity",
                            "choice" to choice,
                            "confidence" to confidence,
                            "probabilities" to probabilities,
                        ),
                    ),
                "usage" to mapOf("input_tokens" to 137, "output_tokens" to 7, "total_tokens" to 144),
            ),
        )
    }
}
