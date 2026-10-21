package com.meetme.server.shared.application.port.output

import java.util.UUID

enum class AiCallStage { CLASSIFICATION, GENERATION }

enum class AiCallPurpose { PRECHECK_CLASSIFICATION, PRECHECK_GENERATION }

enum class AiCallOutcome { SUCCESS, FAILED, REFUSED, TIMED_OUT }

data class AiCallBudgetRequest(
    val roomId: UUID,
    val executionId: UUID,
    val executionGeneration: Int,
    val stage: AiCallStage,
    val purpose: AiCallPurpose,
    val sourceFingerprint: String,
    val contextFingerprint: String,
    val profileFingerprint: String,
    val maximumPhysicalCalls: Int,
)

/** An opaque, independently verified quote; this boundary does not establish prices or billed caps. */
data class AiCallQuote(
    val profileFingerprint: String,
    val evidenceFingerprint: String,
    val maximumPhysicalCalls: Int,
    val verified: Boolean = false,
)

data class AiCallBudgetReservation(
    val id: UUID,
    val request: AiCallBudgetRequest,
    val quote: AiCallQuote,
    val dispatchGranted: Boolean = false,
)

/** Known token observations never establish a known bill or release an UNKNOWN hold. */
data class AiCallObservation(
    val outcome: AiCallOutcome,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val usageComplete: Boolean = false,
    val billedCostKnown: Boolean = false,
    val failureCode: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

interface AiCallBudgetPort {
    fun reserve(request: AiCallBudgetRequest): AiCallBudgetReservation?

    fun record(
        reservation: AiCallBudgetReservation,
        observation: AiCallObservation,
    )
}
