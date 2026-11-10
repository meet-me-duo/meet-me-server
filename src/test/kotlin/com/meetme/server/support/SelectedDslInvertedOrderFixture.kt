package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.util.UUID

/** Same synthetic native content, deliberately opposite participant/version order. No forged bindings. */
object SelectedDslInvertedOrderFixture {
    private val ir = ScheduleIrV2SyntheticFixture
    private val selectedAt = FinalAnalysisRouteTestFixture.selectedAt

    fun input() =
        SelectedDslPhysicalInputFixture.contextInput().let { input ->
            input.copy(
                inputs =
                    input.inputs.map { item ->
                        item.copy(participantId = if (item.submissionVersionId == ir.first) ir.secondParticipant else ir.firstParticipant)
                    },
            )
        }

    fun snapshot() = ScheduleDslOfflinePipeline.snapshotContext(input()).snapshot

    fun choice() = FinalAnalysisRouteTestFixture.choice().copy(context = snapshot().context)

    fun seed(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ) {
        val input = input()
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
}
