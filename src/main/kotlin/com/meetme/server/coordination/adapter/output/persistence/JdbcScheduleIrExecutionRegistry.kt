package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Explicitly configured only; immutable first selection and no historical IR adoption. */
class JdbcScheduleIrExecutionRegistry(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ScheduleIrExecutionRegistry {
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbcTemplate.dataSource)))
    private val reader =
        objectMapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
            .readerFor(IrExecutionTicket::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    override fun bind(ticket: IrExecutionTicket) {
        transaction.executeWithoutResult {
            lockCurrent(ticket)
            val existing = find(ticket.context.runId)
            if (existing != null) {
                check(ticket.copy(preparedAt = existing.preparedAt) == existing) { "Conflicting IR execution selection" }
                return@executeWithoutResult
            }
            check(
                jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM schedule_ir_envelopes WHERE run_id=?)",
                    Boolean::class.java,
                    ticket.context.runId,
                ) == false,
            ) { "Unbound historical IR cannot acquire a new selection" }
            jdbcTemplate.update(
                """
                INSERT INTO schedule_ir_execution_bindings
                    (run_id,batch_id,room_id,response_contract_version,semantic_version,policy_version,meeting_mode,
                     format_version,selection_fingerprint,selection,prepared_at)
                VALUES (?,?,?,?,?,?,?,1,?,CAST(? AS jsonb),?)
                """.trimIndent(),
                ticket.context.runId,
                ticket.context.batchId,
                ticket.context.roomId,
                ticket.identity.responseContractVersion,
                ticket.identity.irSemanticVersion,
                ticket.identity.policyVersion,
                ticket.meetingMode.name,
                ticket.fingerprint(),
                objectMapper.writeValueAsString(ticket),
                ticket.preparedAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
            )
        }
    }

    override fun find(runId: UUID): IrExecutionTicket? {
        val stored =
            jdbcTemplate
                .query(
                    """
                    SELECT batch_id,room_id,response_contract_version,semantic_version,policy_version,meeting_mode,
                        format_version,selection_fingerprint,selection::text,prepared_at
                    FROM schedule_ir_execution_bindings WHERE run_id=?
                    """.trimIndent(),
                    { rs, _ ->
                        StoredSelection(
                            rs.getObject(1, UUID::class.java),
                            rs.getObject(2, UUID::class.java),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getInt(7),
                            rs.getString(8),
                            rs.getString(9),
                            rs.getObject(10, OffsetDateTime::class.java).toInstant(),
                        )
                    },
                    runId,
                ).singleOrNull() ?: return null
        require(stored.formatVersion == 1 && stored.semanticVersion in SUPPORTED_SEMANTIC_VERSIONS) { "Unsupported stored IR selection" }
        val ticket =
            try {
                reader.readValue<IrExecutionTicket>(stored.json)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored IR selection shape")
            }
        validateIdentity(ticket)
        require(ticket.context.runId == runId && ticket.context.batchId == stored.batchId && ticket.context.roomId == stored.roomId)
        require(
            ticket.identity.responseContractVersion == stored.contractVersion &&
                ticket.identity.irSemanticVersion == stored.semanticVersion,
        )
        require(ticket.identity.policyVersion == stored.policyVersion && ticket.meetingMode.name == stored.mode)
        require(ticket.preparedAt.truncatedTo(ChronoUnit.MICROS) == stored.preparedAt)
        require(ticket.fingerprint() == stored.fingerprint) { "IR selection fingerprint mismatch" }
        return ticket
    }

    override fun requireCurrent(ticket: IrExecutionTicket) {
        transaction.executeWithoutResult {
            lockCurrent(ticket)
            check(find(ticket.context.runId) == ticket) { "Missing or substituted IR execution selection" }
        }
    }

    /** All write/consume locks follow room -> run -> selection, matching existing IR publication. */
    private fun lockCurrent(ticket: IrExecutionTicket) {
        validateIdentity(ticket)
        val context = ticket.context
        val actualRoom = jdbcTemplate.queryForObject("SELECT room_id FROM coordination_runs WHERE id=?", UUID::class.java, context.runId)
        require(actualRoom == context.roomId) { "IR execution room mismatch" }
        val room =
            jdbcTemplate.queryForMap(
                """
                SELECT active_run_id,active_revision_round_id,collection_status,closed_at,meeting_mode,
                    time_zone_id,search_start_date,search_end_date FROM meeting_rooms WHERE id=? FOR UPDATE
                """.trimIndent(),
                context.roomId,
            )
        check(room["active_run_id"] == context.runId && room["active_revision_round_id"] == null) { "Inactive IR execution" }
        check(room["collection_status"] == "CLOSED" && room["closed_at"] != null) { "Unfrozen IR execution" }
        check(room["meeting_mode"] == ticket.meetingMode.name && room["time_zone_id"] == context.timeZone)
        check((room["search_start_date"] as java.sql.Date).toLocalDate() == context.searchStart)
        check((room["search_end_date"] as java.sql.Date).toLocalDate() == context.searchEnd)
        val run =
            jdbcTemplate.queryForMap(
                """
                SELECT r.room_id,r.batch_id,r.status,r.version,b.fixed_at,
                    EXISTS(SELECT 1 FROM final_confirmations f WHERE f.coordination_run_id=r.id)
                    OR EXISTS(SELECT 1 FROM recommendation_selections s WHERE s.coordination_run_id=r.id) AS confirmed
                FROM coordination_runs r JOIN submission_batches b ON b.id=r.batch_id WHERE r.id=? FOR UPDATE OF r
                """.trimIndent(),
                context.runId,
            )
        check(run["room_id"] == context.roomId && run["batch_id"] == context.batchId)
        check((run["version"] as Number).toLong() == ticket.expectedRunVersion && run["status"] == "STRUCTURING") { "Stale IR execution" }
        check(run["confirmed"] == false) { "Protected IR execution" }
        require(ticket.preparedAt >= (run["fixed_at"] as java.sql.Timestamp).toInstant())
        // The room/run locks serialize first insertion too; row locking alone cannot protect an absent selection.
        jdbcTemplate.queryForList(
            "SELECT run_id FROM schedule_ir_execution_bindings WHERE run_id=? FOR UPDATE",
            UUID::class.java,
            context.runId,
        )
    }

    private fun validateIdentity(ticket: IrExecutionTicket) {
        require(ticket.expectedRunVersion >= 0 && ticket.identity.irSemanticVersion in SUPPORTED_SEMANTIC_VERSIONS)
        require(ticket.identity.responseContractVersion.matches(TOKEN) && ticket.policy.version.matches(TOKEN))
        require(ticket.identity.policyVersion == ticket.policy.version && ticket.context.policyVersion == ticket.policy.version)
        require(ticket.context.inputs.size in 2..50)
        require(
            ticket.context.inputs
                .map { it.submissionVersionId }
                .distinct()
                .size == ticket.context.inputs.size,
        )
        require(
            ticket.context.inputs
                .map { it.participantId }
                .distinct()
                .size == ticket.context.inputs.size,
        )
    }

    private data class StoredSelection(
        val batchId: UUID,
        val roomId: UUID,
        val contractVersion: String,
        val semanticVersion: String,
        val policyVersion: String,
        val mode: String,
        val formatVersion: Int,
        val fingerprint: String,
        val json: String,
        val preparedAt: java.time.Instant,
    )

    companion object {
        private val SUPPORTED_SEMANTIC_VERSIONS = setOf(SCHEDULE_IR_VERSION, SCHEDULE_IR_V2_VERSION, SCHEDULE_DSL_SEMANTIC_VERSION)
        private val TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
