package com.meetme.server.meetingroom.application.port.output

import java.time.Instant

fun interface RoomDataRetentionPort {
    fun deleteExpiredRooms(
        cutoff: Instant,
        limit: Int,
        now: Instant,
    ): Int
}
