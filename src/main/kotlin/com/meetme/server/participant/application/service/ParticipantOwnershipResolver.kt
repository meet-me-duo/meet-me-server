package com.meetme.server.participant.application.service

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.domain.MeetingRoomId

enum class OwnershipOperation {
    READ_OWN,
    REPLACE_OWN,
    HOST_COMMAND,
    CLAIM_ACCOUNT,
}

class ParticipantOwnershipResolver(
    private val accounts: CurrentAccountPort,
    private val proofs: GuestOwnershipProofPort,
    private val participants: ParticipantRepository,
) {
    fun resolve(
        roomId: MeetingRoomId,
        rawCredential: String?,
        operation: OwnershipOperation,
    ): Participant {
        if (operation == OwnershipOperation.CLAIM_ACCOUNT) return requireVerifiedGuestForClaim(roomId, rawCredential)
        val account = accounts.currentAccount()
        if (account != null) {
            val participant =
                participants
                    .findByRoomAndUser(roomId, account.accountId)
                    ?.takeIf { it.roomId == roomId && it.userId == account.accountId }
                    ?: fail(RoomLifecycleErrorCode.PARTICIPANT_REQUIRED)
            if (operation == OwnershipOperation.HOST_COMMAND && participant.role != ParticipantRole.HOST) {
                fail(RoomLifecycleErrorCode.HOST_PERMISSION_REQUIRED)
            }
            return participant
        }
        if (operation == OwnershipOperation.HOST_COMMAND) fail(RoomLifecycleErrorCode.ACCOUNT_REQUIRED)
        return guest(roomId, rawCredential, operation, false)
    }

    fun requireVerifiedGuestForClaim(
        roomId: MeetingRoomId,
        rawCredential: String?,
    ): Participant {
        accounts.currentAccount() ?: fail(RoomLifecycleErrorCode.ACCOUNT_REQUIRED)
        return guest(roomId, rawCredential, OwnershipOperation.CLAIM_ACCOUNT, true)
    }

    private fun guest(
        roomId: MeetingRoomId,
        rawCredential: String?,
        operation: OwnershipOperation,
        forUpdate: Boolean,
    ): Participant {
        val proof =
            proofs.resolve(roomId, rawCredential, forUpdate)
                ?: fail(
                    if (rawCredential.isNullOrBlank()) {
                        RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED
                    } else {
                        RoomLifecycleErrorCode.GUEST_SESSION_INVALID
                    },
                )
        if (proof.roomId != roomId || (proof.level == GuestProofLevel.REPLACE_ONLY && operation != OwnershipOperation.REPLACE_OWN)) {
            fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        }
        return participants
            .findById(proof.participantId)
            ?.takeIf { it.id == proof.participantId && it.roomId == roomId && it.guestSessionId != null && it.userId == null }
            ?: fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
    }

    private fun fail(code: RoomLifecycleErrorCode): Nothing = throw RoomLifecycleException(code)
}
