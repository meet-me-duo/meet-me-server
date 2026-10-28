package com.meetme.server.precheck.application.service

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.precheck.application.port.output.PrecheckOwnershipBindingPort
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckQuestion
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class ClaimedPrecheck(
    val check: PrecheckExecution,
    val room: MeetingRoom,
)

class PrecheckPublicationExpired : RuntimeException("PRECHECK_PUBLICATION_EXPIRED")

@Service
class PrecheckProcessingService(
    private val rooms: MeetingRoomRepository,
    private val participants: ParticipantRepository,
    private val repository: PrecheckRepository,
    private val bindings: PrecheckOwnershipBindingPort,
    private val submissions: SubmissionRepository,
    private val closure: CollectionClosureService,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    @Transactional
    fun expire(checkId: UUID) {
        val initial = repository.find(checkId) ?: return
        val room = rooms.findByIdForUpdate(MeetingRoomId(initial.candidate.roomId)) ?: return
        val current = repository.find(checkId) ?: return
        if (current.status !in setOf(PrecheckStatus.QUEUED, PrecheckStatus.CHECKING)) return
        val now = clock.instant()
        val status =
            when {
                !open(room, now) -> PrecheckStatus.CLOSED_BEFORE_COMPLETION
                now >= current.deadlineAt -> PrecheckStatus.TIMED_OUT
                else -> return
            }
        val next =
            current.copy(
                status = status,
                version = current.version + 1,
                failureCode =
                    if (status ==
                        PrecheckStatus.TIMED_OUT
                    ) {
                        "TIMEOUT"
                    } else {
                        "ROOM_CLOSED"
                    },
            )
        repository.update(next)
        repository.finishAttempt(next, null, now)
    }

    @Transactional
    fun claim(checkId: UUID): ClaimedPrecheck? {
        val initial = repository.find(checkId) ?: return null
        val room = rooms.findByIdForUpdate(MeetingRoomId(initial.candidate.roomId)) ?: return null
        val current = repository.find(checkId) ?: return null
        if (current.status != PrecheckStatus.QUEUED) return null
        val participant = participants.findById(ParticipantId(current.candidate.participantId)) ?: return null

        fun reject(at: Instant): Boolean {
            val rejection =
                when {
                    repository.current(participant.id.value)?.id != current.id -> PrecheckStatus.SUPERSEDED
                    !bindings.isActive(participant, current.candidate.ownerBinding, at) -> PrecheckStatus.SUPERSEDED
                    !open(room, at) -> PrecheckStatus.CLOSED_BEFORE_COMPLETION
                    at >= current.deadlineAt.minusSeconds(3) -> PrecheckStatus.TIMED_OUT
                    repository.callCount(
                        room.id.value,
                        participant.id.value,
                    ) >= PreSubmissionCheckService.MAX_CALLS -> PrecheckStatus.LIMIT_REACHED
                    else -> null
                }
            if (rejection == null) return false
            repository.update(
                current.copy(
                    status = rejection,
                    version = current.version + 1,
                    failureCode = if (rejection == PrecheckStatus.LIMIT_REACHED) "CALL_LIMIT_REACHED" else rejection.name,
                ),
            )
            return true
        }
        if (reject(clock.instant())) return null
        repository.lockAdmission()
        val now = clock.instant()
        if (reject(now)) return null
        if (repository.inFlight(now) >= 2) return null
        val claimed =
            current.copy(
                status = PrecheckStatus.CHECKING,
                version = current.version + 1,
                ownerToken = ids.next(),
                claimedAt = now,
            )
        repository.update(claimed)
        repository.startAttempt(claimed, now)
        return ClaimedPrecheck(claimed, room)
    }

    @Transactional
    fun publish(
        claim: ClaimedPrecheck,
        result: PrecheckProviderResult?,
        failureCode: String?,
    ): PrecheckStatus {
        val original = claim.check
        val room = rooms.findByIdForUpdate(MeetingRoomId(original.candidate.roomId)) ?: return PrecheckStatus.SUPERSEDED
        val current = repository.find(original.id) ?: return PrecheckStatus.SUPERSEDED
        val now = clock.instant()
        repository.finishAttempt(original.copy(failureCode = failureCode), result, now)
        if (current.version != original.version ||
            current.generation != original.generation ||
            current.ownerToken != original.ownerToken ||
            current.status != PrecheckStatus.CHECKING
        ) {
            return current.status
        }
        val participant = participants.findById(ParticipantId(current.candidate.participantId))
        var status: PrecheckStatus
        var code = failureCode
        var question: PrecheckQuestion? = null
        when {
            repository.current(current.candidate.participantId)?.id != current.id ||
                participant == null ||
                !bindings.isActive(participant, current.candidate.ownerBinding, now) -> status = PrecheckStatus.SUPERSEDED
            !open(room, now) -> status = PrecheckStatus.CLOSED_BEFORE_COMPLETION
            now >= current.deadlineAt -> {
                status = PrecheckStatus.TIMED_OUT
                code = "TIMEOUT"
            }
            failureCode != null || result == null ->
                status =
                    if (failureCode == "TIMEOUT") PrecheckStatus.TIMED_OUT else PrecheckStatus.FAILED
            result.verdict == PrecheckVerdict.CLEAR -> {
                val others =
                    submissions
                        .findLatestByRoom(room.id)
                        .filter { it.participantId.value != current.candidate.participantId }
                        .sumOf { it.latest.rawText?.codePointCount(0, it.latest.rawText.length) ?: 0 }
                if (others + current.candidate.rawText.codePointCount(0, current.candidate.rawText.length) >
                    SubmissionRules.MAX_BATCH_TEXT_CODE_POINTS
                ) {
                    status = PrecheckStatus.NEEDS_EDIT
                    code = "SUBMISSION_BATCH_TEXT_LIMIT_EXCEEDED"
                } else {
                    val source = current.candidate
                    val prior = submissions.findByParticipant(ParticipantId(source.participantId))
                    val accepted =
                        prior?.revise(
                            SubmissionVersionId(ids.next()),
                            source.rawText,
                            emptyList(),
                            Locale.forLanguageTag(source.locale),
                            source.createdAt,
                        )
                            ?: Submission.start(
                                SubmissionId(ids.next()),
                                room.id,
                                ParticipantId(source.participantId),
                                SubmissionVersionId(ids.next()),
                                source.rawText,
                                emptyList(),
                                Locale.forLanguageTag(source.locale),
                                source.createdAt,
                            )
                    submissions.save(accepted)
                    repository.bindAccepted(source.id, accepted.latest.id.value)
                    status = PrecheckStatus.CLEAR
                }
            }
            result.verdict == PrecheckVerdict.NEEDS_EDIT -> {
                status = PrecheckStatus.NEEDS_EDIT
                if (result.prompt != null && result.sourceExcerpt != null) {
                    question = PrecheckQuestion(ids.next(), result.prompt, result.sourceExcerpt, emptyList())
                }
            }
            current.candidate.sourceCandidateId != null -> {
                status = PrecheckStatus.NEEDS_EDIT
                code = "RECHECK_REQUIRES_EDIT"
            }
            repository.questionCount(room.id.value, current.candidate.participantId) >= 3 -> {
                status = PrecheckStatus.NEEDS_EDIT
                code =
                    "QUESTION_LIMIT_REACHED"
            }
            else -> {
                status = PrecheckStatus.NEEDS_CHOICE
                question = PrecheckQuestion(ids.next(), requireNotNull(result.prompt), requireNotNull(result.sourceExcerpt), result.choices)
            }
        }
        if (status == PrecheckStatus.CLEAR) requirePublicationActive(room, current)
        repository.update(current.copy(status = status, version = current.version + 1, question = question, failureCode = code))
        if (status == PrecheckStatus.CLEAR) {
            val count = submissions.countSubmittedParticipants(room.id)
            if (room.automaticClosureReason(clock.instant(), count) ==
                ClosureReason.EXPECTED_PARTICIPANTS
            ) {
                closure.close(room, ClosureReason.EXPECTED_PARTICIPANTS, count, clock.instant())
            }
            requirePublicationActive(room, current)
        }
        return status
    }

    @Transactional
    fun rejectPublication(
        claim: ClaimedPrecheck,
        result: PrecheckProviderResult?,
        failureCode: String?,
    ): PrecheckStatus {
        val original = claim.check
        val room = rooms.findByIdForUpdate(MeetingRoomId(original.candidate.roomId)) ?: return PrecheckStatus.SUPERSEDED
        val current = repository.find(original.id) ?: return PrecheckStatus.SUPERSEDED
        val now = clock.instant()
        repository.finishAttempt(original.copy(failureCode = failureCode), result, now)
        if (current.version != original.version ||
            current.generation != original.generation ||
            current.ownerToken != original.ownerToken ||
            current.status != PrecheckStatus.CHECKING
        ) {
            return current.status
        }
        val status =
            when {
                repository.current(current.candidate.participantId)?.id != current.id -> PrecheckStatus.SUPERSEDED
                !ownerActive(current, now) -> PrecheckStatus.SUPERSEDED
                !open(room, now) -> PrecheckStatus.CLOSED_BEFORE_COMPLETION
                now >= current.deadlineAt -> PrecheckStatus.TIMED_OUT
                else -> PrecheckStatus.SUPERSEDED
            }
        repository.update(
            current.copy(
                status = status,
                version = current.version + 1,
                failureCode = if (status == PrecheckStatus.TIMED_OUT) "TIMEOUT" else status.name,
            ),
        )
        return status
    }

    private fun requirePublicationActive(
        room: MeetingRoom,
        current: PrecheckExecution,
    ) {
        val now = clock.instant()
        if (!ownerActive(current, now) || !open(room, now) || now >= current.deadlineAt) throw PrecheckPublicationExpired()
    }

    private fun ownerActive(
        check: PrecheckExecution,
        at: Instant,
    ): Boolean {
        val participant = participants.findById(ParticipantId(check.candidate.participantId)) ?: return false
        return bindings.isActive(participant, check.candidate.ownerBinding, at)
    }

    private fun open(
        room: MeetingRoom,
        at: Instant,
    ) = room.collectionStatus == CollectionStatus.COLLECTING && room.closurePolicy.deadline?.let { at < it } != false
}
