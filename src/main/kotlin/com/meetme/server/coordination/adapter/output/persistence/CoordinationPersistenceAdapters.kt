package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlace
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceStatus
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxProcessingClaim
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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

    override fun findPendingForUpdate(limit: Int): List<OutboxEvent> {
        require(limit > 0)
        val ids =
            jdbcTemplate.query(
                """
                SELECT id FROM outbox_events
                WHERE status = 'PENDING'
                ORDER BY occurred_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                { rs, _ -> OutboxEventId(rs.getObject("id", UUID::class.java)) },
                limit,
            )
        return ids.mapNotNull(::findById)
    }

    override fun markPublished(
        eventId: OutboxEventId,
        at: Instant,
    ) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = ?
                WHERE id = ? AND status = 'PENDING'
                """.trimIndent(),
                at.atOffset(ZoneOffset.UTC),
                eventId.value,
            )
        check(changed == 1) { "Outbox event was not pending" }
    }

    override fun findRecoverablePublished(
        publishedBefore: Instant,
        limit: Int,
    ): List<OutboxEvent> {
        require(limit > 0)
        val ids =
            jdbcTemplate.query(
                """
                SELECT id FROM outbox_events
                WHERE status = 'PUBLISHED'
                  AND published_at <= ?
                  AND (processing_lease_until IS NULL OR processing_lease_until <= CURRENT_TIMESTAMP)
                ORDER BY published_at, id
                LIMIT ?
                """.trimIndent(),
                { rs, _ -> OutboxEventId(rs.getObject("id", UUID::class.java)) },
                publishedBefore.atOffset(ZoneOffset.UTC),
                limit,
            )
        return ids.mapNotNull(::findById)
    }

    override fun markRepublished(
        eventId: OutboxEventId,
        at: Instant,
    ) {
        jdbcTemplate.update(
            "UPDATE outbox_events SET published_at = ? WHERE id = ? AND status = 'PUBLISHED'",
            at.atOffset(ZoneOffset.UTC),
            eventId.value,
        )
    }

    override fun claimProcessing(
        eventId: OutboxEventId,
        now: Instant,
        leaseUntil: Instant,
    ): OutboxProcessingClaim? {
        val deliveries =
            jdbcTemplate
                .query(
                    """
                    UPDATE outbox_events
                    SET processing_lease_until = ?, delivery_count = delivery_count + 1
                    WHERE id = ? AND status = 'PUBLISHED'
                      AND (processing_lease_until IS NULL OR processing_lease_until <= ?)
                    RETURNING delivery_count
                    """.trimIndent(),
                    { rs, _ -> rs.getInt("delivery_count") },
                    leaseUntil.atOffset(ZoneOffset.UTC),
                    eventId.value,
                    now.atOffset(ZoneOffset.UTC),
                ).firstOrNull() ?: return null
        return OutboxProcessingClaim(requireNotNull(findById(eventId)), deliveries)
    }

    override fun markProcessed(
        eventId: OutboxEventId,
        at: Instant,
    ) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PROCESSED', processed_at = ?, processing_lease_until = NULL
                WHERE id = ? AND status = 'PUBLISHED'
                """.trimIndent(),
                at.atOffset(ZoneOffset.UTC),
                eventId.value,
            )
        check(changed == 1) { "Outbox event could not be marked processed" }
    }

    override fun releaseAfterFailure(
        eventId: OutboxEventId,
        failureKind: String,
    ) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET processing_lease_until = NULL, last_failure_kind = ?
                WHERE id = ? AND status = 'PUBLISHED'
                """.trimIndent(),
                failureKind,
                eventId.value,
            )
        check(changed == 1) { "Outbox failure could not be recorded" }
    }

    override fun markDeadLettered(
        eventId: OutboxEventId,
        at: Instant,
        failureKind: String,
    ) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'DEAD_LETTERED', processing_lease_until = NULL,
                    last_failure_kind = ?, dead_lettered_at = ?
                WHERE id = ? AND status = 'PUBLISHED'
                """.trimIndent(),
                failureKind,
                at.atOffset(ZoneOffset.UTC),
                eventId.value,
            )
        check(changed == 1) { "Outbox event could not be dead-lettered" }
    }

    override fun pendingCount(): Long =
        requireNotNull(
            jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'", Long::class.java),
        )

    override fun oldestPendingAgeSeconds(now: Instant): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(EXTRACT(EPOCH FROM (?::timestamptz - min(occurred_at))), 0)::bigint
            FROM outbox_events WHERE status = 'PENDING'
            """.trimIndent(),
            Long::class.java,
            now.atOffset(ZoneOffset.UTC),
        ) ?: 0

    override fun requeue(eventId: OutboxEventId) {
        val changed =
            jdbcTemplate.update(
                """
                UPDATE outbox_events
                SET status = 'PENDING', published_at = NULL, processed_at = NULL,
                    processing_lease_until = NULL, delivery_count = 0,
                    last_failure_kind = NULL, dead_lettered_at = NULL
                WHERE id = ? AND status = 'DEAD_LETTERED'
                """.trimIndent(),
                eventId.value,
            )
        check(changed == 1) { "Dead-lettered Outbox event could not be requeued" }
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
