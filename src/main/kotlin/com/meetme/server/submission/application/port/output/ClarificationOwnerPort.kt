package com.meetme.server.submission.application.port.output

import com.meetme.server.participant.domain.Participant
import com.meetme.server.shared.domain.MeetingRoomId

/** Implement by delegating to #109's shared account/guest ownership resolver. No guest fallback lives in this feature. */
fun interface ClarificationOwnerPort {
    fun requireOwner(
        roomId: MeetingRoomId,
        rawGuestCredential: String?,
    ): Participant
}
