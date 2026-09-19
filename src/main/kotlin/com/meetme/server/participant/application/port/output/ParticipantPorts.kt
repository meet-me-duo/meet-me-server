package com.meetme.server.participant.application.port.output

import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import java.time.Instant

data class IssuedGuestCredential(
    val rawCredential: String,
    val credentialDigest: String,
    val expiresAt: Instant,
)

interface GuestCredentialPort {
    fun issue(createdAt: Instant): IssuedGuestCredential

    fun digest(rawCredential: String): String
}

interface GuestSessionRepository {
    fun insert(session: GuestSession)

    fun findById(id: GuestSessionId): GuestSession?

    fun findByCredentialDigest(credentialDigest: String): GuestSession?

    fun findByCredentialDigestForUpdate(credentialDigest: String): GuestSession?
}

interface ParticipantRepository {
    fun insert(participant: Participant)

    fun findById(id: ParticipantId): Participant?

    fun findByRoomAndGuestSession(
        roomId: MeetingRoomId,
        guestSessionId: GuestSessionId,
    ): Participant?

    fun countByRoom(roomId: MeetingRoomId): Int
}
