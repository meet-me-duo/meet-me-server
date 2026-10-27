package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.NewRoomAiBudgetPort
import com.meetme.server.shared.application.port.output.NewRoomAiBudgetUnavailableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcNewRoomAiBudgetAdapter(
    private val jdbc: JdbcTemplate,
    private val selection: ManagedAiRuntimeSelection,
    private val clock: Clock,
) : NewRoomAiBudgetPort {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun initialize(
        roomId: UUID,
        createdAt: Instant,
    ) {
        if (!selection.enabled) return
        val policy = selection.policy ?: throw NewRoomAiBudgetUnavailableException()
        val now = clock.instant()
        if (policy.operations.values.any { it.validUntil <= now }) throw NewRoomAiBudgetUnavailableException()
        if (!NewRoomInsertTransactionReceipts.consume(roomId)) throw NewRoomAiBudgetUnavailableException()
        // The one-shot INSERT receipt is the creation proof. The row guards are additional CAS defenses.
        // xmin alone changes on UPDATE and cannot prove an original INSERT.
        val initialized =
            jdbc.update(
                """
                UPDATE ai_room_budgets b
                SET state='READY',epoch_id=?,unit_code=?,unit_scale=?,cap=?,policy_fingerprint=?,
                    coverage_started_at=r.created_at,version=b.version+1
                FROM meeting_rooms r
                WHERE r.id=b.room_id AND b.room_id=? AND r.created_at=? AND r.version=0
                    AND r.xmin::text::bigint=(txid_current() % 4294967296)
                    AND b.xmin::text::bigint=(txid_current() % 4294967296)
                    AND b.state='UNCONFIGURED' AND b.version=1 AND b.settled=0 AND b.held=0
                    AND b.epoch_id IS NULL AND b.unit_code IS NULL AND b.unit_scale IS NULL
                    AND b.cap IS NULL AND b.policy_fingerprint IS NULL AND b.coverage_started_at IS NULL
                    AND NOT EXISTS (SELECT 1 FROM ai_call_reservations a WHERE a.room_id=b.room_id)
                    AND NOT EXISTS (SELECT 1 FROM precheck_candidates c WHERE c.room_id=b.room_id)
                    AND NOT EXISTS (SELECT 1 FROM coordination_runs cr WHERE cr.room_id=b.room_id)
                """.trimIndent(),
                UUID.randomUUID(),
                policy.unit.code,
                policy.unit.scale,
                BigDecimal(policy.roomCap),
                policy.fingerprint,
                roomId,
                createdAt.atOffset(ZoneOffset.UTC),
            )
        if (initialized != 1) throw NewRoomAiBudgetUnavailableException()
    }
}
