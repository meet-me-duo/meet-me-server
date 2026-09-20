package com.meetme.server.coordination.domain

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
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

enum class ResumeStage {
    STRUCTURING,
    MATCHING,
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
    val planType: PlanType = PlanType.A,
    val meetingMode: MeetingMode = MeetingMode.IN_PERSON,
    val participantIds: List<ParticipantId> = emptyList(),
    val totalParticipants: Int = participantIds.size,
    val place: CandidatePlace? = null,
)

data class CandidatePlace(
    val displayName: String,
    val coordinate: GeoCoordinate? = null,
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
    val resumeStage: ResumeStage?,
    val version: Long,
) {
    companion object {
        private val ACTIVE_STATUSES =
            setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING, CoordinationStatus.MATCHING)

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
                resumeStage = null,
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
            resumeStage: ResumeStage? = null,
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
                resumeStage,
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
        require(candidates.size <= 3) { "At most three candidates are allowed" }
        require(candidates.map { it.id }.distinct().size == candidates.size) { "Candidate IDs must be unique" }
        require(candidates.map { it.rank }.distinct().size == candidates.size) { "Candidate ranks must be unique" }
        require(candidates.all { it.rank in 1..3 && it.timeRanges.isNotEmpty() }) { "Candidate rank and time ranges are invalid" }
        return copy(
            status = CoordinationStatus.COMPLETED,
            quality = quality,
            candidates = candidates.sortedBy { it.rank },
            resumeStage = null,
            version = version + 1,
        )
    }

    fun delayAnalysis(): CoordinationRun {
        check(status == CoordinationStatus.QUEUED || status == CoordinationStatus.STRUCTURING || status == CoordinationStatus.MATCHING) {
            "Only an active coordination run can be delayed"
        }
        val stage = if (status == CoordinationStatus.MATCHING) ResumeStage.MATCHING else ResumeStage.STRUCTURING
        return copy(status = CoordinationStatus.ANALYSIS_DELAYED, resumeStage = stage, version = version + 1)
    }

    fun finishStructuring(): CoordinationRun {
        requireStatus(CoordinationStatus.STRUCTURING)
        return copy(status = CoordinationStatus.MATCHING, version = version + 1)
    }

    fun retryAnalysis(): CoordinationRun {
        requireStatus(CoordinationStatus.ANALYSIS_DELAYED)
        return copy(
            status = if (resumeStage == ResumeStage.MATCHING) CoordinationStatus.MATCHING else CoordinationStatus.QUEUED,
            resumeStage = null,
            version = version + 1,
        )
    }

    fun deadLetter(): CoordinationRun {
        check(status in ACTIVE_STATUSES) { "Only an active coordination run can be dead-lettered" }
        val stage = if (status == CoordinationStatus.MATCHING) ResumeStage.MATCHING else ResumeStage.STRUCTURING
        return copy(
            status = CoordinationStatus.DEAD_LETTERED,
            quality = null,
            resumeStage = stage,
            version = version + 1,
        )
    }

    fun retryDeadLetter(): CoordinationRun {
        requireStatus(CoordinationStatus.DEAD_LETTERED)
        return copy(
            status = if (resumeStage == ResumeStage.MATCHING) CoordinationStatus.MATCHING else CoordinationStatus.QUEUED,
            resumeStage = null,
            version = version + 1,
        )
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
