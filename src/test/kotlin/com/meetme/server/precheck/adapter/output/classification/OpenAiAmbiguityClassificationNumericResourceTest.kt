package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DC06 numeric-resource bound: precision <=1000 and scale -1000..1000 before probability arithmetic. */
class OpenAiAmbiguityClassificationNumericResourceTest {
    @ParameterizedTest
    @CsvSource(
        "PROB_NEGATIVE_EXPONENT,CLEAR",
        "PROB_NEGATIVE_EXPONENT,INDETERMINATE",
        "PROB_ZERO_NEGATIVE_EXPONENT,CLEAR",
        "PROB_ZERO_NEGATIVE_EXPONENT,INDETERMINATE",
        "PROB_ZERO_POSITIVE_EXPONENT,CLEAR",
        "PROB_ZERO_POSITIVE_EXPONENT,INDETERMINATE",
        "PROB_PRECISION,CLEAR",
        "PROB_PRECISION,INDETERMINATE",
        "CONF_NEGATIVE_EXPONENT,CLEAR",
        "CONF_NEGATIVE_EXPONENT,INDETERMINATE",
        "CONF_ZERO_NEGATIVE_EXPONENT,CLEAR",
        "CONF_ZERO_NEGATIVE_EXPONENT,INDETERMINATE",
        "CONF_ZERO_POSITIVE_EXPONENT,CLEAR",
        "CONF_ZERO_POSITIVE_EXPONENT,INDETERMINATE",
        "CONF_PRECISION,CLEAR",
        "CONF_PRECISION,INDETERMINATE",
    )
    fun `DCN01 excessive decimal resources are invalid before any assessment shortcut`(
        kind: String,
        choice: String,
    ) {
        // 1001 is deliberately cheap: this reproduces the missing bound without executing OOM-sized arithmetic.
        val primary = if (kind == "PROB_PRECISION") "1." + "0".repeat(1000) else "1"
        val secondary =
            when (kind) {
                "PROB_NEGATIVE_EXPONENT" -> "1e-1001"
                "PROB_ZERO_NEGATIVE_EXPONENT" -> "0e-1001"
                "PROB_ZERO_POSITIVE_EXPONENT" -> "0e1001"
                else -> "0"
            }
        val confidence =
            when (kind) {
                "CONF_NEGATIVE_EXPONENT" -> "1e-1001"
                "CONF_ZERO_NEGATIVE_EXPONENT" -> "0e-1001"
                "CONF_ZERO_POSITIVE_EXPONENT" -> "0e1001"
                "CONF_PRECISION" -> "1." + "0".repeat(1000)
                else -> "0.9"
            }
        val body = response(choice, primary, secondary, "0", confidence)
        assertTrue(body.toByteArray().size < 16 * 1024, "A small valid JSON body must not bypass numeric resource bounds")
        DecisionClassificationFixtures.Endpoint(body = body).use { endpoint ->
            val failure =
                assertFailsWith<AmbiguityClassificationException> {
                    classifier(endpoint, kind.contains("PRECISION")).classify(DecisionClassificationFixtures.request)
                }
            assertEquals("INVALID_RESPONSE", failure.failureCode)
            assertEquals("INVALID_RESPONSE", failure.message)
            assertEquals(1, endpoint.sent.size)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "PROB_SCALE_POSITIVE_1000,CLEAR",
        "PROB_SCALE_POSITIVE_1000,INDETERMINATE",
        "PROB_SCALE_NEGATIVE_1000,CLEAR",
        "PROB_SCALE_NEGATIVE_1000,INDETERMINATE",
        "PROB_PRECISION_1000,CLEAR",
        "PROB_PRECISION_1000,INDETERMINATE",
        "CONF_SCALE_POSITIVE_1000,CLEAR",
        "CONF_SCALE_POSITIVE_1000,INDETERMINATE",
        "CONF_SCALE_NEGATIVE_1000,CLEAR",
        "CONF_SCALE_NEGATIVE_1000,INDETERMINATE",
        "CONF_PRECISION_1000,CLEAR",
        "CONF_PRECISION_1000,INDETERMINATE",
        "ORDINARY,CLEAR",
        "ORDINARY,INDETERMINATE",
        "EXACT_TOLERANCE,CLEAR",
        "EXACT_TOLERANCE,INDETERMINATE",
    )
    fun `DCN02 supported boundary decimals and inclusive sum tolerance retain valid assessments`(
        kind: String,
        choice: String,
    ) {
        val primary =
            when (kind) {
                "PROB_PRECISION_1000" -> "1." + "0".repeat(999)
                "ORDINARY" -> "0.8"
                else -> "1"
            }
        val secondary =
            when (kind) {
                "PROB_SCALE_POSITIVE_1000" -> "1e-1000"
                "PROB_SCALE_NEGATIVE_1000" -> "0e1000"
                "ORDINARY" -> "0.1"
                "EXACT_TOLERANCE" -> "0.000001"
                else -> "0"
            }
        val third = if (kind == "ORDINARY") "0.1" else "0"
        val confidence =
            when (kind) {
                "CONF_SCALE_POSITIVE_1000" -> "1e-1000"
                "CONF_SCALE_NEGATIVE_1000" -> "0e1000"
                "CONF_PRECISION_1000" -> "0.9" + "0".repeat(998) + "1"
                else -> "0.9"
            }
        DecisionClassificationFixtures.Endpoint(body = response(choice, primary, secondary, third, confidence)).use { endpoint ->
            val result =
                assertDoesNotThrow<AmbiguityClassificationResult> {
                    classifier(endpoint, kind.contains("PRECISION")).classify(DecisionClassificationFixtures.request)
                }
            assertEquals(AmbiguityAssessment.valueOf(choice), result.assessment)
            assertEquals(if (choice == "INDETERMINATE") AmbiguityClassificationReason.MODEL_UNCERTAIN else null, result.reason)
            assertEquals(137L, result.inputTokens)
            assertEquals(7L, result.outputTokens)
            assertTrue(result.usageComplete)
            assertEquals(1, endpoint.sent.size)
        }
    }

    @ParameterizedTest
    @CsvSource("CLEAR", "INDETERMINATE")
    fun `DCN03 resource hardening does not relax the existing sum tolerance`(choice: String) {
        DecisionClassificationFixtures.Endpoint(body = response(choice, "1", "0.000001000001", "0", "0.9")).use { endpoint ->
            assertEquals(
                "INVALID_RESPONSE",
                assertFailsWith<AmbiguityClassificationException> {
                    endpoint.classifier().classify(DecisionClassificationFixtures.request)
                }.failureCode,
            )
            assertEquals(1, endpoint.sent.size)
        }
    }

    private fun classifier(
        endpoint: DecisionClassificationFixtures.Endpoint,
        expandedNumericParser: Boolean,
    ): OpenAiAmbiguityClassifier {
        if (!expandedNumericParser) return endpoint.classifier(threshold = 0.0)
        // Only this new test's explicitly injected mapper allows the 1000/1001 precision boundary.
        // Production/default Jackson number-length constraints and the original shared fixture stay unchanged.
        val mapper =
            JsonMapper
                .builder(
                    JsonFactory
                        .builder()
                        .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(4096).build())
                        .build(),
                ).build()
        return OpenAiAmbiguityClassifier(
            DecisionClassificationFixtures.KEY,
            mapper,
            AmbiguityClassifierOptions(endpoint.endpoint, enabled = true, minimumConfidence = 0.0),
            meterRegistry = endpoint.registry,
        )
    }

    private fun response(
        choice: String,
        primary: String,
        secondary: String,
        third: String,
        confidence: String,
    ): String {
        val probabilities =
            listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").joinToString(",", "[", "]") { value ->
                val probability =
                    when (value) {
                        choice -> primary
                        "NEEDS_REVIEW" -> secondary
                        else -> third
                    }
                """{"value":"$value","probability":$probability}"""
            }
        return DecisionClassificationFixtures.envelope(DecisionClassificationFixtures.answer(choice, confidence, probabilities))
    }
}
