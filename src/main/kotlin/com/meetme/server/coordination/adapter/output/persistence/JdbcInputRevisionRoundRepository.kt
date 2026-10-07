package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.InputRevisionRoundRepository
import com.meetme.server.coordination.domain.InputRevisionRound
import com.meetme.server.coordination.domain.RevisionAnalysisOutcome
import com.meetme.server.coordination.domain.RevisionRoundStatus
import com.meetme.server.shared.domain.MeetingRoomId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcInputRevisionRoundRepository(
    private val jdbc: JdbcTemplate,
) : InputRevisionRoundRepository {
    override fun insert(round: InputRevisionRound) {
        jdbc.update(
            """
            INSERT INTO input_revision_rounds
                (id, room_id, generation, source_run_id, reopen_request_id, expected_generation, status, opened_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            round.id,
            round.roomId.value,
            round.generation,
            round.sourceRunId,
            round.reopenRequestId,
            round.expectedGeneration,
            round.status.name,
            round.openedAt.atOffset(ZoneOffset.UTC),
        )
    }

    override fun update(round: InputRevisionRound) {
        val changed =
            jdbc.update(
                """
                UPDATE input_revision_rounds
                SET status = ?, consumed_at = ?, analyze_request_id = ?, force_reparse = ?, resolved_run_id = ?, outcome = ?
                WHERE id = ? AND room_id = ? AND status = 'OPEN'
                """.trimIndent(),
                round.status.name,
                round.consumedAt?.atOffset(ZoneOffset.UTC),
                round.analyzeRequestId,
                round.forceReparse,
                round.resolvedRunId,
                round.outcome?.name,
                round.id,
                round.roomId.value,
            )
        check(changed == 1) { "Revision round was concurrently consumed" }
    }

    override fun findById(
        roomId: MeetingRoomId,
        id: UUID,
    ) = find(roomId, "id", id)

    override fun findByReopenRequest(
        roomId: MeetingRoomId,
        requestId: UUID,
    ) = find(roomId, "reopen_request_id", requestId)

    override fun findByAnalyzeRequest(
        roomId: MeetingRoomId,
        requestId: UUID,
    ) = find(roomId, "analyze_request_id", requestId)

    private fun find(
        roomId: MeetingRoomId,
        column: String,
        id: UUID,
    ): InputRevisionRound? =
        jdbc
            .query("SELECT * FROM input_revision_rounds WHERE room_id = ? AND $column = ?", { rs, _ -> map(rs) }, roomId.value, id)
            .firstOrNull()

    private fun map(rs: ResultSet) =
        InputRevisionRound(
            id = rs.getObject("id", UUID::class.java),
            roomId = MeetingRoomId(rs.getObject("room_id", UUID::class.java)),
            generation = rs.getLong("generation"),
            sourceRunId = rs.getObject("source_run_id", UUID::class.java),
            reopenRequestId = rs.getObject("reopen_request_id", UUID::class.java),
            expectedGeneration = rs.getLong("expected_generation"),
            openedAt = rs.getObject("opened_at", OffsetDateTime::class.java).toInstant(),
            status = RevisionRoundStatus.valueOf(rs.getString("status")),
            consumedAt = rs.getObject("consumed_at", OffsetDateTime::class.java)?.toInstant(),
            analyzeRequestId = rs.getObject("analyze_request_id", UUID::class.java),
            forceReparse = rs.getObject("force_reparse") as Boolean?,
            resolvedRunId = rs.getObject("resolved_run_id", UUID::class.java),
            outcome = rs.getString("outcome")?.let(RevisionAnalysisOutcome::valueOf),
        )
}
