package com.meetme.server.coordination.adapter.output.persistence

import org.komapper.annotation.KomapperColumn
import org.komapper.annotation.KomapperEntityDef
import org.komapper.annotation.KomapperId
import org.komapper.annotation.KomapperTable
import org.komapper.annotation.KomapperVersion
import java.time.OffsetDateTime
import java.util.UUID

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
    val processedAt: OffsetDateTime?,
    val processingLeaseUntil: OffsetDateTime?,
    val deliveryCount: Int,
    val lastFailureKind: String?,
    val deadLetteredAt: OffsetDateTime?,
)

@KomapperEntityDef(OutboxEventRecord::class)
@KomapperTable("outbox_events")
data class OutboxEventRecordDef(
    @KomapperId val id: Nothing,
)
