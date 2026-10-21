package com.meetme.server.participant.application.port.output

import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import java.time.Instant

data class GuestRecordSummary(
    val participantId: ParticipantId,
    val displayName: String,
)

data class GuestReaccessGrant(
    val roomId: MeetingRoomId,
    val sessionId: GuestSessionId,
    val participantId: ParticipantId,
    val level: GuestProofLevel,
    val expectedRevision: Int,
    val expiresAt: Instant,
    val expectedCandidateRevision: Int? = null,
    val generation: java.util.UUID? = null,
) {
    override fun toString(): String = "GuestReaccessGrant(redacted)"
}

interface GuestReaccessRepository {
    fun records(roomId: MeetingRoomId): List<GuestRecordSummary>

    fun passwordHash(participantId: ParticipantId): String?

    fun protect(
        participantId: ParticipantId,
        passwordHash: String,
    )

    fun grant(
        roomId: MeetingRoomId,
        sessionId: GuestSessionId,
        forUpdate: Boolean = false,
    ): GuestReaccessGrant?

    fun saveGrant(
        grant: GuestReaccessGrant,
        createdAt: Instant,
    )
}
