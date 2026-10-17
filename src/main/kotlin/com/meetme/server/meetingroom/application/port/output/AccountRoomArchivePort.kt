package com.meetme.server.meetingroom.application.port.output

import com.meetme.server.meetingroom.application.port.input.AccountRoomView
import java.time.Instant
import java.util.UUID

interface AccountRoomArchivePort {
    fun findHostedBy(
        userId: UUID,
        retainedSince: Instant,
    ): List<AccountRoomView>
}
