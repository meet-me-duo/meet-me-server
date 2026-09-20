package com.meetme.server.submission.application.port.output

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import java.time.Instant

interface SubmissionRepository {
    fun insert(submission: Submission)

    fun findById(id: SubmissionId): Submission?

    fun save(submission: Submission)

    fun findByParticipant(participantId: ParticipantId): Submission?

    fun findLatestByRoom(roomId: MeetingRoomId): List<Submission>

    fun countSubmittedParticipants(roomId: MeetingRoomId): Int
}

interface StructuredSubmissionRepository {
    fun replaceForBatch(
        batchId: SubmissionBatchId,
        results: List<StructuredSubmissionResult>,
        processedAt: Instant,
    )

    fun findByBatch(batchId: SubmissionBatchId): List<StructuredSubmissionResult>
}
