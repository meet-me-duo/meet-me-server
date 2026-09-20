package com.meetme.server.coordination.application.port.output

import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import java.time.Duration

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
}
