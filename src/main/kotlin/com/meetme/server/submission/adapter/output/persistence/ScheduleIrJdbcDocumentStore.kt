package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** Shared SQL mechanics only. Typed codecs and semantic versions remain separate. */
internal class ScheduleIrJdbcDocumentStore(
    private val jdbcTemplate: JdbcTemplate,
    objectMapper: ObjectMapper,
) {
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbcTemplate.dataSource)))
    private val strictMapper =
        objectMapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
    private val evidenceReader =
        strictMapper
            .readerFor(InputEvidence::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun <T : Any> publish(
        context: ScheduleIrContext,
        semanticVersion: String,
        formatVersion: Int,
        expectedRunVersion: Long,
        processedAt: Instant,
        validate: (ScheduleEvidenceSnapshot) -> Unit,
        findExisting: () -> T?,
        same: (T) -> Boolean,
        encode: () -> String,
    ) {
        transaction.executeWithoutResult {
            val room =
                jdbcTemplate.queryForMap(
                    "SELECT active_run_id, active_revision_round_id FROM meeting_rooms WHERE id=? FOR UPDATE",
                    context.roomId,
                )
            check(room["active_run_id"] == context.runId && room["active_revision_round_id"] == null) { "Inactive IR execution" }
            val run =
                jdbcTemplate.queryForMap(
                    """
                    SELECT r.room_id, r.batch_id, r.status, r.version, b.fixed_at,
                        EXISTS(SELECT 1 FROM final_confirmations f WHERE f.coordination_run_id=r.id)
                        OR EXISTS(SELECT 1 FROM recommendation_selections s WHERE s.coordination_run_id=r.id) AS confirmed
                    FROM coordination_runs r JOIN submission_batches b ON b.id=r.batch_id
                    WHERE r.id=? FOR UPDATE OF r
                    """.trimIndent(),
                    context.runId,
                )
            check(run["room_id"] == context.roomId && run["batch_id"] == context.batchId) { "IR execution identity mismatch" }
            check(expectedRunVersion >= 0 && (run["version"] as Number).toLong() == expectedRunVersion) { "Stale IR execution" }
            check(run["status"] == "STRUCTURING" && run["confirmed"] == false) { "Protected IR execution" }
            val fixedAt = (run["fixed_at"] as java.sql.Timestamp).toInstant()
            require(processedAt >= fixedAt) { "IR result precedes its batch" }
            val snapshot = loadSnapshot(context)
            validate(snapshot)
            val existing = findExisting()
            if (existing != null) {
                check(same(existing)) { "Conflicting IR publication" }
                return@executeWithoutResult
            }
            jdbcTemplate.update(
                """
                INSERT INTO schedule_ir_envelopes
                    (run_id,batch_id,room_id,semantic_version,format_version,context_fingerprint,envelope,processed_at)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)
                """.trimIndent(),
                context.runId,
                context.batchId,
                context.roomId,
                semanticVersion,
                formatVersion,
                fingerprint(context),
                encode(),
                processedAt.atOffset(ZoneOffset.UTC),
            )
            context.inputs.forEach { input ->
                jdbcTemplate.update(
                    "INSERT INTO schedule_ir_inputs(run_id,batch_id,submission_version_id) VALUES (?,?,?)",
                    context.runId,
                    context.batchId,
                    input.submissionVersionId,
                )
            }
        }
    }

    fun readMetadata(runId: UUID): StoredScheduleIrDocument? =
        jdbcTemplate
            .query(
                "SELECT semantic_version,format_version,context_fingerprint,envelope::text FROM schedule_ir_envelopes WHERE run_id=?",
                { rs, _ ->
                    StoredScheduleIrDocument(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4))
                },
                runId,
            ).singleOrNull()

    fun verifyReferences(context: ScheduleIrContext) {
        val references =
            jdbcTemplate.queryForList(
                "SELECT submission_version_id FROM schedule_ir_inputs WHERE run_id=? AND batch_id=?",
                UUID::class.java,
                context.runId,
                context.batchId,
            )
        val expected = context.inputs.map { it.submissionVersionId }
        require(references.size == expected.size && references.toSet() == expected.toSet()) { "Incomplete IR source references" }
    }

    fun loadSnapshot(context: ScheduleIrContext): ScheduleEvidenceSnapshot {
        val metadata =
            jdbcTemplate
                .query(
                    """
                    SELECT r.room_id,r.batch_id,b.fixed_at,m.time_zone_id,m.search_start_date,m.search_end_date
                    FROM coordination_runs r JOIN submission_batches b ON b.id=r.batch_id
                    JOIN meeting_rooms m ON m.id=r.room_id WHERE r.id=?
                    """.trimIndent(),
                    { rs, _ ->
                        FrozenMetadata(
                            rs.getObject(1, UUID::class.java),
                            rs.getObject(2, UUID::class.java),
                            rs.getObject(3, OffsetDateTime::class.java).toInstant(),
                            rs.getString(4),
                            rs.getObject(5, LocalDate::class.java),
                            rs.getObject(6, LocalDate::class.java),
                        )
                    },
                    context.runId,
                ).singleOrNull() ?: throw IllegalArgumentException("Missing frozen IR execution")
        val submissions =
            jdbcTemplate.query(
                """
                SELECT h.id AS submission_id,h.room_id,h.participant_id,v.id,v.revision,v.raw_text,v.locale,v.created_at,
                    v.input_evidence::text
                FROM submission_batch_items i JOIN submission_versions v ON v.id=i.submission_version_id
                JOIN submission_heads h ON h.id=v.submission_id WHERE i.batch_id=?
                """.trimIndent(),
                { rs, _ ->
                    Submission.restore(
                        SubmissionId(rs.getObject("submission_id", UUID::class.java)),
                        MeetingRoomId(rs.getObject("room_id", UUID::class.java)),
                        ParticipantId(rs.getObject("participant_id", UUID::class.java)),
                        SubmissionVersion(
                            SubmissionVersionId(rs.getObject("id", UUID::class.java)),
                            rs.getInt("revision"),
                            rs.getString("raw_text"),
                            emptyList(),
                            Locale.forLanguageTag(rs.getString("locale")),
                            rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            readEvidence(rs.getString("input_evidence")),
                        ),
                    )
                },
                metadata.batchId,
            )
        require(submissions.all { it.roomId.value == metadata.roomId && it.latest.createdAt <= metadata.fixedAt }) {
            "Invalid frozen IR cohort"
        }
        val bound = submissions.sortedBy { it.latest.id.value }.map(IrEvidenceBindings::create)
        val trusted =
            context.copy(
                roomId = metadata.roomId,
                batchId = metadata.batchId,
                timeZone = metadata.zone,
                searchStart = metadata.start,
                searchEnd = metadata.end,
                inputs = bound.map { it.first },
            )
        require(trusted == context) { "Frozen IR sources or search context mismatch" }
        return ScheduleEvidenceSnapshot(trusted, bound.associate { it.first.submissionVersionId to it.second })
    }

    private fun readEvidence(json: String?): InputEvidence? =
        json?.let {
            try {
                evidenceReader.readValue<InputEvidence>(it)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid frozen input evidence")
            }
        }

    /** Stable framing is independent of JSON property ordering and serializer configuration. */
    fun fingerprint(context: ScheduleIrContext): String {
        val parts =
            mutableListOf<String?>(
                "schedule-ir-context-1",
                context.runId.toString(),
                context.batchId.toString(),
                context.roomId.toString(),
                context.timeZone,
                context.searchStart.toString(),
                context.searchEnd.toString(),
                context.policyVersion,
                context.inputs.size.toString(),
            )
        context.inputs.forEach { input ->
            parts +=
                listOf(
                    input.submissionVersionId.toString(),
                    input.participantId.toString(),
                    input.submissionRevision.toString(),
                    input.originalRevision?.toString(),
                    input.contentSha256,
                    input.sources.size.toString(),
                )
            input.sources.forEach { source ->
                parts +=
                    listOf(
                        source.sourceId,
                        source.kind.name,
                        source.revision.toString(),
                        source.codePointLength.toString(),
                        source.contentSha256,
                        source.referenceAt.toString(),
                        source.questionId?.toString(),
                        source.originalStartCodePoint?.toString(),
                        source.originalEndCodePoint?.toString(),
                    )
            }
        }
        return IrEvidenceBindings.sha256(parts.joinToString("") { if (it == null) "-1:" else "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    }

    private data class FrozenMetadata(
        val roomId: UUID,
        val batchId: UUID,
        val fixedAt: Instant,
        val zone: String,
        val start: LocalDate,
        val end: LocalDate,
    )
}

internal data class StoredScheduleIrDocument(
    val semanticVersion: String,
    val formatVersion: Int,
    val fingerprint: String,
    val json: String,
)
