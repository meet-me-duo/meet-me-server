package com.meetme.server.meetingroom.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.meetingroom.application.port.input.CloseRoomCommand
import com.meetme.server.meetingroom.application.port.input.CloseRoomUseCase
import com.meetme.server.meetingroom.application.port.input.CreateRoomCommand
import com.meetme.server.meetingroom.application.port.input.CreateRoomUseCase
import com.meetme.server.meetingroom.application.port.input.GetRoomUseCase
import com.meetme.server.meetingroom.application.port.input.JoinRoomCommand
import com.meetme.server.meetingroom.application.port.input.JoinRoomUseCase
import com.meetme.server.meetingroom.application.port.input.PublicRoomStatus
import com.meetme.server.meetingroom.application.port.input.RoomAccessResult
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.input.RoomView
import com.meetme.server.meetingroom.application.port.input.ViewerParticipation
import com.meetme.server.meetingroom.application.port.output.InviteCodeGenerator
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class RoomLifecycleService(
    private val roomRepository: MeetingRoomRepository,
    private val guestSessionRepository: GuestSessionRepository,
    private val participantRepository: ParticipantRepository,
    private val submissionRepository: SubmissionRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val credentialService: GuestCredentialPort,
    private val inviteCodeGenerator: InviteCodeGenerator,
    private val idGenerator: IdGenerator,
    private val closureService: CollectionClosureService,
    private val clock: Clock,
) : CreateRoomUseCase,
    GetRoomUseCase,
    JoinRoomUseCase,
    CloseRoomUseCase {
    @Transactional
    override fun create(command: CreateRoomCommand): RoomAccessResult {
        val now = clock.instant()
        val session = resolveOrIssueSession(command.rawCredential, now)
        val zone = MeetingTimeZone.of("Asia/Seoul")
        val searchRange =
            when {
                command.searchStartDate == null && command.searchEndDate == null -> SearchDateRange.defaultFrom(now, zone)
                command.searchStartDate != null && command.searchEndDate != null ->
                    SearchDateRange.explicit(command.searchStartDate, command.searchEndDate)
                else -> throw IllegalArgumentException("Search start and end dates must be provided together")
            }
        val roomId = MeetingRoomId(idGenerator.next())
        val room =
            (1..5).firstNotNullOfOrNull {
                val candidate =
                    MeetingRoom.create(
                        id = roomId,
                        inviteCode = inviteCodeGenerator.next(),
                        purpose = command.purpose.trim(),
                        mode = command.meetingMode,
                        timeZone = zone,
                        searchRange = searchRange,
                        closurePolicy =
                            ClosurePolicy.of(
                                command.expectedParticipants,
                                command.submissionDeadline,
                                command.manualOnly,
                            ),
                        createdAt = now,
                    )
                candidate.takeIf(roomRepository::insertIfInviteAvailable)
            } ?: throw RoomLifecycleException(RoomLifecycleErrorCode.INVITE_CODE_GENERATION_FAILED)
        val host =
            Participant.host(
                id = ParticipantId(idGenerator.next()),
                roomId = room.id,
                guestSessionId = session.domain.id,
                displayName = ParticipantDisplayName.of(command.hostDisplayName),
                joinedAt = now,
            )
        participantRepository.insert(host)
        return RoomAccessResult(room.toView(host, 0), session.newCredential, true)
    }

    override fun get(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView {
        val room = findRoom(inviteCode, lock = false)
        val participant =
            activeSession(
                rawCredential,
                clock.instant(),
            )?.let { participantRepository.findByRoomAndGuestSession(room.id, it.id) }
        return room.toView(participant, submissionRepository.countSubmittedParticipants(room.id))
    }

    @Transactional
    override fun join(command: JoinRoomCommand): RoomAccessResult {
        val room = findRoom(command.inviteCode, lock = true)
        if (room.collectionStatus == CollectionStatus.CLOSED) {
            throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_CLOSED)
        }
        val now = clock.instant()
        val session = resolveOrIssueSession(command.rawCredential, now, lockExisting = true)
        val existing = participantRepository.findByRoomAndGuestSession(room.id, session.domain.id)
        if (existing != null) {
            return RoomAccessResult(
                room.toView(existing, submissionRepository.countSubmittedParticipants(room.id)),
                session.newCredential,
                false,
            )
        }
        if (participantRepository.countByRoom(room.id) >= com.meetme.server.submission.domain.SubmissionRules.MAX_ROOM_PARTICIPANTS) {
            throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_PARTICIPANT_LIMIT_REACHED)
        }
        val participant =
            Participant.member(
                id = ParticipantId(idGenerator.next()),
                roomId = room.id,
                guestSessionId = session.domain.id,
                displayName = ParticipantDisplayName.of(command.displayName),
                joinedAt = now,
            )
        participantRepository.insert(participant)
        return RoomAccessResult(
            room.toView(participant, submissionRepository.countSubmittedParticipants(room.id)),
            session.newCredential,
            true,
        )
    }

    @Transactional
    override fun close(command: CloseRoomCommand): RoomView {
        val room = findRoom(command.inviteCode, lock = true)
        val session = requireActiveSession(command.rawCredential, clock.instant())
        val participant = participantRepository.findByRoomAndGuestSession(room.id, session.id)
        if (participant?.role != ParticipantRole.HOST) {
            throw RoomLifecycleException(RoomLifecycleErrorCode.HOST_PERMISSION_REQUIRED)
        }
        val submitted = submissionRepository.countSubmittedParticipants(room.id)
        if (room.collectionStatus == CollectionStatus.CLOSED) {
            return room.toView(participant, submitted)
        }
        val now = clock.instant()
        val automaticReason = room.automaticClosureReason(now, submitted)
        if (!room.closurePolicy.manualOnly && automaticReason == null && !command.confirmEarly) {
            throw RoomLifecycleException(
                RoomLifecycleErrorCode.EARLY_CLOSE_CONFIRMATION_REQUIRED,
                mapOf(
                    "submitted_participants" to submitted,
                    "expected_participants" to room.closurePolicy.expectedParticipants,
                    "submission_deadline" to room.closurePolicy.deadline,
                ),
            )
        }
        val reason = automaticReason ?: ClosureReason.MANUAL
        val closed = closureService.close(room, reason, submitted, now)
        return closed.toView(participant, submitted)
    }

    private fun findRoom(
        rawInviteCode: String,
        lock: Boolean,
    ): MeetingRoom {
        val inviteCode =
            runCatching {
                InviteCode.of(rawInviteCode)
            }.getOrElse { throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND) }
        return (if (lock) roomRepository.findByInviteCodeForUpdate(inviteCode) else roomRepository.findByInviteCode(inviteCode))
            ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
    }

    private fun resolveOrIssueSession(
        rawCredential: String?,
        now: java.time.Instant,
        lockExisting: Boolean = false,
    ): SessionResolution {
        val existing =
            rawCredential?.takeIf { it.isNotBlank() }?.let {
                val digest = credentialService.digest(it)
                if (lockExisting) {
                    guestSessionRepository.findByCredentialDigestForUpdate(digest)
                } else {
                    guestSessionRepository.findByCredentialDigest(digest)
                }
            }
        if (existing?.isActive(now) == true) {
            return SessionResolution(existing, null)
        }
        val issued = credentialService.issue(now)
        val session =
            GuestSession(
                id = GuestSessionId(idGenerator.next()),
                credentialDigest = issued.credentialDigest,
                expiresAt = issued.expiresAt,
                revokedAt = null,
                createdAt = now,
            )
        guestSessionRepository.insert(session)
        return SessionResolution(session, issued.rawCredential)
    }

    private fun activeSession(
        rawCredential: String?,
        now: java.time.Instant,
    ): GuestSession? =
        rawCredential
            ?.takeIf { it.isNotBlank() }
            ?.let(credentialService::digest)
            ?.let(guestSessionRepository::findByCredentialDigest)
            ?.takeIf { it.isActive(now) }

    private fun requireActiveSession(
        rawCredential: String?,
        now: java.time.Instant,
    ): GuestSession {
        if (rawCredential.isNullOrBlank()) {
            throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED)
        }
        return activeSession(rawCredential, now)
            ?: throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_INVALID)
    }

    private fun MeetingRoom.toView(
        viewer: Participant?,
        submittedParticipants: Int,
    ): RoomView =
        RoomView(
            inviteCode = inviteCode.value,
            purpose = purpose,
            meetingMode = mode,
            timeZoneId = timeZone.value.id,
            searchStartDate = searchRange.startInclusive,
            searchEndDate = searchRange.endExclusive,
            searchRangeSource = searchRange.source,
            expectedParticipants = closurePolicy.expectedParticipants,
            submissionDeadline = closurePolicy.deadline,
            manualOnly = closurePolicy.manualOnly,
            collectionStatus = collectionStatus,
            closureReason = closureReason,
            closedAt = closedAt,
            publicStatus = publicStatus(submittedParticipants),
            viewer = ViewerParticipation(viewer != null, viewer?.displayName?.value, viewer?.role),
        )

    private fun MeetingRoom.publicStatus(submittedParticipants: Int): PublicRoomStatus {
        if (collectionStatus == CollectionStatus.COLLECTING) return PublicRoomStatus.COLLECTING
        if (submittedParticipants < 2) return PublicRoomStatus.INSUFFICIENT_PARTICIPANTS
        val run = coordinationRunRepository.findLatestByRoom(id) ?: return PublicRoomStatus.ANALYZING
        if (run.confirmedCandidateId != null) return PublicRoomStatus.CONFIRMED
        return when (run.status) {
            com.meetme.server.coordination.domain.CoordinationStatus.ANALYSIS_DELAYED,
            com.meetme.server.coordination.domain.CoordinationStatus.DEAD_LETTERED,
            -> PublicRoomStatus.ANALYSIS_DELAYED
            com.meetme.server.coordination.domain.CoordinationStatus.COMPLETED ->
                when {
                    run.candidates.isEmpty() -> PublicRoomStatus.NO_MATCH
                    run.quality == com.meetme.server.coordination.domain.CandidateQuality.PARTIAL ->
                        PublicRoomStatus.READY_WITH_WARNINGS
                    else -> PublicRoomStatus.READY
                }
            else -> PublicRoomStatus.ANALYZING
        }
    }

    private data class SessionResolution(
        val domain: GuestSession,
        val newCredential: String?,
    )
}
