package com.meetme.server.submission.adapter.output.persistence

import org.komapper.annotation.KomapperEntityDef
import org.komapper.annotation.KomapperId
import org.komapper.annotation.KomapperTable
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

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
