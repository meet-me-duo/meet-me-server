package com.meetme.server.shared.adapter.output.observability

import com.meetme.server.coordination.application.port.output.CoordinationWorkQueuePort
import com.meetme.server.coordination.application.port.output.OutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "meetme.reliability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReliabilityGaugeCollector(
    private val outboxRepository: OutboxRepository,
    private val workQueue: CoordinationWorkQueuePort,
    registry: MeterRegistry,
    private val clock: Clock,
) {
    private val outboxPending = AtomicLong()
    private val outboxOldestSeconds = AtomicLong()
    private val redisPending = AtomicLong()
    private val redisOldestSeconds = AtomicLong()

    init {
        registry.gauge("meetme.outbox.pending", outboxPending)
        registry.gauge("meetme.outbox.oldest.seconds", outboxOldestSeconds)
        registry.gauge("meetme.redis.pending", redisPending)
        registry.gauge("meetme.redis.pending.oldest.seconds", redisOldestSeconds)
    }

    @Scheduled(fixedDelayString = "\${meetme.reliability.metrics-delay:10000}")
    fun refresh() {
        runCatching {
            outboxPending.set(outboxRepository.pendingCount())
            outboxOldestSeconds.set(outboxRepository.oldestPendingAgeSeconds(clock.instant()))
        }.onFailure {
            logger.warn("outbox_metrics_refresh_failed failure_kind={}", it::class.simpleName)
        }
        runCatching {
            val backlog = workQueue.backlog()
            redisPending.set(backlog.pendingCount)
            redisOldestSeconds.set(backlog.oldestPendingAgeSeconds)
        }.onFailure {
            logger.warn("redis_metrics_refresh_failed failure_kind={}", it::class.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ReliabilityGaugeCollector::class.java)
    }
}
