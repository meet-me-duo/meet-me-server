package com.meetme.server.adapter.output.persistence

import com.meetme.server.application.port.output.CoordinationAttempt
import com.meetme.server.application.port.output.CoordinationAttemptRepository
import com.meetme.server.application.port.output.CoordinationRunRepository
import com.meetme.server.application.port.output.GuestSessionRepository
import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.NormalizedPlace
import com.meetme.server.application.port.output.NormalizedPlaceRepository
import com.meetme.server.application.port.output.NormalizedPlaceStatus
import com.meetme.server.application.port.output.OutboxEvent
import com.meetme.server.application.port.output.OutboxRepository
import com.meetme.server.application.port.output.ParticipantRepository
import com.meetme.server.application.port.output.StructuredSubmissionRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.domain.common.CoordinationRunId
import com.meetme.server.domain.common.GuestSessionId
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.common.OutboxEventId
import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.common.SubmissionBatchId
import com.meetme.server.domain.common.SubmissionId
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.coordination.CoordinationRun
import com.meetme.server.domain.location.GeoCoordinate
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.participant.GuestSession
import com.meetme.server.domain.participant.Participant
import com.meetme.server.domain.submission.StructuredCondition
import com.meetme.server.domain.submission.StructuredSubmissionResult
import com.meetme.server.domain.submission.Submission
import com.meetme.server.domain.submission.TimePolarity
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

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

    override fun findDueForUpdate(
        now: java.time.Instant,
        limit: Int,
    ): List<MeetingRoom> =
        jdbcTemplate
            .query(
                """
                SELECT id FROM meeting_rooms
                WHERE collection_status = 'COLLECTING'
                  AND submission_deadline IS NOT NULL
                  AND submission_deadline <= ?
                ORDER BY submission_deadline, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """.trimIndent(),
                { rs, _ -> MeetingRoomId(rs.getObject("id", UUID::class.java)) },
                now.atOffset(ZoneOffset.UTC),
                limit,
            ).mapNotNull(::findById)
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
    private val jdbcTemplate: JdbcTemplate,
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

    override fun countByRoom(roomId: MeetingRoomId): Int =
        requireNotNull(
            jdbcTemplate.queryForObject("SELECT count(*) FROM participants WHERE room_id = ?", Int::class.java, roomId.value),
        )
}

@Repository
class KomapperSubmissionRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
) : SubmissionRepository {
    override fun insert(submission: Submission) {
        check(findByParticipant(submission.participantId) == null) { "Submission already exists for participant" }
        persistVersion(submission, insertHead = true)
    }

    override fun save(submission: Submission) {
        persistVersion(submission, insertHead = findByParticipant(submission.participantId) == null)
    }

    private fun persistVersion(
        submission: Submission,
        insertHead: Boolean,
    ) {
        val records = PersistenceMappers.toRecords(submission)
        if (insertHead) {
            val headWithoutVersion = records.head.copy(latestVersionId = null)
            database.runQuery { QueryDsl.insert(Meta.submissionHeadRecord).single(headWithoutVersion) }
        }
        database.runQuery { QueryDsl.insert(Meta.submissionVersionRecord).single(records.version) }
        if (records.intervals.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.manualAvailabilityRecord).multiple(records.intervals) }
        }
        if (insertHead) {
            database.runQuery { QueryDsl.update(Meta.submissionHeadRecord).single(records.head) }
        } else {
            val changed =
                jdbcTemplate.update(
                    "UPDATE submission_heads SET latest_version_id = ? WHERE id = ?",
                    records.version.id,
                    records.head.id,
                )
            check(changed == 1) { "Submission head was not updated" }
        }
    }

    override fun findByParticipant(participantId: ParticipantId): Submission? {
        val id =
            database
                .runQuery {
                    QueryDsl
                        .from(Meta.submissionHeadRecord)
                        .where { Meta.submissionHeadRecord.participantId eq participantId.value }
                        .firstOrNull()
                }?.id ?: return null
        return findById(SubmissionId(id))
    }

    override fun findLatestByRoom(roomId: MeetingRoomId): List<Submission> =
        database
            .runQuery {
                QueryDsl
                    .from(Meta.submissionHeadRecord)
                    .where { Meta.submissionHeadRecord.roomId eq roomId.value }
            }.mapNotNull { findById(SubmissionId(it.id)) }

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
    private val jdbcTemplate: JdbcTemplate,
) : CoordinationRunRepository {
    override fun insert(run: CoordinationRun) {
        val records = PersistenceMappers.toRecords(run)
        database.runQuery { QueryDsl.insert(Meta.submissionBatchRecord).single(records.batch) }
        database.runQuery { QueryDsl.insert(Meta.submissionBatchItemRecord).multiple(records.batchItems) }
        database.runQuery { QueryDsl.insert(Meta.coordinationRunRecord).single(records.run) }
        if (records.candidates.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateRecord).multiple(records.candidates) }
        }
        if (records.candidateParticipants.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateParticipantRecord).multiple(records.candidateParticipants) }
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
        val candidateParticipants =
            candidates.flatMap { candidate ->
                database.runQuery {
                    QueryDsl
                        .from(Meta.candidateParticipantRecord)
                        .where { Meta.candidateParticipantRecord.candidateId eq candidate.id }
                        .orderBy(Meta.candidateParticipantRecord.participantId)
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
            CoordinationRecords(batch, batchItems, run, candidates, candidateParticipants, ranges, confirmation),
        )
    }

    override fun update(run: CoordinationRun) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE coordination_runs
                SET status = ?, candidate_quality = ?, resume_stage = ?, version = ?
                WHERE id = ? AND version = ?
                """.trimIndent(),
                run.status.name,
                run.quality?.name,
                run.resumeStage?.name,
                run.version,
                run.id.value,
                run.version - 1,
            )
        check(changed == 1) { "Coordination run update lost an optimistic concurrency race" }

        val records = PersistenceMappers.toRecords(run)
        val existingCandidates =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM candidates WHERE coordination_run_id = ?",
                    Int::class.java,
                    run.id.value,
                ),
            )
        if (existingCandidates == 0 && records.candidates.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateRecord).multiple(records.candidates) }
            if (records.candidateParticipants.isNotEmpty()) {
                database.runQuery { QueryDsl.insert(Meta.candidateParticipantRecord).multiple(records.candidateParticipants) }
            }
            if (records.timeRanges.isNotEmpty()) {
                database.runQuery { QueryDsl.insert(Meta.candidateTimeRangeRecord).multiple(records.timeRanges) }
            }
        }
        records.confirmation?.let { confirmation ->
            val inserted =
                jdbcTemplate.update(
                    """
                    INSERT INTO final_confirmations(coordination_run_id, candidate_id, confirmed_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (coordination_run_id) DO NOTHING
                    """.trimIndent(),
                    confirmation.coordinationRunId,
                    confirmation.candidateId,
                    confirmation.confirmedAt,
                )
            if (inserted == 0) {
                val existingCandidateId =
                    jdbcTemplate.queryForObject(
                        "SELECT candidate_id FROM final_confirmations WHERE coordination_run_id = ?",
                        UUID::class.java,
                        confirmation.coordinationRunId,
                    )
                check(existingCandidateId == confirmation.candidateId) { "A different candidate is already confirmed" }
            }
        }
    }

    override fun findLatestByRoom(roomId: MeetingRoomId): CoordinationRun? {
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM coordination_runs WHERE room_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    roomId.value,
                ).firstOrNull() ?: return null
        return findById(id)
    }

    override fun findLatestByRoomForUpdate(roomId: MeetingRoomId): CoordinationRun? {
        val id =
            jdbcTemplate
                .query(
                    """
                    SELECT id FROM coordination_runs
                    WHERE room_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1 FOR UPDATE
                    """.trimIndent(),
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    roomId.value,
                ).firstOrNull() ?: return null
        return findById(id)
    }

    override fun findByBatchId(batchId: SubmissionBatchId): CoordinationRun? {
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM coordination_runs WHERE batch_id = ?",
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    batchId.value,
                ).firstOrNull() ?: return null
        return findById(id)
    }
}

@Repository
class JdbcNormalizedPlaceRepository(
    private val jdbcTemplate: JdbcTemplate,
) : NormalizedPlaceRepository {
    override fun replaceForBatch(
        batchId: SubmissionBatchId,
        places: List<NormalizedPlace>,
    ) {
        require(places.all { it.batchId == batchId }) { "Every normalized place must belong to the requested batch" }
        val runId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM coordination_runs WHERE batch_id = ?",
                UUID::class.java,
                batchId.value,
            ) ?: error("Coordination run does not exist for batch")
        jdbcTemplate.update("DELETE FROM normalized_places WHERE coordination_run_id = ?", runId)
        places.forEach { place ->
            val resolved = place.status == NormalizedPlaceStatus.RESOLVED
            jdbcTemplate.update(
                """
                INSERT INTO normalized_places(
                    id, coordination_run_id, batch_id, submission_version_id, condition_index, query, radius_meters,
                    status, provider, provider_place_id, display_name, latitude, longitude
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                runId,
                batchId.value,
                place.submissionVersionId.value,
                place.conditionIndex,
                place.query,
                place.radiusMeters,
                place.status.name,
                if (resolved) "KAKAO" else null,
                place.providerPlaceId,
                place.displayName,
                place.coordinate?.latitude,
                place.coordinate?.longitude,
            )
        }
    }

    override fun findByBatch(batchId: SubmissionBatchId): List<NormalizedPlace> =
        jdbcTemplate.query(
            """
            SELECT np.submission_version_id, np.condition_index, np.query, np.radius_meters, np.status,
                   np.provider_place_id, np.display_name, np.latitude, np.longitude
            FROM normalized_places np
            JOIN coordination_runs cr ON cr.id = np.coordination_run_id
            WHERE cr.batch_id = ?
            ORDER BY np.submission_version_id, np.condition_index
            """.trimIndent(),
            { rs, _ ->
                val latitude = rs.getBigDecimal("latitude")
                val longitude = rs.getBigDecimal("longitude")
                NormalizedPlace(
                    batchId = batchId,
                    submissionVersionId = SubmissionVersionId(rs.getObject("submission_version_id", UUID::class.java)),
                    conditionIndex = rs.getInt("condition_index"),
                    query = rs.getString("query"),
                    radiusMeters = rs.getInt("radius_meters"),
                    status = NormalizedPlaceStatus.valueOf(rs.getString("status")),
                    providerPlaceId = rs.getString("provider_place_id"),
                    displayName = rs.getString("display_name"),
                    coordinate =
                        if (latitude != null && longitude != null) GeoCoordinate.of(latitude, longitude) else null,
                )
            },
            batchId.value,
        )
}

@Repository
class KomapperOutboxRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
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

    override fun existsPending(
        aggregateId: UUID,
        eventType: String,
    ): Boolean =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM outbox_events WHERE aggregate_id = ? AND event_type = ? AND status = 'PENDING')",
                Boolean::class.java,
                aggregateId,
                eventType,
            ),
        )
}

@Repository
class JdbcStructuredSubmissionRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : StructuredSubmissionRepository {
    override fun replaceForBatch(
        batchId: SubmissionBatchId,
        results: List<StructuredSubmissionResult>,
        processedAt: java.time.Instant,
    ) {
        jdbcTemplate.update("DELETE FROM structured_submission_results WHERE batch_id = ?", batchId.value)
        results.forEach { result ->
            jdbcTemplate.update(
                """
                INSERT INTO structured_submission_results
                    (batch_id, submission_version_id, conditions, rejection_code, processed_at)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                """.trimIndent(),
                batchId.value,
                result.submissionVersionId.value,
                objectMapper.writeValueAsString(result.conditions.map(::conditionToMap)),
                result.rejectionCode,
                processedAt.atOffset(ZoneOffset.UTC),
            )
        }
    }

    override fun findByBatch(batchId: SubmissionBatchId): List<StructuredSubmissionResult> =
        jdbcTemplate.query(
            """
            SELECT submission_version_id, conditions::text, rejection_code
            FROM structured_submission_results WHERE batch_id = ? ORDER BY submission_version_id
            """.trimIndent(),
            { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val maps = objectMapper.readValue(rs.getString("conditions"), List::class.java) as List<Map<String, Any?>>
                StructuredSubmissionResult(
                    SubmissionVersionId(rs.getObject("submission_version_id", UUID::class.java)),
                    maps.map(::mapToCondition),
                    rs.getString("rejection_code"),
                )
            },
            batchId.value,
        )

    private fun conditionToMap(condition: StructuredCondition): Map<String, Any?> =
        when (condition) {
            is StructuredCondition.TimeWindow ->
                mapOf(
                    "type" to "TIME_WINDOW",
                    "polarity" to condition.polarity.name,
                    "date" to condition.date?.toString(),
                    "day_of_week" to condition.dayOfWeek?.name,
                    "start_time" to condition.startTime.toString(),
                    "end_time" to condition.endTime.toString(),
                )
            is StructuredCondition.SpecificPlace ->
                mapOf("type" to "SPECIFIC_PLACE", "query" to condition.query, "radius_meters" to condition.radiusMeters)
            is StructuredCondition.TravelConstraint ->
                mapOf("type" to "TRAVEL_CONSTRAINT", "expression" to condition.expression)
            is StructuredCondition.UnresolvedPlace ->
                mapOf("type" to "UNRESOLVED_PLACE", "query" to condition.query)
        }

    private fun mapToCondition(map: Map<String, Any?>): StructuredCondition =
        when (map["type"]) {
            "TIME_WINDOW" ->
                StructuredCondition.TimeWindow(
                    TimePolarity.valueOf(map.getValue("polarity").toString()),
                    map["date"]?.toString()?.let(LocalDate::parse),
                    map["day_of_week"]?.toString()?.let(DayOfWeek::valueOf),
                    LocalTime.parse(map.getValue("start_time").toString()),
                    LocalTime.parse(map.getValue("end_time").toString()),
                )
            "SPECIFIC_PLACE" ->
                StructuredCondition.SpecificPlace(
                    map.getValue("query").toString(),
                    (map.getValue("radius_meters") as Number).toInt(),
                )
            "TRAVEL_CONSTRAINT" -> StructuredCondition.TravelConstraint(map.getValue("expression").toString())
            "UNRESOLVED_PLACE" -> StructuredCondition.UnresolvedPlace(map.getValue("query").toString())
            else -> error("Unknown structured condition type")
        }
}

@Repository
class JdbcCoordinationAttemptRepository(
    private val jdbcTemplate: JdbcTemplate,
) : CoordinationAttemptRepository {
    override fun insert(attempt: CoordinationAttempt) {
        jdbcTemplate.update(
            """
            INSERT INTO coordination_attempts
                (id, coordination_run_id, attempt_number, started_at, finished_at, failure_code,
                 input_tokens, output_tokens, response_bytes, estimated_cost_usd, failure_kind)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            attempt.id,
            attempt.coordinationRunId.value,
            attempt.attemptNumber,
            attempt.startedAt.atOffset(ZoneOffset.UTC),
            attempt.finishedAt?.atOffset(ZoneOffset.UTC),
            attempt.failureKind,
            attempt.inputTokens,
            attempt.outputTokens,
            attempt.responseBytes,
            attempt.estimatedCostUsd,
            attempt.failureKind,
        )
    }

    override fun update(attempt: CoordinationAttempt) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE coordination_attempts
                SET finished_at = ?, failure_code = ?, input_tokens = ?, output_tokens = ?,
                    response_bytes = ?, estimated_cost_usd = ?, failure_kind = ?
                WHERE id = ?
                """.trimIndent(),
                attempt.finishedAt?.atOffset(ZoneOffset.UTC),
                attempt.failureKind,
                attempt.inputTokens,
                attempt.outputTokens,
                attempt.responseBytes,
                attempt.estimatedCostUsd,
                attempt.failureKind,
                attempt.id,
            )
        check(changed == 1) { "Coordination attempt was not updated" }
    }

    override fun countByRun(runId: CoordinationRunId): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_attempts WHERE coordination_run_id = ?",
                Int::class.java,
                runId.value,
            ),
        )
}
