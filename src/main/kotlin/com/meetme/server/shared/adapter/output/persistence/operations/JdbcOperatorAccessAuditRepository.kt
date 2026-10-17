package com.meetme.server.shared.adapter.output.persistence.operations

import com.meetme.server.shared.application.port.output.operations.OperatorAccessAudit
import com.meetme.server.shared.application.port.output.operations.OperatorAccessAuditPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcOperatorAccessAuditRepository(
    private val jdbc: JdbcTemplate,
) : OperatorAccessAuditPort {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun append(event: OperatorAccessAudit) {
        jdbc.update(
            """
            INSERT INTO operations_access_audits(id, occurred_at, actor_user_id, route, target_id, outcome)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            event.id,
            Timestamp.from(event.occurredAt),
            event.actorUserId,
            event.route.name,
            event.targetId,
            event.outcome.name,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun deleteBefore(
        cutoff: Instant,
        limit: Int,
    ): Int {
        require(limit in 1..1000)
        return jdbc.update(
            """
            WITH expired AS (
                SELECT id FROM operations_access_audits WHERE occurred_at < ?
                ORDER BY occurred_at, id LIMIT ? FOR UPDATE SKIP LOCKED
            )
            DELETE FROM operations_access_audits audit USING expired WHERE audit.id = expired.id
            """.trimIndent(),
            Timestamp.from(cutoff),
            limit,
        )
    }
}
