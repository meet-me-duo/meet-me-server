package com.meetme.server.meetingroom.application.service

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.meetingroom.application.port.input.AccountRoomsResult
import com.meetme.server.meetingroom.application.port.input.GetRoomUseCase
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.input.RoomOwnershipUseCase
import com.meetme.server.meetingroom.application.port.input.RoomView
import com.meetme.server.meetingroom.application.port.output.AccountRoomArchivePort
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class RoomOwnershipService(
    private val accounts: CurrentAccountPort,
    private val rooms: MeetingRoomRepository,
    private val participants: ParticipantRepository,
    private val archive: AccountRoomArchivePort,
    private val getRoom: GetRoomUseCase,
    private val clock: Clock,
    private val ownership: ParticipantOwnershipResolver,
) : RoomOwnershipUseCase {
    @Transactional
    override fun claim(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView {
        val account = accounts.currentAccount() ?: fail(RoomLifecycleErrorCode.ACCOUNT_REQUIRED)
        val code = runCatching { InviteCode.of(inviteCode) }.getOrNull() ?: fail(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val room = rooms.findByInviteCodeForUpdate(code) ?: fail(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val participant = ownership.requireVerifiedGuestForClaim(room.id, rawCredential)
        if (participants.findByRoomAndUser(room.id, account.accountId) != null) fail(RoomLifecycleErrorCode.PARTICIPANT_CONFLICT)
        if (!participants.claimGuest(
                participant.id,
                requireNotNull(participant.guestSessionId),
                account.accountId,
            )
        ) {
            fail(RoomLifecycleErrorCode.PARTICIPANT_CONFLICT)
        }
        return getRoom.get(inviteCode, rawCredential)
    }

    @Transactional(readOnly = true)
    override fun accountRooms(): AccountRoomsResult {
        val account = accounts.currentAccount() ?: fail(RoomLifecycleErrorCode.ACCOUNT_REQUIRED)
        return AccountRoomsResult(archive.findHostedBy(account.accountId, clock.instant().minus(Duration.ofDays(30))))
    }

    private fun fail(code: RoomLifecycleErrorCode): Nothing = throw RoomLifecycleException(code)
}
