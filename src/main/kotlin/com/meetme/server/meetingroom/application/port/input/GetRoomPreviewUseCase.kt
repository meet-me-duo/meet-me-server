package com.meetme.server.meetingroom.application.port.input

/** Only the already-public meeting name; no viewer, participant or submission data. */
fun interface GetRoomPreviewUseCase {
    fun purpose(inviteCode: String): String?
}
