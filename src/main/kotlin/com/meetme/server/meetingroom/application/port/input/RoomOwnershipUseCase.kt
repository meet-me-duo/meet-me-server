package com.meetme.server.meetingroom.application.port.input

import java.time.Instant

interface RoomOwnershipUseCase {
    fun claim(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView

    fun accountRooms(): AccountRoomsResult
}

data class AccountRoomView(
    val inviteCode: String,
    val purpose: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class AccountRoomsResult(
    val rooms: List<AccountRoomView>,
)
