package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.MeetingRoomId
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.ZoneOffset
import java.util.UUID

@Repository
class KomapperMeetingRoomRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : MeetingRoomRepository {
    override fun insert(room: MeetingRoom) {
        database.runQuery { QueryDsl.insert(Meta.meetingRoomRecord).single(PersistenceMappers.toRecord(room)) }
    }

    override fun insertIfInviteAvailable(room: MeetingRoom): Boolean {
        val record = PersistenceMappers.toRecord(room)
        return jdbcTemplate.update(
            """
            INSERT INTO meeting_rooms (
                id, invite_code, purpose, meeting_mode, time_zone_id,
                search_start_date, search_end_date, search_range_source,
                expected_participants, submission_deadline, manual_only,
                collection_status, closure_reason, closed_at, created_at, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (invite_code) DO NOTHING
            """.trimIndent(),
            record.id,
            record.inviteCode,
            record.purpose,
            record.meetingMode,
            record.timeZoneId,
            record.searchStartDate,
            record.searchEndDate,
            record.searchRangeSource,
            record.expectedParticipants,
            record.submissionDeadline,
            record.manualOnly,
            record.collectionStatus,
            record.closureReason,
            record.closedAt,
            record.createdAt,
            record.version,
        ) == 1
    }

    override fun update(room: MeetingRoom) {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE meeting_rooms
                SET collection_status = ?, closure_reason = ?, closed_at = ?, version = ?
                WHERE id = ? AND version = ?
                """.trimIndent(),
                room.collectionStatus.name,
                room.closureReason?.name,
                room.closedAt?.atOffset(java.time.ZoneOffset.UTC),
                room.version,
                room.id.value,
                room.version - 1,
            )
        check(updated == 1) { "Meeting room update lost an optimistic concurrency race" }
    }

    override fun findById(id: MeetingRoomId): MeetingRoom? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.meetingRoomRecord)
                    .where { Meta.meetingRoomRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByInviteCode(inviteCode: InviteCode): MeetingRoom? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.meetingRoomRecord)
                    .where { Meta.meetingRoomRecord.inviteCode eq inviteCode.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByInviteCodeForUpdate(inviteCode: InviteCode): MeetingRoom? {
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM meeting_rooms WHERE invite_code = ? FOR UPDATE",
                    { resultSet, _ -> resultSet.getObject("id", java.util.UUID::class.java) },
                    inviteCode.value,
                ).firstOrNull() ?: return null
        return findById(MeetingRoomId(id))
    }

    override fun existsByInviteCode(inviteCode: InviteCode): Boolean = findByInviteCode(inviteCode) != null

    override fun findDueForUpdate(
        now: java.time.Instant,
        limit: Int,
    ): List<MeetingRoom> =
        jdbcTemplate
            .query(
                """
                SELECT id FROM meeting_rooms
                WHERE collection_status = 'COLLECTING'
                  AND submission_deadline IS NOT NULL
                  AND submission_deadline <= ?
                ORDER BY submission_deadline, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """.trimIndent(),
                { rs, _ -> MeetingRoomId(rs.getObject("id", UUID::class.java)) },
                now.atOffset(ZoneOffset.UTC),
                limit,
            ).mapNotNull(::findById)
}
