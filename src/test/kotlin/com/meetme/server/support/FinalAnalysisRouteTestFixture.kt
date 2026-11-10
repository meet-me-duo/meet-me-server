package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Independent synthetic native rows. No old result, selection, invocation or budget is pre-created. */
object FinalAnalysisRouteTestFixture {
    val q = ScheduleDslQuestionContextFixture
    val ir = ScheduleIrV2SyntheticFixture
    val selectedAt: Instant = Instant.parse("2027-01-04T02:00:00Z")

    fun choice(): FinalAnalysisRouteChoice.DslContextV2 =
        FinalAnalysisRouteChoice.DslContextV2(
            IrExecutionIdentity(SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, ir.POLICY),
            q.wire.f.policy,
            q.snapshot().context,
            q.input2().meetingMode,
        )

    fun route(choice: FinalAnalysisRouteChoice = choice()) = FrozenFinalAnalysisRoute(ir.run, ir.batch, ir.room, choice, selectedAt)

    fun oldTicket(): IrExecutionTicket =
        choice().let {
            IrExecutionTicket(1, it.identity, it.policy, it.context, it.meetingMode, selectedAt)
        }

    fun invocation(
        version: Long = 1,
        ordinal: Long = version,
    ) = AnalysisInvocation(
        UUID(0, 2000 + ordinal),
        CoordinationRunId(ir.run),
        version,
        selectedAt,
        selectedAt.plusSeconds(50),
        UUID(0, 3000 + ordinal),
    )

    fun seed(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
        input: ScheduleDslOfflineInput = q.input2(),
    ) {
        jdbc.update(
            """
            INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,
                search_start_date,search_end_date,search_range_source,manual_only,
                collection_status,closure_reason,closed_at,created_at,submission_protocol)
            VALUES (?,'route-synthetic-000001','synthetic route','REMOTE','Asia/Seoul',
                '2027-01-04','2027-01-11','HOST_SPECIFIED',true,'CLOSED','MANUAL',?,?,'PRE_SUBMIT_V2')
            """.trimIndent(),
            input.roomId,
            Timestamp.from(input.batchFixedAt),
            Timestamp.from(ir.reference),
        )
        input.inputs.forEachIndexed { index, item ->
            val guest = UUID(0, 501L + index)
            jdbc.update(
                "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                guest,
                "synthetic-route-guest-$index",
                Timestamp.from(selectedAt.plusSeconds(86400)),
                Timestamp.from(ir.reference),
            )
            jdbc.update(
                "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                item.participantId,
                input.roomId,
                guest,
                if (index == 0) "HOST" else "MEMBER",
                "synthetic-route-$index",
                Timestamp.from(ir.reference),
            )
            jdbc.update(
                "INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)",
                item.submissionId,
                input.roomId,
                item.participantId,
            )
            jdbc.update(
                """
                INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb))
                """.trimIndent(),
                item.submissionVersionId,
                item.submissionId,
                item.submissionRevision,
                item.rawText,
                item.locale,
                Timestamp.from(item.acceptedAt),
                mapper.writeValueAsString(item.evidence),
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", item.submissionVersionId, item.submissionId)
        }
        jdbc.update(
            "INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)",
            input.batchId,
            input.roomId,
            Timestamp.from(input.batchFixedAt),
        )
        input.inputs.forEach {
            jdbc.update(
                "INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)",
                input.batchId,
                it.submissionVersionId,
            )
        }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            input.runId,
            input.roomId,
            input.batchId,
            Timestamp.from(input.batchFixedAt),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", input.runId, input.roomId)
    }

    fun rows(
        jdbc: JdbcTemplate,
        table: String,
    ): List<String> =
        jdbc
            .queryForList(
                "SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text",
                String::class.java,
            ).map { requireNotNull(it) }

    fun historicalSnapshot(jdbc: JdbcTemplate): Map<String, List<String>> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "coordination_runs",
            "structured_submission_results",
            "schedule_ir_envelopes",
            "schedule_ir_inputs",
            "schedule_ir_execution_bindings",
            "analysis_invocations",
            "coordination_attempts",
        ).associateWith { rows(jdbc, it) }
}
