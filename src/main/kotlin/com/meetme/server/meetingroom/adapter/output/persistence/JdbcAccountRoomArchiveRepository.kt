package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.input.AccountRoomView
import com.meetme.server.meetingroom.application.port.output.AccountRoomArchivePort
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
    override fun findHostedBy(
        userId: UUID,
        retainedSince: Instant,
    ): List<AccountRoomView> =
        jdbc.query(
            """
            SELECT r.invite_code, r.purpose, r.created_at,
                CASE WHEN r.collection_status = 'CLOSED' THEN r.closed_at ELSE r.created_at END AS retention_anchor
            FROM meeting_rooms r JOIN participants p ON p.room_id = r.id
            WHERE p.user_id = ? AND p.role = 'HOST'
              AND ((r.collection_status = 'CLOSED' AND r.closed_at >= ?)
                OR (r.collection_status = 'COLLECTING' AND r.created_at >= ?))
            ORDER BY r.created_at DESC, r.id
            """.trimIndent(),
            { rs, _ ->
                AccountRoomView(
                    rs.getString("invite_code"),
                    rs.getString("purpose"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("retention_anchor").toInstant().plus(Duration.ofDays(30)),
                )
            },
            userId,
            retainedSince.atOffset(ZoneOffset.UTC),
            retainedSince.atOffset(ZoneOffset.UTC),
        )
}
