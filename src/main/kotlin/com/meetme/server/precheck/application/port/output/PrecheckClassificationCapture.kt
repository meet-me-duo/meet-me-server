package com.meetme.server.precheck.application.port.output

/** Bounded diagnostic metadata only. Contains no input, prompt or model response body. */
data class PrecheckClassificationSnapshot(
    val version: Int = 1,
    val assessment: AmbiguityAssessment,
    val reason: AmbiguityClassificationReason?,
    val confidence: Double?,
    val semanticAssessment: AmbiguityAssessment?,
    val probabilities: Map<AmbiguityAssessment, Double>,
    val provider: String,
    val model: String,
    val effectiveAssessment: AmbiguityAssessment? = null,
)

/** One instance per claimed attempt, written only by the existing provider execution. */
class PrecheckClassificationCapture {
    private var first: PrecheckClassificationSnapshot? = null
    private var received = false

    fun classified(result: AmbiguityClassificationResult) {
        if (received) return
        received = true
        if (result.provider.length !in 1..128 ||
            result.model.length !in 1..128 ||
            result.provider.contains('\u0000') ||
            result.model.contains('\u0000') ||
            result.confidence?.let { !it.isFinite() || it !in 0.0..1.0 } == true ||
            result.probabilities.size > 3 ||
            result.probabilities.values.any { !it.isFinite() || it !in 0.0..1.0 }
        ) {
            return
        }
        first =
            PrecheckClassificationSnapshot(
                assessment = result.assessment,
                reason = result.reason,
                confidence = result.confidence,
                semanticAssessment = result.semanticAssessment,
                probabilities = java.util.Collections.unmodifiableMap(LinkedHashMap(result.probabilities)),
                provider = result.provider,
                model = result.model,
            )
    }

    fun routed(assessment: AmbiguityAssessment) {
        val known = first ?: return
        if (known.effectiveAssessment == null) first = known.copy(effectiveAssessment = assessment)
    }

    fun snapshot(): PrecheckClassificationSnapshot? = first
}
