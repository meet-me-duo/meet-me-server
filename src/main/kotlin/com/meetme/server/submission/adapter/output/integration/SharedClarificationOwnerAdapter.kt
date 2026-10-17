package com.meetme.server.submission.adapter.output.integration

import com.meetme.server.participant.application.service.OwnershipOperation
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import com.meetme.server.participant.domain.Participant
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.submission.application.port.output.ClarificationOwnerPort
import org.springframework.stereotype.Component

/** Both reading a question and answering it expose private input and require full read ownership. */
@Component
class SharedClarificationOwnerAdapter(
    private val ownership: ParticipantOwnershipResolver,
) : ClarificationOwnerPort {
    override fun requireOwner(
        roomId: MeetingRoomId,
        rawGuestCredential: String?,
    ): Participant = ownership.resolve(roomId, rawGuestCredential, OwnershipOperation.READ_OWN)
}
