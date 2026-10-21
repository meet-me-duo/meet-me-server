package com.meetme.server.participant.application.port.input

import com.meetme.server.meetingroom.application.port.input.JoinRoomCommand
import com.meetme.server.meetingroom.application.port.input.RoomAccessResult
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestRecordSummary
import java.util.Locale
import java.util.UUID

data class GuestReaccessResult(
    val participantId: UUID,
    val level: GuestProofLevel,
    val newCredential: String?,
) {
    override fun toString(): String = "GuestReaccessResult(redacted)"
}

interface GuestReaccessUseCase {
    fun join(
        command: JoinRoomCommand,
        password: String?,
    ): RoomAccessResult

    fun records(inviteCode: String): List<GuestRecordSummary>

    fun reaccess(
        inviteCode: String,
        participantId: UUID,
        password: String?,
        rawCredential: String?,
        clientAddress: String,
    ): GuestReaccessResult

    fun replace(
        inviteCode: String,
        participantId: UUID,
        rawText: String?,
        rawCredential: String?,
        locale: Locale,
    )
}
