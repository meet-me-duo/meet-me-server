package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Explicit pre-V22 fixture phase, copied from the preserved pre-change storage contract.
 * Never probes columns, catches missing-schema errors, or adds columns to historical schemas.
 * The owning test switches to current storage only after current Flyway migrate/validate.
 */
class HistoricalCoordinationRunRepository(
    private val database: JdbcDatabase,
    private val jdbcTemplate: JdbcTemplate,
    private val recommendations: RecommendationRepository,
    private val current: CoordinationRunRepository,
) : CoordinationRunRepository {
    private var currentSchema = false

    fun useCurrentSchema() {
        currentSchema = true
    }

    override fun insert(run: CoordinationRun) {
        if (currentSchema) return current.insert(run)
        require(
            run.candidates.all { it.unverifiedPlaceParticipantIds.isEmpty() },
        ) { "Historical fixture cannot store V22 verification metadata" }
        val records = PersistenceMappers.toRecords(run)
        database.runQuery { QueryDsl.insert(Meta.submissionBatchRecord).single(records.batch) }
        database.runQuery { QueryDsl.insert(Meta.submissionBatchItemRecord).multiple(records.batchItems) }
        database.runQuery { QueryDsl.insert(Meta.coordinationRunRecord).single(records.run) }
        if (records.candidates.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateRecord).multiple(records.candidates) }
        }
        if (records.candidateParticipants.isNotEmpty()) {
            insertHistoricalParticipants(records.candidateParticipants)
        }
        if (records.timeRanges.isNotEmpty()) {
            database.runQuery { QueryDsl.insert(Meta.candidateTimeRangeRecord).multiple(records.timeRanges) }
        }
        records.confirmation?.let {
            database.runQuery { QueryDsl.insert(Meta.finalConfirmationRecord).single(it) }
        }
    }

    override fun findById(id: CoordinationRunId): CoordinationRun? {
        if (currentSchema) return current.findById(id)
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
                jdbcTemplate.query(
                    "SELECT candidate_id, participant_id, coordination_run_id, room_id FROM candidate_participants " +
                        "WHERE candidate_id = ? ORDER BY participant_id",
                    { rs, _ ->
                        CandidateParticipantRecord(
                            rs.getObject("candidate_id", UUID::class.java),
                            rs.getObject("participant_id", UUID::class.java),
                            rs.getObject("coordination_run_id", UUID::class.java),
                            rs.getObject("room_id", UUID::class.java),
                        )
                    },
                    candidate.id,
                )
            }
        val confirmation =
            database.runQuery {
                QueryDsl
                    .from(Meta.finalConfirmationRecord)
                    .where { Meta.finalConfirmationRecord.coordinationRunId eq run.id }
                    .firstOrNull()
            }
        val metadata =
            jdbcTemplate
                .query(
                    "SELECT protocol, (SELECT count(*) FROM recommendation_options " +
                        "WHERE coordination_run_id = a.coordination_run_id) AS option_count " +
                        "FROM recommendation_analyses a WHERE coordination_run_id = ?",
                    { rs, _ -> rs.getString("protocol") to rs.getInt("option_count") },
                    run.id,
                ).firstOrNull()
        val selection = recommendations.findSelection(run.id)
        return PersistenceMappers.toDomain(
            CoordinationRecords(
                batch,
                batchItems,
                run,
                candidates,
                candidateParticipants,
                ranges,
                confirmation,
                metadata?.first,
                metadata?.second,
                selection,
            ),
        )
    }

    override fun update(run: CoordinationRun) {
        if (currentSchema) return current.update(run)
        require(
            run.candidates.all { it.unverifiedPlaceParticipantIds.isEmpty() },
        ) { "Historical fixture cannot store V22 verification metadata" }
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
                insertHistoricalParticipants(records.candidateParticipants)
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
        run.recommendationSelection?.let { recommendations.insertSelection(it) }
    }

    override fun findByIdForUpdate(id: CoordinationRunId): CoordinationRun? {
        if (currentSchema) return current.findByIdForUpdate(id)
        val found =
            jdbcTemplate
                .query(
                    "SELECT id FROM coordination_runs WHERE id = ? FOR UPDATE",
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    id.value,
                ).firstOrNull() ?: return null
        return findById(found)
    }

    override fun findLatestByRoom(roomId: MeetingRoomId): CoordinationRun? {
        if (currentSchema) return current.findLatestByRoom(roomId)
        val id =
            jdbcTemplate
                .query(
                    "SELECT r.id FROM coordination_runs r JOIN meeting_rooms m ON m.active_run_id = r.id AND m.id = r.room_id WHERE m.id = ?",
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    roomId.value,
                ).firstOrNull() ?: return null
        return findById(id)
    }

    override fun findLatestByRoomForUpdate(roomId: MeetingRoomId): CoordinationRun? {
        if (currentSchema) return current.findLatestByRoomForUpdate(roomId)
        val id =
            jdbcTemplate
                .query(
                    """
                    SELECT r.id FROM coordination_runs r
                    JOIN meeting_rooms m ON m.active_run_id = r.id AND m.id = r.room_id
                    WHERE m.id = ? FOR UPDATE OF r
                    """.trimIndent(),
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    roomId.value,
                ).firstOrNull() ?: return null
        return findById(id)
    }

    override fun findByBatchId(batchId: SubmissionBatchId): CoordinationRun? {
        if (currentSchema) return current.findByBatchId(batchId)
        val id =
            jdbcTemplate
                .query(
                    "SELECT id FROM coordination_runs WHERE batch_id = ?",
                    { rs, _ -> CoordinationRunId(rs.getObject("id", UUID::class.java)) },
                    batchId.value,
                ).firstOrNull() ?: return null
        return findById(id)
    }

    private fun insertHistoricalParticipants(records: List<CandidateParticipantRecord>) {
        require(records.none { it.placeUnverified })
        jdbcTemplate.batchUpdate(
            "INSERT INTO candidate_participants(candidate_id, participant_id, coordination_run_id, room_id) VALUES (?, ?, ?, ?)",
            records.map { arrayOf<Any>(it.candidateId, it.participantId, it.coordinationRunId, it.roomId) },
        )
    }
}
