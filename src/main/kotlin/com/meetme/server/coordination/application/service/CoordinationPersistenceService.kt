package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.domain.CoordinationRun
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CoordinationPersistenceService(
    private val coordinationRunRepository: CoordinationRunRepository,
    private val outboxRepository: OutboxRepository,
) {
    @Transactional
    fun persist(
        run: CoordinationRun,
        event: OutboxEvent,
    ) {
        require(event.aggregateId == run.id.value) { "Outbox event must reference the coordination run" }
        coordinationRunRepository.insert(run)
        outboxRepository.insert(event)
    }
}
