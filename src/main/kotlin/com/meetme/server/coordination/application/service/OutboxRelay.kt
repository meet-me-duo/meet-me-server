package com.meetme.server.coordination.application.service

import com.meetme.server.config.ReliabilityProperties
import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class OutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val workQueue: CoordinationWorkQueuePort,
    private val properties: ReliabilityProperties,
    private val clock: Clock,
    private val metrics: ApplicationMetricsPort,
) {
    @Transactional
    fun relayPending(): Int {
        val events = outboxRepository.findPendingForUpdate(properties.pollBatchSize)
        events.forEach { event ->
            workQueue.publish(event)
            outboxRepository.markPublished(event.id, clock.instant())
            metrics.outboxPublished(false)
        }
        return events.size
    }

    fun recoverPublished(): Int {
        val now = clock.instant()
        val events = outboxRepository.findRecoverablePublished(now.minus(properties.recoveryAge), properties.pollBatchSize)
        events.forEach { event ->
            workQueue.publish(event)
            outboxRepository.markRepublished(event.id, now)
            metrics.outboxPublished(true)
        }
        return events.size
    }
}

@Component
@ConditionalOnProperty(prefix = "meetme.reliability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler(
    private val relay: OutboxRelay,
) {
    @Scheduled(fixedDelayString = "\${meetme.reliability.relay-delay:1000}")
    fun relay() {
        runCatching(relay::relayPending).onFailure {
            logger.warn("outbox_relay_failed failure_kind={}", it::class.simpleName)
        }
    }

    @Scheduled(fixedDelayString = "\${meetme.reliability.recovery-delay:30000}")
    fun recover() {
        runCatching(relay::recoverPublished).onFailure {
            logger.warn("outbox_recovery_failed failure_kind={}", it::class.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxRelayScheduler::class.java)
    }
}
