package com.meetme.server.meetingroom.application.port.output

import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.MeetingRoomId
import java.time.Instant

fun interface InviteCodeGenerator {
    fun next(): InviteCode
}

interface MeetingRoomRepository {
    fun insert(room: MeetingRoom)

    fun insertIfInviteAvailable(room: MeetingRoom): Boolean

    fun update(room: MeetingRoom)

    fun findById(id: MeetingRoomId): MeetingRoom?

    fun findByInviteCode(inviteCode: InviteCode): MeetingRoom?

    fun findByInviteCodeForUpdate(inviteCode: InviteCode): MeetingRoom?

    fun existsByInviteCode(inviteCode: InviteCode): Boolean

    fun findDueForUpdate(
        now: Instant,
        limit: Int,
    ): List<MeetingRoom>
}
