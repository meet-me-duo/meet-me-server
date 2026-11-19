package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import java.time.Instant
import java.util.UUID

interface CoordinationRunRepository {
    fun insert(run: CoordinationRun)

    fun findById(id: CoordinationRunId): CoordinationRun?

    fun findByIdForUpdate(id: CoordinationRunId): CoordinationRun?

    fun update(run: CoordinationRun)

    /** Compatibility name: resolves only meeting_rooms.active_run_id, never timestamp order. */
    fun findLatestByRoom(roomId: MeetingRoomId): CoordinationRun?

    fun findLatestByRoomForUpdate(roomId: MeetingRoomId): CoordinationRun?

    fun findByBatchId(batchId: SubmissionBatchId): CoordinationRun?
}

enum class NormalizedPlaceStatus {
    RESOLVED,
    NO_EXACT_MATCH,
    AMBIGUOUS,
}

data class NormalizedPlace(
    val batchId: SubmissionBatchId,
    val submissionVersionId: SubmissionVersionId,
    val conditionIndex: Int,
    val query: String,
    val radiusMeters: Int,
    val status: NormalizedPlaceStatus,
    val providerPlaceId: String? = null,
    val displayName: String? = null,
    val coordinate: GeoCoordinate? = null,
)

interface NormalizedPlaceRepository {
    fun replaceForBatch(
        batchId: SubmissionBatchId,
        places: List<NormalizedPlace>,
    )

    fun findByBatch(batchId: SubmissionBatchId): List<NormalizedPlace>
}

data class CoordinationAttempt(
    val id: UUID,
    val coordinationRunId: CoordinationRunId,
    val attemptNumber: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val failureKind: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val responseBytes: Int?,
    val estimatedCostUsd: java.math.BigDecimal?,
    val provider: AnalysisProvider = AnalysisProvider.GEMINI,
    val model: String = "gemini-3.8-flash",
    val policyVersion: String = "gemini-v1",
    val invocationId: UUID? = null,
)

interface CoordinationAttemptRepository {
    /** Unsupported readers cannot admit overlapping selected attempts. */
    fun findLatestByInvocation(invocationId: UUID): CoordinationAttempt? = null

    /** Unsupported legacy readers fail closed in the explicitly selected path. */
    fun findById(id: UUID): CoordinationAttempt? = null

    fun insert(attempt: CoordinationAttempt)

    fun update(attempt: CoordinationAttempt)

    fun countByRun(runId: CoordinationRunId): Int
}

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
    PROCESSED,
    DEAD_LETTERED,
}

data class OutboxEvent(
    val id: OutboxEventId,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val occurredAt: Instant,
    val publishedAt: Instant? = null,
    val processedAt: Instant? = null,
    val processingLeaseUntil: Instant? = null,
    val deliveryCount: Int = 0,
    val lastFailureKind: String? = null,
    val deadLetteredAt: Instant? = null,
    val nextAttemptAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val unexpectedFailures: Int = 0,
) {
    init {
        require(aggregateType.isNotBlank())
        require(eventType.isNotBlank())
        require(payload.isNotBlank())
        require(deliveryCount >= 0)
        require(unexpectedFailures >= 0)
        require(nextAttemptAt == null || status == OutboxStatus.PUBLISHED)
        when (status) {
            OutboxStatus.PENDING -> {
                require(publishedAt == null && processedAt == null && deadLetteredAt == null)
                require(processingLeaseUntil == null)
            }
            OutboxStatus.PUBLISHED -> {
                require(publishedAt != null && processedAt == null && deadLetteredAt == null)
            }
            OutboxStatus.PROCESSED -> {
                require(publishedAt != null && processedAt != null && deadLetteredAt == null)
                require(processingLeaseUntil == null)
            }
            OutboxStatus.DEAD_LETTERED -> {
                require(publishedAt != null && processedAt == null && deadLetteredAt != null)
                require(processingLeaseUntil == null)
            }
        }
    }

    fun processed(at: Instant): OutboxEvent {
        check(status == OutboxStatus.PUBLISHED) { "Only a published event can be processed" }
        return copy(status = OutboxStatus.PROCESSED, processedAt = at, processingLeaseUntil = null, nextAttemptAt = null)
    }

    fun failed(failureKind: String): OutboxEvent {
        check(status == OutboxStatus.PUBLISHED) { "Only a published event can fail" }
        require(failureKind.isNotBlank())
        return copy(processingLeaseUntil = null, lastFailureKind = failureKind)
    }

    fun deadLettered(
        at: Instant,
        failureKind: String,
    ): OutboxEvent {
        check(status == OutboxStatus.PUBLISHED) { "Only a published event can be dead-lettered" }
        require(failureKind.isNotBlank())
        return copy(
            status = OutboxStatus.DEAD_LETTERED,
            processingLeaseUntil = null,
            lastFailureKind = failureKind,
            deadLetteredAt = at,
            nextAttemptAt = null,
        )
    }

    fun requeued(): OutboxEvent {
        check(status == OutboxStatus.DEAD_LETTERED) { "Only a dead-lettered event can be requeued" }
        return copy(
            status = OutboxStatus.PENDING,
            publishedAt = null,
            processedAt = null,
            processingLeaseUntil = null,
            deliveryCount = 0,
            unexpectedFailures = 0,
            nextAttemptAt = null,
            lastAttemptAt = null,
            lastFailureKind = null,
            deadLetteredAt = null,
        )
    }
}

data class OutboxProcessingClaim(
    val event: OutboxEvent,
    val deliveryCount: Int,
)

interface OutboxRepository {
    /** Empty denies; two rows are ambiguous authority, never latest-event-wins. */
    fun findSelectedMatchingRetryEvents(
        runId: CoordinationRunId,
        matchingRunVersion: Long,
    ): List<OutboxEvent> = emptyList()

    fun insert(event: OutboxEvent)

    fun findById(id: OutboxEventId): OutboxEvent?

    fun existsPending(
        aggregateId: UUID,
        eventType: String,
    ): Boolean

    fun findPendingForUpdate(limit: Int): List<OutboxEvent>

    fun findPendingByIdForUpdate(eventId: OutboxEventId): OutboxEvent? =
        throw UnsupportedOperationException("Targeted pending claim is not implemented")

    fun markPublished(
        eventId: OutboxEventId,
        at: Instant,
    )

    fun findRecoverablePublished(
        publishedBefore: Instant,
        limit: Int,
    ): List<OutboxEvent>

    fun markRepublished(
        eventId: OutboxEventId,
        at: Instant,
    )

    fun claimProcessing(
        eventId: OutboxEventId,
        now: Instant,
        leaseUntil: Instant,
    ): OutboxProcessingClaim?

    fun markProcessed(
        eventId: OutboxEventId,
        at: Instant,
    )

    fun releaseAfterFailure(
        eventId: OutboxEventId,
        failureKind: String,
    )

    fun recordUnexpectedFailure(
        claim: OutboxProcessingClaim,
        failureKind: String,
    ): Int? = claim.deliveryCount

    /** The claim delivery number fences an old consumer after a later claim. */
    fun completeClaim(
        claim: OutboxProcessingClaim,
        at: Instant,
    ): Boolean {
        markProcessed(claim.event.id, at)
        return true
    }

    fun scheduleRetry(
        claim: OutboxProcessingClaim,
        failureKind: String,
        nextAttemptAt: Instant,
    ): Boolean {
        releaseAfterFailure(claim.event.id, failureKind)
        return true
    }

    fun markDeadLettered(
        eventId: OutboxEventId,
        at: Instant,
        failureKind: String,
    )

    fun pendingCount(): Long = 0

    fun oldestPendingAgeSeconds(now: Instant): Long = 0

    fun requeue(eventId: OutboxEventId)
}
