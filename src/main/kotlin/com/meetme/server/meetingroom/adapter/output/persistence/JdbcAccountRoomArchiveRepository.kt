package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.input.AccountRoomView
import com.meetme.server.meetingroom.application.port.input.AccountRoomsQuery
import com.meetme.server.meetingroom.application.port.output.AccountRoomArchivePort
import com.meetme.server.participant.domain.ParticipantRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcAccountRoomArchiveRepository(
    private val jdbc: JdbcTemplate,
) : AccountRoomArchivePort {
    override fun findByAccount(
        userId: UUID,
        retainedSince: Instant,
        query: AccountRoomsQuery,
    ): List<AccountRoomView> {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<Any>(userId, retainedSince.atOffset(ZoneOffset.UTC), retainedSince.atOffset(ZoneOffset.UTC))
        query.role?.let {
            conditions += "p.role = ?"
            arguments += it.name
        }
        query.createdFrom?.let {
            conditions += "r.created_at >= ?"
            arguments += it.atOffset(ZoneOffset.UTC)
        }
        query.createdBefore?.let {
            conditions += "r.created_at < ?"
            arguments += it.atOffset(ZoneOffset.UTC)
        }
        query.query?.takeIf { it.isNotBlank() }?.let {
            conditions += "strpos(lower(r.purpose), lower(?)) > 0"
            arguments += it
        }
        query.cursor?.let {
            conditions += "(r.created_at < ? OR (r.created_at = ? AND r.id > ?))"
            arguments += it.createdAt.atOffset(ZoneOffset.UTC)
            arguments += it.createdAt.atOffset(ZoneOffset.UTC)
            arguments += it.roomId
        }
        arguments += query.limit + 1
        val additionalWhere = conditions.joinToString("") { " AND $it" }
        return jdbc.query(
            """
            SELECT r.id, r.invite_code, r.purpose, r.created_at, p.role,
                CASE WHEN r.collection_status = 'CLOSED' THEN r.closed_at ELSE r.created_at END AS retention_anchor
            FROM meeting_rooms r JOIN participants p ON p.room_id = r.id
            WHERE p.user_id = ?
              AND ((r.collection_status = 'CLOSED' AND r.closed_at >= ?)
                OR (r.collection_status = 'COLLECTING' AND r.created_at >= ?))
              $additionalWhere
            ORDER BY r.created_at DESC, r.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                AccountRoomView(
                    rs.getString("invite_code"),
                    rs.getString("purpose"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("retention_anchor").toInstant().plus(Duration.ofDays(30)),
                    ParticipantRole.valueOf(rs.getString("role")),
                    rs.getObject("id", UUID::class.java),
                )
            },
            *arguments.toTypedArray(),
        )
    }
}
