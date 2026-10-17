package com.meetme.server.participant.adapter.output.persistence

import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestReaccessGrant
import com.meetme.server.participant.application.port.output.GuestReaccessRepository
import com.meetme.server.participant.application.port.output.GuestRecordSummary
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcGuestReaccessRepository(
    private val jdbc: JdbcTemplate,
) : GuestReaccessRepository {
    override fun records(roomId: MeetingRoomId): List<GuestRecordSummary> =
        jdbc.query(
            "SELECT id, display_name FROM participants WHERE room_id = ? AND user_id IS NULL AND guest_session_id IS NOT NULL ORDER BY joined_at, id",
            {
                rs,
                _,
                ->
                GuestRecordSummary(ParticipantId(rs.getObject("id", UUID::class.java)), rs.getString("display_name"))
            },
            roomId.value,
        )

    override fun passwordHash(participantId: ParticipantId): String? =
        jdbc
            .query(
                "SELECT password_hash FROM guest_participant_passwords WHERE participant_id = ?",
                { rs, _ -> rs.getString(1) },
                participantId.value,
            ).firstOrNull()

    override fun protect(
        participantId: ParticipantId,
        passwordHash: String,
    ) {
        jdbc.update(
            "INSERT INTO guest_participant_passwords(participant_id, password_hash) VALUES (?, ?)",
            participantId.value,
            passwordHash,
        )
    }

    override fun grant(
        roomId: MeetingRoomId,
        sessionId: GuestSessionId,
        forUpdate: Boolean,
    ): GuestReaccessGrant? =
        jdbc
            .query(
                "SELECT * FROM guest_reaccess_grants WHERE room_id = ? AND guest_session_id = ?" + if (forUpdate) " FOR UPDATE" else "",
                { rs, _ ->
                    GuestReaccessGrant(
                        roomId,
                        sessionId,
                        ParticipantId(rs.getObject("participant_id", UUID::class.java)),
                        GuestProofLevel.valueOf(rs.getString("level")),
                        rs.getInt("expected_revision"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("expected_candidate_revision", Integer::class.java)?.toInt(),
                        rs.getObject("grant_generation", java.util.UUID::class.java),
                    )
                },
                roomId.value,
                sessionId.value,
            ).firstOrNull()

    override fun saveGrant(
        grant: GuestReaccessGrant,
        createdAt: Instant,
    ) {
        jdbc.update(
            """INSERT INTO guest_reaccess_grants(room_id,guest_session_id,participant_id,level,expected_revision,expires_at,created_at,expected_candidate_revision,grant_generation)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(room_id,guest_session_id) DO UPDATE SET level=EXCLUDED.level,
                expected_revision=EXCLUDED.expected_revision,expires_at=EXCLUDED.expires_at,
                expected_candidate_revision=EXCLUDED.expected_candidate_revision,grant_generation=EXCLUDED.grant_generation""",
            grant.roomId.value,
            grant.sessionId.value,
            grant.participantId.value,
            grant.level.name,
            grant.expectedRevision,
            Timestamp.from(grant.expiresAt),
            Timestamp.from(createdAt),
            grant.expectedCandidateRevision,
            grant.generation,
        )
    }
}
