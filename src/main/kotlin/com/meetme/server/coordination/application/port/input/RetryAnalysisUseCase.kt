package com.meetme.server.coordination.application.port.input

import com.meetme.server.meetingroom.application.port.input.RoomView

interface RetryAnalysisUseCase {
    fun retry(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView
}
