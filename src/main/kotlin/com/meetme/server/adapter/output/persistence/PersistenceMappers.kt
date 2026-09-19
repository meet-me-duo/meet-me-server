package com.meetme.server.adapter.output.persistence

import com.meetme.server.application.port.output.OutboxEvent
import com.meetme.server.application.port.output.OutboxStatus
import com.meetme.server.domain.common.CandidateId
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.GuestSessionId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.OutboxEventId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.coordination.CandidateQuality
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.coordination.CoordinationStatus
import com.meetme.server.domain.coordination.MeetingCandidate
import com.meetme.server.domain.coordination.SubmissionBatch
import com.meetme.server.domain.meeting.ClosurePolicy
import com.meetme.server.domain.meeting.ClosureReason
import com.meetme.server.domain.meeting.CollectionStatus
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.participant.GuestSession
import com.meetme.server.domain.participant.Participant
import com.meetme.server.domain.participant.ParticipantDisplayName
import com.meetme.server.domain.participant.ParticipantRole
import com.meetme.server.domain.submission.ManualAvailability
import com.meetme.server.domain.submission.Submission
import com.meetme.server.domain.submission.SubmissionVersion
import com.meetme.server.domain.time.DatedTimeRange
import com.meetme.server.domain.time.InstantTimeRange
import com.meetme.server.domain.time.LocalTimeRange
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
import com.meetme.server.domain.time.SearchRangeSource
import com.meetme.server.domain.time.WeeklyTimeRange
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

object PersistenceMappers {
    fun toRecord(domain: MeetingRoom): MeetingRoomRecord =
        MeetingRoomRecord(
            id = domain.id.value,
            inviteCode = domain.inviteCode.value,
            purpose = domain.purpose,
            durationMinutes =
                domain.duration.value
                    .toMinutes()
                    .toInt(),
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
            duration = MeetingDuration.ofMinutes(record.durationMinutes.toLong()),
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

    fun toRecord(domain: GuestSession): GuestSessionRecord =
        GuestSessionRecord(
            domain.id.value,
            domain.credentialDigest,
            domain.expiresAt.atOffset(ZoneOffset.UTC),
            domain.revokedAt?.atOffset(ZoneOffset.UTC),
            domain.createdAt.atOffset(ZoneOffset.UTC),
        )

    fun toDomain(record: GuestSessionRecord): GuestSession =
        GuestSession(
            GuestSessionId(record.id),
            record.credentialDigest,
            record.expiresAt.toInstant(),
            record.revokedAt?.toInstant(),
            record.createdAt.toInstant(),
        )

    fun toRecord(domain: Participant): ParticipantRecord =
        ParticipantRecord(
            domain.id.value,
            domain.roomId.value,
            domain.guestSessionId.value,
            domain.displayName.value,
            domain.role.name,
            domain.joinedAt.atOffset(ZoneOffset.UTC),
        )

    fun toDomain(record: ParticipantRecord): Participant =
        Participant.restore(
            ParticipantId(record.id),
            MeetingRoomId(record.roomId),
            GuestSessionId(record.guestSessionId),
            ParticipantDisplayName.of(record.displayName),
            ParticipantRole.valueOf(record.role),
            record.joinedAt.toInstant(),
        )

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

    fun toRecords(domain: CoordinationRun): CoordinationRecords {
        val candidates =
            domain.candidates.map {
                CandidateRecord(it.id.value, domain.id.value, it.rank, null, null, null)
            }
        val ranges =
            domain.candidates.flatMap { candidate ->
                candidate.timeRanges.mapIndexed { index, range ->
                    CandidateTimeRangeRecord(
                        candidate.id.value,
                        index,
                        range.startInclusive.atOffset(ZoneOffset.UTC),
                        range.endExclusive.atOffset(ZoneOffset.UTC),
                    )
                }
            }
        return CoordinationRecords(
            batch = SubmissionBatchRecord(domain.batch.id.value, domain.roomId.value, domain.batch.fixedAt.atOffset(ZoneOffset.UTC)),
            batchItems =
                domain.batch.submissionVersionIds.map {
                    SubmissionBatchItemRecord(domain.batch.id.value, it.value)
                },
            run =
                CoordinationRunRecord(
                    domain.id.value,
                    domain.roomId.value,
                    domain.batch.id.value,
                    domain.status.name,
                    domain.quality?.name,
                    domain.batch.fixedAt.atOffset(ZoneOffset.UTC),
                    domain.version,
                ),
            candidates = candidates,
            timeRanges = ranges,
            confirmation =
                domain.confirmedCandidateId?.let {
                    FinalConfirmationRecord(domain.id.value, it.value, requireNotNull(domain.confirmedAt).atOffset(ZoneOffset.UTC))
                },
        )
    }

    fun toDomain(records: CoordinationRecords): CoordinationRun {
        val candidates =
            records.candidates.map { candidate ->
                MeetingCandidate(
                    CandidateId(candidate.id),
                    candidate.rank,
                    records.timeRanges
                        .filter { it.candidateId == candidate.id }
                        .sortedBy { it.rangeOrder }
                        .map { InstantTimeRange(it.startAt.toInstant(), it.endAt.toInstant()) },
                )
            }
        val batch =
            SubmissionBatch(
                SubmissionBatchId(records.batch.id),
                MeetingRoomId(records.batch.roomId),
                records.batchItems.map { SubmissionVersionId(it.submissionVersionId) },
                records.batch.fixedAt.toInstant(),
            )
        return CoordinationRun.restore(
            id = CoordinationRunId(records.run.id),
            roomId = MeetingRoomId(records.run.roomId),
            batch = batch,
            status = CoordinationStatus.valueOf(records.run.status),
            quality = records.run.candidateQuality?.let(CandidateQuality::valueOf),
            candidates = candidates,
            confirmedCandidateId = records.confirmation?.let { CandidateId(it.candidateId) },
            confirmedAt = records.confirmation?.confirmedAt?.toInstant(),
            version = records.run.version,
        )
    }

    fun toRecord(domain: OutboxEvent): OutboxEventRecord =
        OutboxEventRecord(
            domain.id.value,
            domain.aggregateType,
            domain.aggregateId,
            domain.eventType,
            JsonPayload.from(domain.payload),
            domain.status.name,
            domain.occurredAt.atOffset(ZoneOffset.UTC),
            domain.publishedAt?.atOffset(ZoneOffset.UTC),
        )

    fun toDomain(record: OutboxEventRecord): OutboxEvent =
        OutboxEvent(
            OutboxEventId(record.id),
            record.aggregateType,
            record.aggregateId,
            record.eventType,
            record.payload.value,
            OutboxStatus.valueOf(record.status),
            record.occurredAt.toInstant(),
            record.publishedAt?.toInstant(),
        )
}

data class SubmissionRecords(
    val head: SubmissionHeadRecord,
    val version: SubmissionVersionRecord,
    val intervals: List<ManualAvailabilityRecord>,
)

data class CoordinationRecords(
    val batch: SubmissionBatchRecord,
    val batchItems: List<SubmissionBatchItemRecord>,
    val run: CoordinationRunRecord,
    val candidates: List<CandidateRecord>,
    val timeRanges: List<CandidateTimeRangeRecord>,
    val confirmation: FinalConfirmationRecord?,
)
