package com.meetme.server.precheck.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestOwnershipProofPort
import com.meetme.server.participant.application.port.output.GuestProofLevel
import com.meetme.server.participant.application.port.output.GuestReaccessRepository
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.service.OwnershipOperation
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import com.meetme.server.participant.domain.Participant
import com.meetme.server.precheck.application.port.input.BlindPreSubmissionCheckUseCase
import com.meetme.server.precheck.application.port.input.CreatePrecheckCommand
import com.meetme.server.precheck.application.port.input.PreSubmissionCheckUseCase
import com.meetme.server.precheck.application.port.input.PrecheckAcceptedView
import com.meetme.server.precheck.application.port.input.PrecheckMutationCommand
import com.meetme.server.precheck.application.port.input.PrecheckOutcome
import com.meetme.server.precheck.application.port.input.PrecheckRoomView
import com.meetme.server.precheck.application.port.input.PrecheckSnapshot
import com.meetme.server.precheck.application.port.input.PrecheckTextView
import com.meetme.server.precheck.application.port.input.PrecheckView
import com.meetme.server.precheck.application.port.output.PrecheckOperation
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import com.meetme.server.submission.application.port.output.GuestSubmissionWriteGuard
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.SubmissionRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
class PreSubmissionCheckService(
    private val rooms: MeetingRoomRepository,
    private val ownership: ParticipantOwnershipResolver,
    private val guard: GuestSubmissionWriteGuard,
    private val submissions: SubmissionRepository,
    private val repository: PrecheckRepository,
    private val bindings: PrecheckOwnershipBindingPort,
    private val proofs: GuestOwnershipProofPort,
    private val sessions: GuestSessionRepository,
    private val credentials: GuestCredentialPort,
    private val grants: GuestReaccessRepository,
    private val runs: CoordinationRunRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : PreSubmissionCheckUseCase,
    BlindPreSubmissionCheckUseCase {
    @Transactional
    override fun create(command: CreatePrecheckCommand): PrecheckSnapshot {
        val room = room(command.inviteCode, true)
        val participant = ownership.resolve(room.id, command.rawCredential, OwnershipOperation.READ_OWN)
        validateWrite(room, participant, command.rawCredential)
        val text = normalize(command.rawText)
        val fingerprint = fingerprint(command.expectedRevision, text, command.locale.toLanguageTag())
        replay(room, participant, "CREATE", command.requestId, fingerprint)?.let { return it }
        requireOpen(room)
        val current = repository.current(participant.id.value)
        if (command.expectedRevision != (current?.candidate?.revision ?: 0)) conflict()
        val next = saveCandidate(room, participant, command.rawCredential, text, command.locale.toLanguageTag(), current)
        return record(room, participant, "CREATE", command.requestId, fingerprint, next)
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun current(
        inviteCode: String,
        rawCredential: String?,
    ): PrecheckSnapshot {
        val room = room(inviteCode, false)
        val participant = ownership.resolve(room.id, rawCredential, OwnershipOperation.READ_OWN)
        requireProtocol(room)
        return snapshot(room, participant)
    }

    @Transactional
    override fun answer(command: PrecheckMutationCommand): PrecheckSnapshot {
        val (room, participant) = writeContext(command)
        val fingerprint = mutationFingerprint(command)
        replay(room, participant, "ANSWER", command.requestId, fingerprint)?.let { return it }
        requireOpen(room)
        val current = expected(participant, command)
        if (current.status !in setOf(PrecheckStatus.NEEDS_CHOICE, PrecheckStatus.NEEDS_EDIT)) conflict()
        val direct = command.directText
        val question = current.question
        val choice =
            if (direct != null) {
                require(command.choiceId == null) { "Choose one answer method" }
                null
            } else {
                if (question == null || question.id != command.questionId) conflict()
                question.choices.singleOrNull { it.id == command.choiceId } ?: conflict()
            }
        val text = normalize(direct ?: requireNotNull(choice).proposedText)
        val next =
            saveCandidate(
                room,
                participant,
                command.rawCredential,
                text,
                current.candidate.locale,
                current,
                referenceAt = if (choice != null) current.candidate.referenceAt else null,
                sourceQuestionId = question?.id,
                choiceId = choice?.id,
                derived = true,
            )
        return record(room, participant, "ANSWER", command.requestId, fingerprint, next)
    }

    @Transactional
    override fun retry(command: PrecheckMutationCommand): PrecheckSnapshot {
        val (room, participant) = writeContext(command)
        val fingerprint = mutationFingerprint(command)
        replay(room, participant, "RETRY", command.requestId, fingerprint)?.let { return it }
        requireOpen(room)
        val current = expected(participant, command)
        if (current.status !in RETRYABLE ||
            current.retryCount != 0 ||
            repository.callCount(room.id.value, participant.id.value) >= MAX_CALLS ||
            !bindings.isActive(participant, current.candidate.ownerBinding, clock.instant())
        ) {
            conflict()
        }
        repository.lockAdmission()
        val queued = repository.queueSize() < MAX_QUEUE
        val next =
            current.copy(
                status = if (queued) PrecheckStatus.QUEUED else PrecheckStatus.FAILED_QUEUE,
                version = current.version + 1,
                generation = current.generation + 1,
                retryCount = 1,
                deadlineAt = clock.instant().plusSeconds(30),
                ownerToken = null,
                claimedAt = null,
                failureCode = if (queued) null else "QUEUE_FULL",
                question = null,
            )
        repository.update(next)
        requireOpen(room)
        return record(room, participant, "RETRY", command.requestId, fingerprint, next)
    }

    @Transactional
    override fun cancel(command: PrecheckMutationCommand): PrecheckSnapshot {
        val (room, participant) = writeContext(command)
        val fingerprint = mutationFingerprint(command)
        replay(room, participant, "CANCEL", command.requestId, fingerprint)?.let { return it }
        requireOpen(room)
        val current = expected(participant, command)
        if (current.status in
            setOf(PrecheckStatus.CLEAR, PrecheckStatus.CANCELLED, PrecheckStatus.SUPERSEDED, PrecheckStatus.CLOSED_BEFORE_COMPLETION)
        ) {
            conflict()
        }
        val next = current.copy(status = PrecheckStatus.CANCELLED, version = current.version + 1)
        repository.update(next)
        requireOpen(room)
        return record(room, participant, "CANCEL", command.requestId, fingerprint, next)
    }

    @Transactional
    override fun saveBlind(
        inviteCode: String,
        participantId: UUID,
        rawText: String?,
        rawCredential: String?,
        locale: Locale,
    ) {
        val room = room(inviteCode, true)
        requireProtocol(room)
        val participant = ownership.resolve(room.id, rawCredential, OwnershipOperation.REPLACE_OWN)
        val proof = proofs.resolve(room.id, rawCredential, true)
        if (participant.id.value != participantId ||
            proof?.level != GuestProofLevel.REPLACE_ONLY
        ) {
            fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        }
        requireOpen(room)
        val session =
            rawCredential
                ?.let { sessions.findByCredentialDigestForUpdate(credentials.digest(it)) }
                ?.takeIf { it.isActive(clock.instant()) } ?: fail(RoomLifecycleErrorCode.GUEST_SESSION_INVALID)
        val grant =
            grants
                .grant(room.id, session.id, true)
                ?.takeIf {
                    it.participantId == participant.id && it.level == GuestProofLevel.REPLACE_ONLY && clock.instant() < it.expiresAt
                }
                ?: fail(RoomLifecycleErrorCode.GUEST_PROOF_INSUFFICIENT)
        val current = repository.current(participant.id.value)
        if (grant.expectedCandidateRevision != (current?.candidate?.revision ?: 0) || grant.generation == null) conflict()
        guard.validate(room.id, participant.id, rawCredential, submissions.findByParticipant(participant.id)?.latest?.revision ?: 0, true)
        saveCandidate(room, participant, rawCredential, normalize(rawText), locale.toLanguageTag(), current)
        requireOpen(room)
    }

    private fun saveCandidate(
        room: MeetingRoom,
        participant: Participant,
        credential: String?,
        text: String,
        locale: String,
        previous: PrecheckExecution?,
        referenceAt: Instant? = null,
        sourceQuestionId: UUID? = null,
        choiceId: String? = null,
        derived: Boolean = false,
    ): PrecheckExecution {
        val revision = previous?.candidate?.revision ?: 0
        if (revision == Int.MAX_VALUE) conflict()
        if (repository.latestCandidateTextLength(room.id.value, participant.id.value) + text.codePointCount(0, text.length) >
            SubmissionRules.MAX_BATCH_TEXT_CODE_POINTS
        ) {
            throw SubmissionException(
                SubmissionErrorCode.SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED,
                mapOf(
                    "maximum" to SubmissionRules.MAX_BATCH_TEXT_CODE_POINTS,
                ),
            )
        }
        repository.lockAdmission()
        previous?.takeIf { it.status != PrecheckStatus.SUPERSEDED }?.let {
            repository.update(
                it.copy(
                    status = PrecheckStatus.SUPERSEDED,
                    version =
                        it.version + 1,
                ),
            )
        }
        val now = clock.instant()
        val exhausted = repository.callCount(room.id.value, participant.id.value) >= MAX_CALLS
        val full = repository.queueSize() >= MAX_QUEUE
        val candidate =
            PrecheckCandidate(
                ids.next(),
                room.id.value,
                participant.id.value,
                revision + 1,
                text,
                locale,
                now,
                referenceAt ?: now,
                bindings.capture(participant, credential),
                if (derived) previous?.candidate?.id else null,
                sourceQuestionId,
                choiceId,
            )
        val check =
            PrecheckExecution(
                ids.next(),
                candidate,
                when {
                    exhausted -> PrecheckStatus.LIMIT_REACHED
                    full -> PrecheckStatus.FAILED_QUEUE
                    else -> PrecheckStatus.QUEUED
                },
                1,
                1,
                0,
                now.plusSeconds(30),
                failureCode =
                    when {
                        exhausted -> "CALL_LIMIT_REACHED"
                        full -> "QUEUE_FULL"
                        else -> null
                    },
            )
        repository.insert(candidate, check)
        requireOpen(room)
        return check
    }

    private fun writeContext(command: PrecheckMutationCommand): Pair<MeetingRoom, Participant> {
        val room = room(command.inviteCode, true)
        val participant = ownership.resolve(room.id, command.rawCredential, OwnershipOperation.READ_OWN)
        validateWrite(room, participant, command.rawCredential)
        return room to participant
    }

    private fun validateWrite(
        room: MeetingRoom,
        participant: Participant,
        credential: String?,
    ) {
        requireProtocol(room)
        guard.validate(room.id, participant.id, credential, submissions.findByParticipant(participant.id)?.latest?.revision ?: 0, false)
    }

    private fun expected(
        participant: Participant,
        command: PrecheckMutationCommand,
    ): PrecheckExecution {
        val current = repository.current(participant.id.value) ?: conflict()
        if (current.id != command.checkId ||
            current.candidate.revision != command.expectedRevision ||
            current.version != command.expectedCheckVersion
        ) {
            conflict()
        }
        return current
    }

    private fun replay(
        room: MeetingRoom,
        participant: Participant,
        kind: String,
        requestId: UUID,
        fingerprint: String,
    ): PrecheckSnapshot? {
        val previous = repository.operation(participant.id.value, kind, requestId) ?: return null
        if (previous.fingerprint != fingerprint) conflict()
        return snapshot(room, participant, previous.outcome)
    }

    private fun record(
        room: MeetingRoom,
        participant: Participant,
        kind: String,
        requestId: UUID,
        fingerprint: String,
        check: PrecheckExecution,
    ): PrecheckSnapshot {
        val outcome = PrecheckOutcome(requestId, check.candidate.revision, check.id, check.status)
        repository.recordOperation(participant.id.value, kind, requestId, PrecheckOperation(fingerprint, outcome))
        return snapshot(room, participant, outcome)
    }

    private fun snapshot(
        room: MeetingRoom,
        participant: Participant,
        outcome: PrecheckOutcome? = null,
    ): PrecheckSnapshot {
        val check = repository.current(participant.id.value)
        val accepted = submissions.findByParticipant(participant.id)
        val now = clock.instant()
        val open = isOpen(room, now)
        val status = if (!open && check?.status in WAITING) PrecheckStatus.CLOSED_BEFORE_COMPLETION else check?.status
        val frozen =
            room.activeRunId
                ?.let {
                    runs.findById(
                        com.meetme.server.shared.domain
                            .CoordinationRunId(it),
                    )
                }?.batch
                ?.submissionVersionIds
        return PrecheckSnapshot(
            check?.candidate?.let { PrecheckTextView(it.revision, it.rawText, it.createdAt, it.referenceAt) },
            accepted?.latest?.takeIf { it.rawText != null }?.let {
                val anchor = repository.anchor(it.id.value)
                PrecheckAcceptedView(
                    it.revision,
                    requireNotNull(it.rawText),
                    it.createdAt,
                    anchor ?: it.createdAt,
                    if (anchor !=
                        null
                    ) {
                        "PRECHECK"
                    } else {
                        "LEGACY_CORRECTION"
                    },
                )
            },
            check?.let {
                PrecheckView(
                    it.id,
                    requireNotNull(status),
                    it.version,
                    if (open && status in setOf(PrecheckStatus.NEEDS_CHOICE, PrecheckStatus.NEEDS_EDIT)) it.question else null,
                    open &&
                        it.status in RETRYABLE &&
                        it.retryCount == 0 &&
                        repository.callCount(room.id.value, participant.id.value) < MAX_CALLS &&
                        bindings.isActive(participant, it.candidate.ownerBinding, now),
                    it.failureCode,
                )
            },
            PrecheckRoomView(room.collectionStatus.name, room.version, room.closurePolicy.deadline),
            check?.status == PrecheckStatus.CLEAR && accepted != null && check.candidate.acceptedVersionId == accepted.latest.id.value,
            room.collectionStatus == CollectionStatus.CLOSED && accepted?.let { it.latest.id in frozen.orEmpty() } == true,
            outcome,
        )
    }

    private fun room(
        code: String,
        lock: Boolean,
    ): MeetingRoom {
        val invite = runCatching { InviteCode.of(code) }.getOrNull() ?: fail(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        return (if (lock) rooms.findByInviteCodeForUpdate(invite) else rooms.findByInviteCode(invite))
            ?: fail(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
    }

    private fun requireProtocol(room: MeetingRoom) {
        if (repository.protocol(room.id.value) != "PRE_SUBMIT_V1") conflict()
    }

    private fun requireOpen(room: MeetingRoom) {
        if (!isOpen(room, clock.instant())) fail(RoomLifecycleErrorCode.ROOM_CLOSED)
    }

    private fun isOpen(
        room: MeetingRoom,
        at: Instant,
    ) = room.collectionStatus == CollectionStatus.COLLECTING && room.closurePolicy.deadline?.let { at < it } != false

    private fun normalize(rawText: String?): String {
        val text = rawText?.let(SubmissionRules::trimRawText)
        if (text.isNullOrEmpty()) throw SubmissionException(SubmissionErrorCode.SUBMISSION_INPUT_REQUIRED)
        require(!text.contains('\u0000')) { "Unsupported text character" }
        val length = text.codePointCount(0, text.length)
        if (length >
            SubmissionRules.MAX_RAW_TEXT_CODE_POINTS
        ) {
            throw SubmissionException(
                SubmissionErrorCode.SUBMISSION_TEXT_TOO_LONG,
                mapOf(
                    "actual" to length,
                    "maximum" to SubmissionRules.MAX_RAW_TEXT_CODE_POINTS,
                ),
            )
        }
        return text
    }

    private fun mutationFingerprint(command: PrecheckMutationCommand) =
        fingerprint(
            command.checkId,
            command.expectedRevision,
            command.expectedCheckVersion,
            command.questionId,
            command.choiceId,
            command.directText,
        )

    private fun fingerprint(vararg parts: Any?): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                parts
                    .joinToString("") { value ->
                        value?.toString()?.let { "${it.length}:$it" }
                            ?: "-1:"
                    }.toByteArray(StandardCharsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    private fun conflict(): Nothing = fail(RoomLifecycleErrorCode.REVISION_CONFLICT)

    private fun fail(code: RoomLifecycleErrorCode): Nothing = throw RoomLifecycleException(code)

    companion object {
        const val MAX_CALLS = 6
        const val MAX_QUEUE = 100
        private val RETRYABLE = setOf(PrecheckStatus.FAILED, PrecheckStatus.FAILED_QUEUE, PrecheckStatus.TIMED_OUT)
        private val WAITING = setOf(PrecheckStatus.QUEUED, PrecheckStatus.CHECKING, PrecheckStatus.NEEDS_CHOICE, PrecheckStatus.NEEDS_EDIT)
    }
}
