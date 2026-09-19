package com.meetme.server.adapter.output.persistence

import org.komapper.annotation.KomapperColumn
import org.komapper.annotation.KomapperEntityDef
import org.komapper.annotation.KomapperId
import org.komapper.annotation.KomapperTable
import org.komapper.annotation.KomapperVersion
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

data class GuestSessionRecord(
    val id: UUID,
    val credentialDigest: String,
    val expiresAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

@KomapperEntityDef(GuestSessionRecord::class)
@KomapperTable("guest_browser_sessions")
data class GuestSessionRecordDef(
    @KomapperId val id: Nothing,
)

data class MeetingRoomRecord(
    val id: UUID,
    val inviteCode: String,
    val purpose: String,
    val durationMinutes: Int,
    val meetingMode: String,
    val timeZoneId: String,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val searchRangeSource: String,
    val expectedParticipants: Int?,
    val submissionDeadline: OffsetDateTime?,
    val manualOnly: Boolean,
    val collectionStatus: String,
    val closureReason: String?,
    val closedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val version: Long,
)

@KomapperEntityDef(MeetingRoomRecord::class)
@KomapperTable("meeting_rooms")
data class MeetingRoomRecordDef(
    @KomapperId val id: Nothing,
    @KomapperVersion val version: Nothing,
)

data class ParticipantRecord(
    val id: UUID,
    val roomId: UUID,
    val guestSessionId: UUID,
    val displayName: String,
    val role: String,
    val joinedAt: OffsetDateTime,
)

@KomapperEntityDef(ParticipantRecord::class)
@KomapperTable("participants")
data class ParticipantRecordDef(
    @KomapperId val id: Nothing,
)

data class SubmissionHeadRecord(
    val id: UUID,
    val roomId: UUID,
    val participantId: UUID,
    val latestVersionId: UUID?,
)

@KomapperEntityDef(SubmissionHeadRecord::class)
@KomapperTable("submission_heads")
data class SubmissionHeadRecordDef(
    @KomapperId val id: Nothing,
)

data class SubmissionVersionRecord(
    val id: UUID,
    val submissionId: UUID,
    val revision: Int,
    val rawText: String?,
    val locale: String,
    val createdAt: OffsetDateTime,
)

@KomapperEntityDef(SubmissionVersionRecord::class)
@KomapperTable("submission_versions")
data class SubmissionVersionRecordDef(
    @KomapperId val id: Nothing,
)

data class ManualAvailabilityRecord(
    val id: UUID,
    val submissionVersionId: UUID,
    val intervalOrder: Int,
    val intervalKind: String,
    val localDate: LocalDate?,
    val dayOfWeek: Int?,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

@KomapperEntityDef(ManualAvailabilityRecord::class)
@KomapperTable("manual_availability_intervals")
data class ManualAvailabilityRecordDef(
    @KomapperId val id: Nothing,
)

data class SubmissionBatchRecord(
    val id: UUID,
    val roomId: UUID,
    val fixedAt: OffsetDateTime,
)

@KomapperEntityDef(SubmissionBatchRecord::class)
@KomapperTable("submission_batches")
data class SubmissionBatchRecordDef(
    @KomapperId val id: Nothing,
)

data class SubmissionBatchItemRecord(
    val batchId: UUID,
    val submissionVersionId: UUID,
)

@KomapperEntityDef(SubmissionBatchItemRecord::class)
@KomapperTable("submission_batch_items")
data class SubmissionBatchItemRecordDef(
    @KomapperId @KomapperColumn(name = "batch_id") val batchId: Nothing,
    @KomapperId @KomapperColumn(name = "submission_version_id") val submissionVersionId: Nothing,
)

data class CoordinationRunRecord(
    val id: UUID,
    val roomId: UUID,
    val batchId: UUID,
    val status: String,
    val candidateQuality: String?,
    val resumeStage: String?,
    val createdAt: OffsetDateTime,
    val version: Long,
)

@KomapperEntityDef(CoordinationRunRecord::class)
@KomapperTable("coordination_runs")
data class CoordinationRunRecordDef(
    @KomapperId val id: Nothing,
    @KomapperVersion val version: Nothing,
)

data class CandidateRecord(
    val id: UUID,
    val coordinationRunId: UUID,
    val rank: Int,
    val planType: String,
    val meetingMode: String,
    val attendanceCount: Int,
    val totalParticipants: Int,
    val placeName: String?,
    val latitude: java.math.BigDecimal?,
    val longitude: java.math.BigDecimal?,
)

@KomapperEntityDef(CandidateRecord::class)
@KomapperTable("candidates")
data class CandidateRecordDef(
    @KomapperId val id: Nothing,
)

data class CandidateParticipantRecord(
    val candidateId: UUID,
    val participantId: UUID,
    val coordinationRunId: UUID,
    val roomId: UUID,
)

@KomapperEntityDef(CandidateParticipantRecord::class)
@KomapperTable("candidate_participants")
data class CandidateParticipantRecordDef(
    @KomapperId @KomapperColumn(name = "candidate_id") val candidateId: Nothing,
    @KomapperId @KomapperColumn(name = "participant_id") val participantId: Nothing,
)

data class CandidateTimeRangeRecord(
    val candidateId: UUID,
    val rangeOrder: Int,
    val startAt: OffsetDateTime,
    val endAt: OffsetDateTime,
)

@KomapperEntityDef(CandidateTimeRangeRecord::class)
@KomapperTable("candidate_time_ranges")
data class CandidateTimeRangeRecordDef(
    @KomapperId @KomapperColumn(name = "candidate_id") val candidateId: Nothing,
    @KomapperId @KomapperColumn(name = "range_order") val rangeOrder: Nothing,
)

data class FinalConfirmationRecord(
    val coordinationRunId: UUID,
    val candidateId: UUID,
    val confirmedAt: OffsetDateTime,
)

@KomapperEntityDef(FinalConfirmationRecord::class)
@KomapperTable("final_confirmations")
data class FinalConfirmationRecordDef(
    @KomapperId val coordinationRunId: Nothing,
)

data class NormalizedPlaceRecord(
    val id: UUID,
    val coordinationRunId: UUID,
    val batchId: UUID,
    val submissionVersionId: UUID,
    val conditionIndex: Int,
    val query: String,
    val radiusMeters: Int,
    val status: String,
    val provider: String?,
    val providerPlaceId: String?,
    val displayName: String?,
    val latitude: java.math.BigDecimal?,
    val longitude: java.math.BigDecimal?,
)

@KomapperEntityDef(NormalizedPlaceRecord::class)
@KomapperTable("normalized_places")
data class NormalizedPlaceRecordDef(
    @KomapperId val id: Nothing,
)

data class OutboxEventRecord(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: JsonPayload,
    val status: String,
    val occurredAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
)

@KomapperEntityDef(OutboxEventRecord::class)
@KomapperTable("outbox_events")
data class OutboxEventRecordDef(
    @KomapperId val id: Nothing,
)
