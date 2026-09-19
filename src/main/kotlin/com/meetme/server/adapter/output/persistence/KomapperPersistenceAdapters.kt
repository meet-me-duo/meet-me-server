package com.meetme.server.adapter.output.persistence

import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.GuestSessionRepository
import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.OutboxEvent
import com.meetme.server.application.port.output.OutboxRepository
import com.meetme.server.application.port.output.ParticipantRepository
import com.meetme.server.application.port.output.SubmissionRepository
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
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class KomapperMeetingRoomRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : MeetingRoomRepository {
    override fun insert(room: MeetingRoom) {
        database.runQuery { QueryDsl.insert(Meta.meetingRoomRecord).single(PersistenceMappers.toRecord(room)) }
    }

    override fun insertIfInviteAvailable(room: MeetingRoom): Boolean {
        val record = PersistenceMappers.toRecord(room)
        return jdbcTemplate.update(
            """
            INSERT INTO meeting_rooms (
                id, invite_code, purpose, duration_minutes, meeting_mode, time_zone_id,
                search_start_date, search_end_date, search_range_source,
                expected_participants, submission_deadline, manual_only,
                collection_status, closure_reason, closed_at, created_at, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (invite_code) DO NOTHING
            """.trimIndent(),
            record.id,
            record.inviteCode,
            record.purpose,
            record.durationMinutes,
            record.meetingMode,
            record.timeZoneId,
            record.searchStartDate,
            record.searchEndDate,
            record.searchRangeSource,
            record.expectedParticipants,
            record.submissionDeadline,
            record.manualOnly,
            record.collectionStatus,
            record.closureReason,
            record.closedAt,
            record.createdAt,
            record.version,
        ) == 1
    }

    override fun update(room: MeetingRoom) {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE meeting_rooms
                SET collection_status = ?, closure_reason = ?, closed_at = ?, version = ?
                WHERE id = ? AND version = ?
                """.trimIndent(),
                room.collectionStatus.name,
                room.closureReason?.name,
                room.closedAt?.atOffset(java.time.ZoneOffset.UTC),
                room.version,
                room.id.value,
                room.version - 1,
            )
        check(updated == 1) { "Meeting room update lost an optimistic concurrency race" }
    }

    override fun findById(id: MeetingRoomId): MeetingRoom? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.meetingRoomRecord)
                    .where { Meta.meetingRoomRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByInviteCode(inviteCode: InviteCode): MeetingRoom? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.meetingRoomRecord)
                    .where { Meta.meetingRoomRecord.inviteCode eq inviteCode.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByInviteCodeForUpdate(inviteCode: InviteCode): MeetingRoom? {
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM meeting_rooms WHERE invite_code = ? FOR UPDATE",
                    { resultSet, _ -> resultSet.getObject("id", java.util.UUID::class.java) },
                    inviteCode.value,
                ).firstOrNull() ?: return null
        return findById(MeetingRoomId(id))
    }

    override fun existsByInviteCode(inviteCode: InviteCode): Boolean = findByInviteCode(inviteCode) != null
}

@Repository
class KomapperGuestSessionRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : GuestSessionRepository {
    override fun insert(session: GuestSession) {
        database.runQuery { QueryDsl.insert(Meta.guestSessionRecord).single(PersistenceMappers.toRecord(session)) }
    }

    override fun findById(id: GuestSessionId): GuestSession? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.guestSessionRecord)
                    .where { Meta.guestSessionRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByCredentialDigest(credentialDigest: String): GuestSession? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.guestSessionRecord)
                    .where { Meta.guestSessionRecord.credentialDigest eq credentialDigest }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByCredentialDigestForUpdate(credentialDigest: String): GuestSession? {
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM guest_browser_sessions WHERE credential_digest = ? FOR UPDATE",
                    { resultSet, _ -> resultSet.getObject("id", java.util.UUID::class.java) },
                    credentialDigest,
                ).firstOrNull() ?: return null
        return findById(GuestSessionId(id))
    }
}

@Repository
class KomapperParticipantRepository(
    private val database: JdbcDatabase,
) : ParticipantRepository {
    override fun insert(participant: Participant) {
        database.runQuery { QueryDsl.insert(Meta.participantRecord).single(PersistenceMappers.toRecord(participant)) }
    }

    override fun findById(id: ParticipantId): Participant? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.participantRecord)
                    .where { Meta.participantRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)

    override fun findByRoomAndGuestSession(
        roomId: MeetingRoomId,
        guestSessionId: GuestSessionId,
    ): Participant? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.participantRecord)
                    .where { Meta.participantRecord.roomId eq roomId.value }
                    .where { Meta.participantRecord.guestSessionId eq guestSessionId.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)
}

@Repository
class KomapperSubmissionRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : SubmissionRepository {
    override fun insert(submission: Submission) {
        val records = PersistenceMappers.toRecords(submission)
        val headWithoutVersion = records.head.copy(latestVersionId = null)
        database.runQuery { QueryDsl.insert(Meta.submissionHeadRecord).single(headWithoutVersion) }
        database.runQuery { QueryDsl.insert(Meta.submissionVersionRecord).single(records.version) }
        if (records.intervals.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.manualAvailabilityRecord).multiple(records.intervals) }
        }
        database.runQuery { QueryDsl.update(Meta.submissionHeadRecord).single(records.head) }
    }

    override fun findById(id: SubmissionId): Submission? {
        val head =
            database.runQuery {
                QueryDsl
                    .from(Meta.submissionHeadRecord)
                    .where { Meta.submissionHeadRecord.id eq id.value }
                    .firstOrNull()
            } ?: return null
        val latestVersionId = head.latestVersionId ?: return null
        val version =
            requireNotNull(
                database.runQuery {
                    QueryDsl
                        .from(Meta.submissionVersionRecord)
                        .where { Meta.submissionVersionRecord.id eq latestVersionId }
                        .firstOrNull()
                },
            )
        val intervals =
            database.runQuery {
                QueryDsl
                    .from(Meta.manualAvailabilityRecord)
                    .where { Meta.manualAvailabilityRecord.submissionVersionId eq latestVersionId }
                    .orderBy(Meta.manualAvailabilityRecord.intervalOrder)
            }
        return PersistenceMappers.toDomain(SubmissionRecords(head, version, intervals))
    }

    override fun countSubmittedParticipants(roomId: MeetingRoomId): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM submission_heads WHERE room_id = ? AND latest_version_id IS NOT NULL",
                Int::class.java,
                roomId.value,
            ),
        )
}

@Repository
class KomapperCoordinationRunRepository(
    private val database: JdbcDatabase,
) : CoordinationRunRepository {
    override fun insert(run: CoordinationRun) {
        val records = PersistenceMappers.toRecords(run)
        database.runQuery { QueryDsl.insert(Meta.submissionBatchRecord).single(records.batch) }
        database.runQuery { QueryDsl.insert(Meta.submissionBatchItemRecord).multiple(records.batchItems) }
        database.runQuery { QueryDsl.insert(Meta.coordinationRunRecord).single(records.run) }
        if (records.candidates.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateRecord).multiple(records.candidates) }
        }
        if (records.timeRanges.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateTimeRangeRecord).multiple(records.timeRanges) }
        }
        records.confirmation?.let {
            database.runQuery { QueryDsl.insert(Meta.finalConfirmationRecord).single(it) }
        }
    }

    override fun findById(id: CoordinationRunId): CoordinationRun? {
        val run =
            database.runQuery {
                QueryDsl
                    .from(Meta.coordinationRunRecord)
                    .where { Meta.coordinationRunRecord.id eq id.value }
                    .firstOrNull()
            } ?: return null
        val batch =
            requireNotNull(
                database.runQuery {
                    QueryDsl
                        .from(Meta.submissionBatchRecord)
                        .where { Meta.submissionBatchRecord.id eq run.batchId }
                        .firstOrNull()
                },
            )
        val batchItems =
            database.runQuery {
                QueryDsl
                    .from(Meta.submissionBatchItemRecord)
                    .where { Meta.submissionBatchItemRecord.batchId eq batch.id }
            }
        val candidates =
            database.runQuery {
                QueryDsl
                    .from(Meta.candidateRecord)
                    .where { Meta.candidateRecord.coordinationRunId eq run.id }
                    .orderBy(Meta.candidateRecord.rank)
            }
        val ranges =
            candidates.flatMap { candidate ->
                database.runQuery {
                    QueryDsl
                        .from(Meta.candidateTimeRangeRecord)
                        .where { Meta.candidateTimeRangeRecord.candidateId eq candidate.id }
                        .orderBy(Meta.candidateTimeRangeRecord.rangeOrder)
                }
            }
        val confirmation =
            database.runQuery {
                QueryDsl
                    .from(Meta.finalConfirmationRecord)
                    .where { Meta.finalConfirmationRecord.coordinationRunId eq run.id }
                    .firstOrNull()
            }
        return PersistenceMappers.toDomain(
            CoordinationRecords(batch, batchItems, run, candidates, ranges, confirmation),
        )
    }
}

@Repository
class KomapperOutboxRepository(
    private val database: JdbcDatabase,
) : OutboxRepository {
    override fun insert(event: OutboxEvent) {
        database.runQuery { QueryDsl.insert(Meta.outboxEventRecord).single(PersistenceMappers.toRecord(event)) }
    }

    override fun findById(id: OutboxEventId): OutboxEvent? =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.outboxEventRecord)
                    .where { Meta.outboxEventRecord.id eq id.value }
                    .firstOrNull()
            }?.let(PersistenceMappers::toDomain)
}
