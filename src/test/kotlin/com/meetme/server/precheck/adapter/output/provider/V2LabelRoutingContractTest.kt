package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.adapter.output.classification.JevAmbiguityClassifier
import com.meetme.server.precheck.adapter.output.classification.JevClassifierOptions
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.DispatchBudget
import com.meetme.server.shared.adapter.output.aihttp.DispatchEndpoint
import com.meetme.server.shared.adapter.output.aihttp.dispatchContext
import com.meetme.server.shared.adapter.output.aihttp.dispatchFramedHash
import com.meetme.server.shared.adapter.output.aihttp.dispatchNow
import com.meetme.server.shared.adapter.output.aihttp.gate
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** User decision 2026-10-30: V2 labels route; confidence is evidence, never a substitute semantic label. */
class V2LabelRoutingContractTest {
    private val mapper = JsonMapper.builder().build()
    private val raw = "다음 화요일 6시에서 8시 사이에는 가능해요. 수요일은 제외해 주세요."
    private val date = LocalDate.of(2026, 10, 18)

    @ParameterizedTest
    @CsvSource(
        "CLEAR,0.0,0.3333333333333333",
        "CLEAR,0.1,0.4",
        "CLEAR,0.8,0.8666666666666667",
        "CLEAR,1.0,1.0",
        "NEEDS_REVIEW,0.0,0.3333333333333333",
        "NEEDS_REVIEW,0.1,0.4",
        "NEEDS_REVIEW,0.8,0.8666666666666667",
        "NEEDS_REVIEW,1.0,1.0",
        "INDETERMINATE,0.0,0.3333333333333333",
        "INDETERMINATE,0.1,0.4",
        "INDETERMINATE,0.8,0.8666666666666667",
        "INDETERMINATE,1.0,1.0",
    )
    fun `V2 valid choice labels and probabilities survive low confidence exact boundary and ties`(
        label: AmbiguityAssessment,
        confidence: Double,
        maximum: Double,
    ) {
        val probabilities = distribution(label, maximum)
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope(label, confidence, probabilities) }).use { endpoint ->
            val result = classifier(endpoint, budget).classify(classification())

            assertEquals(label, result.assessment)
            assertEquals(label, result.semanticAssessment)
            assertEquals(confidence, result.confidence)
            assertEquals(probabilities.mapKeys { AmbiguityAssessment.valueOf(it.key) }, result.probabilities)
            assertEquals(
                if (label ==
                    AmbiguityAssessment.INDETERMINATE
                ) {
                    AmbiguityClassificationReason.MODEL_UNCERTAIN
                } else {
                    null
                },
                result.reason,
            )
            assertEquals(137L, result.inputTokens)
            assertEquals(7L, result.outputTokens)
            assertEquals(1, endpoint.requests.size)
            assertEquals(1, budget.observations.size)
        }
    }

    @ParameterizedTest
    @EnumSource(AmbiguityAssessment::class)
    fun `V2 label routing does not require an unused confidence action threshold`(label: AmbiguityAssessment) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope(label, 0.1, distribution(label, 0.4)) }).use { endpoint ->
            val result = classifier(endpoint, budget, threshold = null).classify(classification())

            assertEquals(label, result.assessment)
            assertEquals(0.1, result.confidence)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LOW", "TIE"])
    fun `V1 continues to normalize statistical uncertainty and preserves raw evidence`(kind: String) {
        val budget = DispatchBudget()
        val confidence = if (kind == "TIE") 0.0 else 0.1
        val probabilities = distribution(AmbiguityAssessment.CLEAR, if (kind == "TIE") 1.0 / 3.0 else 0.4)
        DispatchEndpoint(response = { _, _ -> 200 to envelope(AmbiguityAssessment.CLEAR, confidence, probabilities) }).use { endpoint ->
            val result = classifier(endpoint, budget).classify(classification(v2 = false))

            assertEquals(AmbiguityAssessment.INDETERMINATE, result.assessment)
            assertEquals(AmbiguityClassificationReason.LOW_CONFIDENCE, result.reason)
            assertEquals(AmbiguityAssessment.CLEAR, result.semanticAssessment)
            assertEquals(confidence, result.confidence)
            assertEquals(probabilities.mapKeys { AmbiguityAssessment.valueOf(it.key) }, result.probabilities)
        }
    }

    @Test
    fun `V1 still rejects a required missing threshold before HTTP`() {
        val budget = DispatchBudget()
        DispatchEndpoint().use { endpoint ->
            val failure =
                assertFailsWith<AmbiguityClassificationException> {
                    classifier(endpoint, budget, threshold = null).classify(classification(v2 = false))
                }
            assertEquals("CONFIGURATION", failure.failureCode)
            assertEquals(0, endpoint.requests.size)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "CLEAR,CLEAR,0",
        "NEEDS_REVIEW,CLEAR,1",
        "NEEDS_REVIEW,NEEDS_CHOICE,1",
        "INDETERMINATE,CLEAR,1",
        "INDETERMINATE,NEEDS_CHOICE,1",
    )
    fun `first V2 label selects a single verification stage without inventing a question`(
        label: AmbiguityAssessment,
        generatedVerdict: PrecheckVerdict,
        expectedGenerations: Int,
    ) {
        val initial = classified(label, confidence = 0.1)
        val question =
            PrecheckProviderResult(
                PrecheckVerdict.NEEDS_CHOICE,
                "오전 또는 오후인가요?",
                "6시에서 8시",
                emptyList(),
            )
        val expected = if (generatedVerdict == PrecheckVerdict.NEEDS_CHOICE) question else PrecheckProviderResult(PrecheckVerdict.CLEAR)
        var generations = 0
        var generatedRequest: PrecheckProviderRequest? = null
        val request = request()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { initial },
                PreSubmissionCheckProvider {
                    generations++
                    generatedRequest = it
                    expected
                },
                TwoStageFakeBudget(),
            )

        val result = provider.check(request)

        assertEquals(if (label == AmbiguityAssessment.CLEAR) PrecheckVerdict.CLEAR else generatedVerdict, result.verdict)
        assertEquals(expectedGenerations, generations)
        if (expectedGenerations == 0) {
            assertNull(result.prompt)
            assertEquals(emptyList(), result.choices)
        } else {
            assertEquals(request.rawText, generatedRequest?.rawText)
            assertEquals(request.evidence, generatedRequest?.evidence)
            assertEquals(request.referenceAt, generatedRequest?.referenceAt)
            assertEquals(expected, result)
        }
    }

    @ParameterizedTest
    @CsvSource("CLEAR,CLEAR,0", "NEEDS_REVIEW,CLEAR,1", "INDETERMINATE,CLEAR,1")
    fun `V2 semantic evidence controls routing even when a shared port supplied legacy confidence normalization`(
        label: AmbiguityAssessment,
        expectedVerdict: PrecheckVerdict,
        expectedGenerations: Int,
    ) {
        val initial =
            classified(label, 0.1).copy(
                assessment = AmbiguityAssessment.INDETERMINATE,
                reason = AmbiguityClassificationReason.LOW_CONFIDENCE,
            )
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { initial },
                PreSubmissionCheckProvider {
                    generations++
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                TwoStageFakeBudget(),
            )

        assertEquals(expectedVerdict, provider.check(request()).verdict)
        assertEquals(expectedGenerations, generations)
    }

    @ParameterizedTest
    @EnumSource(AmbiguityAssessment::class)
    fun `one scoped answer is reclassified with the same original and never causes a second generator call`(label: AmbiguityAssessment) {
        val request = answeredRequest()
        var generations = 0
        var received: AmbiguityClassificationRequest? = null
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    received = it
                    classified(label, 0.1)
                },
                PreSubmissionCheckProvider {
                    generations++
                    error("An answered V2 input cannot generate another question")
                },
                TwoStageFakeBudget(),
            )

        val result = provider.check(request)

        assertEquals(if (label == AmbiguityAssessment.CLEAR) PrecheckVerdict.CLEAR else PrecheckVerdict.UNRESOLVED, result.verdict)
        assertEquals(0, generations)
        assertEquals(request.rawText, received?.rawText)
        assertEquals(request.evidence, received?.evidence)
        assertEquals(request.referenceAt, received?.referenceAt)
        if (label == AmbiguityAssessment.NEEDS_REVIEW) assertEquals("AFTER_ANSWER_REVIEW", result.unresolvedReason)
        if (label == AmbiguityAssessment.INDETERMINATE) assertEquals("CLASSIFIER_MODEL_UNCERTAIN", result.unresolvedReason)
    }

    @Test
    fun `after-answer raw review remains unresolved even if legacy normalization changed the assessment`() {
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classified(AmbiguityAssessment.NEEDS_REVIEW, 0.1).copy(
                        assessment = AmbiguityAssessment.INDETERMINATE,
                        reason = AmbiguityClassificationReason.LOW_CONFIDENCE,
                    )
                },
                PreSubmissionCheckProvider {
                    generations++
                    error("No second question")
                },
                TwoStageFakeBudget(),
            )

        val result = provider.check(answeredRequest())

        assertEquals(PrecheckVerdict.UNRESOLVED, result.verdict)
        assertEquals("AFTER_ANSWER_REVIEW", result.unresolvedReason)
        assertEquals(0, generations)
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "NULL", "NAN", "OUTSIDE", "BAD_SUM", "WRONG_MAX", "UNKNOWN_LABEL", "BROKEN_JSON"])
    fun `malformed V2 wire cannot become clear or invoke Luna`(fault: String) {
        val valid = envelope(AmbiguityAssessment.CLEAR, 0.1, distribution(AmbiguityAssessment.CLEAR, 0.4))
        val body =
            when (fault) {
                "MISSING" -> valid.replace(",\"confidence\":0.1", "")
                "NULL" -> valid.replace("\"confidence\":0.1", "\"confidence\":null")
                "NAN" -> valid.replace("\"confidence\":0.1", "\"confidence\":\"NaN\"")
                "OUTSIDE" -> valid.replace("\"confidence\":0.1", "\"confidence\":1.1")
                "BAD_SUM" -> valid.replace("\"CLEAR\":0.4", "\"CLEAR\":0.9")
                "WRONG_MAX" -> valid.replace("\"choice\":\"CLEAR\"", "\"choice\":\"NEEDS_REVIEW\"")
                "UNKNOWN_LABEL" -> valid.replace("\"choice\":\"CLEAR\"", "\"choice\":\"UNRECOGNIZED\"")
                else -> "{broken"
            }
        val budget = DispatchBudget()
        var generations = 0
        DispatchEndpoint(response = { _, _ -> 200 to body }).use { endpoint ->
            val dispatchGate = gate(budget)
            val provider =
                TwoStagePreSubmissionCheckProvider(
                    classifier(endpoint, budget),
                    PreSubmissionCheckProvider {
                        generations++
                        error("Invalid provider answers cannot generate")
                    },
                    physicalDispatch = dispatchGate,
                )
            val failure = assertFailsWith<PrecheckProviderException> { provider.check(request(physical = true)) }
            assertEquals("INVALID_RESPONSE", failure.failureCode)
            assertEquals(0, generations)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["TIMEOUT", "CONNECTION", "SERVER", "RATE_LIMITED", "INVALID_RESPONSE", "REFUSED"])
    fun `V2 technical failure is never normal clear and never triggers a second provider`(code: String) {
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { throw AmbiguityClassificationException(code) },
                PreSubmissionCheckProvider {
                    generations++
                    error("A failed classifier cannot issue another provider request")
                },
                TwoStageFakeBudget(),
            )

        assertEquals(code, assertFailsWith<PrecheckProviderException> { provider.check(request()) }.failureCode)
        assertEquals(0, generations)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NULL", "NAN", "INFINITY", "NEGATIVE", "ABOVE_ONE"])
    fun `V2 valid-looking raw clear cannot bypass invalid confidence validation`(fault: String) {
        val confidence =
            when (fault) {
                "NULL" -> null
                "NAN" -> Double.NaN
                "INFINITY" -> Double.POSITIVE_INFINITY
                "NEGATIVE" -> -0.01
                else -> 1.01
            }
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { classified(AmbiguityAssessment.CLEAR, 0.1).copy(confidence = confidence) },
                PreSubmissionCheckProvider {
                    generations++
                    error("Invalid classifier evidence cannot clear or generate")
                },
                TwoStageFakeBudget(),
            )
        assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { provider.check(request()) }.failureCode)
        assertEquals(0, generations)
    }

    @ParameterizedTest
    @CsvSource("NEEDS_REVIEW,TIMEOUT", "NEEDS_REVIEW,INVALID_RESPONSE", "INDETERMINATE,TIMEOUT", "INDETERMINATE,INVALID_RESPONSE")
    fun `V2 generator technical failures remain failures without another generation`(
        label: AmbiguityAssessment,
        code: String,
    ) {
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { classified(label, 0.1) },
                PreSubmissionCheckProvider {
                    generations++
                    throw PrecheckProviderException(code)
                },
                TwoStageFakeBudget(),
            )
        assertEquals(code, assertFailsWith<PrecheckProviderException> { provider.check(request()) }.failureCode)
        assertEquals(1, generations)
    }

    @ParameterizedTest
    @CsvSource(
        "CLEAR,false,false,0,CLEAR",
        "NEEDS_REVIEW,false,false,1,NEEDS_CHOICE",
        "INDETERMINATE,false,false,1,NEEDS_CHOICE",
        "CLEAR,true,false,0,CLEAR",
        "NEEDS_REVIEW,true,false,0,UNRESOLVED",
        "INDETERMINATE,true,false,0,UNRESOLVED",
        "CLEAR,false,true,0,CLEAR",
        "NEEDS_REVIEW,false,true,1,NEEDS_CHOICE",
        "INDETERMINATE,false,true,1,NEEDS_CHOICE",
        "NEEDS_REVIEW,true,true,0,UNRESOLVED",
    )
    fun `managed physical routing honors V2 semantic labels before and after one answer`(
        label: AmbiguityAssessment,
        answered: Boolean,
        legacyNormalized: Boolean,
        expectedGenerations: Int,
        expectedVerdict: PrecheckVerdict,
    ) {
        val request = if (answered) answeredRequest(physical = true) else request(physical = true)
        val initial =
            classified(label, 0.1).let {
                if (legacyNormalized) {
                    it.copy(
                        assessment = AmbiguityAssessment.INDETERMINATE,
                        reason = AmbiguityClassificationReason.LOW_CONFIDENCE,
                    )
                } else {
                    it
                }
            }
        var classifications = 0
        var generations = 0
        var classifiedRequest: AmbiguityClassificationRequest? = null
        var generatedRequest: PrecheckProviderRequest? = null
        val legacyBudget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classifications++
                    classifiedRequest = it
                    initial
                },
                PreSubmissionCheckProvider {
                    generations++
                    generatedRequest = it
                    check(!answered && label != AmbiguityAssessment.CLEAR) { "Unexpected generation after a scoped answer or CLEAR" }
                    PrecheckProviderResult(PrecheckVerdict.NEEDS_CHOICE, "오전 또는 오후인가요?", "6시에서 8시", emptyList())
                },
                legacyBudget,
                physicalDispatch = gate(DispatchBudget()),
            )

        val result = provider.check(request)

        assertEquals(expectedVerdict, result.verdict)
        assertEquals(1, classifications)
        assertEquals(expectedGenerations, generations)
        assertEquals(0, legacyBudget.requests.size)
        val first = assertNotNull(classifiedRequest)
        val firstContext = assertNotNull(first.dispatchContext)
        assertEquals(request.dispatchContext?.execution, firstContext.execution)
        assertEquals(request.rawText, first.rawText)
        assertEquals(request.evidence, first.evidence)
        assertEquals(request.referenceAt, first.referenceAt)
        if (expectedGenerations == 1) {
            val second = assertNotNull(generatedRequest)
            val secondContext = assertNotNull(second.dispatchContext)
            assertEquals(request.dispatchContext?.execution, secondContext.execution)
            assertEquals(request.rawText, second.rawText)
            assertEquals(request.evidence, second.evidence)
            assertEquals(request.referenceAt, second.referenceAt)
        } else {
            assertNull(result.prompt)
            assertEquals(emptyList(), result.choices)
        }
        if (answered && label == AmbiguityAssessment.NEEDS_REVIEW) assertEquals("AFTER_ANSWER_REVIEW", result.unresolvedReason)
        if (answered && label == AmbiguityAssessment.INDETERMINATE) assertEquals("CLASSIFIER_MODEL_UNCERTAIN", result.unresolvedReason)
    }

    private fun classifier(
        endpoint: DispatchEndpoint,
        budget: DispatchBudget,
        threshold: Double? = 0.8,
    ) = JevAmbiguityClassifier(
        "synthetic-v2-label-contract-key",
        mapper,
        JevClassifierOptions(endpoint.uri("/v1/systemone"), enabled = true, minimumConfidence = threshold),
        dispatchGate = gate(budget),
    )

    private fun classification(v2: Boolean = true): AmbiguityClassificationRequest {
        val evidence = if (v2) InputEvidence(1, dispatchNow) else null
        val source = if (evidence == null) dispatchFramedHash(raw) else dispatchFramedHash("personal-source-v2", raw, evidence.canonical())
        val context =
            dispatchContext().let {
                it.copy(
                    execution =
                        it.execution.copy(
                            sourceFingerprint = source,
                            contextFingerprint =
                                dispatchFramedHash(
                                    dispatchNow.toString(),
                                    "Asia/Seoul",
                                    "ko-KR",
                                    date.toString(),
                                    date.plusDays(7).toString(),
                                ),
                        ),
                )
            }
        return AmbiguityClassificationRequest(
            raw,
            dispatchNow,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(7),
            Duration.ofSeconds(3),
            context,
            evidence,
        )
    }

    private fun request(physical: Boolean = false): PrecheckProviderRequest {
        val base = classification()
        val context = requireNotNull(base.dispatchContext)
        return PrecheckProviderRequest(
            context.execution.executionId,
            raw,
            dispatchNow,
            base.timeZone,
            base.locale,
            base.searchStartDate,
            base.searchEndDate,
            base.timeout,
            roomId = context.execution.roomId,
            executionGeneration = context.execution.generation.toInt(),
            dispatchContext = if (physical) context else null,
            evidence = base.evidence,
        )
    }

    private fun answeredRequest(physical: Boolean = false): PrecheckProviderRequest {
        val base = request(physical)
        val excerpt = "6시에서 8시"
        return base.copy(
            evidence =
                base.evidence!!.copy(
                    supplement =
                        InputSupplement(
                            UUID(0, 100),
                            1,
                            excerpt,
                            raw.codePointCount(0, raw.indexOf(excerpt)),
                            "오전인가요 오후인가요?",
                            "오후",
                            "B",
                            dispatchNow.plusSeconds(1),
                            dispatchNow,
                        ),
                ),
        )
    }

    private fun classified(
        label: AmbiguityAssessment,
        confidence: Double,
    ) = AmbiguityClassificationResult(
        label,
        reason = if (label == AmbiguityAssessment.INDETERMINATE) AmbiguityClassificationReason.MODEL_UNCERTAIN else null,
        confidence = confidence,
        inputTokens = 137,
        outputTokens = 7,
        semanticAssessment = label,
        probabilities = distribution(label, 0.4).mapKeys { AmbiguityAssessment.valueOf(it.key) },
    )

    private fun distribution(
        selected: AmbiguityAssessment,
        maximum: Double,
    ): Map<String, Double> =
        AmbiguityAssessment.entries.associate { label ->
            label.name to if (maximum < 0.34 || label == selected) maximum else (1.0 - maximum) / 2.0
        }

    private fun envelope(
        label: AmbiguityAssessment,
        confidence: Double,
        probabilities: Map<String, Double>,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "model" to "jev-1.14.0",
                "answers" to
                    mapOf(
                        "material_ambiguity" to
                            mapOf("type" to "choice", "choice" to label.name, "probabilities" to probabilities, "confidence" to confidence),
                    ),
                "usage" to mapOf("input_tokens" to 137, "output_tokens" to 7),
            ),
        )
}
