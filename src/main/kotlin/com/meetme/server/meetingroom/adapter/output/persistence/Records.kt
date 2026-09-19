package com.meetme.server.meetingroom.adapter.output.persistence

import org.komapper.annotation.KomapperEntityDef
import org.komapper.annotation.KomapperId
import org.komapper.annotation.KomapperTable
import org.komapper.annotation.KomapperVersion
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class MeetingRoomRecord(
    val id: UUID,
    val inviteCode: String,
    val purpose: String,
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
