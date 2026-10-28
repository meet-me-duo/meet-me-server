package com.meetme.server.meetingroom.application.port.output

import com.meetme.server.meetingroom.application.port.input.AccountRoomView
import com.meetme.server.meetingroom.application.port.input.AccountRoomsQuery
import java.time.Instant
import java.util.UUID

interface AccountRoomArchivePort {
    fun findByAccount(
        userId: UUID,
        retainedSince: Instant,
        query: AccountRoomsQuery,
    ): List<AccountRoomView>
}
