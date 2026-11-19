package com.meetme.server.coordination.application.event

import com.meetme.server.shared.domain.OutboxEventId

/** Best-effort wake-up only; the durable record remains in PostgreSQL. */
data class OutboxEnqueued(
    val eventId: OutboxEventId,
)
