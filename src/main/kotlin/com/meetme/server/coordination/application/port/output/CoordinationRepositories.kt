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

    fun update(run: CoordinationRun)

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
)

interface CoordinationAttemptRepository {
    fun insert(attempt: CoordinationAttempt)

    fun update(attempt: CoordinationAttempt)

    fun countByRun(runId: CoordinationRunId): Int
}

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
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
) {
    init {
        require(aggregateType.isNotBlank())
        require(eventType.isNotBlank())
        require(payload.isNotBlank())
        require((status == OutboxStatus.PENDING) == (publishedAt == null))
    }
}

interface OutboxRepository {
    fun insert(event: OutboxEvent)

    fun findById(id: OutboxEventId): OutboxEvent?

    fun existsPending(
        aggregateId: UUID,
        eventType: String,
    ): Boolean
}
