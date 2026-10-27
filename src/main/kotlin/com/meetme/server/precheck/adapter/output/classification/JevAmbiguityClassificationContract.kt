package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** TypeSafe wire validation. Numeric consistency never becomes a confidence routing policy. */
internal class JevAmbiguityClassificationContract(
    private val mapper: ObjectMapper,
) {
    /** Usage is evidence even when the answer fails validation; it is never a complete bill. */
    fun usageSnapshot(bytes: ByteArray): com.meetme.server.shared.application.port.output.AiBudgetUsage =
        try {
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            val envelope = reader.readValue<JsonNode>(text)
            val usage = envelope.path("usage")
            val input = token(usage.path("input_tokens"))
            val output = token(usage.path("output_tokens"))
            com.meetme.server.shared.application.port.output.AiBudgetUsage(
                inputTokens = input,
                outputTokens = output,
                usageComplete =
                    usage.isObject &&
                        usage.propertyNames().toSet() == setOf("input_tokens", "output_tokens") &&
                        input != null &&
                        output != null,
            )
        } catch (_: Exception) {
            com.meetme.server.shared.application.port.output
                .AiBudgetUsage()
        }

    private val reader =
        mapper.readerFor(JsonNode::class.java).with(
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
        )

    fun requestBytes(request: AmbiguityClassificationRequest): ByteArray =
        mapper.writeValueAsBytes(
            mapOf(
                "model" to MODEL,
                "state" to
                    mapOf(
                        "raw_text" to request.rawText,
                        "reference_at" to request.referenceAt.toString(),
                        "time_zone" to request.timeZone.id,
                        "locale" to request.locale,
                        "search_start_date" to request.searchStartDate.toString(),
                        "search_end_date" to request.searchEndDate.toString(),
                    ),
                "questions" to
                    mapOf(
                        QUESTION to
                            mapOf(
                                "type" to "choice",
                                "instructions" to AmbiguityClassificationRubric.instructions,
                                "criteria" to AmbiguityClassificationRubric.criteria,
                            ),
                    ),
            ),
        )

    fun parse(bytes: ByteArray): AmbiguityClassificationResult {
        val envelope =
            try {
                val text =
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                reader.readValue<JsonNode>(text)
            } catch (_: Exception) {
                invalid()
            }
        if (!envelope.isObject ||
            !envelope.propertyNames().all { it in setOf("model", "answers", "usage") } ||
            string(envelope.path("model")) != MODEL
        ) {
            invalid()
        }
        val answers = envelope.path("answers")
        if (!answers.isObject || answers.propertyNames().toSet() != setOf(QUESTION)) invalid()
        val answer = answers.path(QUESTION)
        if (!answer.isObject ||
            answer.propertyNames().toSet() != setOf("type", "choice", "probabilities", "confidence") ||
            string(answer.path("type")) != "choice"
        ) {
            invalid()
        }
        val assessment = AmbiguityAssessment.entries.firstOrNull { it.name == string(answer.path("choice")) } ?: invalid()
        val node = answer.path("probabilities")
        if (!node.isObject || node.propertyNames().toSet() != VALUES) invalid()
        val distribution = VALUES.associateWith { probability(node.path(it)) }
        if (distribution.values
                .fold(BigDecimal.ZERO, BigDecimal::add)
                .subtract(BigDecimal.ONE)
                .abs() > SUM_TOLERANCE ||
            distribution.getValue(assessment.name).compareTo(distribution.values.maxOrNull()) != 0
        ) {
            invalid()
        }
        val confidence = probability(answer.path("confidence"))
        val derived =
            distribution.values
                .maxOrNull()!!
                .multiply(BigDecimal(3))
                .subtract(BigDecimal.ONE)
                .divide(BigDecimal(2))
        // The official rounded .88/.12/0, confidence .81 example differs by .01.
        // This wire-consistency allowance is not a calibrated decision/action threshold.
        if (confidence.subtract(derived).abs() > ROUNDING_TOLERANCE) invalid()
        val usage = envelope.path("usage")
        val input = token(usage.path("input_tokens"))
        val output = token(usage.path("output_tokens"))
        val complete =
            usage.isObject &&
                usage.propertyNames().toSet() == setOf("input_tokens", "output_tokens") &&
                input != null &&
                output != null
        return AmbiguityClassificationResult(
            assessment = assessment,
            reason = if (assessment == AmbiguityAssessment.INDETERMINATE) AmbiguityClassificationReason.MODEL_UNCERTAIN else null,
            confidence = confidence.toDouble(),
            inputTokens = input,
            outputTokens = output,
            usageComplete = complete,
            provider = "TYPESAFE",
            model = MODEL,
            semanticAssessment = assessment,
            probabilities = distribution.mapKeys { AmbiguityAssessment.valueOf(it.key) }.mapValues { it.value.toDouble() },
        )
    }

    private fun string(node: JsonNode): String = if (node.isString) node.stringValue() else invalid()

    private fun token(node: JsonNode): Long? =
        if (node.isIntegralNumber && node.canConvertToLong() && node.longValue() >= 0) node.longValue() else null

    private fun probability(node: JsonNode): BigDecimal {
        if (!node.isNumber) invalid()
        val value =
            try {
                node.decimalValue()
            } catch (_: RuntimeException) {
                invalid()
            }
        if (value.precision() > 1000 ||
            value.scale() !in -1000..1000 ||
            !node.doubleValue().isFinite() ||
            value < BigDecimal.ZERO ||
            value > BigDecimal.ONE
        ) {
            invalid()
        }
        return value
    }

    private fun invalid(): Nothing = throw AmbiguityClassificationException("INVALID_RESPONSE")

    companion object {
        const val MODEL = "jev-1.14.0"
        private const val QUESTION = "material_ambiguity"
        private val VALUES = setOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE")
        private val SUM_TOLERANCE = BigDecimal("0.000001")
        private val ROUNDING_TOLERANCE = BigDecimal("0.02")
    }
}
