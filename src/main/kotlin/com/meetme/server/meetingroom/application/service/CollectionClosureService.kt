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
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import org.springframework.beans.factory.annotation.Value
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
    @param:Value("\${meetme.analysis.default-contract:LEGACY}")
    private val defaultAnalysisContract: FinalAnalysisContractSelection = FinalAnalysisContractSelection.LEGACY,
    private val prechecks: PrecheckRepository? = null,
) {
    @Transactional
    fun close(
        room: MeetingRoom,
        reason: ClosureReason,
        submittedParticipants: Int,
        at: Instant,
        analysisContract: FinalAnalysisContractSelection? = null,
    ): MeetingRoom {
        if (room.collectionStatus == CollectionStatus.CLOSED) return room
        val submissions =
            if (submittedParticipants >= 2) {
                submissionRepository.findLatestByRoom(room.id).sortedBy { it.participantId.value }
            } else {
                emptyList()
            }
        if (submittedParticipants >= 2) {
            check(submissions.size == submittedParticipants) { "Submitted participant count changed while closing" }
        }
        val defaultV5 =
            analysisContract == null &&
                defaultAnalysisContract == FinalAnalysisContractSelection.DSL_STRUCTURAL_V5 &&
                submissions.isNotEmpty() &&
                submissions.all { it.latest.rawText != null }
        if (defaultV5) {
            // Unsupported historical anchors may retain Legacy; a genuinely future source must still fail.
            val fixedAt = at.truncatedTo(ChronoUnit.MICROS)
            require(submissions.all { it.roomId == room.id && it.latest.createdAt <= fixedAt })
        }
        val chosenContract =
            analysisContract ?: if (defaultV5 && submissions.all { supportsNativeReferenceDate(it, room) }) {
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5
            } else {
                FinalAnalysisContractSelection.LEGACY
            }
        val recordedAt =
            if (chosenContract != FinalAnalysisContractSelection.LEGACY) at.truncatedTo(ChronoUnit.MICROS) else at
        val closed = room.close(reason, recordedAt, submittedParticipants)
        if (closed === room) return room
        roomRepository.update(closed)
        if (submittedParticipants < 2) return closed

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
        if (chosenContract != FinalAnalysisContractSelection.LEGACY) {
            checkNotNull(finalAnalysisSelection) { "Selected contract graph is not configured" }
                .freeze(run, active, submissions, chosenContract)
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

    private fun supportsNativeReferenceDate(
        submission: Submission,
        room: MeetingRoom,
    ): Boolean {
        val version = submission.latest
        if (version.evidence != null) return true
        val anchor = prechecks?.anchor(version.id.value) ?: return true
        val zone = room.timeZone.value
        return anchor.atZone(zone).toLocalDate() == version.createdAt.atZone(zone).toLocalDate()
    }

    companion object {
        const val STRUCTURING_REQUESTED = "SubmissionBatchStructuringRequested"
        const val MATCHING_REQUESTED = "SubmissionBatchMatchingRequested"
    }
}
