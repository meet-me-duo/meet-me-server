package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.AnalyzeRevisionCommand
import com.meetme.server.coordination.application.port.input.InputRevisionUseCase
import com.meetme.server.coordination.application.port.input.ReopenInputCommand
import com.meetme.server.coordination.application.port.input.ReopenedInputView
import com.meetme.server.coordination.application.port.input.RevisionAnalysisView
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.InputRevisionRoundRepository
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.InputRevisionRound
import com.meetme.server.coordination.domain.RevisionAnalysisOutcome
import com.meetme.server.coordination.domain.RevisionRoundStatus
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.application.port.input.GetRoomUseCase
import com.meetme.server.meetingroom.application.port.input.RevisionRoundView
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class InputRevisionService(
    private val rooms: MeetingRoomRepository,
    private val runs: CoordinationRunRepository,
    private val rounds: InputRevisionRoundRepository,
    private val submissions: SubmissionRepository,
    private val sessions: GuestSessionRepository,
    private val participants: ParticipantRepository,
    private val credentials: GuestCredentialPort,
    private val outbox: OutboxRepository,
    private val ids: IdGenerator,
    private val getRoom: GetRoomUseCase,
    private val clock: Clock,
) : InputRevisionUseCase {
    @Transactional
    override fun reopen(command: ReopenInputCommand): ReopenedInputView {
        val room = hostRoom(command.inviteCode, command.rawCredential)
        require(command.expectedGeneration >= 0)
        val replay = rounds.findByReopenRequest(room.id, command.requestId)
        if (replay != null) {
            if (replay.sourceRunId != command.sourceAnalysisId || replay.expectedGeneration != command.expectedGeneration) conflict()
            return ReopenedInputView(
                getRoom.get(command.inviteCode, command.rawCredential),
                RevisionRoundView(replay.id, replay.generation, replay.status),
            )
        }
        if (room.collectionStatus != CollectionStatus.CLOSED ||
            room.activeRevisionRoundId != null ||
            room.revisionGeneration != command.expectedGeneration ||
            room.activeRunId != command.sourceAnalysisId
        ) {
            conflict()
        }
        val source = runs.findByIdForUpdate(CoordinationRunId(command.sourceAnalysisId)) ?: conflict()
        if (source.roomId != room.id ||
            source.status != CoordinationStatus.COMPLETED ||
            source.isConfirmed ||
            (source.hasSelectableResult && source.quality != CandidateQuality.PARTIAL)
        ) {
            conflict()
        }
        // Validate the immutable source cohort before enabling edits.
        FrozenSubmissionReader.read(submissions, source.batch)
        val round =
            InputRevisionRound(
                ids.next(),
                room.id,
                room.revisionGeneration + 1,
                source.id.value,
                command.requestId,
                command.expectedGeneration,
                clock.instant(),
            )
        rounds.insert(round)
        rooms.update(room.transition(revisionGeneration = round.generation, activeRevisionRoundId = round.id))
        return ReopenedInputView(
            getRoom.get(command.inviteCode, command.rawCredential),
            RevisionRoundView(round.id, round.generation, round.status),
        )
    }

    @Transactional
    override fun analyze(command: AnalyzeRevisionCommand): RevisionAnalysisView {
        val room = hostRoom(command.inviteCode, command.rawCredential)
        val replay = rounds.findByAnalyzeRequest(room.id, command.requestId)
        if (replay != null) {
            if (replay.id != command.revisionRoundId || replay.forceReparse != command.forceReparse) conflict()
            return result(replay, command)
        }
        val round = rounds.findById(room.id, command.revisionRoundId) ?: conflict()
        if (round.status != RevisionRoundStatus.OPEN ||
            room.activeRevisionRoundId != round.id ||
            room.activeRunId != round.sourceRunId ||
            room.collectionStatus != CollectionStatus.CLOSED
        ) {
            conflict()
        }
        val source = runs.findByIdForUpdate(CoordinationRunId(round.sourceRunId)) ?: conflict()
        if (source.roomId != room.id || source.status != CoordinationStatus.COMPLETED || source.isConfirmed) conflict()
        val original = FrozenSubmissionReader.read(submissions, source.batch)
        val ownerIds = original.map { it.participantId }.toSet()
        val current = submissions.findLatestByRoom(room.id).filter { it.participantId in ownerIds }.sortedBy { it.participantId.value }
        check(current.size == ownerIds.size && current.map { it.participantId }.toSet() == ownerIds) {
            "Correction cohort is incomplete"
        }
        check(current.all { it.roomId == room.id }) { "Correction submission belongs to another room" }
        val unchanged = current.map { it.latest.id }.toSet() == source.batch.submissionVersionIds.toSet()
        val now = clock.instant()
        if (unchanged && !command.forceReparse) {
            val consumed = round.consume(command.requestId, false, source.id.value, RevisionAnalysisOutcome.REUSED, now)
            rounds.update(consumed)
            rooms.update(room.transition(activeRevisionRoundId = null))
            return result(consumed, command)
        }
        if (room.correctionAnalysisCount >= 3) {
            throw RoomLifecycleException(
                RoomLifecycleErrorCode.CORRECTION_ANALYSIS_LIMIT_REACHED,
                mapOf("limit" to 3, "used" to room.correctionAnalysisCount, "remaining" to 0),
            )
        }
        val batch = SubmissionBatch(SubmissionBatchId(ids.next()), room.id, current.map { it.latest.id }, now)
        var run = CoordinationRun.queued(CoordinationRunId(ids.next()), batch)
        val natural = current.any { it.latest.rawText != null }
        if (!natural) run = run.startMatching()
        runs.insert(run)
        val eventId = OutboxEventId(ids.next())
        outbox.insert(
            OutboxEvent(
                eventId,
                "CoordinationRun",
                run.id.value,
                if (natural) CollectionClosureService.STRUCTURING_REQUESTED else CollectionClosureService.MATCHING_REQUESTED,
                "{\"event_id\":\"${eventId.value}\",\"submission_batch_id\":\"${batch.id.value}\"}",
                occurredAt = now,
            ),
        )
        val consumed = round.consume(command.requestId, command.forceReparse, run.id.value, RevisionAnalysisOutcome.QUEUED, now)
        rounds.update(consumed)
        rooms.update(
            room.transition(
                activeRunId = run.id.value,
                activeRevisionRoundId = null,
                correctionAnalysisCount = room.correctionAnalysisCount + 1,
            ),
        )
        return result(consumed, command)
    }

    private fun result(
        round: InputRevisionRound,
        command: AnalyzeRevisionCommand,
    ) = RevisionAnalysisView(
        requireNotNull(round.outcome),
        requireNotNull(round.resolvedRunId),
        round.id,
        getRoom.get(command.inviteCode, command.rawCredential),
    )

    private fun hostRoom(
        inviteCode: String,
        credential: String?,
    ): MeetingRoom {
        val code =
            runCatching { InviteCode.of(inviteCode) }.getOrNull()
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val room = rooms.findByInviteCodeForUpdate(code) ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        if (credential.isNullOrBlank()) throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED)
        val session =
            sessions.findByCredentialDigest(credentials.digest(credential))?.takeIf { it.isActive(clock.instant()) }
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_INVALID)
        if (participants.findByRoomAndGuestSession(room.id, session.id)?.role != ParticipantRole.HOST) {
            throw RoomLifecycleException(RoomLifecycleErrorCode.HOST_PERMISSION_REQUIRED)
        }
        return room
    }

    private fun conflict(): Nothing = throw RoomLifecycleException(RoomLifecycleErrorCode.REVISION_CONFLICT)
}
