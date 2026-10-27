package com.meetme.server.participant.application.service

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestReaccessRepository
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.application.port.output.GuestSubmissionWriteGuard
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class GuestReaccessWriteGuard(
    private val accounts: CurrentAccountPort,
    private val proofs: GuestOwnershipProofPort,
    private val sessions: GuestSessionRepository,
    private val credentials: GuestCredentialPort,
    private val reaccess: GuestReaccessRepository,
    private val clock: Clock,
    private val prechecks: com.meetme.server.precheck.application.port.output.PrecheckRepository? = null,
) : GuestSubmissionWriteGuard {
    override fun validate(
        roomId: MeetingRoomId,
        participantId: ParticipantId,
        rawCredential: String?,
        currentRevision: Int,
        blindReplacement: Boolean,
    ) {
        if (accounts.currentAccount() != null) return
        val proof = proofs.resolve(roomId, rawCredential, false) ?: fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        if (proof.level != GuestProofLevel.REPLACE_ONLY) return
        if (!blindReplacement || proof.participantId != participantId) fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        val session =
            rawCredential
                ?.let { sessions.findByCredentialDigestForUpdate(credentials.digest(it)) }
                ?.takeIf { it.isActive(clock.instant()) } ?: fail(RoomLifecycleErrorCode.GUEST_SESSION_INVALID)
        val grant =
            reaccess
                .grant(roomId, session.id, true)
                ?.takeIf { clock.instant() < it.expiresAt && it.participantId == participantId && it.level == GuestProofLevel.REPLACE_ONLY }
                ?: fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        if (prechecks?.protocol(roomId.value) == "PRE_SUBMIT_V1") {
            if (grant.expectedCandidateRevision !=
                (prechecks.current(participantId.value)?.candidate?.revision ?: 0)
            ) {
                fail(RoomLifecycleErrorCode.REVISION_CONFLICT)
            }
        } else if (grant.expectedRevision != currentRevision) {
            fail(RoomLifecycleErrorCode.REVISION_CONFLICT)
        }
    }

    private fun fail(code: RoomLifecycleErrorCode): Nothing = throw RoomLifecycleException(code)
}
