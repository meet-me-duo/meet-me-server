package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import org.komapper.core.dsl.Meta
import org.komapper.core.dsl.QueryDsl
import org.komapper.core.dsl.query.firstOrNull
import org.komapper.jdbc.JdbcDatabase
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Explicit historical fixture phase, copied from main 40aea51's pre-V21 storage contract.
 * It never probes columns or catches schema errors. Current production storage is selected
 * explicitly only after the test has applied and validated the current Flyway catalog.
 */
class HistoricalSubmissionRepository(
    database: JdbcDatabase,
    jdbc: JdbcTemplate,
    private val current: SubmissionRepository,
) : SubmissionRepository {
    private val historical = HistoricalSubmissionStore(database, jdbc)
    private var currentSchema = false
    private val selected: SubmissionRepository get() = if (currentSchema) current else historical

    fun useCurrentSchema() {
        currentSchema = true
    }

    override fun insert(submission: Submission) = selected.insert(submission)

    override fun save(submission: Submission) = selected.save(submission)

    override fun findByParticipant(participantId: ParticipantId): Submission? = selected.findByParticipant(participantId)

    override fun findById(id: SubmissionId): Submission? = selected.findById(id)

    override fun findLatestByRoom(roomId: MeetingRoomId): List<Submission> = selected.findLatestByRoom(roomId)

    override fun findFrozenByVersionIds(
        roomId: MeetingRoomId,
        versionIds: List<SubmissionVersionId>,
    ): List<Submission> = selected.findFrozenByVersionIds(roomId, versionIds)

    override fun countSubmittedParticipants(roomId: MeetingRoomId): Int = selected.countSubmittedParticipants(roomId)
}

internal class HistoricalSubmissionStore(
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
        require(submission.latest.evidence == null) { "Historical fixture cannot persist V2 evidence" }
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

    override fun findFrozenByVersionIds(
        roomId: MeetingRoomId,
        versionIds: List<SubmissionVersionId>,
    ): List<Submission> {
        if (versionIds.isEmpty()) return emptyList()
        require(versionIds.distinct().size == versionIds.size)
        val marks = versionIds.joinToString(",") { "?" }
        val found =
            jdbcTemplate.query(
                "SELECT v.id, v.submission_id FROM submission_versions v " +
                    "JOIN submission_heads h ON h.id = v.submission_id WHERE h.room_id = ? AND v.id IN ($marks)",
                { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("submission_id", UUID::class.java) },
                roomId.value,
                *versionIds.map { it.value }.toTypedArray(),
            )
        return found.map { (versionId, submissionId) ->
            val head =
                requireNotNull(
                    database.runQuery {
                        QueryDsl.from(Meta.submissionHeadRecord).where { Meta.submissionHeadRecord.id eq submissionId }.firstOrNull()
                    },
                )
            val version =
                requireNotNull(
                    database.runQuery {
                        QueryDsl.from(Meta.submissionVersionRecord).where { Meta.submissionVersionRecord.id eq versionId }.firstOrNull()
                    },
                )
            val intervals =
                database.runQuery {
                    QueryDsl
                        .from(Meta.manualAvailabilityRecord)
                        .where { Meta.manualAvailabilityRecord.submissionVersionId eq versionId }
                        .orderBy(Meta.manualAvailabilityRecord.intervalOrder)
                }
            PersistenceMappers.toDomain(SubmissionRecords(head.copy(latestVersionId = versionId), version, intervals))
        }
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
