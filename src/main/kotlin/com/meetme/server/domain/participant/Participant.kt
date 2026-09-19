package com.meetme.server.domain.participant

import com.meetme.server.domain.common.GuestSessionId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.ParticipantId
import java.time.Instant

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
    val guestSessionId: GuestSessionId,
    val displayName: ParticipantDisplayName,
    val role: ParticipantRole,
    val joinedAt: Instant,
) {
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
            guestSessionId: GuestSessionId,
            displayName: ParticipantDisplayName,
            role: ParticipantRole,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, guestSessionId, displayName, role, joinedAt)
    }
}
