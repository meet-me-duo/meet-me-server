package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlace
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceStatus
import com.meetme.server.coordination.application.port.output.OutboxEvent
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
