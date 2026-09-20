package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.TimePolarity
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
