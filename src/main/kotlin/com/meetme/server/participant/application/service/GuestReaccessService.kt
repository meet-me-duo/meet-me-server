package com.meetme.server.participant.application.service

import com.meetme.server.account.application.port.output.CurrentAccountPort
import com.meetme.server.meetingroom.application.port.input.JoinRoomCommand
import com.meetme.server.meetingroom.application.port.input.JoinRoomUseCase
import com.meetme.server.meetingroom.application.port.input.RoomAccessResult
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.participant.application.port.input.GuestReaccessResult
import com.meetme.server.participant.application.port.input.GuestReaccessUseCase
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestPasswordPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestReaccessAttemptPort
import com.meetme.server.participant.application.port.output.GuestReaccessGrant
import com.meetme.server.participant.application.port.output.GuestReaccessRepository
import com.meetme.server.participant.application.port.output.GuestRecordSummary
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestReaccessDecision
import com.meetme.server.participant.domain.GuestReaccessProof
import com.meetme.server.participant.domain.GuestReaccessRecord
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.application.port.input.SaveSubmissionCommand
import com.meetme.server.submission.application.port.input.SaveSubmissionUseCase
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Service
class GuestReaccessService(
    private val rooms: MeetingRoomRepository,
    private val participants: ParticipantRepository,
    private val sessions: GuestSessionRepository,
    private val credentials: GuestCredentialPort,
    private val reaccess: GuestReaccessRepository,
    private val passwords: GuestPasswordPort,
    private val policy: GuestReaccessPolicy,
    private val attempts: GuestReaccessAttemptPort,
    private val submissions: SubmissionRepository,
    private val joinRoom: JoinRoomUseCase,
    private val saveSubmission: SaveSubmissionUseCase,
    private val ownership: ParticipantOwnershipResolver,
    private val proofs: GuestOwnershipProofPort,
    private val accounts: CurrentAccountPort,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val prechecks: com.meetme.server.precheck.application.port.output.PrecheckRepository? = null,
    private val blindPrechecks: com.meetme.server.precheck.application.port.input.BlindPreSubmissionCheckUseCase? = null,
) : GuestReaccessUseCase {
    @Transactional
    override fun join(
        command: JoinRoomCommand,
        password: String?,
    ): RoomAccessResult {
        val account = accounts.currentAccount()
        val room = room(command.inviteCode, true)
        if (room.closurePolicy.deadline?.let { clock.instant() >= it } == true) fail(RoomLifecycleErrorCode.ROOM_CLOSED)
        val result = joinRoom.join(command)
        if (account == null && result.participantCreated && password != null) {
            val id = ParticipantId(requireNotNull(result.room.viewer.contextId))
            val participant = participants.findById(id) ?: fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
            check(participant.userId == null && participant.guestSessionId != null)
            reaccess.protect(id, passwords.hash(password))
        }
        return result
    }

    @Transactional(readOnly = true)
    override fun records(inviteCode: String): List<GuestRecordSummary> = reaccess.records(room(inviteCode, false).id)

    @Transactional
    override fun reaccess(
        inviteCode: String,
        participantId: UUID,
        password: String?,
        rawCredential: String?,
        clientAddress: String,
    ): GuestReaccessResult {
        val publicRoom = room(inviteCode, false)
        attempts.check(publicRoom.id.value, participantId, clientAddress)
        val room = room(inviteCode, true)
        val now = clock.instant()
        val activeSession =
            rawCredential?.takeIf { it.isNotBlank() }?.let {
                sessions.findByCredentialDigestForUpdate(credentials.digest(it))?.takeIf { session -> session.isActive(now) }
            }
        val participant = participants.findById(ParticipantId(participantId))
        val record =
            participant?.let {
                GuestReaccessRecord(it.roomId.value, it.id.value, it.guestSessionId?.value, reaccess.passwordHash(it.id), it.userId != null)
            }
        val decision = policy.evaluate(record, GuestReaccessProof(room.id.value, participantId, activeSession?.id?.value, password))
        if (decision == GuestReaccessDecision.DENIED) fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        val target = requireNotNull(participant)
        if (activeSession != null) {
            val original = participants.findByRoomAndGuestSession(room.id, activeSession.id)
            val grant = reaccess.grant(room.id, activeSession.id, true)
            if ((original != null && original.id != target.id) || (grant != null && grant.participantId != target.id)) {
                fail(RoomLifecycleErrorCode.PARTICIPANT_CONFLICT)
            }
        }
        if (decision == GuestReaccessDecision.OWNER) {
            return GuestReaccessResult(participantId, GuestProofLevel.VERIFIED_GUEST, null)
        }
        var issuedRaw: String? = null
        val session =
            activeSession ?: run {
                val issued = credentials.issue(now)
                issuedRaw = issued.rawCredential
                GuestSession(GuestSessionId(ids.next()), issued.credentialDigest, issued.expiresAt, null, now).also(sessions::insert)
            }
        val level = if (decision == GuestReaccessDecision.REPLACE_ONLY) GuestProofLevel.REPLACE_ONLY else GuestProofLevel.VERIFIED_GUEST
        val existingGrant = reaccess.grant(room.id, session.id, true)
        // A stronger proof remains valid when this browser later omits the password.
        val grantedLevel = if (existingGrant?.level == GuestProofLevel.VERIFIED_GUEST) existingGrant.level else level
        reaccess.saveGrant(
            GuestReaccessGrant(
                room.id,
                session.id,
                target.id,
                grantedLevel,
                submissions.findByParticipant(target.id)?.latest?.revision ?: 0,
                session.expiresAt,
                if (prechecks?.protocol(room.id.value) ==
                    "PRE_SUBMIT_V1"
                ) {
                    prechecks.current(target.id.value)?.candidate?.revision ?: 0
                } else {
                    null
                },
                if (prechecks?.protocol(room.id.value) == "PRE_SUBMIT_V1") ids.next() else null,
            ),
            now,
        )
        return GuestReaccessResult(participantId, grantedLevel, issuedRaw)
    }

    @Transactional
    override fun replace(
        inviteCode: String,
        participantId: UUID,
        rawText: String?,
        rawCredential: String?,
        locale: Locale,
    ) {
        val room = room(inviteCode, true)
        if (accounts.currentAccount() != null) fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        val participant = ownership.resolve(room.id, rawCredential, OwnershipOperation.REPLACE_OWN)
        val proof = proofs.resolve(room.id, rawCredential, true)
        if (participant.id.value != participantId || proof?.level != GuestProofLevel.REPLACE_ONLY) {
            fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        }
        if (room.collectionStatus != CollectionStatus.COLLECTING || room.closurePolicy.deadline?.let { clock.instant() >= it } == true) {
            fail(RoomLifecycleErrorCode.ROOM_CLOSED)
        }
        if (prechecks?.protocol(room.id.value) == "PRE_SUBMIT_V1") {
            requireNotNull(blindPrechecks).saveBlind(inviteCode, participantId, rawText, rawCredential, locale)
        } else {
            saveSubmission.save(SaveSubmissionCommand(inviteCode, rawCredential, rawText, locale, blindReplacement = true))
        }
    }

    private fun room(
        inviteCode: String,
        lock: Boolean,
    ): com.meetme.server.meetingroom.domain.MeetingRoom {
        val code = runCatching { InviteCode.of(inviteCode) }.getOrNull() ?: fail(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        return (if (lock) rooms.findByInviteCodeForUpdate(code) else rooms.findByInviteCode(code))
            ?: fail(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
    }

    private fun fail(code: RoomLifecycleErrorCode): Nothing = throw RoomLifecycleException(code)
}
