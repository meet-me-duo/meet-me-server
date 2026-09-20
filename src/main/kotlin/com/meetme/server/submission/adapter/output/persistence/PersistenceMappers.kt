package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.DatedTimeRange
import com.meetme.server.shared.domain.time.LocalTimeRange
import com.meetme.server.shared.domain.time.WeeklyTimeRange
import com.meetme.server.submission.domain.ManualAvailability
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

object PersistenceMappers {
    fun toRecords(domain: Submission): SubmissionRecords {
        val version = domain.latest
        val intervals =
            version.manualAvailability.mapIndexed { index, availability ->
                when (availability) {
                    is ManualAvailability.Dated ->
                        ManualAvailabilityRecord(
                            UUID.randomUUID(),
                            version.id.value,
                            index,
                            "DATED",
                            availability.range.date,
                            null,
                            availability.range.time.startInclusive,
                            availability.range.time.endExclusive,
                        )

                    is ManualAvailability.Weekly ->
                        ManualAvailabilityRecord(
                            UUID.randomUUID(),
                            version.id.value,
                            index,
                            "WEEKLY",
                            null,
                            availability.range.dayOfWeek.value,
                            availability.range.time.startInclusive,
                            availability.range.time.endExclusive,
                        )
                }
            }
        return SubmissionRecords(
            head = SubmissionHeadRecord(domain.id.value, domain.roomId.value, domain.participantId.value, version.id.value),
            version =
                SubmissionVersionRecord(
                    version.id.value,
                    domain.id.value,
                    version.revision,
                    version.rawText,
                    version.locale.toLanguageTag(),
                    version.createdAt.atOffset(ZoneOffset.UTC),
                ),
            intervals = intervals,
        )
    }

    fun toDomain(records: SubmissionRecords): Submission {
        val availability =
            records.intervals.sortedBy { it.intervalOrder }.map { interval ->
                val time = LocalTimeRange.of(interval.startTime, interval.endTime)
                when (interval.intervalKind) {
                    "DATED" -> ManualAvailability.Dated(DatedTimeRange(requireNotNull(interval.localDate), time))
                    "WEEKLY" ->
                        ManualAvailability.Weekly(
                            WeeklyTimeRange(DayOfWeek.of(requireNotNull(interval.dayOfWeek)), time),
                        )
                    else -> error("Unsupported manual availability kind: ${interval.intervalKind}")
                }
            }
        val version =
            SubmissionVersion(
                SubmissionVersionId(records.version.id),
                records.version.revision,
                records.version.rawText,
                availability,
                Locale.forLanguageTag(records.version.locale),
                records.version.createdAt.toInstant(),
            )
        return Submission.restore(
            SubmissionId(records.head.id),
            MeetingRoomId(records.head.roomId),
            ParticipantId(records.head.participantId),
            version,
        )
    }
}

data class SubmissionRecords(
    val head: SubmissionHeadRecord,
    val version: SubmissionVersionRecord,
    val intervals: List<ManualAvailabilityRecord>,
)
