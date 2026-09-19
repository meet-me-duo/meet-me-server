package com.meetme.server.application.port.output

import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.GuestSessionId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.OutboxEventId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.participant.GuestSession
import com.meetme.server.domain.participant.Participant
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
}

interface SubmissionRepository {
    fun insert(submission: Submission)

    fun findById(id: SubmissionId): Submission?

    fun countSubmittedParticipants(roomId: MeetingRoomId): Int
}

interface CoordinationRunRepository {
    fun insert(run: CoordinationRun)

    fun findById(id: CoordinationRunId): CoordinationRun?
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
}
