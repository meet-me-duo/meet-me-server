package com.meetme.server.shared.application.port.input.operations

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OperationsCursor(
    val at: Instant,
    val id: UUID,
)

data class OperationsPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

data class RoomSummary(
    val id: UUID,
    val createdAt: Instant,
    val meetingMode: String,
    val timeZoneId: String,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val expectedParticipants: Int?,
    val submissionDeadline: Instant?,
    val manualOnly: Boolean,
    val collectionStatus: String,
    val closureReason: String?,
    val closedAt: Instant?,
    val participantCount: Long,
    val submittedParticipantCount: Long,
    val hostSubmitted: Boolean,
    val currentRunStatus: String?,
)

data class InvocationSummary(
    val id: UUID,
    val runId: UUID,
    val runVersion: Long,
    val startedAt: Instant,
    val deadlineAt: Instant,
    val finishedAt: Instant?,
    val winnerAttemptId: UUID?,
    val geminiAdmittedAttempts: Int,
    val lunaAdmittedAttempts: Int,
    val executionOrdinal: Long,
    val revisionLinked: Boolean,
    val recordedElapsedMillis: Long?,
    val parserDispatchCount: Long? = null,
    val httpRequestCount: Long? = null,
    val clientCallCount: Long? = null,
    val knownClientCallCount: Long = 0,
    val transportObservedAttemptCount: Long = 0,
    val transportUnknownAttemptCount: Long = geminiAdmittedAttempts.toLong() + lunaAdmittedAttempts,
)

data class AttemptSummary(
    val id: UUID,
    val attemptNumber: Int,
    val provider: String,
    val model: String,
    val policyVersion: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val failureKind: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val estimatedCostUsd: String?,
    val recordedElapsedMillis: Long?,
    val role: String,
    val parserDispatched: Boolean? = null,
    val clientCalls: List<LocalClientCall>? = null,
)

data class LocalClientCall(
    val callNumber: Int,
    val elapsedNanos: Long,
    val outcome: String,
    val httpStatus: Int?,
)

data class ProviderUsage(
    val provider: String,
    val recordedAttemptCount: Long,
    val inputKnownCount: Long,
    val outputKnownCount: Long,
    val costKnownCount: Long,
    val inputUnknownCount: Long,
    val outputUnknownCount: Long,
    val costUnknownCount: Long,
    val knownInputTokens: Long?,
    val knownOutputTokens: Long?,
    val knownCostUsd: String?,
    val coverageBasis: String = "RECORDED_ADMISSIONS",
)

data class RevisionCounts(
    val open: Long,
    val queued: Long,
    val reused: Long,
)

data class RoomDetail(
    val room: RoomSummary,
    val invocations: List<InvocationSummary>,
    val nextCursor: String?,
    val revisionCounts: RevisionCounts,
    val legacyAttemptCount: Long,
)

data class InvocationDetail(
    val roomId: UUID,
    val invocation: InvocationSummary,
    val attempts: List<AttemptSummary>,
    val usageByProvider: List<ProviderUsage>,
)

enum class OperatorQueryError(
    val status: Int,
) {
    OPERATOR_UNAUTHENTICATED(401),
    OPERATOR_FORBIDDEN(403),
    OPERATOR_NOT_FOUND(404),
    OPERATOR_INVALID_QUERY(400),
    OPERATOR_UNAVAILABLE(503),
}

class OperatorQueryException(
    val code: OperatorQueryError,
) : RuntimeException(code.name)
