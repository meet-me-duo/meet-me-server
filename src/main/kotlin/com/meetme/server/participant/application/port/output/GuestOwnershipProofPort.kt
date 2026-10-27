package com.meetme.server.participant.application.port.output

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId

data class GuestProofProvenance(
    val sessionId: com.meetme.server.shared.domain.GuestSessionId,
    val validUntil: java.time.Instant,
    val grantGeneration: java.util.UUID? = null,
)

enum class GuestProofLevel { VERIFIED_GUEST, REPLACE_ONLY }

data class GuestOwnershipProof(
    val roomId: MeetingRoomId,
    val participantId: ParticipantId,
    val level: GuestProofLevel,
    val provenance: GuestProofProvenance? = null,
)

interface GuestOwnershipProofPort {
    fun resolve(
        roomId: MeetingRoomId,
        rawCredential: String?,
        forUpdate: Boolean = false,
    ): GuestOwnershipProof?
}
