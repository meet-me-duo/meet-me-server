package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationQueueMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.DeadLetterMessage
import com.meetme.server.coordination.application.port.output.MalformedCoordinationWorkMessage
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import java.time.Clock
import java.time.Duration

class CoordinationWorker(
    private val outboxRepository: OutboxRepository,
    private val workQueue: CoordinationWorkQueuePort,
    private val eventProcessor: CoordinationEventProcessor,
    private val deadLetterPersistence: DeadLetterPersistence,
    private val clock: Clock,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
    private val metrics: ApplicationMetricsPort? = null,
    private val malformedDeadLetterPersistence: MalformedDeadLetterPersistence? = null,
) {
    fun process(message: CoordinationQueueMessage) {
        when (message) {
            is CoordinationWorkMessage -> processValid(message)
            is MalformedCoordinationWorkMessage -> processMalformed(message)
        }
    }

    private fun processValid(message: CoordinationWorkMessage) {
        val event = outboxRepository.findById(message.eventId)
        if (event == null || event.status in setOf(OutboxStatus.PROCESSED, OutboxStatus.DEAD_LETTERED)) {
            workQueue.acknowledge(message.streamRecordId)
            return
        }
        require(event.eventType == message.eventType) { "Stream event type does not match PostgreSQL Outbox" }
        require(event.payload.contains(message.batchId.value.toString())) { "Stream batch does not match PostgreSQL Outbox" }

        val now = clock.instant()
        val claim = outboxRepository.claimProcessing(message.eventId, now, now.plus(leaseDuration))
        if (claim == null) {
            workQueue.acknowledge(message.streamRecordId)
            return
        }

        try {
            eventProcessor.process(message.eventType, message.batchId)
            outboxRepository.markProcessed(message.eventId, clock.instant())
            workQueue.acknowledge(message.streamRecordId)
            metrics?.workerCompleted("PROCESSED")
        } catch (exception: Exception) {
            val failureKind = exception::class.simpleName ?: "WorkerFailure"
            if (claim.deliveryCount >= MAX_DELIVERIES) {
                deadLetterPersistence.persist(message.eventId, failureKind, claim.deliveryCount)
                workQueue.deadLetter(
                    DeadLetterMessage(message.eventId, message.batchId, failureKind, claim.deliveryCount),
                )
                workQueue.acknowledge(message.streamRecordId)
                metrics?.deadLettered(failureKind)
                metrics?.workerCompleted("DEAD_LETTERED")
            } else {
                outboxRepository.releaseAfterFailure(message.eventId, failureKind)
                metrics?.workerCompleted("RETRY_PENDING")
            }
        }
    }

    private fun processMalformed(message: MalformedCoordinationWorkMessage) {
        if (message.deliveryCount < MAX_DELIVERIES) {
            metrics?.workerCompleted("MALFORMED_RETRY_PENDING")
            return
        }
        requireNotNull(malformedDeadLetterPersistence) { "Malformed message persistence is required" }
            .persist(
                message.streamRecordId,
                message.eventId,
                message.batchId,
                message.failureKind,
                message.deliveryCount,
            )
        workQueue.deadLetter(
            DeadLetterMessage(message.eventId, message.batchId, message.failureKind, message.deliveryCount),
        )
        workQueue.acknowledge(message.streamRecordId)
        metrics?.deadLettered(message.failureKind)
        metrics?.workerCompleted("MALFORMED_DEAD_LETTERED")
    }

    companion object {
        const val MAX_DELIVERIES = 5
    }
}

fun interface DeadLetterPersistence {
    fun persist(
        eventId: com.meetme.server.shared.domain.OutboxEventId,
        failureKind: String,
        deliveryCount: Int,
    )
}

fun interface MalformedDeadLetterPersistence {
    fun persist(
        streamRecordId: String,
        eventId: com.meetme.server.shared.domain.OutboxEventId?,
        batchId: com.meetme.server.shared.domain.SubmissionBatchId?,
        failureKind: String,
        deliveryCount: Int,
    )
}
