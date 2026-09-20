package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DeadLetterReplayService(
    private val outboxRepository: OutboxRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun replay(eventId: OutboxEventId): Boolean {
        val event = outboxRepository.findById(eventId) ?: return false
        if (event.status != OutboxStatus.DEAD_LETTERED) return false
        val run = coordinationRunRepository.findById(CoordinationRunId(event.aggregateId)) ?: return false
        coordinationRunRepository.update(run.retryDeadLetter())
        outboxRepository.requeue(eventId)
        jdbcTemplate.update("DELETE FROM coordination_worker_failures WHERE event_id = ?", eventId.value)
        return true
    }
}

@Component
@ConditionalOnProperty(prefix = "meetme.operations", name = ["replay-dead-letter-event-id"])
class DeadLetterReplayCommand(
    private val service: DeadLetterReplayService,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val rawId = requireNotNull(environment.getProperty("meetme.operations.replay-dead-letter-event-id"))
        val eventId = OutboxEventId(UUID.fromString(rawId))
        if (service.replay(eventId)) {
            logger.info("dead_letter_requeued event_id={}", eventId.value)
        } else {
            logger.info("dead_letter_requeue_skipped event_id={}", eventId.value)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DeadLetterReplayCommand::class.java)
    }
}
