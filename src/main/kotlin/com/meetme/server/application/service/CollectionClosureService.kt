package com.meetme.server.application.service

import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.IdGenerator
import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.OutboxEvent
import com.meetme.server.application.port.output.OutboxRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.OutboxEventId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.coordination.SubmissionBatch
import com.meetme.server.domain.meeting.ClosureReason
import com.meetme.server.domain.meeting.MeetingRoom
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class CollectionClosureService(
    private val roomRepository: MeetingRoomRepository,
    private val submissionRepository: SubmissionRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val outboxRepository: OutboxRepository,
    private val idGenerator: IdGenerator,
) {
    @Transactional
    fun close(
        room: MeetingRoom,
        reason: ClosureReason,
        submittedParticipants: Int,
        at: Instant,
    ): MeetingRoom {
        val closed = room.close(reason, at, submittedParticipants)
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
                at,
            )
        var run = CoordinationRun.queued(CoordinationRunId(idGenerator.next()), batch)
        val hasNaturalLanguage = submissions.any { !it.latest.rawText.isNullOrBlank() }
        if (!hasNaturalLanguage) run = run.startMatching()
        coordinationRunRepository.insert(run)
        val eventId = OutboxEventId(idGenerator.next())
        outboxRepository.insert(
            OutboxEvent(
                id = eventId,
                aggregateType = "CoordinationRun",
                aggregateId = run.id.value,
                eventType = if (hasNaturalLanguage) STRUCTURING_REQUESTED else MATCHING_REQUESTED,
                payload = "{\"event_id\":\"${eventId.value}\",\"submission_batch_id\":\"${batch.id.value}\"}",
                occurredAt = at,
            ),
        )
        return closed
    }

    companion object {
        const val STRUCTURING_REQUESTED = "SubmissionBatchStructuringRequested"
        const val MATCHING_REQUESTED = "SubmissionBatchMatchingRequested"
    }
}
