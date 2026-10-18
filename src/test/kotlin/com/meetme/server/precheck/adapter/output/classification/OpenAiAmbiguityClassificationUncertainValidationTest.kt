package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** DC06-07: MODEL_UNCERTAIN is a validated choice, not a shortcut around mandatory wire checks. */
class OpenAiAmbiguityClassificationUncertainValidationTest {
    @ParameterizedTest
    @ValueSource(strings = ["MISSING_CONFIDENCE", "STRING", "NEGATIVE", "ABOVE_ONE", "NONFINITE", "MISSING_PROBABILITIES", "INVALID_SUM"])
    fun `DCU01 explicit uncertainty still validates confidence and complete probabilities`(invalid: String) {
        val valid = DecisionClassificationFixtures.answer("INDETERMINATE", confidence = "0.1")
        val answer =
            when (invalid) {
                "MISSING_CONFIDENCE" -> valid.replace("\"confidence\":0.1,", "")
                "STRING" -> DecisionClassificationFixtures.answer("INDETERMINATE", confidence = "\"0.1\"")
                "NEGATIVE" -> DecisionClassificationFixtures.answer("INDETERMINATE", confidence = "-0.1")
                "ABOVE_ONE" -> DecisionClassificationFixtures.answer("INDETERMINATE", confidence = "1.1")
                "NONFINITE" -> DecisionClassificationFixtures.answer("INDETERMINATE", confidence = "1e309")
                "MISSING_PROBABILITIES" -> """{"type":"choice","name":"material_ambiguity","choice":"INDETERMINATE","confidence":0.1}"""
                else -> valid.replace("\"probability\":0.8", "\"probability\":0.7")
            }
        DecisionClassificationFixtures.Endpoint(body = DecisionClassificationFixtures.envelope(answer)).use { endpoint ->
            val error =
                assertFailsWith<AmbiguityClassificationException> {
                    endpoint.classifier().classify(DecisionClassificationFixtures.request)
                }
            assertEquals("INVALID_RESPONSE", error.failureCode)
            assertEquals(1, endpoint.sent.size)
        }
    }
}
