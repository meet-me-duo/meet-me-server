package com.meetme.server.shared.application.port.output

import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** Exactly one physical request per operation/ordinal. Final primary does not imply token-count HTTP. */
enum class AiPhysicalOperation {
    PRECHECK_CLASSIFICATION,
    PRECHECK_COUNT,
    PRECHECK_GENERATION,
    FINAL_PRIMARY,
    FINAL_FALLBACK,
}

enum class AiBudgetExecutionKind { PRECHECK, FINAL_ANALYSIS }

/** Internal only; no field is copied into provider payloads or accepted from a browser as authority. */
data class AiBudgetExecution(
    val roomId: UUID,
    val kind: AiBudgetExecutionKind,
    val executionId: UUID,
    val generation: Long,
    val ownerToken: UUID,
    val version: Long,
    val sourceFingerprint: String,
    val contextFingerprint: String,
)

/** Hashes must come from the actual prepared whole wire/profile, never from supplied request hints. */
data class AiPhysicalCallSpec(
    val operation: AiPhysicalOperation,
    val ordinal: Int,
    val wholePayloadFingerprint: String,
    val profileFingerprint: String,
    val attemptId: UUID? = null,
)

/** PRECHECK_COUNT+PRECHECK_GENERATION may reserve atomically as one two-child group. */
data class AiBudgetReserveRequest(
    val execution: AiBudgetExecution,
    val calls: List<AiPhysicalCallSpec>,
)

data class AiReservedPhysicalCall(
    val reservationId: UUID,
    val call: AiPhysicalCallSpec,
)

/** Replay can return the same IDs, but it grants no physical dispatch. */
data class AiBudgetReservation(
    val execution: AiBudgetExecution,
    val calls: List<AiReservedPhysicalCall>,
)

data class AiBudgetDispatchRequest(
    val reservationId: UUID,
    val execution: AiBudgetExecution,
    val call: AiPhysicalCallSpec,
)

/** Returned by claim CAS once. Adapters call claim themselves immediately before their exact I/O. */
data class AiBudgetDispatchPermit(
    val reservationId: UUID,
    val dispatchToken: UUID,
    val deadlineAt: Instant,
)

enum class AiBudgetOutcome { SUCCEEDED, FAILED, REFUSED, TIMED_OUT, UNKNOWN }

enum class AiBudgetFailure {
    CONFIGURATION,
    AUTHENTICATION,
    PERMISSION,
    QUOTA,
    RATE_LIMIT,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
    PROVIDER,
    ACCOUNTING,
    STALE,
}

data class AiBudgetUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val usageComplete: Boolean = false,
)

/** No body, Throwable, raw error text, source, participant ID, or caller-provided cost-authority flag. */
data class AiBudgetObservation(
    val reservationId: UUID,
    val dispatchToken: UUID,
    val eventId: UUID,
    val outcome: AiBudgetOutcome,
    val usage: AiBudgetUsage = AiBudgetUsage(),
    val failure: AiBudgetFailure? = null,
)

/** Explicit arbitrary test unit/scale; no default currency, exchange rate, price, or amount. */
data class AiBudgetUnit(
    val code: String,
    val scale: Int,
)

data class AiBudgetQuoteRequest(
    val execution: AiBudgetExecution,
    val call: AiPhysicalCallSpec,
    val policyFingerprint: String,
)

/** Immutable registry-derived evidence. These data values alone are never accepted as dispatch permission. */
data class AiVerifiedBudgetQuote(
    val quoteFingerprint: String,
    val profileFingerprint: String,
    val wholePayloadFingerprint: String,
    val policyFingerprint: String,
    val evidenceFingerprint: String,
    val maximumAmount: BigInteger,
    val unit: AiBudgetUnit,
    val validUntil: Instant,
)

/** Opaque complete-bill evidence lookup key, not an amount or caller assertion of completeness. */
data class AiBudgetSettlementRequest(
    val reservationId: UUID,
    val dispatchToken: UUID,
    val evidenceReference: String,
)

data class AiBudgetBillRequest(
    val reservationId: UUID,
    val quoteFingerprint: String,
    val evidenceReference: String,
)

/** Only the trusted authority resolves this complete bill; token usage/HTTP success is insufficient. */
data class AiVerifiedCompleteBill(
    val quoteFingerprint: String,
    val evidenceFingerprint: String,
    val amount: BigInteger,
    val unit: AiBudgetUnit,
)

/** Offline, preloaded trusted proof resolution only: no HTTP, SDK, or live token count inside budget transactions. */
interface AiBudgetAuthorityRegistryPort {
    fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote?

    fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill?
}

// The default registry denies both operations until actual policy/evidence exists.

/** Returned from locked DB state, never from the caller. Epoch is active AND tracked for this execution. */
data class AiCurrentBudgetExecution(
    val epochId: UUID,
    val deadlineAt: Instant,
)

/** Called inside the coordinator transaction before acquiring the budget row lock. */
interface AiBudgetExecutionFencePort {
    fun lockAndValidate(
        request: AiBudgetReserveRequest,
        at: Instant,
    ): AiCurrentBudgetExecution?
}

enum class AiRoomBudgetState { UNCONFIGURED, HISTORICAL_UNKNOWN, UNTRACKED_EPOCH, READY, FROZEN }

data class AiRoomBudgetSnapshot(
    val roomId: UUID,
    val state: AiRoomBudgetState,
    val epochId: UUID?,
    val unit: AiBudgetUnit?,
    val cap: BigInteger?,
    val settled: BigInteger,
    val held: BigInteger,
    val version: Long,
)

interface DurableAiBudgetPort {
    fun reserve(request: AiBudgetReserveRequest): AiBudgetReservation?

    fun claim(request: AiBudgetDispatchRequest): AiBudgetDispatchPermit?

    /** True = applied or identical replay; false = conflict/unrecognized/deleted scope. Holds remain. */
    fun observe(observation: AiBudgetObservation): Boolean

    /** Only trusted complete-bill evidence can settle. An over-quote bill is recorded and freezes admission. */
    fun settle(request: AiBudgetSettlementRequest): Boolean

    fun snapshot(roomId: UUID): AiRoomBudgetSnapshot?
}
