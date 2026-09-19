package com.meetme.server.submission.application.service

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.input.GetOwnSubmissionUseCase
import com.meetme.server.submission.application.port.input.SaveSubmissionCommand
import com.meetme.server.submission.application.port.input.SaveSubmissionUseCase
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import com.meetme.server.submission.application.port.input.SubmissionView
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class SubmissionService(
    private val roomRepository: MeetingRoomRepository,
    private val guestSessionRepository: GuestSessionRepository,
    private val participantRepository: ParticipantRepository,
    private val submissionRepository: SubmissionRepository,
    private val credentialPort: GuestCredentialPort,
    private val idGenerator: IdGenerator,
    private val closureService: CollectionClosureService,
    private val clock: Clock,
) : SaveSubmissionUseCase,
    GetOwnSubmissionUseCase {
    @Transactional
    override fun save(command: SaveSubmissionCommand): SubmissionView {
        val room = findRoom(command.inviteCode, lock = true)
        if (room.collectionStatus == CollectionStatus.CLOSED) {
            throw com.meetme.server.meetingroom.application.port.input.RoomLifecycleException(
                com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode.ROOM_CLOSED,
            )
        }
        val participant = requireParticipant(room.id, command.rawCredential)
        val rawText = normalizeText(command.rawText)
        val availability =
            try {
                SubmissionRules.normalizeAvailability(command.manualAvailability, room.searchRange)
            } catch (exception: IllegalArgumentException) {
                throw SubmissionException(SubmissionErrorCode.SUBMISSION_TIME_RANGE_INVALID, causeDetails(exception))
            }
        if (rawText == null && availability.isEmpty()) {
            throw SubmissionException(SubmissionErrorCode.SUBMISSION_INPUT_REQUIRED)
        }

        val existing = submissionRepository.findByParticipant(participant.id)
        val otherTextLength =
            submissionRepository
                .findLatestByRoom(room.id)
                .filter { it.participantId != participant.id }
                .sumOf { it.latest.rawText?.codePointCount(0, it.latest.rawText.length) ?: 0 }
        val newLength = rawText?.codePointCount(0, rawText.length) ?: 0
        if (otherTextLength + newLength > SubmissionRules.MAX_BATCH_TEXT_CODE_POINTS) {
            throw SubmissionException(
                SubmissionErrorCode.SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED,
                mapOf("maximum" to SubmissionRules.MAX_BATCH_TEXT_CODE_POINTS),
            )
        }
        val now = clock.instant()
        val submission =
            existing?.revise(SubmissionVersionId(idGenerator.next()), rawText, availability, command.locale, now)
                ?: Submission.start(
                    SubmissionId(idGenerator.next()),
                    room.id,
                    participant.id,
                    SubmissionVersionId(idGenerator.next()),
                    rawText,
                    availability,
                    command.locale,
                    now,
                )
        submissionRepository.save(submission)
        val submitted = submissionRepository.countSubmittedParticipants(room.id)
        if (room.automaticClosureReason(now, submitted) == ClosureReason.EXPECTED_PARTICIPANTS) {
            closureService.close(room, ClosureReason.EXPECTED_PARTICIPANTS, submitted, now)
        }
        return submission.toView(editable = submitted < (room.closurePolicy.expectedParticipants ?: Int.MAX_VALUE))
    }

    override fun get(
        inviteCode: String,
        rawCredential: String?,
    ): SubmissionView {
        val room = findRoom(inviteCode, lock = false)
        val participant = requireParticipant(room.id, rawCredential)
        val submission =
            submissionRepository.findByParticipant(participant.id)
                ?: throw SubmissionException(SubmissionErrorCode.SUBMISSION_NOT_FOUND)
        return submission.toView(room.collectionStatus == CollectionStatus.COLLECTING)
    }

    private fun normalizeText(rawText: String?): String? {
        if (rawText == null) return null
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        val length = trimmed.codePointCount(0, trimmed.length)
        if (length > SubmissionRules.MAX_RAW_TEXT_CODE_POINTS) {
            throw SubmissionException(
                SubmissionErrorCode.SUBMISSION_TEXT_TOO_LONG,
                mapOf("actual" to length, "maximum" to SubmissionRules.MAX_RAW_TEXT_CODE_POINTS),
            )
        }
        return trimmed
    }

    private fun findRoom(
        inviteCode: String,
        lock: Boolean,
    ): com.meetme.server.meetingroom.domain.MeetingRoom {
        val code =
            runCatching { InviteCode.of(inviteCode) }.getOrNull()
                ?: throw com.meetme.server.meetingroom.application.port.input.RoomLifecycleException(
                    com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode.ROOM_NOT_FOUND,
                )
        return (if (lock) roomRepository.findByInviteCodeForUpdate(code) else roomRepository.findByInviteCode(code))
            ?: throw com.meetme.server.meetingroom.application.port.input.RoomLifecycleException(
                com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode.ROOM_NOT_FOUND,
            )
    }

    private fun requireParticipant(
        roomId: com.meetme.server.shared.domain.MeetingRoomId,
        rawCredential: String?,
    ): com.meetme.server.participant.domain.Participant {
        if (rawCredential.isNullOrBlank()) {
            throw com.meetme.server.meetingroom.application.port.input.RoomLifecycleException(
                com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED,
            )
        }
        val now = clock.instant()
        val session =
            guestSessionRepository.findByCredentialDigest(credentialPort.digest(rawCredential))?.takeIf { it.isActive(now) }
                ?: throw com.meetme.server.meetingroom.application.port.input.RoomLifecycleException(
                    com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode.GUEST_SESSION_INVALID,
                )
        return participantRepository.findByRoomAndGuestSession(roomId, session.id)
            ?: throw SubmissionException(SubmissionErrorCode.PARTICIPANT_REQUIRED)
    }

    private fun Submission.toView(editable: Boolean) =
        SubmissionView(
            latest.revision,
            latest.rawText,
            latest.manualAvailability,
            latest.locale.toLanguageTag(),
            latest.createdAt,
            editable,
        )

    private fun causeDetails(exception: IllegalArgumentException) = mapOf("reason" to (exception.message ?: "invalid"))
}
