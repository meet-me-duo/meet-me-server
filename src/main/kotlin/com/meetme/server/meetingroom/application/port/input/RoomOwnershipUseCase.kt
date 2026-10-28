package com.meetme.server.meetingroom.application.port.input

import com.meetme.server.participant.domain.ParticipantRole
import java.time.Instant
import java.util.UUID

interface RoomOwnershipUseCase {
    fun claim(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView

    fun accountRooms(query: AccountRoomsQuery = AccountRoomsQuery()): AccountRoomsResult
}

data class AccountRoomsQuery(
    val role: ParticipantRole? = null,
    val createdFrom: Instant? = null,
    val createdBefore: Instant? = null,
    val query: String? = null,
    val limit: Int = 20,
    val cursor: AccountRoomCursor? = null,
) {
    init {
        require(limit in 1..100)
        require(createdFrom == null || createdBefore == null || createdFrom < createdBefore)
        require(query == null || query.codePointCount(0, query.length) <= 100)
    }
}

data class AccountRoomCursor(
    val createdAt: Instant,
    val roomId: UUID,
)

data class AccountRoomView(
    val inviteCode: String,
    val purpose: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val role: ParticipantRole,
    val roomId: UUID,
)

data class AccountRoomsResult(
    val rooms: List<AccountRoomView>,
    val nextCursor: AccountRoomCursor? = null,
)
