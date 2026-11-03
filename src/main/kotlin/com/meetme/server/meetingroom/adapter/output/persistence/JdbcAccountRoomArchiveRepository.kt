package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.input.AccountRoomView
import com.meetme.server.meetingroom.application.port.input.AccountRoomsQuery
import com.meetme.server.meetingroom.application.port.output.AccountRoomArchivePort
import com.meetme.server.participant.domain.ParticipantRole
import org.postgresql.PGStatement
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcAccountRoomArchiveRepository(
    private val jdbc: JdbcTemplate,
) : AccountRoomArchivePort {
    override fun hideByAccount(
        userId: UUID,
        roomId: UUID,
        hiddenAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE participants
            SET account_archive_hidden_at = COALESCE(account_archive_hidden_at, ?)
            WHERE room_id = ? AND user_id = ?
            """.trimIndent(),
            hiddenAt.atOffset(ZoneOffset.UTC),
            roomId,
            userId,
        ) == 1

    override fun findByAccount(
        userId: UUID,
        retainedSince: Instant,
        query: AccountRoomsQuery,
    ): List<AccountRoomView> {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<Any>(userId, retainedSince.atOffset(ZoneOffset.UTC))
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
        // The predicate still proves this role; avoid a heap fetch solely to return its known value.
        val roleProjection = query.role?.let { "'${it.name}'::varchar AS role" } ?: "p.role"
        return jdbc.query(
            """
            SELECT r.id, r.invite_code, r.purpose, r.created_at, $roleProjection
            FROM meeting_rooms r JOIN participants p ON p.room_id = r.id
            WHERE p.user_id = ?
              AND r.created_at >= ?
              AND p.account_archive_hidden_at IS NULL
              $additionalWhere
            ORDER BY r.created_at DESC, r.id ASC
            LIMIT ?
            """.trimIndent(),
            PreparedStatementSetter { statement ->
                // Account sizes vary sharply: let PostgreSQL plan this lookup for each bound account.
                // Keep the connection default and preparation of all other statements unchanged.
                statement.unwrap(PGStatement::class.java).prepareThreshold = 0
                ArgumentPreparedStatementSetter(arguments.toTypedArray()).setValues(statement)
            },
            { rs, _ ->
                AccountRoomView(
                    rs.getString("invite_code"),
                    rs.getString("purpose"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant().plus(Duration.ofDays(30)),
                    ParticipantRole.valueOf(rs.getString("role")),
                    rs.getObject("id", UUID::class.java),
                )
            },
        )
    }
}
