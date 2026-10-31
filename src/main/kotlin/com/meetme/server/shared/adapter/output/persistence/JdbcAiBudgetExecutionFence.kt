package com.meetme.server.shared.adapter.output.persistence

import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.Participant
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.shared.application.port.output.AiBudgetCoverageEpochQueryPort
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchProfiles
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.ParticipantId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

private typealias FenceRow = Map<String, Any?>

private data class CurrentDispatchExecution(
    val deadlineAt: Instant,
    val coverageReferenceAt: Instant,
)

/** Room then existing claim locks; the coordinator acquires and rechecks its budget lock last. */
@Component
class JdbcAiBudgetExecutionFence(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val coverage: AiBudgetCoverageEpochQueryPort,
    private val participants: ParticipantRepository,
    private val bindings: PrecheckOwnershipBindingPort,
    private val mapper: ObjectMapper,
) : AiBudgetExecutionFencePort,
    AiRuntimeDispatchGuardPort {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun authorize(
        request: AiBudgetReserveRequest,
        at: Instant,
    ): Instant? {
        if (request.calls.any {
                it.profileFingerprint != AiRuntimeDispatchProfiles.fingerprints[it.operation] ||
                    !PAYLOAD_HASH.matches(it.wholePayloadFingerprint)
            }
        ) {
            return null
        }
        // The short transaction ends before the caller opens or continues an HTTP exchange.
        // A current execution is authority; a monetary coverage epoch is not required here.
        return currentExecution(request, at)?.deadlineAt?.takeIf { maxOf(at, clock.instant()) < it }
    }

    override fun lockAndValidate(
        request: AiBudgetReserveRequest,
        at: Instant,
    ): AiCurrentBudgetExecution? {
        val current = currentExecution(request, at) ?: return null
        val epoch = coverage.trackedEpoch(request.execution.roomId, current.coverageReferenceAt) ?: return null
        if (maxOf(at, clock.instant()) >= current.deadlineAt) return null
        return AiCurrentBudgetExecution(epoch, current.deadlineAt)
    }

    private fun currentExecution(
        request: AiBudgetReserveRequest,
        at: Instant,
    ): CurrentDispatchExecution? {
        val room = one("SELECT * FROM meeting_rooms WHERE id=? FOR UPDATE", request.execution.roomId) ?: return null
        return when (request.execution.kind) {
            AiBudgetExecutionKind.PRECHECK -> personal(request, room, at)
            AiBudgetExecutionKind.FINAL_ANALYSIS -> finalAnalysis(request, room, at)
        }
    }

    private fun personal(
        request: AiBudgetReserveRequest,
        room: FenceRow,
        at: Instant,
    ): CurrentDispatchExecution? {
        val execution = request.execution
        val protocol = room["submission_protocol"]
        if (protocol !in setOf("PRE_SUBMIT_V1", "PRE_SUBMIT_V2") || room["collection_status"] != "COLLECTING") return null
        if (request.calls.isEmpty() ||
            request.calls.size > 2 ||
            request.calls
                .map { it.operation }
                .distinct()
                .size != request.calls.size ||
            request.calls.any { it.operation !in PERSONAL_OPERATIONS || it.ordinal != 1 || it.attemptId != null }
        ) {
            return null
        }
        if (request.calls.size == 2 &&
            request.calls.map { it.operation }.toSet() != setOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)
        ) {
            return null
        }
        val row =
            one(
                """
                SELECT k.*,c.room_id,c.participant_id,c.revision,c.raw_text,c.locale,c.interpretation_reference_at,c.owner_binding,
                    c.input_evidence::text input_evidence_json,
                    e.queued_at execution_queued_at,e.deadline_at execution_deadline_at,e.started_at execution_started_at,
                    e.finished_at execution_finished_at,e.owner_token execution_owner_token
                FROM precheck_checks k JOIN precheck_candidates c ON c.id=k.candidate_id
                JOIN precheck_executions e ON e.check_id=k.id AND e.generation=k.generation
                WHERE k.id=? AND c.room_id=? FOR UPDATE OF k,e
                """.trimIndent(),
                execution.executionId,
                execution.roomId,
            ) ?: return null
        if (row["status"] != "CHECKING" ||
            row.number("version") != execution.version ||
            row.number("generation") != execution.generation ||
            row.uuid("owner_token") != execution.ownerToken ||
            row.uuid("execution_owner_token") != execution.ownerToken ||
            row["execution_finished_at"] != null ||
            row["claimed_at"] == null ||
            row["execution_started_at"] == null
        ) {
            return null
        }
        val participantId = row.uuid("participant_id") ?: return null
        val currentRevision =
            one(
                "SELECT max(revision) revision FROM precheck_candidates WHERE participant_id=?",
                participantId,
            )?.number("revision")
        if (currentRevision != row.number("revision")) return null
        val participant = participants.findById(ParticipantId(participantId)) ?: return null
        if (participant.roomId.value != execution.roomId) return null
        val binding = row["owner_binding"] as? String ?: return null
        val now = maxOf(at, clock.instant())
        if (!bindings.isActive(participant, binding, now)) return null
        val zone = ZoneId.of(room["time_zone_id"] as String)
        val raw = row["raw_text"] as String
        val evidence = runCatching { readEvidence(row, raw) }.getOrElse { return null }
        if ((protocol == "PRE_SUBMIT_V2") != (evidence != null)) return null
        if (evidence != null && evidence.referenceAt != row.instant("interpretation_reference_at")) return null
        if (execution.sourceFingerprint != AiDispatchFingerprints.personalSource(raw, evidence) ||
            execution.contextFingerprint !=
            AiDispatchFingerprints.personalContext(
                row.instant("interpretation_reference_at") ?: return null,
                zone,
                row["locale"] as String,
                room.date("search_start_date"),
                room.date("search_end_date"),
            )
        ) {
            return null
        }
        var deadline =
            minOf(
                row.instant("deadline_at")!!.minusSeconds(3),
                row.instant("execution_deadline_at")!!.minusSeconds(3),
                row.instant("claimed_at")!!.plusSeconds(15),
                row.instant("execution_started_at")!!.plusSeconds(15),
            )
        room.instant("submission_deadline")?.let { deadline = minOf(deadline, it) }
        deadline = bindingExpiry(participant, binding, deadline) ?: return null
        if (maxOf(at, clock.instant()) >= deadline) return null
        return CurrentDispatchExecution(deadline, row.instant("execution_queued_at") ?: return null)
    }

    private fun bindingExpiry(
        participant: Participant,
        binding: String,
        deadline: Instant,
    ): Instant? {
        if (participant.userId != null) return deadline
        val parts = binding.split('|')
        if (parts.size != 6) return null
        val session = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return null
        val capturedExpiry = parts[3].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
        val currentSession = one("SELECT expires_at,revoked_at FROM guest_browser_sessions WHERE id=?", session) ?: return null
        if (currentSession["revoked_at"] != null) return null
        var expiry = minOf(deadline, capturedExpiry, currentSession.instant("expires_at") ?: return null)
        if (parts[4].isNotEmpty()) {
            val generation = runCatching { UUID.fromString(parts[4]) }.getOrNull() ?: return null
            val grant =
                one(
                    "SELECT expires_at FROM guest_reaccess_grants WHERE room_id=? AND guest_session_id=? AND participant_id=? AND grant_generation=? AND level=?",
                    participant.roomId.value,
                    session,
                    participant.id.value,
                    generation,
                    parts[5],
                )
                    ?: return null
            expiry = minOf(expiry, grant.instant("expires_at") ?: return null)
        }
        return expiry
    }

    private fun finalAnalysis(
        request: AiBudgetReserveRequest,
        room: FenceRow,
        at: Instant,
    ): CurrentDispatchExecution? {
        val execution = request.execution
        val call = request.calls.singleOrNull() ?: return null
        if (call.operation !in FINAL_OPERATIONS || call.attemptId == null) return null
        val primary = call.operation == AiPhysicalOperation.FINAL_PRIMARY
        if ((primary && call.ordinal !in 1..4) || (!primary && call.ordinal != 1)) return null
        val run =
            one(
                "SELECT r.* FROM coordination_runs r JOIN analysis_invocations i ON i.coordination_run_id=r.id WHERE i.id=? AND r.room_id=? FOR UPDATE OF r",
                execution.executionId,
                execution.roomId,
            )
                ?: return null
        val runId = run.uuid("id") ?: return null
        if (room.uuid("active_run_id") != runId ||
            run["status"] != "STRUCTURING" ||
            run.number("version") != execution.version ||
            execution.generation != execution.version
        ) {
            return null
        }
        val invocation =
            one("SELECT * FROM analysis_invocations WHERE id=? AND coordination_run_id=? FOR UPDATE", execution.executionId, runId)
                ?: return null
        if (invocation.number("run_version") != execution.version ||
            invocation.uuid("owner_token") != execution.ownerToken ||
            invocation["finished_at"] != null ||
            invocation["winner_attempt_id"] != null ||
            invocation["policy_version"] != "bounded-luna-v1"
        ) {
            return null
        }
        val attempt =
            one(
                "SELECT * FROM coordination_attempts WHERE id=? AND invocation_id=? AND coordination_run_id=? FOR UPDATE",
                call.attemptId,
                execution.executionId,
                runId,
            )
                ?: return null
        val provider = if (primary) "GEMINI" else "OPENAI"
        val model = if (primary) "gemini-3.8-flash" else "gpt-6-luna"
        if (attempt["provider"] != provider ||
            attempt["model"] != model ||
            attempt["policy_version"] != "bounded-luna-v1" ||
            attempt["finished_at"] != null
        ) {
            return null
        }
        val ordinal =
            one(
                "SELECT count(*) ordinal FROM coordination_attempts WHERE invocation_id=? AND provider=? AND attempt_number<=?",
                execution.executionId,
                provider,
                attempt.number("attempt_number"),
            )?.number("ordinal")
                ?: return null
        val admitted = invocation.number(if (primary) "gemini_attempts" else "luna_attempts")
        if (ordinal != call.ordinal.toLong() ||
            admitted != ordinal ||
            (primary && invocation.number("luna_attempts") != 0L) ||
            (!primary && (invocation.number("gemini_attempts") ?: 0L) < 1)
        ) {
            return null
        }
        val batchId = run.uuid("batch_id") ?: return null
        val inputs =
            readInputs(batchId, execution.roomId, ZoneId.of(room["time_zone_id"] as String), room["submission_protocol"] as String)
                ?: return null
        if (execution.sourceFingerprint != AiDispatchFingerprints.batchSource(inputs) ||
            execution.contextFingerprint !=
            AiDispatchFingerprints.batchContext(
                ZoneId.of(room["time_zone_id"] as String),
                room.date("search_start_date"),
                room.date("search_end_date"),
                inputs,
            )
        ) {
            return null
        }
        val deadline =
            minOf(
                invocation.instant("deadline_at")!!.minusSeconds(if (primary) 30 else 3),
                attempt.instant("started_at")!!.plusSeconds(if (primary) 15 else 27),
            )
        if (maxOf(at, clock.instant()) >= deadline) return null
        return CurrentDispatchExecution(deadline, invocation.instant("started_at") ?: return null)
    }

    /** Preserve FrozenSubmissionReader's cohort and UUID ordering, including historical versions. */
    private fun readInputs(
        batchId: UUID,
        roomId: UUID,
        zone: ZoneId,
        protocol: String,
    ): List<AiDispatchInputSnapshot>? {
        val rows: List<FenceRow> =
            jdbc.queryForList(
                """
                SELECT v.id version_id,v.raw_text,v.locale,v.created_at,v.input_evidence::text input_evidence_json,h.room_id,h.participant_id,
                    c.id anchor_candidate_id,c.room_id anchor_room_id,c.participant_id anchor_participant_id,c.interpretation_reference_at
                FROM submission_batch_items b JOIN submission_versions v ON v.id=b.submission_version_id
                JOIN submission_heads h ON h.id=v.submission_id
                LEFT JOIN precheck_candidates c ON c.accepted_version_id=v.id WHERE b.batch_id=?
                """.trimIndent(),
                batchId,
            )
        if (rows.size < 2 ||
            rows.map { it.uuid("participant_id") }.distinct().size != rows.size ||
            rows.map { it.uuid("version_id") }.distinct().size != rows.size ||
            rows.any { it.uuid("room_id") != roomId }
        ) {
            return null
        }
        val inputs = mutableListOf<AiDispatchInputSnapshot>()
        for (row in rows.sortedBy { it.uuid("participant_id") }) {
            val participantId = row.uuid("participant_id") ?: return null
            if (row["anchor_candidate_id"] != null &&
                (row.uuid("anchor_room_id") != roomId || row.uuid("anchor_participant_id") != participantId)
            ) {
                return null
            }
            val raw = row["raw_text"] as? String ?: continue
            val evidence = runCatching { readEvidence(row, raw) }.getOrElse { return null }
            if ((protocol == "PRE_SUBMIT_V2") != (evidence != null)) return null
            if (evidence?.interpretation == InputInterpretation.PENDING) return null
            val anchor = evidence?.referenceAt ?: row.instant("interpretation_reference_at") ?: row.instant("created_at") ?: return null
            inputs +=
                AiDispatchInputSnapshot(
                    row.uuid("version_id")!!.toString(),
                    raw,
                    Locale.forLanguageTag(row["locale"] as String).toLanguageTag(),
                    anchor.atZone(zone).toLocalDate(),
                    evidence,
                )
        }
        return inputs.takeIf { it.isNotEmpty() }
    }

    private fun readEvidence(
        row: FenceRow,
        rawText: String,
    ): InputEvidence? =
        (row["input_evidence_json"] as? String)?.let {
            mapper.readValue(it, InputEvidence::class.java).also { evidence -> evidence.validateAgainst(rawText) }
        }

    private fun one(
        sql: String,
        vararg parameters: Any?,
    ): FenceRow? = jdbc.queryForList(sql, *parameters).singleOrNull()

    private fun FenceRow.uuid(key: String): UUID? = this[key] as? UUID

    private fun FenceRow.number(key: String): Long? = (this[key] as? Number)?.toLong()

    private fun FenceRow.instant(key: String): Instant? =
        when (val value = this[key]) {
            is Timestamp -> value.toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            is Instant -> value
            else -> null
        }

    private fun FenceRow.date(key: String): LocalDate =
        when (val value = this[key]) {
            is java.sql.Date -> value.toLocalDate()
            is LocalDate -> value
            else -> error("INVALID_PERSISTED_DATE")
        }

    companion object {
        private val PAYLOAD_HASH = Regex("[0-9a-f]{64}")
        private val PERSONAL_OPERATIONS =
            setOf(AiPhysicalOperation.PRECHECK_CLASSIFICATION, AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)
        private val FINAL_OPERATIONS = setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)
    }
}
