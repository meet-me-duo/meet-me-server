package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxStatus
import com.meetme.server.coordination.domain.CandidatePlace
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.MeetingCandidate
import com.meetme.server.coordination.domain.ResumeStage
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.ZoneOffset

object PersistenceMappers {
    fun toRecords(domain: CoordinationRun): CoordinationRecords {
        val candidates =
            domain.candidates.map { candidate ->
                CandidateRecord(
                    id = candidate.id.value,
                    coordinationRunId = domain.id.value,
                    rank = candidate.rank,
                    planType = "PLAN_${candidate.planType.name}",
                    meetingMode = candidate.meetingMode.name,
                    attendanceCount = candidate.participantIds.size,
                    totalParticipants = candidate.totalParticipants,
                    placeName = candidate.place?.displayName,
                    latitude = candidate.place?.coordinate?.latitude,
                    longitude = candidate.place?.coordinate?.longitude,
                )
            }
        val candidateParticipants =
            domain.candidates.flatMap { candidate ->
                candidate.participantIds.map {
                    CandidateParticipantRecord(candidate.id.value, it.value, domain.id.value, domain.roomId.value)
                }
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
                    domain.resumeStage?.name,
                    domain.batch.fixedAt.atOffset(ZoneOffset.UTC),
                    domain.version,
                ),
            candidates = candidates,
            candidateParticipants = candidateParticipants,
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
                    planType = PlanType.valueOf(candidate.planType.removePrefix("PLAN_")),
                    meetingMode = MeetingMode.valueOf(candidate.meetingMode),
                    participantIds =
                        records.candidateParticipants
                            .filter { it.candidateId == candidate.id }
                            .map { ParticipantId(it.participantId) }
                            .sortedBy { it.value.toString() },
                    totalParticipants = candidate.totalParticipants,
                    place =
                        candidate.placeName?.let { placeName ->
                            CandidatePlace(
                                placeName,
                                if (candidate.latitude != null && candidate.longitude != null) {
                                    GeoCoordinate.of(candidate.latitude, candidate.longitude)
                                } else {
                                    null
                                },
                            )
                        },
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
            resumeStage = records.run.resumeStage?.let(ResumeStage::valueOf),
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
            domain.processedAt?.atOffset(ZoneOffset.UTC),
            domain.processingLeaseUntil?.atOffset(ZoneOffset.UTC),
            domain.deliveryCount,
            domain.lastFailureKind,
            domain.deadLetteredAt?.atOffset(ZoneOffset.UTC),
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
            record.processedAt?.toInstant(),
            record.processingLeaseUntil?.toInstant(),
            record.deliveryCount,
            record.lastFailureKind,
            record.deadLetteredAt?.toInstant(),
        )
}

data class CoordinationRecords(
    val batch: SubmissionBatchRecord,
    val batchItems: List<SubmissionBatchItemRecord>,
    val run: CoordinationRunRecord,
    val candidates: List<CandidateRecord>,
    val candidateParticipants: List<CandidateParticipantRecord>,
    val timeRanges: List<CandidateTimeRangeRecord>,
    val confirmation: FinalConfirmationRecord?,
)
