package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.shared.domain.CoordinationRunId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcAnalysisInvocationRepository(
    private val jdbc: JdbcTemplate,
) : AnalysisInvocationRepository {
    override fun findByRunVersion(
        runId: CoordinationRunId,
        runVersion: Long,
    ): AnalysisInvocation? =
        jdbc
            .query("SELECT * FROM analysis_invocations WHERE coordination_run_id = ? AND run_version = ?", mapper, runId.value, runVersion)
            .firstOrNull()

    override fun findLatestByRun(runId: CoordinationRunId): AnalysisInvocation? =
        jdbc
            .query(
                "SELECT * FROM analysis_invocations WHERE coordination_run_id = ? ORDER BY run_version DESC LIMIT 1",
                mapper,
                runId.value,
            ).firstOrNull()

    override fun findExpired(
        now: Instant,
        limit: Int,
    ): List<AnalysisInvocation> {
        require(limit > 0)
        return jdbc.query(
            """
            SELECT i.* FROM analysis_invocations i JOIN coordination_runs r ON r.id = i.coordination_run_id
            WHERE i.deadline_at <= ? AND (
                (i.finished_at IS NULL AND r.status = 'STRUCTURING' AND r.version = i.run_version)
                OR (i.winner_attempt_id IS NOT NULL AND r.status = 'MATCHING' AND r.version = i.run_version + 1)
            ) ORDER BY i.deadline_at, i.id LIMIT ?
            """.trimIndent(),
            mapper,
            now.atOffset(ZoneOffset.UTC),
            limit,
        )
    }

    override fun hasAttempt(
        invocationId: UUID,
        attemptId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM coordination_attempts WHERE invocation_id = ? AND id = ?)",
            Boolean::class.java,
            invocationId,
            attemptId,
        ) == true

    override fun insert(invocation: AnalysisInvocation) {
        jdbc.update(
            """
            INSERT INTO analysis_invocations(id, coordination_run_id, run_version, started_at, deadline_at, owner_token,
                gemini_attempts, luna_attempts, finished_at, winner_attempt_id, policy_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            invocation.id,
            invocation.runId.value,
            invocation.runVersion,
            invocation.startedAt.atOffset(ZoneOffset.UTC),
            invocation.deadlineAt.atOffset(ZoneOffset.UTC),
            invocation.ownerToken,
            invocation.geminiAttempts,
            invocation.lunaAttempts,
            invocation.finishedAt?.atOffset(ZoneOffset.UTC),
            invocation.winnerAttemptId,
            AnalysisInvocation.POLICY_VERSION,
        )
    }

    override fun update(invocation: AnalysisInvocation) {
        val updated =
            jdbc.update(
                """
                UPDATE analysis_invocations SET gemini_attempts = ?, luna_attempts = ?, finished_at = ?, winner_attempt_id = ?
                WHERE id = ? AND coordination_run_id = ? AND run_version = ? AND owner_token = ?
                """.trimIndent(),
                invocation.geminiAttempts,
                invocation.lunaAttempts,
                invocation.finishedAt?.atOffset(ZoneOffset.UTC),
                invocation.winnerAttemptId,
                invocation.id,
                invocation.runId.value,
                invocation.runVersion,
                invocation.ownerToken,
            )
        check(updated == 1) { "Analysis invocation ownership changed" }
    }

    private val mapper =
        RowMapper { rs, _ ->
            AnalysisInvocation(
                rs.getObject("id", UUID::class.java),
                CoordinationRunId(rs.getObject("coordination_run_id", UUID::class.java)),
                rs.getLong("run_version"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("deadline_at").toInstant(),
                rs.getObject("owner_token", UUID::class.java),
                rs.getInt("gemini_attempts"),
                rs.getInt("luna_attempts"),
                rs.getTimestamp("finished_at")?.toInstant(),
                rs.getObject("winner_attempt_id", UUID::class.java),
            )
        }
}
