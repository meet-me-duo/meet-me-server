package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.event.OutboxEnqueued
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.Executor

@Component
@ConditionalOnProperty(prefix = "meetme.reliability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCommitListener(
    private val relay: OutboxRelay,
    @Qualifier("outboxCommitExecutor") private val executor: Executor,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommitted(event: OutboxEnqueued) {
        runCatching {
            executor.execute {
                runCatching { relay.relayCommitted(event.eventId) }.onFailure {
                    logger.warn("outbox_immediate_publish_failed failure_kind={}", it::class.simpleName)
                }
            }
        }.onFailure {
            logger.warn("outbox_immediate_dispatch_rejected failure_kind={}", it::class.simpleName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(OutboxCommitListener::class.java)
    }
}
