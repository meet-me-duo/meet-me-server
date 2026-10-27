package com.meetme.server.participant.domain

import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import java.time.Instant
import java.util.UUID

enum class ParticipantRole {
    HOST,
    MEMBER,
}

data class GuestSession(
    val id: GuestSessionId,
    val credentialDigest: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val createdAt: Instant,
) {
    init {
        require(credentialDigest.isNotBlank()) { "Credential digest must not be blank" }
        require(expiresAt > createdAt) { "Guest session expiry must be after creation" }
        require(revokedAt == null || revokedAt >= createdAt) { "Revocation cannot precede creation" }
    }

    fun isActive(at: Instant): Boolean = revokedAt == null && at < expiresAt
}

data class Participant private constructor(
    val id: ParticipantId,
    val roomId: MeetingRoomId,
    val guestSessionId: GuestSessionId?,
    val displayName: ParticipantDisplayName,
    val role: ParticipantRole,
    val joinedAt: Instant,
    val userId: UUID? = null,
) {
    init {
        require((guestSessionId == null) != (userId == null)) { "Participant must have exactly one owner" }
    }

    companion object {
        fun host(
            id: ParticipantId,
            roomId: MeetingRoomId,
            guestSessionId: GuestSessionId,
            displayName: ParticipantDisplayName,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, guestSessionId, displayName, ParticipantRole.HOST, joinedAt)

        fun member(
            id: ParticipantId,
            roomId: MeetingRoomId,
            guestSessionId: GuestSessionId,
            displayName: ParticipantDisplayName,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, guestSessionId, displayName, ParticipantRole.MEMBER, joinedAt)

        fun restore(
            id: ParticipantId,
            roomId: MeetingRoomId,
            guestSessionId: GuestSessionId?,
            displayName: ParticipantDisplayName,
            role: ParticipantRole,
            joinedAt: Instant,
            userId: UUID? = null,
        ): Participant = Participant(id, roomId, guestSessionId, displayName, role, joinedAt, userId)

        fun account(
            id: ParticipantId,
            roomId: MeetingRoomId,
            userId: UUID,
            displayName: ParticipantDisplayName,
            role: ParticipantRole,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, null, displayName, role, joinedAt, userId)
    }
}
