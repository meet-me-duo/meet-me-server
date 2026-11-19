package com.meetme.server.coordination.application.port.output

import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import java.time.Duration
import java.time.Instant

sealed interface CoordinationQueueMessage {
    val streamRecordId: String
    val deliveryCount: Int
}

data class CoordinationWorkMessage(
    override val streamRecordId: String,
    val eventId: OutboxEventId,
    val batchId: SubmissionBatchId,
    val eventType: String,
    override val deliveryCount: Int = 1,
) : CoordinationQueueMessage

data class MalformedCoordinationWorkMessage(
    override val streamRecordId: String,
    val eventId: OutboxEventId?,
    val batchId: SubmissionBatchId?,
    val failureKind: String,
    override val deliveryCount: Int,
) : CoordinationQueueMessage

data class DeadLetterMessage(
    val eventId: OutboxEventId?,
    val batchId: SubmissionBatchId?,
    val failureKind: String,
    val deliveryCount: Int,
)

data class QueueBacklog(
    val pendingCount: Long,
    val oldestPendingAgeSeconds: Long,
)

interface CoordinationWorkQueuePort {
    fun publish(event: OutboxEvent)

    fun readNew(limit: Int): List<CoordinationQueueMessage>

    fun claimStale(
        minIdleTime: Duration,
        limit: Int,
    ): List<CoordinationQueueMessage>

    fun acknowledge(streamRecordId: String)

    fun deadLetter(message: DeadLetterMessage)

    fun backlog(): QueueBacklog = QueueBacklog(0, 0)
}

fun interface CoordinationEventProcessor {
    fun process(
        eventType: String,
        batchId: SubmissionBatchId,
    )

    fun processDelivery(
        eventType: String,
        batchId: SubmissionBatchId,
        claim: OutboxProcessingClaim,
    ) = process(eventType, batchId)
}

/** A durable continuation, not an unexpected processor failure or permission to reset its budget. */
class CoordinationRetryScheduled(
    val nextAttemptAt: Instant,
    val failureKind: String,
) : RuntimeException("Coordination retry scheduled")
