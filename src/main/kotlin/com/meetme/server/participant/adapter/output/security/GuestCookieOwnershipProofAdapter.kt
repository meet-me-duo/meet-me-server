package com.meetme.server.participant.adapter.output.security

import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestProofProvenance
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.shared.domain.MeetingRoomId
import java.time.Clock

class GuestCookieOwnershipProofAdapter(
    private val sessions: GuestSessionRepository,
    private val credentials: GuestCredentialPort,
    private val participants: ParticipantRepository,
    private val clock: Clock,
) : GuestOwnershipProofPort {
    override fun resolve(
        roomId: MeetingRoomId,
        rawCredential: String?,
        forUpdate: Boolean,
    ): GuestOwnershipProof? {
        if (rawCredential.isNullOrBlank()) return null
        val digest = credentials.digest(rawCredential)
        val session =
            (if (forUpdate) sessions.findByCredentialDigestForUpdate(digest) else sessions.findByCredentialDigest(digest))
                ?.takeIf { it.isActive(clock.instant()) } ?: return null
        val participant =
            participants.findByRoomAndGuestSession(roomId, session.id)
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.PARTICIPANT_REQUIRED)
        return GuestOwnershipProof(
            roomId,
            participant.id,
            GuestProofLevel.VERIFIED_GUEST,
            GuestProofProvenance(session.id, session.expiresAt),
        )
    }
}
