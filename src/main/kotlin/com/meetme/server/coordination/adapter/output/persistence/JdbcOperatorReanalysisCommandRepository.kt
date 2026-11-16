package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandRecord
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcOperatorReanalysisCommandRepository(
    private val jdbc: JdbcTemplate,
) : OperatorReanalysisCommandRepository {
    override fun hasOpenRevisionRound(roomId: UUID): Boolean =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM input_revision_rounds WHERE room_id = ? AND status = 'OPEN')",
                Boolean::class.java,
                roomId,
            ),
        )

    override fun find(
        roomId: UUID,
        requestId: UUID,
    ): OperatorReanalysisCommandRecord? =
        jdbc
            .query(
                "SELECT * FROM operations_reanalysis_commands WHERE room_id = ? AND request_id = ?",
                { rs, _ ->
                    val id = rs.getObject("request_id", UUID::class.java)
                    val source = rs.getObject("source_run_id", UUID::class.java)
                    OperatorReanalysisCommandRecord(
                        rs.getObject("room_id", UUID::class.java),
                        rs.getObject("actor_user_id", UUID::class.java),
                        OperatorReanalysisRequest(id, source, rs.getLong("expected_state_version")),
                        OperatorReanalysisReceipt(
                            id,
                            source,
                            rs.getObject("created_run_id", UUID::class.java),
                            rs.getLong("accepted_state_version"),
                            rs.getTimestamp("accepted_at").toInstant(),
                            rs.getString("analysis_contract_version"),
                        ),
                    )
                },
                roomId,
                requestId,
            ).singleOrNull()

    override fun insert(record: OperatorReanalysisCommandRecord) {
        jdbc.update(
            """
            INSERT INTO operations_reanalysis_commands
                (room_id, request_id, actor_user_id, source_run_id, created_run_id,
                 expected_state_version, accepted_state_version, accepted_at, analysis_contract_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.roomId,
            record.request.requestId,
            record.actorId,
            record.request.sourceAnalysisId,
            record.receipt.analysisId,
            record.request.expectedStateVersion,
            record.receipt.acceptedStateVersion,
            Timestamp.from(record.receipt.acceptedAt),
            record.receipt.analysisContractVersion,
        )
    }
}
