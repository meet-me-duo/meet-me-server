package com.meetme.server.shared.domain

import java.util.UUID

@JvmInline
value class MeetingRoomId(
    val value: UUID,
)

@JvmInline
value class GuestSessionId(
    val value: UUID,
)

@JvmInline
value class ParticipantId(
    val value: UUID,
)

@JvmInline
value class SubmissionId(
    val value: UUID,
)

@JvmInline
value class SubmissionVersionId(
    val value: UUID,
)

@JvmInline
value class SubmissionBatchId(
    val value: UUID,
)

@JvmInline
value class CoordinationRunId(
    val value: UUID,
)

@JvmInline
value class CandidateId(
    val value: UUID,
)

@JvmInline
value class OutboxEventId(
    val value: UUID,
)
