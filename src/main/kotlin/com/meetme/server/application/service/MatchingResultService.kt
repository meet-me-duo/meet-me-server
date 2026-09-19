package com.meetme.server.application.service

import com.meetme.server.application.port.input.CandidateListView
import com.meetme.server.application.port.input.CandidatePlaceView
import com.meetme.server.application.port.input.CandidateView
import com.meetme.server.application.port.input.ConfirmCandidateUseCase
import com.meetme.server.application.port.input.ConfirmedResultView
import com.meetme.server.application.port.input.GetCandidatesUseCase
import com.meetme.server.application.port.input.GetConfirmedResultUseCase
import com.meetme.server.application.port.input.GetUnappliedInputsUseCase
import com.meetme.server.application.port.input.MatchingResultErrorCode
import com.meetme.server.application.port.input.MatchingResultException
import com.meetme.server.application.port.input.UnappliedInputView
import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.GuestCredentialPort
import com.meetme.server.application.port.output.GuestSessionRepository
import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.NormalizedPlaceRepository
import com.meetme.server.application.port.output.NormalizedPlaceStatus
import com.meetme.server.application.port.output.ParticipantRepository
import com.meetme.server.application.port.output.StructuredSubmissionRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.domain.common.CandidateId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.coordination.CandidateQuality
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.coordination.CoordinationStatus
import com.meetme.server.domain.coordination.MeetingCandidate
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.participant.Participant
import com.meetme.server.domain.participant.ParticipantRole
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Service
class MatchingResultService(
    private val roomRepository: MeetingRoomRepository,
    private val guestSessionRepository: GuestSessionRepository,
    private val participantRepository: ParticipantRepository,
    private val submissionRepository: SubmissionRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val normalizedPlaceRepository: NormalizedPlaceRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val credentialPort: GuestCredentialPort,
    private val messageSource: MessageSource,
    private val clock: Clock,
) : GetCandidatesUseCase,
    GetUnappliedInputsUseCase,
    ConfirmCandidateUseCase,
    GetConfirmedResultUseCase {
    @Transactional(readOnly = true)
    override fun getCandidates(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): CandidateListView {
        val room = room(inviteCode)
        participant(room.id, rawCredential)
        return candidateList(completedRun(room.id), room, locale)
    }

    @Transactional(readOnly = true)
    override fun getUnappliedInputs(
        inviteCode: String,
        rawCredential: String?,
    ): List<UnappliedInputView> {
        val room = room(inviteCode)
        val viewer = participant(room.id, rawCredential)
        if (viewer.role != ParticipantRole.HOST) fail(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
        val run = completedRun(room.id)
        val submissions = frozenSubmissions(run)
        val structured = structuredSubmissionRepository.findByBatch(run.batch.id).associateBy { it.submissionVersionId }
        val placeFailures =
            normalizedPlaceRepository
                .findByBatch(run.batch.id)
                .filter { it.status != NormalizedPlaceStatus.RESOLVED }
                .groupBy { it.submissionVersionId }
        return submissions.mapNotNull { submission ->
            val result = structured[submission.latest.id] ?: return@mapNotNull null
            val reason = result.rejectionCode ?: placeFailures[submission.latest.id]?.firstOrNull()?.status?.name ?: return@mapNotNull null
            val rawText = submission.latest.rawText ?: return@mapNotNull null
            val owner = participantRepository.findById(submission.participantId) ?: return@mapNotNull null
            UnappliedInputView(owner.displayName.value, rawText.toPlainText(), reason)
        }
    }

    @Transactional
    override fun confirm(
        inviteCode: String,
        candidateId: UUID,
        rawCredential: String?,
        locale: Locale,
    ): ConfirmedResultView {
        val room = room(inviteCode)
        val viewer = participant(room.id, rawCredential)
        if (viewer.role != ParticipantRole.HOST) fail(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
        val run =
            coordinationRunRepository.findLatestByRoomForUpdate(room.id)
                ?: fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        if (run.status != CoordinationStatus.COMPLETED) fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        val requested = CandidateId(candidateId)
        val existing = run.confirmedCandidateId
        if (existing != null && existing != requested) fail(MatchingResultErrorCode.CANDIDATE_ALREADY_CONFIRMED)
        val candidate =
            run.candidates.firstOrNull { it.id == requested }
                ?: fail(MatchingResultErrorCode.CANDIDATE_NOT_FOUND)
        val confirmed = if (existing == requested) run else run.confirm(requested, clock.instant()).also(coordinationRunRepository::update)
        return ConfirmedResultView(candidate.toView(room, locale), requireNotNull(confirmed.confirmedAt))
    }

    @Transactional(readOnly = true)
    override fun getConfirmed(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): ConfirmedResultView {
        val room = room(inviteCode)
        participant(room.id, rawCredential)
        val run = completedRun(room.id)
        val id = run.confirmedCandidateId ?: fail(MatchingResultErrorCode.RESULT_NOT_CONFIRMED)
        val candidate = run.candidates.firstOrNull { it.id == id } ?: fail(MatchingResultErrorCode.CANDIDATE_NOT_FOUND)
        return ConfirmedResultView(candidate.toView(room, locale), requireNotNull(run.confirmedAt))
    }

    private fun candidateList(
        run: CoordinationRun,
        room: MeetingRoom,
        locale: Locale,
    ): CandidateListView {
        val structured = structuredSubmissionRepository.findByBatch(run.batch.id)
        val rejectedIds = structured.filter { it.rejectionCode != null }.mapTo(mutableSetOf()) { it.submissionVersionId }
        normalizedPlaceRepository
            .findByBatch(run.batch.id)
            .filter { it.status != NormalizedPlaceStatus.RESOLVED }
            .mapTo(rejectedIds) { it.submissionVersionId }
        val rejected = rejectedIds.size
        return CandidateListView(
            quality = run.quality ?: CandidateQuality.COMPLETE,
            appliedSubmissions = run.batch.submissionVersionIds.size - rejected,
            totalSubmissions = run.batch.submissionVersionIds.size,
            unappliedInputs = rejected,
            candidates = run.candidates.map { it.toView(room, locale) },
        )
    }

    private fun MeetingCandidate.toView(
        room: MeetingRoom,
        locale: Locale,
    ): CandidateView =
        CandidateView(
            candidateId = id.value,
            planType = planType,
            meetingMode = meetingMode,
            rank = rank,
            attendanceCount = participantIds.size,
            totalParticipants = totalParticipants,
            timeRanges = timeRanges,
            place =
                place?.let {
                    CandidatePlaceView(
                        it.displayName,
                        it.coordinate.latitude.toDouble(),
                        it.coordinate.longitude.toDouble(),
                    )
                },
            summary =
                messageSource.getMessage(
                    "candidate.summary.explicit",
                    arrayOf(
                        CandidateSummaryRenderer
                            .render(
                                CandidateSummaryRequest(
                                    occurrences = timeRanges,
                                    searchStartDate = room.searchRange.startInclusive,
                                    searchEndDate = room.searchRange.endExclusive,
                                    timeZone = room.timeZone.value,
                                    locale = locale,
                                    recurringPattern = inferredPattern(room),
                                ),
                            ).text,
                    ),
                    locale,
                ),
        )

    private fun MeetingCandidate.inferredPattern(room: MeetingRoom): RecurringCandidatePattern? {
        val localRanges =
            timeRanges.map {
                val start = it.startInclusive.atZone(room.timeZone.value)
                val end = it.endExclusive.atZone(room.timeZone.value)
                RecurringCandidatePattern(start.dayOfWeek, start.toLocalTime(), end.toLocalTime())
            }
        val dominant = localRanges.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key.dayOfWeek.value }))
        return dominant?.takeIf { it.value >= 2 }?.key
    }

    private fun room(rawInviteCode: String): MeetingRoom {
        val code = runCatching { InviteCode.of(rawInviteCode) }.getOrNull() ?: fail(MatchingResultErrorCode.ROOM_NOT_FOUND)
        return roomRepository.findByInviteCode(code) ?: fail(MatchingResultErrorCode.ROOM_NOT_FOUND)
    }

    private fun participant(
        roomId: MeetingRoomId,
        rawCredential: String?,
    ): Participant {
        if (rawCredential.isNullOrBlank()) fail(MatchingResultErrorCode.GUEST_SESSION_REQUIRED)
        val session =
            guestSessionRepository
                .findByCredentialDigest(credentialPort.digest(rawCredential))
                ?.takeIf { it.isActive(clock.instant()) }
                ?: fail(MatchingResultErrorCode.GUEST_SESSION_INVALID)
        return participantRepository.findByRoomAndGuestSession(roomId, session.id)
            ?: fail(MatchingResultErrorCode.PARTICIPANT_REQUIRED)
    }

    private fun completedRun(roomId: MeetingRoomId): CoordinationRun {
        val run = coordinationRunRepository.findLatestByRoom(roomId) ?: fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        if (run.status != CoordinationStatus.COMPLETED) fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        return run
    }

    private fun frozenSubmissions(run: CoordinationRun) =
        submissionRepository.findLatestByRoom(run.roomId).filter { it.latest.id in run.batch.submissionVersionIds.toSet() }

    private fun String.toPlainText(): String = filter { it == '\n' || it == '\t' || !it.isISOControl() }

    private fun fail(code: MatchingResultErrorCode): Nothing = throw MatchingResultException(code)
}
