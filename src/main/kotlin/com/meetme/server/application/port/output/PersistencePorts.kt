package com.meetme.server.application.port.output

import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.GuestSessionId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.OutboxEventId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.location.GeoCoordinate
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.participant.GuestSession
import com.meetme.server.domain.participant.Participant
import com.meetme.server.domain.submission.StructuredSubmissionResult
import com.meetme.server.domain.submission.Submission
import java.time.Instant
import java.util.UUID

interface MeetingRoomRepository {
    fun insert(room: MeetingRoom)

    fun insertIfInviteAvailable(room: MeetingRoom): Boolean

    fun update(room: MeetingRoom)

    fun findById(id: MeetingRoomId): MeetingRoom?

    fun findByInviteCode(inviteCode: InviteCode): MeetingRoom?

    fun findByInviteCodeForUpdate(inviteCode: InviteCode): MeetingRoom?

    fun existsByInviteCode(inviteCode: InviteCode): Boolean

    fun findDueForUpdate(
        now: Instant,
        limit: Int,
    ): List<MeetingRoom>
}

interface GuestSessionRepository {
    fun insert(session: GuestSession)

    fun findById(id: GuestSessionId): GuestSession?

    fun findByCredentialDigest(credentialDigest: String): GuestSession?

    fun findByCredentialDigestForUpdate(credentialDigest: String): GuestSession?
}

interface ParticipantRepository {
    fun insert(participant: Participant)

    fun findById(id: ParticipantId): Participant?

    fun findByRoomAndGuestSession(
        roomId: MeetingRoomId,
        guestSessionId: GuestSessionId,
    ): Participant?

    fun countByRoom(roomId: MeetingRoomId): Int
}

interface SubmissionRepository {
    fun insert(submission: Submission)

    fun findById(id: SubmissionId): Submission?

    fun save(submission: Submission)

    fun findByParticipant(participantId: ParticipantId): Submission?

    fun findLatestByRoom(roomId: MeetingRoomId): List<Submission>

    fun countSubmittedParticipants(roomId: MeetingRoomId): Int
}

interface CoordinationRunRepository {
    fun insert(run: CoordinationRun)

    fun findById(id: CoordinationRunId): CoordinationRun?

    fun update(run: CoordinationRun)

    fun findLatestByRoom(roomId: MeetingRoomId): CoordinationRun?

    fun findLatestByRoomForUpdate(roomId: MeetingRoomId): CoordinationRun?

    fun findByBatchId(batchId: SubmissionBatchId): CoordinationRun?
}

interface StructuredSubmissionRepository {
    fun replaceForBatch(
        batchId: SubmissionBatchId,
        results: List<StructuredSubmissionResult>,
        processedAt: Instant,
    )

    fun findByBatch(batchId: SubmissionBatchId): List<StructuredSubmissionResult>
}

enum class NormalizedPlaceStatus {
    RESOLVED,
    NO_EXACT_MATCH,
    AMBIGUOUS,
}

data class NormalizedPlace(
    val batchId: SubmissionBatchId,
    val submissionVersionId: com.meetme.server.domain.common.SubmissionVersionId,
    val conditionIndex: Int,
    val query: String,
    val radiusMeters: Int,
    val status: NormalizedPlaceStatus,
    val providerPlaceId: String? = null,
    val displayName: String? = null,
    val coordinate: GeoCoordinate? = null,
)

interface NormalizedPlaceRepository {
    fun replaceForBatch(
        batchId: SubmissionBatchId,
        places: List<NormalizedPlace>,
    )

    fun findByBatch(batchId: SubmissionBatchId): List<NormalizedPlace>
}

data class CoordinationAttempt(
    val id: UUID,
    val coordinationRunId: CoordinationRunId,
    val attemptNumber: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val failureKind: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val responseBytes: Int?,
    val estimatedCostUsd: java.math.BigDecimal?,
)

interface CoordinationAttemptRepository {
    fun insert(attempt: CoordinationAttempt)

    fun update(attempt: CoordinationAttempt)

    fun countByRun(runId: CoordinationRunId): Int
}

enum class OutboxStatus {
    PENDING,
    PUBLISHED,
}

data class OutboxEvent(
    val id: OutboxEventId,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val occurredAt: Instant,
    val publishedAt: Instant? = null,
) {
    init {
        require(aggregateType.isNotBlank())
        require(eventType.isNotBlank())
        require(payload.isNotBlank())
        require((status == OutboxStatus.PENDING) == (publishedAt == null))
    }
}

interface OutboxRepository {
    fun insert(event: OutboxEvent)

    fun findById(id: OutboxEventId): OutboxEvent?

    fun existsPending(
        aggregateId: UUID,
        eventType: String,
    ): Boolean
}
