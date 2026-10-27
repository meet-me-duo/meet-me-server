package com.meetme.server.participant.adapter.output.security

import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProof
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestReaccessRepository
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.shared.domain.MeetingRoomId
import java.time.Clock

class GuestReaccessOwnershipProofAdapter(
    private val sessions: GuestSessionRepository,
    private val credentials: GuestCredentialPort,
    private val participants: ParticipantRepository,
    private val reaccess: GuestReaccessRepository,
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
        participants.findByRoomAndGuestSession(roomId, session.id)?.let {
            return GuestOwnershipProof(
                roomId,
                it.id,
                GuestProofLevel.VERIFIED_GUEST,
                com.meetme.server.participant.application.port.output
                    .GuestProofProvenance(session.id, session.expiresAt),
            )
        }
        val stored =
            reaccess.grant(roomId, session.id, forUpdate)
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.PARTICIPANT_REQUIRED)
        val grant =
            stored.takeIf { clock.instant() < it.expiresAt }
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        val target =
            participants.findById(grant.participantId)?.takeIf {
                it.roomId == roomId && it.guestSessionId != null && it.userId == null
            } ?: throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        return GuestOwnershipProof(
            roomId,
            target.id,
            grant.level,
            com.meetme.server.participant.application.port.output.GuestProofProvenance(
                session.id,
                minOf(session.expiresAt, grant.expiresAt),
                grant.generation,
            ),
        )
    }
}
