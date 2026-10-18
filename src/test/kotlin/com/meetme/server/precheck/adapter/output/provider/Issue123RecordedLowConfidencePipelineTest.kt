package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.shared.application.port.output.AiCallStage
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recorded synthetic-input response replay only: no new model calls, threshold tuning or production activation. */
class Issue123RecordedLowConfidencePipelineTest {
    @ParameterizedTest
    @EnumSource(PrecheckVerdict::class, names = ["NEEDS_CHOICE", "CLEAR"])
    fun `LIVE KO03 raw CLEAR remains low confidence and only the generator decides the pipeline result`(verdict: PrecheckVerdict) {
        // Existing e83d5280 fixture default is 0.8, frozen before LIVE-KO-03; this is not production policy.
        assertEquals(
            RESPONSE_SHA256,
            MessageDigest.getInstance("SHA-256").digest(RECORDED_RESPONSE.toByteArray()).joinToString("") {
                "%02x".format(it)
            },
        )
        val mapper = DecisionClassificationFixtures.mapper
        assertEquals("CLEAR", mapper.readTree(RECORDED_RESPONSE)["answers"][0]["choice"].asText())
        val input =
            TwoStageFixtures.request().copy(
                rawText = "10월 24일 토요일 8시부터 9시까지 가능해요.",
                referenceAt = Instant.parse("2026-10-18T04:00:00Z"),
                searchStartDate = LocalDate.of(2026, 10, 24),
                searchEndDate = LocalDate.of(2026, 10, 25),
            )
        val expected =
            PrecheckProviderResult(
                verdict,
                prompt = if (verdict == PrecheckVerdict.NEEDS_CHOICE) "오전인가요, 오후인가요?" else null,
                sourceExcerpt = if (verdict == PrecheckVerdict.NEEDS_CHOICE) "8시부터 9시" else null,
                choices =
                    if (verdict == PrecheckVerdict.NEEDS_CHOICE) {
                        listOf(
                            PrecheckChoice("AM", "오전", "10월 24일 토요일 오전 8시부터 9시까지 가능해요."),
                            PrecheckChoice("PM", "오후", "10월 24일 토요일 오후 8시부터 9시까지 가능해요."),
                        )
                    } else {
                        emptyList()
                    },
                inputTokens = 73,
                outputTokens = 9,
                provider = "SYNTHETIC_RECORDING_GENERATOR",
                model = "synthetic-recheck",
            )
        DecisionClassificationFixtures.Endpoint(body = RECORDED_RESPONSE).use { endpoint ->
            val actualClassifier = endpoint.classifier()
            val normalized = mutableListOf<AmbiguityClassificationResult>()
            var generated = 0
            val budget = TwoStageFakeBudget()
            val actualPipeline =
                TwoStagePreSubmissionCheckProvider(
                    AmbiguityClassificationPort { actualClassifier.classify(it).also(normalized::add) },
                    PreSubmissionCheckProvider {
                        generated++
                        assertEquals(input.copy(timeout = it.timeout), it)
                        assertTrue(it.timeout > java.time.Duration.ZERO && it.timeout <= input.timeout)
                        expected
                    },
                    budget,
                )
            val result = actualPipeline.check(input)
            assertEquals(expected, result)
            assertEquals(1, generated)
            assertEquals(1, endpoint.sent.size)
            val classification = normalized.single()
            assertEquals(AmbiguityAssessment.INDETERMINATE, classification.assessment)
            assertEquals(AmbiguityClassificationReason.LOW_CONFIDENCE, classification.reason)
            assertEquals(0.06, classification.confidence)
            assertEquals(512L, classification.inputTokens)
            assertEquals(0L, classification.outputTokens)
            assertTrue(classification.usageComplete)
            assertEquals(listOf(AiCallStage.CLASSIFICATION, AiCallStage.GENERATION), budget.requests.map { it.stage })
            assertEquals(
                512L,
                budget.observations
                    .first()
                    .second.inputTokens,
            )
            val sentInput = mapper.readTree(mapper.readTree(endpoint.sent.single().body)["input"].asText())
            assertEquals(input.rawText, sentInput["raw_text"].asText())
            assertEquals(input.referenceAt.toString(), sentInput["reference_at"].asText())
        }
    }

    private companion object {
        const val RESPONSE_SHA256 = "7f6d7d7267a016e5f4b08426421936725122f674e6158e030abde4b1bd01609c"
        const val RECORDED_RESPONSE =
            """{"model":"gpt-6-luna","answers":[{"type":"choice","name":"material_ambiguity","choice":"CL""" +
                """EAR","probabilities":[{"value":"CLEAR","probability":0.37},{"value":"NEEDS_REVIEW","probab""" +
                """ility":0.31},{"value":"INDETERMINATE","probability":0.32}],"confidence":0.06}],"usage":{"i""" +
                """nput_tokens":512,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},"output""" +
                """_tokens":0,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":512}}"""
    }
}
