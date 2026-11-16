package com.meetme.server.shared.adapter.output.persistence.operations

import com.meetme.server.precheck.application.port.output.PrecheckClassificationSnapshot
import com.meetme.server.precheck.domain.PrecheckQuestion
import com.meetme.server.shared.application.port.input.operations.OperationsCursor
import com.meetme.server.shared.application.port.input.operations.OperationsCursorCodec
import com.meetme.server.shared.application.port.input.operations.OperationsPage
import com.meetme.server.shared.application.port.input.operations.OperatorAnalysis
import com.meetme.server.shared.application.port.input.operations.OperatorInput
import com.meetme.server.shared.application.port.input.operations.OperatorInputCheck
import com.meetme.server.shared.application.port.input.operations.OperatorPrecheckAttempt
import com.meetme.server.shared.application.port.input.operations.OperatorQuestion
import com.meetme.server.shared.application.port.input.operations.OperatorResult
import com.meetme.server.shared.application.port.input.operations.OperatorRoomDiagnostics
import com.meetme.server.shared.application.port.input.operations.OperatorStoredCondition
import com.meetme.server.shared.application.port.input.operations.OperatorStoredInterpretation
import com.meetme.server.shared.application.port.input.operations.OperatorTimelineEvent
import com.meetme.server.shared.application.port.output.operations.OperatorDiagnosticsRepository
import com.meetme.server.shared.application.service.operations.OperatorViewMasker
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Stored facts only. Does not use provider, calculator, publication or current-result access fences. */
@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class JdbcOperatorDiagnosticsRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val operations: JdbcOperationsQueryRepository,
) : OperatorDiagnosticsRepository {
    private val mask = OperatorViewMasker()

    override fun analyses(
        roomId: UUID,
        limit: Int,
        cursor: OperationsCursor?,
    ): OperationsPage<OperatorAnalysis>? {
        require(limit in 1..100)
        if (!exists(roomId)) return null
        val predicate = if (cursor == null) "" else "AND (r.created_at,r.id) < (?,?)"
        val arguments = listOf(roomId) + (cursor?.let { listOf(Timestamp.from(it.at), it.id) } ?: emptyList()) + (limit + 1)
        val rows =
            jdbc.query(
                "$RUN_SELECT WHERE r.room_id=? $predicate ORDER BY r.created_at DESC,r.id DESC LIMIT ?",
                { rs, _ -> run(rs).analysis },
                *arguments.toTypedArray(),
            )
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let { OperationsCursorCodec.encode(OperationsCursor(it.createdAt, it.id)) } else null
        return OperationsPage(items, next)
    }

    override fun diagnostics(
        roomId: UUID,
        analysisId: UUID?,
    ): OperatorRoomDiagnostics? {
        if (!exists(roomId)) return null
        val selected =
            analysisId?.let {
                jdbc.query("$RUN_SELECT WHERE r.room_id=? AND r.id=?", { rs, _ -> run(rs) }, roomId, it).singleOrNull() ?: return null
            }
        if (selected != null) verifyCohort(roomId, selected.batchId)
        val rows = inputRows(roomId, selected?.batchId)
        val declarations = selected?.let { storedInterpretations(roomId, it) }.orEmpty()
        val inputs =
            rows.take(50).map {
                input(
                    roomId,
                    it,
                    selected == null,
                    declarations[it.versionId],
                    selected?.analysis?.contract?.startsWith("DSL_") == true,
                )
            }
        if (selected == null) {
            return OperatorRoomDiagnostics(
                roomId,
                "CURRENT_COLLECTION",
                null,
                inputs,
                rows.size > 50,
                emptyList(),
                false,
                emptyList(),
                false,
                emptyList(),
                false,
            )
        }
        val invocationIds =
            jdbc.query(
                "SELECT id FROM analysis_invocations WHERE coordination_run_id=? ORDER BY started_at,id LIMIT 21",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                selected.analysis.id,
            )
        val invocations = invocationIds.take(20).map { requireNotNull(operations.invocationDetail(it)) }
        val timeline = timeline(roomId, selected)
        val results = results(roomId, selected)
        return OperatorRoomDiagnostics(
            roomId,
            "FROZEN_ANALYSIS",
            selected.analysis,
            inputs,
            rows.size > 50,
            invocations,
            invocationIds.size > 20,
            timeline.first.take(100),
            timeline.second || timeline.first.size > 100,
            results.take(20),
            results.size > 20,
        )
    }

    private fun exists(roomId: UUID): Boolean =
        jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM meeting_rooms WHERE id=?)", Boolean::class.java, roomId) == true

    private fun verifyCohort(
        roomId: UUID,
        batchId: UUID,
    ) {
        val inconsistent =
            jdbc.queryForObject(
                """SELECT EXISTS(SELECT 1 FROM submission_batch_items i
                JOIN submission_versions v ON v.id=i.submission_version_id
                JOIN submission_heads h ON h.id=v.submission_id
                JOIN participants p ON p.id=h.participant_id
                WHERE i.batch_id=? AND (h.room_id<>? OR p.room_id<>h.room_id))""",
                Boolean::class.java,
                batchId,
                roomId,
            )
        check(inconsistent == false) { "Inconsistent frozen cohort" }
    }

    private fun inputRows(
        roomId: UUID,
        batchId: UUID?,
    ): List<InputRow> {
        val projection = """SELECT p.id AS participant_id,p.display_name,h.id AS head_id,
            v.id AS version_id,v.revision,v.raw_text,v.created_at,v.input_evidence::text"""
        val from =
            if (batchId == null) {
                """FROM participants p LEFT JOIN submission_heads h ON h.participant_id=p.id AND h.room_id=p.room_id
                LEFT JOIN submission_versions v ON v.id=h.latest_version_id AND v.submission_id=h.id
                WHERE p.room_id=?"""
            } else {
                """FROM submission_batch_items i JOIN submission_versions v ON v.id=i.submission_version_id
                JOIN submission_heads h ON h.id=v.submission_id
                JOIN participants p ON p.id=h.participant_id AND p.room_id=h.room_id
                WHERE h.room_id=? AND i.batch_id=?"""
            }
        val arguments = if (batchId == null) arrayOf(roomId) else arrayOf(roomId, batchId)
        return jdbc.query(
            "$projection $from ORDER BY p.joined_at,p.id LIMIT 51",
            { rs, _ ->
                val raw = rs.getString("raw_text")
                val evidence = rs.getString("input_evidence")?.let { mapper.readValue(it, InputEvidence::class.java) }
                if (evidence != null) evidence.validateAgainst(requireNotNull(raw))
                InputRow(
                    rs.getObject("participant_id", UUID::class.java),
                    rs.getString("display_name"),
                    rs.getObject("head_id", UUID::class.java),
                    rs.getObject("version_id", UUID::class.java),
                    rs.getObject("revision", Int::class.javaObjectType),
                    raw,
                    rs.instantOrNull("created_at"),
                    evidence,
                )
            },
            *arguments,
        )
    }

    private fun input(
        roomId: UUID,
        row: InputRow,
        current: Boolean,
        stored: OperatorStoredInterpretation?,
        nativeSource: Boolean,
    ): OperatorInput {
        val chain = mutableListOf<Candidate>()
        val visited = mutableSetOf<UUID>()
        var candidate =
            if (current) {
                candidate(roomId, row.participantId, "ORDER BY c.revision DESC LIMIT 1")
            } else if (row.versionId != null) {
                candidate(roomId, row.participantId, "AND c.accepted_version_id=?", row.versionId)
            } else {
                null
            }
        var truncated = false
        while (candidate != null) {
            if (chain.size == 8 || !visited.add(candidate.id)) {
                truncated = true
                break
            }
            chain += candidate
            val parent = candidate.parentId
            candidate = parent?.let { candidate(roomId, row.participantId, "AND c.id=?", it) }
            if (parent != null && candidate == null) truncated = true
        }
        val acceptedIndex = chain.indexOfFirst { row.versionId != null && it.acceptedVersionId == row.versionId }
        val checks =
            chain
                .mapIndexed { index, item ->
                    val attempts = if (item.checkId == null) emptyList() else attempts(item.checkId)
                    val child = chain.getOrNull(index - 1)
                    val question =
                        item.question?.let { q ->
                            val answer = child?.takeIf { it.sourceQuestionId == q.id }
                            OperatorQuestion(
                                mask.text(q.prompt),
                                mask.text(q.sourceExcerpt),
                                answer?.let { mask.text(it.evidence?.supplement?.answerText ?: it.rawText) },
                                answer?.evidence?.supplement?.answeredAt,
                                answer != null && acceptedIndex >= 0 && index - 1 >= acceptedIndex,
                                q.choices.map { mask.text(it.label) },
                                mask.text(item.rawText),
                            )
                        }
                    OperatorInputCheck(
                        mask.text(item.rawText),
                        item.createdAt,
                        item.referenceAt,
                        item.status,
                        question,
                        attempts.take(8).reversed(),
                        attempts.size > 8,
                    )
                }.reversed()
        val legacy = row.versionId?.let { legacyQuestions(roomId, row.participantId, it) }.orEmpty()
        val supplement =
            row.evidence?.supplement?.let {
                OperatorQuestion(
                    mask.text(it.prompt),
                    mask.text(it.sourceExcerpt),
                    mask.text(it.answerText),
                    it.answeredAt,
                    true,
                    sourceRawText = row.rawText?.let(mask::text),
                )
            }
        // IrEvidenceBindings uses createdAt only for known native source contracts.
        // Personal precheck anchors remain separate under checks.referenceAt.
        val reference = row.evidence?.referenceAt ?: row.createdAt?.takeIf { nativeSource && row.rawText != null }
        return OperatorInput(
            mask.participantAlias(roomId, row.participantId),
            mask.name(row.name),
            row.revision,
            row.rawText?.let(mask::text),
            row.createdAt,
            reference,
            row.evidence?.interpretation?.name,
            row.evidence?.unresolvedReason?.let(mask::text),
            supplement,
            checks,
            truncated || legacy.size > 8,
            legacy.take(8),
            stored,
        )
    }

    private fun candidate(
        roomId: UUID,
        participantId: UUID,
        suffix: String,
        vararg arguments: Any,
    ): Candidate? =
        jdbc
            .query(
                """SELECT c.*,k.id AS check_id,k.status,k.question::text
            FROM precheck_candidates c LEFT JOIN precheck_checks k ON k.candidate_id=c.id
            WHERE c.room_id=? AND c.participant_id=? $suffix""",
                { rs, _ ->
                    Candidate(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("source_candidate_id", UUID::class.java),
                        rs.getObject("accepted_version_id", UUID::class.java),
                        rs.getObject("source_question_id", UUID::class.java),
                        rs.getString("raw_text"),
                        requireNotNull(rs.instantOrNull("created_at")),
                        requireNotNull(rs.instantOrNull("interpretation_reference_at")),
                        rs.getString("input_evidence")?.let { mapper.readValue(it, InputEvidence::class.java) },
                        rs.getObject("check_id", UUID::class.java),
                        rs.getString("status") ?: "NOT_RECORDED",
                        rs.getString("question")?.let { mapper.readValue(it, PrecheckQuestion::class.java) },
                    )
                },
                roomId,
                participantId,
                *arguments,
            ).singleOrNull()

    private fun attempts(checkId: UUID): List<OperatorPrecheckAttempt> =
        jdbc.query(
            """SELECT generation,started_at,finished_at,failure_code,classification_snapshot::text
            FROM precheck_executions WHERE check_id=? ORDER BY generation DESC LIMIT 9""",
            { rs, _ ->
                val snapshot =
                    rs.getString("classification_snapshot")?.let {
                        mapper
                            .readValue(it, PrecheckClassificationSnapshot::class.java)
                            .also { value ->
                                require(value.version == 1 && value.provider.length in 1..128 && value.model.length in 1..128)
                                require(value.confidence?.let { c -> c.isFinite() && c in 0.0..1.0 } != false)
                                require(
                                    value.probabilities.size <= 3 && value.probabilities.values.all { p -> p.isFinite() && p in 0.0..1.0 },
                                )
                            }.let { value -> value.copy(provider = mask.text(value.provider), model = mask.text(value.model)) }
                    }
                OperatorPrecheckAttempt(
                    rs.getInt("generation"),
                    rs.instantOrNull("started_at"),
                    rs.instantOrNull("finished_at"),
                    code(rs.getString("failure_code")),
                    snapshot,
                )
            },
            checkId,
        )

    private fun legacyQuestions(
        roomId: UUID,
        participantId: UUID,
        versionId: UUID,
    ): List<OperatorQuestion> =
        jdbc.query(
            """SELECT q.proposal::text,q.direct_text,q.answer_choice,q.answered_at,q.saved_version_id,
                source.raw_text AS source_raw,saved.raw_text AS saved_raw
            FROM submission_clarifications q JOIN submission_versions source ON source.id=q.source_version_id
            JOIN submission_heads h ON h.id=source.submission_id
            LEFT JOIN submission_versions saved ON saved.id=q.saved_version_id AND saved.submission_id=h.id
            WHERE h.room_id=? AND h.participant_id=? AND (q.source_version_id=? OR q.saved_version_id=?)
                AND (q.saved_version_id IS NULL OR saved.id IS NOT NULL)
            ORDER BY q.created_at,q.question_id LIMIT 9""",
            { rs, _ ->
                val proposal = mapper.readTree(rs.getString("proposal"))
                val choice = rs.getString("answer_choice")
                val answer =
                    when (choice) {
                        "DIRECT" -> rs.getString("direct_text")
                        "A", "B" -> rs.getString("saved_raw")
                        else -> null
                    }
                OperatorQuestion(
                    mask.text(requireNotNull(proposal.path("question").stringValue())),
                    proposal.path("excerpt").stringValue()?.let(mask::text),
                    answer?.let(mask::text),
                    rs.instantOrNull("answered_at"),
                    choice != "CANCEL" && rs.getObject("saved_version_id") == versionId,
                    listOfNotNull(proposal.path("optionA").stringValue(), proposal.path("optionB").stringValue()).map(mask::text),
                    rs.getString("source_raw")?.let(mask::text),
                )
            },
            roomId,
            participantId,
            versionId,
            versionId,
        )

    private fun storedInterpretations(
        roomId: UUID,
        run: RunRow,
    ): Map<UUID, OperatorStoredInterpretation> {
        val document =
            jdbc
                .query(
                    "SELECT format_version,envelope::text FROM schedule_ir_envelopes WHERE run_id=? AND room_id=? AND batch_id=?",
                    { rs, _ -> rs.getInt(1) to rs.getString(2) },
                    run.analysis.id,
                    roomId,
                    run.batchId,
                ).singleOrNull() ?: return emptyMap()
        val (format, json) = document
        require(json.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "Oversized stored declaration" }
        if (format !in 5..12) {
            return jdbc
                .query(
                    "SELECT submission_version_id FROM submission_batch_items WHERE batch_id=? LIMIT 51",
                    { rs, _ -> rs.getObject(1, UUID::class.java) },
                    run.batchId,
                ).associateWith { OperatorStoredInterpretation("UNSUPPORTED_FORMAT", emptyList(), emptyList(), false) }
        }
        val shape = mapper.readTree(json)
        val contextNode = if (format >= 7) shape.path("context") else shape.path("calculationProjection").path("claims").path("context")
        val context = requireNotNull(mapper.treeToValue(contextNode, ScheduleIrContext::class.java))
        require(context.runId == run.analysis.id && context.batchId == run.batchId && context.roomId == roomId)
        val declaration =
            when (format) {
                5 -> requireNotNull(JdbcScheduleDslApprovedTermsRepository(jdbc, mapper).find(context)).calculationProjection
                6 -> requireNotNull(JdbcScheduleDslContextModeRepository(jdbc, mapper).find(context)).calculationProjection
                else -> requireNotNull(JdbcScheduleDslStructuralRepository(jdbc, mapper).find(context)).calculationProjection
            }
        return declaration.claims.results.associate { result ->
            val problems =
                (
                    result.quarantined.map { it.problem.name } +
                        declaration.programs.filter { it.submissionVersionId == result.submissionVersionId }.flatMap {
                            it.problems.map { p ->
                                p.name
                            }
                        }
                ).distinct()
            result.submissionVersionId to
                OperatorStoredInterpretation(
                    "STORED_DECLARATION",
                    result.conditions.take(64).map {
                        OperatorStoredCondition(
                            it.effect.name,
                            it.interpretation.name,
                            it.temporalResolution.name,
                            it.scope?.dimension?.name,
                            it.scope?.start?.toString(),
                            it.scope?.end?.toString(),
                        )
                    },
                    problems.take(64),
                    result.conditions.size > 64 || problems.size > 64,
                )
        }
    }

    private fun results(
        roomId: UUID,
        run: RunRow,
    ): List<OperatorResult> {
        val modern =
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM recommendation_analyses WHERE coordination_run_id=? AND room_id=?)",
                Boolean::class.java,
                run.analysis.id,
                roomId,
            ) == true
        val sql =
            if (modern) {
                """SELECT v.id,o.start_at,o.end_at,v.meeting_mode,v.place_name FROM recommendation_options o
                JOIN recommendation_variants v ON v.option_id=o.id AND v.room_id=o.room_id AND v.coordination_run_id=o.coordination_run_id
                WHERE o.room_id=? AND o.coordination_run_id=? ORDER BY o.ordinal,v.meeting_mode,v.id LIMIT 21"""
            } else {
                """SELECT c.id,t.start_at,t.end_at,c.meeting_mode,c.place_name FROM candidates c
                JOIN candidate_time_ranges t ON t.candidate_id=c.id
                JOIN coordination_runs r ON r.id=c.coordination_run_id
                WHERE r.room_id=? AND r.id=? ORDER BY c.rank,t.range_order LIMIT 21"""
            }
        return jdbc.query(
            sql,
            { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val table = if (modern) "recommendation_variant_participants" else "candidate_participants"
                val column = if (modern) "variant_id" else "candidate_id"
                val members =
                    jdbc.query(
                        """SELECT vp.participant_id,vp.place_unverified FROM $table vp
                        JOIN participants p ON p.id=vp.participant_id AND p.room_id=vp.room_id
                        WHERE vp.$column=? AND vp.room_id=? AND vp.coordination_run_id=?
                        AND EXISTS(SELECT 1 FROM submission_heads h JOIN submission_versions v ON v.submission_id=h.id
                            JOIN submission_batch_items bi ON bi.submission_version_id=v.id
                            WHERE h.participant_id=vp.participant_id AND h.room_id=vp.room_id AND bi.batch_id=?)
                        ORDER BY vp.participant_id LIMIT 51""",
                        { member, _ ->
                            mask.participantAlias(roomId, member.getObject("participant_id", UUID::class.java)) to
                                member.getBoolean("place_unverified")
                        },
                        id,
                        roomId,
                        run.analysis.id,
                        run.batchId,
                    )
                check(members.size <= 50) { "Oversized stored recommendation cohort" }
                val recordedCount =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM $table WHERE $column=? AND room_id=? AND coordination_run_id=?",
                        Long::class.java,
                        id,
                        roomId,
                        run.analysis.id,
                    )
                check(recordedCount == members.size.toLong()) { "Inconsistent stored recommendation cohort" }
                OperatorResult(
                    requireNotNull(rs.instantOrNull("start_at")),
                    requireNotNull(rs.instantOrNull("end_at")),
                    rs.getString("meeting_mode"),
                    rs.getString("place_name")?.let(mask::text),
                    members.any { it.second },
                    members.map { it.first },
                )
            },
            roomId,
            run.analysis.id,
        )
    }

    private fun timeline(
        roomId: UUID,
        run: RunRow,
    ): Pair<List<OperatorTimelineEvent>, Boolean> {
        val events =
            mutableListOf(
                OperatorTimelineEvent("INPUTS_FROZEN", run.analysis.fixedAt, "RECORDED"),
                OperatorTimelineEvent("ANALYSIS_CREATED", run.analysis.createdAt, "RECORDED"),
            )
        val outbox =
            jdbc.query(
                """SELECT status,occurred_at,published_at,processed_at,dead_lettered_at FROM outbox_events
                WHERE aggregate_type='CoordinationRun' AND aggregate_id=? ORDER BY occurred_at,id LIMIT 26""",
                { rs, _ ->
                    listOfNotNull(
                        rs.instantOrNull("occurred_at")?.let { OperatorTimelineEvent("OUTBOX_OCCURRED", it, "RECORDED") },
                        rs.instantOrNull("published_at")?.let { OperatorTimelineEvent("OUTBOX_PUBLISHED", it, "RECORDED") },
                        rs.instantOrNull("processed_at")?.let { OperatorTimelineEvent("OUTBOX_PROCESSED", it, "RECORDED") },
                        rs.instantOrNull("dead_lettered_at")?.let { OperatorTimelineEvent("OUTBOX_DEAD_LETTERED", it, "RECORDED") },
                    )
                },
                run.analysis.id,
            )
        events += outbox.take(25).flatten()
        val failures =
            jdbc.query(
                """SELECT event_id,failed_at,failure_kind FROM coordination_worker_failures f
                WHERE f.submission_batch_id=? AND (f.event_id IS NULL OR EXISTS(
                    SELECT 1 FROM outbox_events e WHERE e.id=f.event_id AND e.aggregate_id=? AND e.aggregate_type='CoordinationRun'))
                ORDER BY failed_at,stream_record_id LIMIT 101""",
                { rs, _ ->
                    OperatorTimelineEvent(
                        if (rs.getObject("event_id") == null) "BATCH_WORKER_FAILURE" else "WORKER_FAILURE",
                        rs.instantOrNull("failed_at"),
                        code(rs.getString("failure_kind")) ?: "NOT_RECORDED",
                    )
                },
                run.batchId,
                run.analysis.id,
            )
        events += failures.take(100)
        listOf(
            Triple("final_analysis_routes", "selected_at", "ROUTE_SELECTED"),
            Triple("schedule_ir_execution_bindings", "prepared_at", "INTERPRETATION_PREPARED"),
            Triple("schedule_ir_envelopes", "processed_at", "INTERPRETATION_STORED"),
        ).forEach { (table, column, kind) ->
            events +=
                jdbc.query(
                    "SELECT $column FROM $table WHERE run_id=? AND room_id=? AND batch_id=?",
                    { rs, _ -> OperatorTimelineEvent(kind, rs.instantOrNull(column), "RECORDED") },
                    run.analysis.id,
                    roomId,
                    run.batchId,
                )
        }
        return events.sortedWith(compareBy<OperatorTimelineEvent> { it.at }.thenBy { it.kind }) to (outbox.size > 25 || failures.size > 100)
    }

    private fun run(rs: ResultSet) =
        RunRow(
            rs.getObject("batch_id", UUID::class.java),
            OperatorAnalysis(
                rs.getObject("id", UUID::class.java),
                rs.getString("status"),
                requireNotNull(rs.instantOrNull("created_at")),
                requireNotNull(rs.instantOrNull("fixed_at")),
                rs.getBoolean("active"),
                rs.getString("contract"),
            ),
        )

    private fun code(value: String?): String? = value?.let { if (SAFE_CODE.matches(it)) it else "UNKNOWN" }

    private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

    private data class RunRow(
        val batchId: UUID,
        val analysis: OperatorAnalysis,
    )

    private data class InputRow(
        val participantId: UUID,
        val name: String?,
        val headId: UUID?,
        val versionId: UUID?,
        val revision: Int?,
        val rawText: String?,
        val createdAt: Instant?,
        val evidence: InputEvidence?,
    )

    private data class Candidate(
        val id: UUID,
        val parentId: UUID?,
        val acceptedVersionId: UUID?,
        val sourceQuestionId: UUID?,
        val rawText: String,
        val createdAt: Instant,
        val referenceAt: Instant,
        val evidence: InputEvidence?,
        val checkId: UUID?,
        val status: String,
        val question: PrecheckQuestion?,
    )

    companion object {
        private val SAFE_CODE = Regex("[A-Z0-9_]{1,64}")
        private const val RUN_SELECT = """SELECT r.id,r.batch_id,r.status,r.created_at,b.fixed_at,
            (m.active_run_id=r.id) AS active,COALESCE(f.route_kind,'NOT_RECORDED') AS contract
            FROM coordination_runs r JOIN submission_batches b ON b.id=r.batch_id AND b.room_id=r.room_id
            JOIN meeting_rooms m ON m.id=r.room_id LEFT JOIN final_analysis_routes f
                ON f.run_id=r.id AND f.room_id=r.room_id AND f.batch_id=r.batch_id"""
    }
}
