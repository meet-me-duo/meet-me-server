package com.meetme.server.domain.coordination

import com.meetme.server.domain.common.CandidateId
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.time.InstantTimeRange
import java.time.Instant

enum class CoordinationStatus {
    QUEUED,
    STRUCTURING,
    MATCHING,
    COMPLETED,
    ANALYSIS_DELAYED,
    DEAD_LETTERED,
}

enum class CandidateQuality {
    COMPLETE,
    PARTIAL,
}

data class SubmissionBatch(
    val id: SubmissionBatchId,
    val roomId: MeetingRoomId,
    val submissionVersionIds: List<SubmissionVersionId>,
    val fixedAt: Instant,
)

data class MeetingCandidate(
    val id: CandidateId,
    val rank: Int,
    val timeRanges: List<InstantTimeRange>,
)

data class CoordinationRun private constructor(
    val id: CoordinationRunId,
    val roomId: MeetingRoomId,
    val batch: SubmissionBatch,
    val status: CoordinationStatus,
    val quality: CandidateQuality?,
    val candidates: List<MeetingCandidate>,
    val confirmedCandidateId: CandidateId?,
    val confirmedAt: Instant?,
    val version: Long,
) {
    companion object {
        fun queued(
            id: CoordinationRunId,
            batch: SubmissionBatch,
        ): CoordinationRun {
            require(batch.submissionVersionIds.distinct().size >= 2) { "At least two unique submissions are required" }
            return CoordinationRun(
                id = id,
                roomId = batch.roomId,
                batch = batch.copy(submissionVersionIds = batch.submissionVersionIds.distinct()),
                status = CoordinationStatus.QUEUED,
                quality = null,
                candidates = emptyList(),
                confirmedCandidateId = null,
                confirmedAt = null,
                version = 0,
            )
        }

        fun restore(
            id: CoordinationRunId,
            roomId: MeetingRoomId,
            batch: SubmissionBatch,
            status: CoordinationStatus,
            quality: CandidateQuality?,
            candidates: List<MeetingCandidate>,
            confirmedCandidateId: CandidateId?,
            confirmedAt: Instant?,
            version: Long,
        ): CoordinationRun =
            CoordinationRun(
                id,
                roomId,
                batch,
                status,
                quality,
                candidates.sortedBy { it.rank },
                confirmedCandidateId,
                confirmedAt,
                version,
            )
    }

    fun startStructuring(): CoordinationRun {
        requireStatus(CoordinationStatus.QUEUED)
        return copy(status = CoordinationStatus.STRUCTURING, version = version + 1)
    }

    fun startMatching(): CoordinationRun {
        check(status == CoordinationStatus.QUEUED || status == CoordinationStatus.STRUCTURING) {
            "Matching can start only from queued or structuring"
        }
        return copy(status = CoordinationStatus.MATCHING, version = version + 1)
    }

    fun complete(
        quality: CandidateQuality,
        candidates: List<MeetingCandidate>,
    ): CoordinationRun {
        requireStatus(CoordinationStatus.MATCHING)
        require(candidates.isNotEmpty() && candidates.size <= 3) { "One to three candidates are required" }
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Candidate IDs must be unique" }
        require(candidates.map { it.rank }.distinct().size == candidates.size) { "Candidate ranks must be unique" }
        require(candidates.all { it.rank in 1..3 && it.timeRanges.isNotEmpty() }) { "Candidate rank and time ranges are invalid" }
        return copy(
            status = CoordinationStatus.COMPLETED,
            quality = quality,
            candidates = candidates.sortedBy { it.rank },
            version = version + 1,
        )
    }

    fun delayAnalysis(): CoordinationRun {
        check(status == CoordinationStatus.QUEUED || status == CoordinationStatus.STRUCTURING || status == CoordinationStatus.MATCHING) {
            "Only an active coordination run can be delayed"
        }
        return copy(status = CoordinationStatus.ANALYSIS_DELAYED, version = version + 1)
    }

    fun finishStructuring(): CoordinationRun {
        requireStatus(CoordinationStatus.STRUCTURING)
        return copy(status = CoordinationStatus.MATCHING, version = version + 1)
    }

    fun retryAnalysis(): CoordinationRun {
        requireStatus(CoordinationStatus.ANALYSIS_DELAYED)
        return copy(status = CoordinationStatus.QUEUED, version = version + 1)
    }

    fun confirm(
        candidateId: CandidateId,
        at: Instant,
    ): CoordinationRun {
        requireStatus(CoordinationStatus.COMPLETED)
        check(confirmedCandidateId == null) { "Candidate is already confirmed" }
        require(candidates.any { it.id == candidateId }) { "Candidate does not belong to this coordination run" }
        require(at >= batch.fixedAt) { "Confirmation cannot precede the fixed batch" }
        return copy(confirmedCandidateId = candidateId, confirmedAt = at, version = version + 1)
    }

    private fun requireStatus(expected: CoordinationStatus) {
        check(status == expected) { "Expected status $expected but was $status" }
    }
}
