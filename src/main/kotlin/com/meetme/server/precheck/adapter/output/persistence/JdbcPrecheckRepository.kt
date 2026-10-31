package com.meetme.server.precheck.adapter.output.persistence

import com.meetme.server.precheck.application.port.input.PrecheckOutcome
import com.meetme.server.precheck.application.port.output.PrecheckOperation
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckQuestion
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.domain.InputEvidence
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcPrecheckRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : PrecheckRepository {
    override fun protocol(roomId: UUID): String =
        requireNotNull(jdbc.queryForObject("SELECT submission_protocol FROM meeting_rooms WHERE id=?", String::class.java, roomId))

    override fun current(participantId: UUID): PrecheckExecution? =
        jdbc.query("$SELECT WHERE c.participant_id=? ORDER BY c.revision DESC LIMIT 1", { rs, _ -> read(rs) }, participantId).firstOrNull()

    override fun find(checkId: UUID): PrecheckExecution? = jdbc.query("$SELECT WHERE k.id=?", { rs, _ -> read(rs) }, checkId).firstOrNull()

    override fun insert(
        candidate: PrecheckCandidate,
        check: PrecheckExecution,
    ) {
        jdbc.update(
            """INSERT INTO precheck_candidates(id,room_id,participant_id,revision,raw_text,locale,created_at,
            interpretation_reference_at,owner_binding,source_candidate_id,source_question_id,selected_choice_id,input_evidence)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))""",
            candidate.id,
            candidate.roomId,
            candidate.participantId,
            candidate.revision,
            candidate.rawText,
            candidate.locale,
            Timestamp.from(candidate.createdAt),
            Timestamp.from(candidate.referenceAt),
            candidate.ownerBinding,
            candidate.sourceCandidateId,
            candidate.sourceQuestionId,
            candidate.selectedChoiceId,
            candidate.evidence?.let(mapper::writeValueAsString),
        )
        jdbc.update(
            """INSERT INTO precheck_checks(id,candidate_id,status,version,generation,retry_count,deadline_at,failure_code)
            VALUES (?,?,?,?,?,?,?,?)""",
            check.id,
            candidate.id,
            check.status.name,
            check.version,
            check.generation,
            check.retryCount,
            Timestamp.from(check.deadlineAt),
            check.failureCode,
        )
        execution(check)
    }

    override fun update(check: PrecheckExecution) {
        val changed =
            jdbc.update(
                """UPDATE precheck_checks SET status=?,version=?,generation=?,retry_count=?,deadline_at=?,
            question=CAST(? AS jsonb),owner_token=?,claimed_at=?,failure_code=? WHERE id=? AND version=?""",
                check.status.name,
                check.version,
                check.generation,
                check.retryCount,
                Timestamp.from(check.deadlineAt),
                check.question?.let(mapper::writeValueAsString),
                check.ownerToken,
                check.claimedAt?.let(Timestamp::from),
                check.failureCode,
                check.id,
                check.version - 1,
            )
        check(changed == 1) { "Precheck state changed concurrently" }
        if (check.status == PrecheckStatus.QUEUED) execution(check)
    }

    private fun execution(check: PrecheckExecution) {
        jdbc.update(
            "INSERT INTO precheck_executions(check_id,generation,queued_at,deadline_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            check.id,
            check.generation,
            Timestamp.from(check.deadlineAt.minusSeconds(30)),
            Timestamp.from(check.deadlineAt),
        )
    }

    override fun operation(
        participantId: UUID,
        kind: String,
        requestId: UUID,
    ): PrecheckOperation? =
        jdbc
            .query(
                "SELECT fingerprint,outcome::text FROM precheck_operations WHERE participant_id=? AND kind=? AND request_id=?",
                {
                    rs,
                    _,
                    ->
                    PrecheckOperation(rs.getString(1), mapper.readValue(rs.getString(2), PrecheckOutcome::class.java))
                },
                participantId,
                kind,
                requestId,
            ).firstOrNull()

    override fun recordOperation(
        participantId: UUID,
        kind: String,
        requestId: UUID,
        operation: PrecheckOperation,
    ) {
        jdbc.update(
            "INSERT INTO precheck_operations(participant_id,kind,request_id,fingerprint,outcome) VALUES (?,?,?,?,CAST(? AS jsonb))",
            participantId,
            kind,
            requestId,
            operation.fingerprint,
            mapper.writeValueAsString(operation.outcome),
        )
    }

    override fun latestCandidateTextLength(
        roomId: UUID,
        excludingParticipantId: UUID,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT COALESCE(sum(length(raw_text) + COALESCE(length(input_evidence->'supplement'->>'answerText'),0)),0) " +
                    "FROM (SELECT DISTINCT ON(participant_id) participant_id,raw_text,input_evidence FROM precheck_candidates " +
                    "WHERE room_id=? AND participant_id<>? ORDER BY participant_id,revision DESC) c",
                Int::class.java,
                roomId,
                excludingParticipantId,
            ),
        )

    override fun questionCount(
        roomId: UUID,
        participantId: UUID,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                """SELECT (SELECT count(*) FROM precheck_checks k JOIN precheck_candidates c ON c.id=k.candidate_id WHERE c.room_id=? AND c.participant_id=? AND k.question IS NOT NULL)
            + (SELECT count(*) FROM submission_clarifications q JOIN submission_versions v ON v.id=q.source_version_id JOIN submission_heads h ON h.id=v.submission_id WHERE h.room_id=? AND h.participant_id=?)""",
                Int::class.java,
                roomId,
                participantId,
                roomId,
                participantId,
            ),
        )

    override fun callCount(
        roomId: UUID,
        participantId: UUID,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM precheck_executions e JOIN precheck_checks k ON k.id=e.check_id JOIN precheck_candidates c ON c.id=k.candidate_id WHERE c.room_id=? AND c.participant_id=? AND e.started_at IS NOT NULL",
                Int::class.java,
                roomId,
                participantId,
            ),
        )

    override fun queueSize(): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM precheck_checks WHERE status='QUEUED'", Int::class.java))

    override fun lockAdmission() {
        jdbc.execute("SELECT pg_advisory_xact_lock(710715016)")
    }

    override fun queuedCheckIds(limit: Int): List<UUID> =
        jdbc
            .queryForList(
                "SELECT id FROM precheck_checks WHERE status='QUEUED' ORDER BY queue_sequence LIMIT ?",
                UUID::class.java,
                limit,
            ).map(::requireNotNull)

    override fun unfinishedCheckIds(limit: Int): List<UUID> =
        jdbc
            .queryForList(
                "SELECT id FROM precheck_checks WHERE status IN ('QUEUED','CHECKING') ORDER BY queue_sequence LIMIT ?",
                UUID::class.java,
                limit,
            ).map(::requireNotNull)

    override fun inFlight(now: Instant): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM precheck_executions WHERE started_at IS NOT NULL AND finished_at IS NULL AND deadline_at>?",
                Int::class.java,
                Timestamp.from(now),
            ),
        )

    override fun startAttempt(
        check: PrecheckExecution,
        at: Instant,
    ) {
        val changed =
            jdbc.update(
                "UPDATE precheck_executions SET started_at=?,owner_token=? WHERE check_id=? AND generation=? AND started_at IS NULL",
                Timestamp.from(at),
                check.ownerToken,
                check.id,
                check.generation,
            )
        check(changed == 1) { "Precheck execution was already admitted" }
    }

    override fun finishAttempt(
        check: PrecheckExecution,
        result: PrecheckProviderResult?,
        at: Instant,
    ) {
        jdbc.update(
            """UPDATE precheck_executions SET finished_at=COALESCE(finished_at,?),input_tokens=COALESCE(input_tokens,?),
            output_tokens=COALESCE(output_tokens,?),response_bytes=COALESCE(response_bytes,?),failure_code=COALESCE(failure_code,?),
            provider=COALESCE(provider,?),model=COALESCE(model,?)
            WHERE check_id=? AND generation=? AND owner_token=? AND started_at IS NOT NULL""",
            Timestamp.from(at),
            result?.inputTokens,
            result?.outputTokens,
            result?.responseBytes,
            check.failureCode,
            result?.provider,
            result?.model,
            check.id,
            check.generation,
            check.ownerToken,
        )
    }

    override fun anchor(versionId: UUID): Instant? =
        jdbc
            .query(
                "SELECT interpretation_reference_at FROM precheck_candidates WHERE accepted_version_id=?",
                { rs, _ -> rs.getTimestamp(1).toInstant() },
                versionId,
            ).firstOrNull()

    override fun bindAccepted(
        candidateId: UUID,
        versionId: UUID,
    ) {
        check(
            jdbc.update(
                "UPDATE precheck_candidates SET accepted_version_id=? WHERE id=? AND accepted_version_id IS NULL",
                versionId,
                candidateId,
            ) ==
                1,
        )
    }

    private fun read(rs: ResultSet): PrecheckExecution {
        val candidate =
            PrecheckCandidate(
                rs.getObject("c_id", UUID::class.java),
                rs.getObject("room_id", UUID::class.java),
                rs.getObject("participant_id", UUID::class.java),
                rs.getInt("revision"),
                rs.getString("raw_text"),
                rs.getString("locale"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("interpretation_reference_at").toInstant(),
                rs.getString("owner_binding"),
                rs.getObject("source_candidate_id", UUID::class.java),
                rs.getObject("source_question_id", UUID::class.java),
                rs.getString("selected_choice_id"),
                rs.getObject("accepted_version_id", UUID::class.java),
                rs.getString("input_evidence")?.let {
                    mapper.readValue(it, InputEvidence::class.java).also { evidence ->
                        evidence.validateAgainst(rs.getString("raw_text"))
                    }
                },
            )
        return PrecheckExecution(
            rs.getObject("id", UUID::class.java),
            candidate,
            PrecheckStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getInt("generation"),
            rs.getInt("retry_count"),
            rs.getTimestamp("deadline_at").toInstant(),
            rs.getString("question")?.let { mapper.readValue(it, PrecheckQuestion::class.java) },
            rs.getObject("owner_token", UUID::class.java),
            rs.getTimestamp("claimed_at")?.toInstant(),
            rs.getString("failure_code"),
        )
    }

    companion object {
        private const val SELECT =
            "SELECT k.*,c.id c_id,c.room_id,c.participant_id,c.revision,c.raw_text,c.locale,c.created_at," +
                "c.interpretation_reference_at,c.owner_binding,c.source_candidate_id,c.source_question_id," +
                "c.selected_choice_id,c.accepted_version_id,c.input_evidence::text FROM precheck_checks k " +
                "JOIN precheck_candidates c ON c.id=k.candidate_id"
    }
}
