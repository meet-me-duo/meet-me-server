package com.meetme.server.precheck.application.port.output

import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AmbiguityClassificationRequest(
    val rawText: String,
    val referenceAt: Instant,
    val timeZone: ZoneId,
    val locale: String,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val timeout: Duration,
    val dispatchContext: AiProviderDispatchContext? = null,
)

enum class AmbiguityAssessment {
    CLEAR,
    NEEDS_REVIEW,
    INDETERMINATE,
}

enum class AmbiguityClassificationReason {
    LOW_CONFIDENCE,
    REFUSED,
    MODEL_UNCERTAIN,
}

/** A classification is neither an accepted submission nor permission to call another model. */
data class AmbiguityClassificationResult(
    val assessment: AmbiguityAssessment,
    val reason: AmbiguityClassificationReason? = null,
    val confidence: Double? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val usageComplete: Boolean = false,
    val provider: String = "OPENAI_DECISIONS",
    val model: String = "gpt-6-luna",
)

fun interface AmbiguityClassificationPort {
    fun classify(request: AmbiguityClassificationRequest): AmbiguityClassificationResult
}

class AmbiguityClassificationException(
    val failureCode: String,
) : RuntimeException(failureCode)
