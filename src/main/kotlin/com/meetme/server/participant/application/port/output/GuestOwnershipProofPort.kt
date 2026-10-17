package com.meetme.server.participant.application.port.output

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId

enum class GuestProofLevel { VERIFIED_GUEST, REPLACE_ONLY }

data class GuestOwnershipProof(
    val roomId: MeetingRoomId,
    val participantId: ParticipantId,
    val level: GuestProofLevel,
)

interface GuestOwnershipProofPort {
    fun resolve(
        roomId: MeetingRoomId,
        rawCredential: String?,
        forUpdate: Boolean = false,
    ): GuestOwnershipProof?
}
