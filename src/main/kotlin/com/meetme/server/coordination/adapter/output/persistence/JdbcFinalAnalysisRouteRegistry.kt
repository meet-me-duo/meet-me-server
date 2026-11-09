package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.adapter.output.persistence.ScheduleIrJdbcDocumentStore
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Explicit selection only. This registry grants neither a physical call slot nor a stored winner. */
class JdbcFinalAnalysisRouteRegistry(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : FinalAnalysisRouteRegistry {
    private val transaction = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource)))
    private val frozenSources = ScheduleIrJdbcDocumentStore(jdbc, mapper)
    private val strictMapper =
        mapper
            .rebuild<JsonMapper, JsonMapper.Builder>()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build()
    private val dslReader =
        strictMapper
            .readerFor(StoredDslChoice::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val legacyReader =
        strictMapper
            .readerFor(Map::class.java)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val approvedReader =
        strictMapper
            .readerFor(StoredApprovedChoice::class.java)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    override fun freeze(
        runId: UUID,
        expectedRunVersion: Long,
        choice: FinalAnalysisRouteChoice,
    ): FrozenFinalAnalysisRoute =
        requireNotNull(
            transaction.execute {
                val current = lockCurrent(runId, expectedRunVersion, setOf("QUEUED", "STRUCTURING"))
                val requested = canonical(choice)
                val selectedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
                require(selectedAt >= current.fixedAt) { "Route selection precedes the frozen batch" }
                val proposed = FrozenFinalAnalysisRoute(runId, current.batchId, current.roomId, requested, selectedAt)
                validateIdentity(proposed)
                validateNative(proposed, current)
                val existing = find(runId)
                if (existing != null) {
                    check(existing.choice == requested && existing.batchId == current.batchId && existing.roomId == current.roomId) {
                        "Conflicting final analysis route"
                    }
                    return@execute existing
                }
                check(
                    !exists("SELECT EXISTS(SELECT 1 FROM schedule_ir_execution_bindings WHERE run_id=?)", runId) &&
                        !exists("SELECT EXISTS(SELECT 1 FROM schedule_ir_envelopes WHERE run_id=?)", runId),
                ) {
                    "Historical IR cannot acquire a final analysis route"
                }
                if (requested != FinalAnalysisRouteChoice.Legacy) {
                    check(
                        !exists("SELECT EXISTS(SELECT 1 FROM analysis_invocations WHERE coordination_run_id=?)", runId) &&
                            !exists("SELECT EXISTS(SELECT 1 FROM structured_submission_results WHERE batch_id=?)", current.batchId),
                    ) {
                        "Legacy analysis history cannot become DSL"
                    }
                }
                val kind =
                    when (requested) {
                        is FinalAnalysisRouteChoice.DslStructuralV5 -> "DSL_STRUCTURAL_V5"
                        is FinalAnalysisRouteChoice.DslContextModeV4 -> "DSL_CONTEXT_MODE_V4"
                        FinalAnalysisRouteChoice.Legacy -> "LEGACY"
                        is FinalAnalysisRouteChoice.DslContextV2 -> "DSL_CONTEXT_V2"
                        is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> "DSL_APPROVED_TERMS_V3"
                    }
                val json =
                    when (requested) {
                        is FinalAnalysisRouteChoice.DslStructuralV5 ->
                            mapper.writeValueAsString(
                                StoredApprovedChoice(
                                    requested.identity,
                                    requested.policy,
                                    requested.context,
                                    requested.meetingMode,
                                    requested.vocabularyVersion,
                                ),
                            )
                        is FinalAnalysisRouteChoice.DslContextModeV4 ->
                            mapper.writeValueAsString(
                                StoredApprovedChoice(
                                    requested.identity,
                                    requested.policy,
                                    requested.context,
                                    requested.meetingMode,
                                    requested.vocabularyVersion,
                                ),
                            )
                        FinalAnalysisRouteChoice.Legacy -> "{}"
                        is FinalAnalysisRouteChoice.DslApprovedTermsV3 ->
                            mapper.writeValueAsString(
                                StoredApprovedChoice(
                                    requested.identity,
                                    requested.policy,
                                    requested.context,
                                    requested.meetingMode,
                                    requested.vocabularyVersion,
                                ),
                            )
                        is FinalAnalysisRouteChoice.DslContextV2 ->
                            mapper.writeValueAsString(
                                StoredDslChoice(requested.identity, requested.policy, requested.context, requested.meetingMode),
                            )
                    }
                jdbc.update(
                    """
                    INSERT INTO final_analysis_routes
                        (run_id,batch_id,room_id,route_kind,format_version,route_fingerprint,selection,selected_at)
                    VALUES (?,?,?,?,1,?,CAST(? AS jsonb),?)
                    """.trimIndent(),
                    runId,
                    current.batchId,
                    current.roomId,
                    kind,
                    proposed.fingerprint(),
                    json,
                    selectedAt.atOffset(ZoneOffset.UTC),
                )
                proposed
            },
        )

    override fun find(runId: UUID): FrozenFinalAnalysisRoute? {
        val stored =
            jdbc
                .query(
                    "SELECT batch_id,room_id,route_kind,format_version,route_fingerprint,selection::text,selected_at FROM final_analysis_routes WHERE run_id=?",
                    { rs, _ ->
                        StoredRoute(
                            rs.getObject(1, UUID::class.java),
                            rs.getObject(2, UUID::class.java),
                            rs.getString(3),
                            rs.getInt(4),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getObject(7, OffsetDateTime::class.java).toInstant(),
                        )
                    },
                    runId,
                ).singleOrNull() ?: return null
        require(stored.format == 1) { "Unsupported final analysis route format" }
        val choice =
            try {
                when (stored.kind) {
                    "LEGACY" -> {
                        require(legacyReader.readValue<Map<*, *>>(stored.json).isEmpty())
                        FinalAnalysisRouteChoice.Legacy
                    }
                    "DSL_CONTEXT_V2" ->
                        dslReader.readValue<StoredDslChoice>(stored.json).let {
                            FinalAnalysisRouteChoice.DslContextV2(it.identity, it.policy, it.context, it.meetingMode)
                        }
                    "DSL_APPROVED_TERMS_V3" ->
                        approvedReader.readValue<StoredApprovedChoice>(stored.json).let {
                            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                                it.identity,
                                it.policy,
                                it.context,
                                it.meetingMode,
                                it.vocabularyVersion,
                            )
                        }
                    "DSL_STRUCTURAL_V5" ->
                        approvedReader.readValue<StoredApprovedChoice>(stored.json).let {
                            FinalAnalysisRouteChoice.DslStructuralV5(
                                it.identity,
                                it.policy,
                                it.context,
                                it.meetingMode,
                                it.vocabularyVersion,
                            )
                        }
                    "DSL_CONTEXT_MODE_V4" ->
                        approvedReader.readValue<StoredApprovedChoice>(stored.json).let {
                            FinalAnalysisRouteChoice.DslContextModeV4(
                                it.identity,
                                it.policy,
                                it.context,
                                it.meetingMode,
                                it.vocabularyVersion,
                            )
                        }
                    else -> throw IllegalArgumentException("Unsupported final analysis route kind")
                }
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid stored final analysis route shape")
            }
        val route = FrozenFinalAnalysisRoute(runId, stored.batchId, stored.roomId, choice, stored.selectedAt)
        validateIdentity(route)
        require(route.fingerprint() == stored.fingerprint) { "Final analysis route fingerprint mismatch" }
        return route
    }

    override fun bindAttempt(
        route: FrozenFinalAnalysisRoute,
        invocation: AnalysisInvocation,
    ): FinalAnalysisAttemptBinding {
        val binding = FinalAnalysisAttemptBinding(route, invocation)
        requireDispatchCurrent(binding)
        return binding
    }

    override fun requireDispatchCurrent(binding: FinalAnalysisAttemptBinding) {
        transaction.executeWithoutResult {
            val route = binding.route
            val invocation = binding.invocation
            require(invocation.runId.value == route.runId) { "Invocation route mismatch" }
            val current = lockCurrent(route.runId, invocation.runVersion, setOf("STRUCTURING"))
            check(find(route.runId) == route) { "Missing or substituted final analysis route" }
            validateIdentity(route)
            validateNative(route, current)
            val owned =
                jdbc
                    .query(
                        """
                        SELECT id,coordination_run_id,run_version,started_at,deadline_at,owner_token,finished_at,winner_attempt_id,policy_version
                        FROM analysis_invocations WHERE id=? FOR UPDATE
                        """.trimIndent(),
                        { rs, _ ->
                            StoredInvocation(
                                rs.getObject(1, UUID::class.java),
                                rs.getObject(2, UUID::class.java),
                                rs.getLong(3),
                                rs.getObject(4, OffsetDateTime::class.java).toInstant(),
                                rs.getObject(5, OffsetDateTime::class.java).toInstant(),
                                rs.getObject(6, UUID::class.java),
                                rs.getObject(7, OffsetDateTime::class.java)?.toInstant(),
                                rs.getObject(8, UUID::class.java),
                                rs.getString(9),
                            )
                        },
                        invocation.id,
                    ).singleOrNull()
            check(
                owned != null &&
                    owned.id == invocation.id &&
                    owned.runId == invocation.runId.value &&
                    owned.version == invocation.runVersion &&
                    owned.owner == invocation.ownerToken &&
                    owned.startedAt == invocation.startedAt &&
                    owned.deadlineAt == invocation.deadlineAt &&
                    owned.policyVersion == invocation.policyVersion &&
                    AnalysisInvocationBudget.isAllowedPolicy(
                        owned.policyVersion,
                        route.choice is FinalAnalysisRouteChoice.DslStructuralV5,
                    ),
            ) { "Missing or substituted durable invocation" }
            check(invocation.finishedAt == null && invocation.winnerAttemptId == null && owned.finishedAt == null && owned.winner == null) {
                "Finished invocation cannot dispatch"
            }
            val now = clock.instant()
            check(now >= owned.startedAt && now < owned.deadlineAt) { "Invocation is outside its dispatch deadline" }
            // Existing budget counters may advance; this reference never grants or resets an attempt.
        }
    }

    /** Existing publication lock order also serializes first selection when no route row exists. */
    private fun lockCurrent(
        runId: UUID,
        expectedVersion: Long,
        statuses: Set<String>,
    ): CurrentRun {
        require(expectedVersion >= 0)
        val roomId = requireNotNull(jdbc.queryForObject("SELECT room_id FROM coordination_runs WHERE id=?", UUID::class.java, runId))
        val room =
            jdbc.queryForMap(
                """
                SELECT active_run_id,active_revision_round_id,collection_status,closed_at,meeting_mode
                FROM meeting_rooms WHERE id=? FOR UPDATE
                """.trimIndent(),
                roomId,
            )
        check(room["active_run_id"] == runId && room["active_revision_round_id"] == null) { "Inactive final analysis route" }
        check(room["collection_status"] == "CLOSED" && room["closed_at"] != null) { "Unfrozen final analysis route" }
        val row =
            jdbc.queryForMap(
                """
                SELECT r.room_id,r.batch_id,r.status,r.version,b.fixed_at,
                    EXISTS(SELECT 1 FROM final_confirmations f WHERE f.coordination_run_id=r.id)
                    OR EXISTS(SELECT 1 FROM recommendation_selections s WHERE s.coordination_run_id=r.id) AS confirmed
                FROM coordination_runs r JOIN submission_batches b ON b.id=r.batch_id WHERE r.id=? FOR UPDATE OF r
                """.trimIndent(),
                runId,
            )
        check(row["room_id"] == roomId && (row["version"] as Number).toLong() == expectedVersion && (row["status"] as String) in statuses) {
            "Stale final analysis execution"
        }
        check(row["confirmed"] == false) { "Confirmed execution cannot dispatch" }
        jdbc.queryForList("SELECT run_id FROM final_analysis_routes WHERE run_id=? FOR UPDATE", UUID::class.java, runId)
        return CurrentRun(
            roomId,
            row["batch_id"] as UUID,
            (row["fixed_at"] as java.sql.Timestamp).toInstant(),
            room["meeting_mode"] as String,
        )
    }

    private fun validateNative(
        route: FrozenFinalAnalysisRoute,
        current: CurrentRun,
    ) {
        require(route.roomId == current.roomId && route.batchId == current.batchId && route.selectedAt >= current.fixedAt)
        val context =
            when (val choice = route.choice) {
                is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                    check(choice.meetingMode.name == current.mode) { "Changed final analysis meeting mode" }
                    choice.context
                }
                is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                    check(choice.meetingMode.name == current.mode) { "Changed final analysis meeting mode" }
                    choice.context
                }
                FinalAnalysisRouteChoice.Legacy -> return
                is FinalAnalysisRouteChoice.DslContextV2 -> {
                    check(choice.meetingMode.name == current.mode) { "Changed final analysis meeting mode" }
                    choice.context
                }
                is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                    check(choice.meetingMode.name == current.mode) { "Changed final analysis meeting mode" }
                    choice.context
                }
            }
        // Reuse the existing strict native-source reader; do not duplicate evidence SQL or invent sources.
        val snapshot = frozenSources.loadSnapshot(context)
        ScheduleIrV2Validator().validate(
            ScheduleIrV2Proposal(
                SCHEDULE_IR_V2_VERSION,
                snapshot.context.inputs.map { ProposedIrV2Result(it.submissionVersionId, emptyList()) },
            ),
            snapshot,
        )
    }

    private fun validateIdentity(route: FrozenFinalAnalysisRoute) {
        val identity: IrExecutionIdentity
        val context: ScheduleIrContext
        val policy: IrMatchingPolicy
        when (val choice = route.choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                identity = choice.identity
                context = choice.context
                policy = choice.policy
                require(
                    identity.responseContractVersion == SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT &&
                        identity.irSemanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION &&
                        choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
                ) { "Unsupported context-mode final analysis contract" }
                require(
                    policy == ScheduleDslContextModePolicy.contextual(policy.alternativeChoices),
                ) { "Unsupported context-mode service policy" }
            }
            is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                identity = choice.identity
                context = choice.context
                policy = choice.policy
                require(
                    identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT &&
                        identity.irSemanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION &&
                        choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
                ) { "Unsupported context-mode final analysis contract" }
                require(
                    policy == ScheduleDslContextModePolicy.contextual(policy.alternativeChoices),
                ) { "Unsupported context-mode service policy" }
            }
            FinalAnalysisRouteChoice.Legacy -> return
            is FinalAnalysisRouteChoice.DslContextV2 -> {
                identity = choice.identity
                context = choice.context
                policy = choice.policy
                require(
                    identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT &&
                        identity.irSemanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION,
                ) { "Unsupported final analysis contract" }
            }
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                identity = choice.identity
                context = choice.context
                policy = choice.policy
                require(
                    identity.responseContractVersion == SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT &&
                        identity.irSemanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION &&
                        choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
                ) { "Unsupported approved final analysis contract" }
                require(
                    policy == ApprovedScheduleDslMatchingPolicy.contextual(policy.alternativeChoices),
                ) { "Unsupported approved service policy" }
            }
        }
        require(context.runId == route.runId && context.batchId == route.batchId && context.roomId == route.roomId)
        require(policy.version.matches(TOKEN) && policy.version == identity.policyVersion && policy.version == context.policyVersion)
        require(
            IrInterpretation.UNRESOLVED !in policy.eligibleInterpretations,
        ) { "Unresolved interpretation is not an eligible service policy" }
        require(
            context.inputs.size in 2..50 &&
                context.inputs
                    .map { it.submissionVersionId }
                    .distinct()
                    .size == context.inputs.size &&
                context.inputs
                    .map { it.participantId }
                    .distinct()
                    .size == context.inputs.size,
        )
        val versions = context.inputs.map { it.submissionVersionId }.toSet()
        require(
            policy.alternativeChoices
                .map { it.submissionVersionId to it.group }
                .distinct()
                .size == policy.alternativeChoices.size,
        )
        require(
            policy.alternativeChoices.all {
                it.submissionVersionId in versions &&
                    it.group.matches(TOKEN) &&
                    it.alternative.matches(TOKEN)
            },
        )
    }

    private fun canonical(choice: FinalAnalysisRouteChoice): FinalAnalysisRouteChoice =
        when (choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 ->
                choice.copy(
                    policy =
                        choice.policy.copy(
                            eligibleInterpretations =
                                choice.policy.eligibleInterpretations
                                    .sortedBy { it.name }
                                    .toSet(),
                            alternativeChoices =
                                choice.policy.alternativeChoices.sortedWith(
                                    compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative }),
                                ),
                        ),
                )
            is FinalAnalysisRouteChoice.DslContextModeV4 ->
                choice.copy(
                    policy =
                        choice.policy.copy(
                            eligibleInterpretations =
                                choice.policy.eligibleInterpretations
                                    .sortedBy { it.name }
                                    .toSet(),
                            alternativeChoices =
                                choice.policy.alternativeChoices.sortedWith(
                                    compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative }),
                                ),
                        ),
                )
            FinalAnalysisRouteChoice.Legacy -> choice
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 ->
                choice.copy(
                    policy =
                        choice.policy.copy(
                            eligibleInterpretations =
                                choice.policy.eligibleInterpretations
                                    .sortedBy { it.name }
                                    .toSet(),
                            alternativeChoices =
                                choice.policy.alternativeChoices.sortedWith(
                                    compareBy({
                                        it.submissionVersionId.toString()
                                    }, { it.group }, { it.alternative }),
                                ),
                        ),
                )
            is FinalAnalysisRouteChoice.DslContextV2 ->
                choice.copy(
                    policy =
                        choice.policy.copy(
                            eligibleInterpretations =
                                choice.policy.eligibleInterpretations
                                    .sortedBy { it.name }
                                    .toSet(),
                            alternativeChoices =
                                choice.policy.alternativeChoices.sortedWith(
                                    compareBy({
                                        it.submissionVersionId.toString()
                                    }, { it.group }, { it.alternative }),
                                ),
                        ),
                )
        }

    private fun exists(
        sql: String,
        id: UUID,
    ) = jdbc.queryForObject(sql, Boolean::class.java, id) == true

    private data class CurrentRun(
        val roomId: UUID,
        val batchId: UUID,
        val fixedAt: Instant,
        val mode: String,
    )

    private data class StoredRoute(
        val batchId: UUID,
        val roomId: UUID,
        val kind: String,
        val format: Int,
        val fingerprint: String,
        val json: String,
        val selectedAt: Instant,
    )

    private data class StoredDslChoice(
        val identity: IrExecutionIdentity,
        val policy: IrMatchingPolicy,
        val context: ScheduleIrContext,
        val meetingMode: MeetingMode,
    )

    private data class StoredInvocation(
        val id: UUID,
        val runId: UUID,
        val version: Long,
        val startedAt: Instant,
        val deadlineAt: Instant,
        val owner: UUID,
        val finishedAt: Instant?,
        val winner: UUID?,
        val policyVersion: String,
    )

    private data class StoredApprovedChoice(
        val identity: IrExecutionIdentity,
        val policy: IrMatchingPolicy,
        val context: ScheduleIrContext,
        val meetingMode: MeetingMode,
        val vocabularyVersion: String,
    )

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
