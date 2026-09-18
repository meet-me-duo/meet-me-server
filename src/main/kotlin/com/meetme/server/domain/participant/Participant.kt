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
}

data class Participant private constructor(
    val id: ParticipantId,
    val roomId: MeetingRoomId,
    val guestSessionId: GuestSessionId,
    val role: ParticipantRole,
    val joinedAt: Instant,
) {
    companion object {
        fun host(
            id: ParticipantId,
            roomId: MeetingRoomId,
            guestSessionId: GuestSessionId,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, guestSessionId, ParticipantRole.HOST, joinedAt)

        fun member(
            id: ParticipantId,
            roomId: MeetingRoomId,
            guestSessionId: GuestSessionId,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, guestSessionId, ParticipantRole.MEMBER, joinedAt)

        fun restore(
            id: ParticipantId,
            roomId: MeetingRoomId,
            guestSessionId: GuestSessionId,
            role: ParticipantRole,
            joinedAt: Instant,
        ): Participant = Participant(id, roomId, guestSessionId, role, joinedAt)
    }
}
