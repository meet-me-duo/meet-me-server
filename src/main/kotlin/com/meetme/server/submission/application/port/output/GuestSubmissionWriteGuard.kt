package com.meetme.server.submission.application.port.output

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId

fun interface GuestSubmissionWriteGuard {
    fun validate(
        roomId: MeetingRoomId,
        participantId: ParticipantId,
        rawCredential: String?,
        currentRevision: Int,
        blindReplacement: Boolean,
    )
}
