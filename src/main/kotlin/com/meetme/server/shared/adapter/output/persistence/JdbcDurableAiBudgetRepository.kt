package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.shared.application.port.output.AiBudgetCallState
import com.meetme.server.shared.application.port.output.AiBudgetCoverageEpochQueryPort
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetRoomConfiguration
import com.meetme.server.shared.application.port.output.AiBudgetStoredEvent
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRoomBudgetSnapshot
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import com.meetme.server.shared.application.port.output.AiStoredBudgetCall
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcDurableAiBudgetRepository(
    private val jdbc: JdbcTemplate,
) : DurableAiBudgetRepository,
    AiBudgetCoverageEpochQueryPort {
    override fun lockRoom(roomId: UUID): AiRoomBudgetSnapshot? = queryRoom(roomId, true)

    override fun findRoom(roomId: UUID): AiRoomBudgetSnapshot? = queryRoom(roomId, false)

    override fun lockExistingRoom(roomId: UUID): Boolean =
        jdbc
            .query(
                "SELECT id FROM meeting_rooms WHERE id = ? FOR UPDATE",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                roomId,
            ).isNotEmpty()

    override fun configuration(roomId: UUID): AiBudgetRoomConfiguration? =
        jdbc
            .query(
                "SELECT policy_fingerprint, coverage_started_at FROM ai_room_budgets WHERE room_id = ?",
                { rs, _ -> AiBudgetRoomConfiguration(rs.getString(1), rs.getTimestamp(2)?.toInstant()) },
                roomId,
            ).singleOrNull()

    private fun queryRoom(
        roomId: UUID,
        lock: Boolean,
    ): AiRoomBudgetSnapshot? =
        jdbc
            .query(
                "SELECT * FROM ai_room_budgets WHERE room_id = ?" + if (lock) " FOR UPDATE" else "",
                { rs, _ ->
                    val code = rs.getString("unit_code")
                    val scale = rs.getObject("unit_scale") as? Number
                    AiRoomBudgetSnapshot(
                        rs.getObject("room_id", UUID::class.java),
                        AiRoomBudgetState.valueOf(rs.getString("state")),
                        rs.getObject("epoch_id", UUID::class.java),
                        if (code != null && scale != null) AiBudgetUnit(code, scale.toInt()) else null,
                        rs.getBigDecimal("cap")?.toBigIntegerExact(),
                        rs.getBigDecimal("settled").toBigIntegerExact(),
                        rs.getBigDecimal("held").toBigIntegerExact(),
                        rs.getLong("version"),
                    )
                },
                roomId,
            ).singleOrNull()

    override fun trackedEpoch(
        roomId: UUID,
        executionStartedAt: Instant,
    ): UUID? =
        jdbc
            .query(
                "SELECT epoch_id FROM ai_room_budgets WHERE room_id = ? AND state = 'READY' AND coverage_started_at <= ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                roomId,
                time(executionStartedAt),
            ).singleOrNull()

    override fun findCall(
        id: UUID,
        lock: Boolean,
    ): AiStoredBudgetCall? =
        jdbc
            .query(
                "SELECT * FROM ai_call_reservations WHERE id = ?" + if (lock) " FOR UPDATE" else "",
                callMapper,
                id,
            ).singleOrNull()

    override fun findPhysical(
        execution: AiBudgetExecution,
        call: AiPhysicalCallSpec,
    ): AiStoredBudgetCall? =
        jdbc
            .query(
                """
                SELECT * FROM ai_call_reservations WHERE room_id = ? AND execution_kind = ? AND execution_id = ?
                AND generation = ? AND operation = ? AND ordinal = ?
                """.trimIndent(),
                callMapper,
                execution.roomId,
                execution.kind.name,
                execution.executionId,
                execution.generation,
                call.operation.name,
                call.ordinal,
            ).singleOrNull()

    override fun insertProfile(
        snapshotFingerprint: String,
        quote: AiVerifiedBudgetQuote,
    ): Boolean {
        jdbc.update(
            """
            INSERT INTO ai_budget_profiles(profile_snapshot_fingerprint,profile_fingerprint,policy_fingerprint,unit_code,unit_scale)
            VALUES (?,?,?,?,?) ON CONFLICT (profile_snapshot_fingerprint) DO NOTHING
            """.trimIndent(),
            snapshotFingerprint,
            quote.profileFingerprint,
            quote.policyFingerprint,
            quote.unit.code,
            quote.unit.scale,
        )
        return jdbc
            .query("SELECT * FROM ai_budget_profiles WHERE profile_snapshot_fingerprint = ?", { rs, _ ->
                rs.getString("profile_fingerprint") == quote.profileFingerprint &&
                    rs.getString("policy_fingerprint") == quote.policyFingerprint &&
                    rs.getString("unit_code") == quote.unit.code &&
                    rs.getInt("unit_scale") == quote.unit.scale
            }, snapshotFingerprint)
            .singleOrNull() == true
    }

    override fun insertCall(
        call: AiStoredBudgetCall,
        profileSnapshot: String,
    ) {
        val execution = call.execution
        val quote = call.quote
        insert(
            "ai_call_reservations",
            linkedMapOf(
                "id" to call.id,
                "room_id" to execution.roomId,
                "execution_kind" to execution.kind.name,
                "execution_id" to execution.executionId,
                "generation" to execution.generation,
                "execution_version" to execution.version,
                "owner_token" to execution.ownerToken,
                "source_fingerprint" to execution.sourceFingerprint,
                "context_fingerprint" to execution.contextFingerprint,
                "operation" to call.call.operation.name,
                "ordinal" to call.call.ordinal,
                "attempt_id" to call.call.attemptId,
                "profile_snapshot_fingerprint" to profileSnapshot,
                "quote_fingerprint" to quote.quoteFingerprint,
                "whole_payload_fingerprint" to quote.wholePayloadFingerprint,
                "profile_fingerprint" to quote.profileFingerprint,
                "policy_fingerprint" to quote.policyFingerprint,
                "evidence_fingerprint" to quote.evidenceFingerprint,
                "maximum_amount" to BigDecimal(quote.maximumAmount),
                "unit_code" to quote.unit.code,
                "unit_scale" to quote.unit.scale,
                "quote_valid_until" to time(quote.validUntil),
                "epoch_id" to call.epochId,
                "state" to call.state.name,
                "hold_amount" to BigDecimal(call.hold),
                "reserved_at" to time(call.reservedAt),
                "updated_at" to time(call.reservedAt),
            ),
        )
    }

    override fun updateRoom(snapshot: AiRoomBudgetSnapshot) {
        val count =
            jdbc.update(
                "UPDATE ai_room_budgets SET state=?,settled=?,held=?,version=? WHERE room_id=? AND version=?",
                snapshot.state.name,
                BigDecimal(snapshot.settled),
                BigDecimal(snapshot.held),
                snapshot.version,
                snapshot.roomId,
                snapshot.version - 1,
            )
        check(count == 1) { "AI_BUDGET_VERSION_CONFLICT" }
    }

    override fun claim(
        id: UUID,
        dispatchToken: UUID,
        deadline: Instant,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE ai_call_reservations SET state='DISPATCH_INTENT',dispatch_token=?,dispatched_at=?,dispatch_deadline_at=?,lease_until=?,updated_at=?
            WHERE id=? AND state='RESERVED' AND dispatch_token IS NULL
            """.trimIndent(),
            dispatchToken,
            time(at),
            time(deadline),
            time(deadline),
            time(at),
            id,
        ) ==
            1

    override fun updateAccounting(
        id: UUID,
        state: AiBudgetCallState,
        hold: BigInteger,
        settled: BigInteger?,
        evidence: String?,
        at: Instant,
    ) {
        check(
            jdbc.update(
                "UPDATE ai_call_reservations SET state=?,hold_amount=?,settled_amount=?,settled_evidence_fingerprint=?,updated_at=? WHERE id=?",
                state.name,
                BigDecimal(hold),
                settled?.let(::BigDecimal),
                evidence,
                time(at),
                id,
            ) == 1,
        ) { "AI_BUDGET_CALL_MISSING" }
    }

    override fun eventFingerprint(
        reservationId: UUID,
        eventKey: String,
    ): String? =
        jdbc
            .query(
                "SELECT event_fingerprint FROM ai_call_observations WHERE reservation_id=? AND event_key=?",
                { rs, _ -> rs.getString(1) },
                reservationId,
                eventKey,
            ).singleOrNull()

    override fun insertEvent(event: AiBudgetStoredEvent) {
        val observation = event.observation
        val usage = observation?.usage
        insert(
            "ai_call_observations",
            linkedMapOf(
                "reservation_id" to event.reservationId,
                "event_key" to event.key,
                "event_fingerprint" to event.fingerprint,
                "kind" to if (observation == null) "BILL" else "USAGE",
                "outcome" to if (event.accountingFailure) AiBudgetOutcome.UNKNOWN.name else observation?.outcome?.name,
                "failure" to if (event.accountingFailure) AiBudgetFailure.ACCOUNTING.name else observation?.failure?.name,
                "input_tokens" to usage?.inputTokens,
                "output_tokens" to usage?.outputTokens,
                "reasoning_tokens" to usage?.reasoningTokens,
                "cached_input_tokens" to usage?.cachedInputTokens,
                "usage_complete" to (usage?.usageComplete ?: false),
                "bill_amount" to event.billAmount?.let(::BigDecimal),
                "evidence_fingerprint" to event.evidenceFingerprint,
                "created_at" to time(event.at),
            ),
        )
    }

    private fun insert(
        table: String,
        fields: LinkedHashMap<String, Any?>,
    ) {
        jdbc.update(
            "INSERT INTO $table (${fields.keys.joinToString(",")}) VALUES (${fields.keys.joinToString(",") { "?" }})",
            *fields.values.toTypedArray(),
        )
    }

    private fun time(at: Instant) = at.atOffset(ZoneOffset.UTC)

    private val callMapper =
        RowMapper { rs, _ ->
            val execution =
                AiBudgetExecution(
                    rs.getObject("room_id", UUID::class.java),
                    AiBudgetExecutionKind.valueOf(rs.getString("execution_kind")),
                    rs.getObject("execution_id", UUID::class.java),
                    rs.getLong("generation"),
                    rs.getObject("owner_token", UUID::class.java),
                    rs.getLong("execution_version"),
                    rs.getString("source_fingerprint"),
                    rs.getString("context_fingerprint"),
                )
            val call =
                AiPhysicalCallSpec(
                    AiPhysicalOperation.valueOf(rs.getString("operation")),
                    rs.getInt("ordinal"),
                    rs.getString("whole_payload_fingerprint"),
                    rs.getString("profile_fingerprint"),
                    rs.getObject("attempt_id", UUID::class.java),
                )
            val quote =
                AiVerifiedBudgetQuote(
                    rs.getString("quote_fingerprint"),
                    call.profileFingerprint,
                    call.wholePayloadFingerprint,
                    rs.getString("policy_fingerprint"),
                    rs.getString("evidence_fingerprint"),
                    rs.getBigDecimal("maximum_amount").toBigIntegerExact(),
                    AiBudgetUnit(rs.getString("unit_code"), rs.getInt("unit_scale")),
                    rs.getTimestamp("quote_valid_until").toInstant(),
                )
            AiStoredBudgetCall(
                rs.getObject("id", UUID::class.java),
                execution,
                call,
                quote,
                rs.getObject("epoch_id", UUID::class.java),
                AiBudgetCallState.valueOf(rs.getString("state")),
                rs.getBigDecimal("hold_amount").toBigIntegerExact(),
                rs.getTimestamp("reserved_at").toInstant(),
                rs.getObject("dispatch_token", UUID::class.java),
                rs.getTimestamp("dispatch_deadline_at")?.toInstant(),
                rs.getBigDecimal("settled_amount")?.toBigIntegerExact(),
                rs.getString("settled_evidence_fingerprint"),
            )
        }
}
