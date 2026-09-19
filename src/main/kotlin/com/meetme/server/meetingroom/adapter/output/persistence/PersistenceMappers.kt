package com.meetme.server.meetingroom.adapter.output.persistence

import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.shared.domain.time.SearchRangeSource
import java.time.ZoneOffset

object PersistenceMappers {
    fun toRecord(domain: MeetingRoom): MeetingRoomRecord =
        MeetingRoomRecord(
            id = domain.id.value,
            inviteCode = domain.inviteCode.value,
            purpose = domain.purpose,
            meetingMode = domain.mode.name,
            timeZoneId = domain.timeZone.value.id,
            searchStartDate = domain.searchRange.startInclusive,
            searchEndDate = domain.searchRange.endExclusive,
            searchRangeSource = domain.searchRange.source.name,
            expectedParticipants = domain.closurePolicy.expectedParticipants,
            submissionDeadline = domain.closurePolicy.deadline?.atOffset(ZoneOffset.UTC),
            manualOnly = domain.closurePolicy.manualOnly,
            collectionStatus = domain.collectionStatus.name,
            closureReason = domain.closureReason?.name,
            closedAt = domain.closedAt?.atOffset(ZoneOffset.UTC),
            createdAt = domain.createdAt.atOffset(ZoneOffset.UTC),
            version = domain.version,
        )

    fun toDomain(record: MeetingRoomRecord): MeetingRoom =
        MeetingRoom.restore(
            id = MeetingRoomId(record.id),
            inviteCode = InviteCode.of(record.inviteCode),
            purpose = record.purpose,
            mode = MeetingMode.valueOf(record.meetingMode),
            timeZone = MeetingTimeZone.of(record.timeZoneId),
            searchRange =
                SearchDateRange.restore(
                    record.searchStartDate,
                    record.searchEndDate,
                    SearchRangeSource.valueOf(record.searchRangeSource),
                ),
            closurePolicy =
                ClosurePolicy.of(
                    record.expectedParticipants,
                    record.submissionDeadline?.toInstant(),
                    record.manualOnly,
                ),
            collectionStatus = CollectionStatus.valueOf(record.collectionStatus),
            closureReason = record.closureReason?.let(ClosureReason::valueOf),
            closedAt = record.closedAt?.toInstant(),
            createdAt = record.createdAt.toInstant(),
            version = record.version,
        )
}
