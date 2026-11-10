package com.meetme.server.meetingroom.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class CollectionClosureService(
    private val roomRepository: MeetingRoomRepository,
    private val submissionRepository: SubmissionRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val outboxRepository: OutboxRepository,
    private val idGenerator: IdGenerator,
    private val finalAnalysisSelection: FinalAnalysisRouteSelectionService? = null,
) {
    @Transactional
    fun close(
        room: MeetingRoom,
        reason: ClosureReason,
        submittedParticipants: Int,
        at: Instant,
        analysisContract: FinalAnalysisContractSelection = FinalAnalysisContractSelection.LEGACY,
    ): MeetingRoom {
        val recordedAt =
            if (analysisContract != FinalAnalysisContractSelection.LEGACY) at.truncatedTo(ChronoUnit.MICROS) else at
        val closed = room.close(reason, recordedAt, submittedParticipants)
        if (closed === room) return room
        roomRepository.update(closed)
        if (submittedParticipants < 2) return closed

        val submissions = submissionRepository.findLatestByRoom(room.id).sortedBy { it.participantId.value }
        check(submissions.size == submittedParticipants) { "Submitted participant count changed while closing" }
        val batch =
            SubmissionBatch(
                SubmissionBatchId(idGenerator.next()),
                room.id,
                submissions.map { it.latest.id },
                recordedAt,
            )
        var run = CoordinationRun.queued(CoordinationRunId(idGenerator.next()), batch)
        val hasNaturalLanguage = submissions.any { it.latest.rawText != null }
        if (!hasNaturalLanguage) run = run.startMatching()
        coordinationRunRepository.insert(run)
        val active = closed.transition(activeRunId = run.id.value)
        roomRepository.update(active)
        if (analysisContract != FinalAnalysisContractSelection.LEGACY) {
            checkNotNull(finalAnalysisSelection) { "Selected contract graph is not configured" }
                .freeze(run, active, submissions, analysisContract)
        }
        val eventId = OutboxEventId(idGenerator.next())
        outboxRepository.insert(
            OutboxEvent(
                id = eventId,
                aggregateType = "CoordinationRun",
                aggregateId = run.id.value,
                eventType = if (hasNaturalLanguage) STRUCTURING_REQUESTED else MATCHING_REQUESTED,
                payload = "{\"event_id\":\"${eventId.value}\",\"submission_batch_id\":\"${batch.id.value}\"}",
                occurredAt = recordedAt,
            ),
        )
        return active
    }

    companion object {
        const val STRUCTURING_REQUESTED = "SubmissionBatchStructuringRequested"
        const val MATCHING_REQUESTED = "SubmissionBatchMatchingRequested"
    }
}
