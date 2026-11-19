package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.shared.domain.OutboxEventId
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Explicit pre-V34 fixture phase; no schema probing or production compatibility fallback. */
class HistoricalOutboxRepository(
    private val jdbc: JdbcTemplate,
    private val current: OutboxRepository,
) : OutboxRepository by current {
    private var currentSchema = false

    fun useCurrentSchema() {
        currentSchema = true
    }

    override fun insert(event: OutboxEvent) {
        if (currentSchema) return current.insert(event)
        require(event.nextAttemptAt == null && event.lastAttemptAt == null && event.unexpectedFailures == 0) {
            "Historical fixture cannot store V34 retry metadata"
        }
        jdbc.update(
            """
            INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at,
                published_at,processed_at,processing_lease_until,delivery_count,last_failure_kind,dead_lettered_at)
            VALUES (?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?,?,?)
            """.trimIndent(),
            event.id.value,
            event.aggregateType,
            event.aggregateId,
            event.eventType,
            event.payload,
            event.status.name,
            event.occurredAt.atOffset(ZoneOffset.UTC),
            event.publishedAt?.atOffset(ZoneOffset.UTC),
            event.processedAt?.atOffset(ZoneOffset.UTC),
            event.processingLeaseUntil?.atOffset(ZoneOffset.UTC),
            event.deliveryCount,
            event.lastFailureKind,
            event.deadLetteredAt?.atOffset(ZoneOffset.UTC),
        )
    }

    override fun findById(id: OutboxEventId): OutboxEvent? {
        if (currentSchema) return current.findById(id)
        return jdbc
            .query(
                """
                SELECT id,aggregate_type,aggregate_id,event_type,payload::text,status,occurred_at,published_at,
                    processed_at,processing_lease_until,delivery_count,last_failure_kind,dead_lettered_at
                FROM outbox_events WHERE id=?
                """.trimIndent(),
                { rs, _ ->
                    OutboxEvent(
                        OutboxEventId(rs.getObject("id", UUID::class.java)),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID::class.java),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        OutboxStatus.valueOf(rs.getString("status")),
                        rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                        rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
                        rs.getObject("processed_at", OffsetDateTime::class.java)?.toInstant(),
                        rs.getObject("processing_lease_until", OffsetDateTime::class.java)?.toInstant(),
                        rs.getInt("delivery_count"),
                        rs.getString("last_failure_kind"),
                        rs.getObject("dead_lettered_at", OffsetDateTime::class.java)?.toInstant(),
                    )
                },
                id.value,
            ).singleOrNull()
    }
}
