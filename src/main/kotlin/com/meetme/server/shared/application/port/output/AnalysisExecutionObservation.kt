package com.meetme.server.shared.application.port.output

import java.math.BigDecimal
import java.time.Duration

enum class ObservedAnalysisProvider {
    GEMINI,
    OPENAI,
}

enum class AnalysisExecutionOutcome {
    COMPLETED,
    ANALYSIS_DELAYED,
}

/** Local parser dispatch is not proof of an HTTP request or a billable provider call. */
data class AnalysisAttemptObservation(
    val provider: ObservedAnalysisProvider,
    val parserDispatched: Boolean = false,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val estimatedCostUsd: BigDecimal? = null,
)

/** No input text, response text, identity, or credential can be attached to this observation. */
data class AnalysisExecutionObservation(
    val outcome: AnalysisExecutionOutcome,
    val elapsed: Duration,
    val attempts: List<AnalysisAttemptObservation>,
)
