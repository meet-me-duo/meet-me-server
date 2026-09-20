package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.output.RoomDataRetentionPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class RoomDataRetentionRepository(
    private val jdbcTemplate: JdbcTemplate,
) : RoomDataRetentionPort {
    @Transactional
    override fun deleteExpiredRooms(
        cutoff: Instant,
        limit: Int,
        now: Instant,
    ): Int {
        require(limit > 0)
        val roomIds =
            jdbcTemplate.query(
                """
                SELECT id FROM meeting_rooms
                WHERE (collection_status = 'CLOSED' AND closed_at < ?)
                   OR (collection_status = 'COLLECTING' AND created_at < ?)
                ORDER BY COALESCE(closed_at, created_at), id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> rs.getObject("id", UUID::class.java) },
                cutoff.atOffset(ZoneOffset.UTC),
                cutoff.atOffset(ZoneOffset.UTC),
                limit,
            )
        roomIds.forEach(::deleteRoom)
        jdbcTemplate.update(
            """
            DELETE FROM guest_browser_sessions g
            WHERE g.expires_at < ?
              AND NOT EXISTS (SELECT 1 FROM participants p WHERE p.guest_session_id = g.id)
            """.trimIndent(),
            now.atOffset(ZoneOffset.UTC),
        )
        return roomIds.size
    }

    private fun deleteRoom(roomId: UUID) {
        execute(
            """
            DELETE FROM coordination_worker_failures
            WHERE event_id IN (
                SELECT id FROM outbox_events
                WHERE aggregate_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)
            ) OR submission_batch_id IN (SELECT id FROM submission_batches WHERE room_id = ?)
            """.trimIndent(),
            roomId,
            roomId,
        )
        execute("DELETE FROM final_confirmations WHERE coordination_run_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)", roomId)
        execute(
            "DELETE FROM candidate_time_ranges WHERE candidate_id IN (SELECT id FROM candidates WHERE coordination_run_id IN (SELECT id FROM coordination_runs WHERE room_id = ?))",
            roomId,
        )
        execute(
            "DELETE FROM candidate_participants WHERE coordination_run_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)",
            roomId,
        )
        execute("DELETE FROM candidates WHERE coordination_run_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)", roomId)
        execute("DELETE FROM normalized_places WHERE coordination_run_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)", roomId)
        execute("DELETE FROM structured_submission_results WHERE batch_id IN (SELECT id FROM submission_batches WHERE room_id = ?)", roomId)
        execute(
            "DELETE FROM coordination_attempts WHERE coordination_run_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)",
            roomId,
        )
        execute("DELETE FROM outbox_events WHERE aggregate_id IN (SELECT id FROM coordination_runs WHERE room_id = ?)", roomId)
        execute("DELETE FROM coordination_runs WHERE room_id = ?", roomId)
        execute("DELETE FROM submission_batch_items WHERE batch_id IN (SELECT id FROM submission_batches WHERE room_id = ?)", roomId)
        execute("DELETE FROM submission_batches WHERE room_id = ?", roomId)
        execute(
            "DELETE FROM manual_availability_intervals WHERE submission_version_id IN (SELECT sv.id FROM submission_versions sv JOIN submission_heads sh ON sh.id = sv.submission_id WHERE sh.room_id = ?)",
            roomId,
        )
        execute("UPDATE submission_heads SET latest_version_id = NULL WHERE room_id = ?", roomId)
        execute("DELETE FROM submission_versions WHERE submission_id IN (SELECT id FROM submission_heads WHERE room_id = ?)", roomId)
        execute("DELETE FROM submission_heads WHERE room_id = ?", roomId)
        execute("DELETE FROM participants WHERE room_id = ?", roomId)
        execute("DELETE FROM meeting_rooms WHERE id = ?", roomId)
    }

    private fun execute(
        sql: String,
        vararg arguments: Any,
    ) {
        jdbcTemplate.update(sql, *arguments)
    }
}
