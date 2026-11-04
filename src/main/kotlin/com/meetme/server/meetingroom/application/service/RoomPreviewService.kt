package com.meetme.server.meetingroom.application.service

import com.meetme.server.meetingroom.application.port.input.GetRoomPreviewUseCase
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.InviteCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoomPreviewService(
    private val rooms: MeetingRoomRepository,
) : GetRoomPreviewUseCase {
    @Transactional(readOnly = true)
    override fun purpose(inviteCode: String): String? {
        val code = runCatching { InviteCode.of(inviteCode) }.getOrNull() ?: return null
        return rooms.findByInviteCode(code)?.purpose
    }
}
