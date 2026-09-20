package com.meetme.server.coordination.application.service

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.application.port.output.CoordinationEventProcessor
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZoneOffset

@Component
class CoordinationEventDispatcher(
    private val geminiBatchProcessor: GeminiBatchProcessor,
    private val matchingProcessor: MatchingProcessor,
) : CoordinationEventProcessor {
    override fun process(
        eventType: String,
        batchId: SubmissionBatchId,
    ) {
        when (eventType) {
            CollectionClosureService.STRUCTURING_REQUESTED -> geminiBatchProcessor.process(batchId)
            CollectionClosureService.MATCHING_REQUESTED -> matchingProcessor.process(batchId)
            else -> error("Unsupported coordination event type")
        }
    }
}

@Service
class CoordinationDeadLetterPersistence(
    private val outboxRepository: OutboxRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) : DeadLetterPersistence,
    MalformedDeadLetterPersistence {
    @Transactional
    override fun persist(
        eventId: OutboxEventId,
        failureKind: String,
        deliveryCount: Int,
    ) {
        val event = requireNotNull(outboxRepository.findById(eventId)) { "Outbox event does not exist" }
        val run =
            requireNotNull(coordinationRunRepository.findById(CoordinationRunId(event.aggregateId))) {
                "Coordination run does not exist"
            }
        coordinationRunRepository.update(run.deadLetter())
        val failedAt = clock.instant()
        outboxRepository.markDeadLettered(eventId, failedAt, failureKind)
        jdbcTemplate.update(
            """
            INSERT INTO coordination_worker_failures(
                stream_record_id, event_id, submission_batch_id, failure_kind, delivery_count, failed_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (stream_record_id) DO NOTHING
            """.trimIndent(),
            "event:${eventId.value}",
            eventId.value,
            run.batch.id.value,
            failureKind,
            deliveryCount,
            failedAt.atOffset(ZoneOffset.UTC),
        )
    }

    @Transactional
    override fun persist(
        streamRecordId: String,
        eventId: OutboxEventId?,
        batchId: SubmissionBatchId?,
        failureKind: String,
        deliveryCount: Int,
    ) {
        val failedAt = clock.instant()
        var persistedBatchId = batchId
        if (eventId != null) {
            val event = outboxRepository.findById(eventId)
            if (event?.status == OutboxStatus.PUBLISHED) {
                val run = coordinationRunRepository.findById(CoordinationRunId(event.aggregateId))
                if (run != null) {
                    coordinationRunRepository.update(run.deadLetter())
                    outboxRepository.markDeadLettered(eventId, failedAt, failureKind)
                    persistedBatchId = run.batch.id
                }
            }
        }
        jdbcTemplate.update(
            """
            INSERT INTO coordination_worker_failures(
                stream_record_id, event_id, submission_batch_id, failure_kind, delivery_count, failed_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (stream_record_id) DO NOTHING
            """.trimIndent(),
            streamRecordId,
            eventId?.value,
            persistedBatchId?.value,
            failureKind,
            deliveryCount,
            failedAt.atOffset(ZoneOffset.UTC),
        )
    }
}

@Component
@ConditionalOnProperty(prefix = "meetme.reliability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CoordinationWorkerScheduler(
    private val outboxRepository: OutboxRepository,
    private val workQueue: CoordinationWorkQueuePort,
    private val eventProcessor: CoordinationEventProcessor,
    private val deadLetterPersistence: CoordinationDeadLetterPersistence,
    private val properties: ReliabilityProperties,
    private val clock: Clock,
    private val metrics: ApplicationMetricsPort,
) {
    private val worker =
        CoordinationWorker(
            outboxRepository,
            workQueue,
            eventProcessor,
            deadLetterPersistence,
            clock,
            properties.pendingMinIdle,
            metrics,
            deadLetterPersistence,
        )

    @Scheduled(fixedDelayString = "\${meetme.reliability.worker-delay:500}")
    fun consume() {
        runCatching {
            workQueue.readNew(properties.pollBatchSize).forEach(worker::process)
            workQueue.claimStale(properties.pendingMinIdle, properties.pollBatchSize).forEach(worker::process)
        }.onFailure {
            logger.warn("coordination_worker_poll_failed failure_kind={}", it::class.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CoordinationWorkerScheduler::class.java)
    }
}
