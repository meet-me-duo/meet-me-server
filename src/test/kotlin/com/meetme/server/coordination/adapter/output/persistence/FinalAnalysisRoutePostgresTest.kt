package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.adapter.output.integration.ScheduleDslProviderContract
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrV2Repository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.ProposedIrResult
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrProposal
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import com.meetme.server.submission.domain.ir.ScheduleIrValidator
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Actual Flyway/PostgreSQL transactions; no parser/provider/map call or replacement budget implementation. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class FinalAnalysisRoutePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var legacy: GeminiProcessingPersistenceService

    @BeforeEach
    fun pristineNativeBatch() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper)
    }

    @Test
    fun `FARP01 absent selection defaults to explicit legacy with zero other writes`() {
        val before = F.historicalSnapshot(jdbc)
        assertNull(routes.find(F.ir.run))
        val frozen = routes.freeze(F.ir.run, 1)
        assertEquals(FinalAnalysisRouteChoice.Legacy, frozen.choice)
        assertEquals(F.ir.run, frozen.runId)
        assertEquals(F.ir.batch, frozen.batchId)
        assertEquals(F.ir.room, frozen.roomId)
        assertEquals(F.selectedAt, frozen.selectedAt)
        assertEquals(frozen, fresh().find(F.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(1, count("final_analysis_routes"))
    }

    @Test
    fun `FARP02 explicit DSL stores full native choice and survives a new adapter instance`() {
        val before = F.historicalSnapshot(jdbc)
        val choice = F.choice()
        val route = routes.freeze(F.ir.run, 1, choice)
        assertEquals(choice, route.choice)
        assertEquals(route, fresh().find(F.ir.run))
        assertEquals(route.fingerprint(), fresh().find(F.ir.run)!!.fingerprint())
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals("DSL_CONTEXT_V2", jdbc.queryForObject("SELECT route_kind FROM final_analysis_routes", String::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT format_version FROM final_analysis_routes", Int::class.java))
    }

    @Test
    fun `FARP03 repeated same choice keeps first timestamp and selection bytes`() {
        val route = routes.freeze(F.ir.run, 1, F.choice())
        val before = F.rows(jdbc, "final_analysis_routes")
        val later = fresh(F.selectedAt.plusSeconds(3600)).freeze(F.ir.run, 1, F.choice())
        assertEquals(route, later)
        assertEquals(before, F.rows(jdbc, "final_analysis_routes"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["route", "policy", "context", "mode"])
    fun `FARP04 first selected choice cannot be replaced`(field: String) {
        val original = F.choice()
        routes.freeze(F.ir.run, 1, original)
        val before = F.rows(jdbc, "final_analysis_routes")
        val replacement =
            when (field) {
                "route" -> FinalAnalysisRouteChoice.Legacy
                "policy" -> original.copy(policy = original.policy.copy(allowExplicitUnconstrained = true))
                "context" -> original.copy(context = original.context.copy(searchEnd = original.context.searchEnd.plusDays(1)))
                else -> original.copy(meetingMode = MeetingMode.IN_PERSON)
            }
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, replacement) }
        assertEquals(before, F.rows(jdbc, "final_analysis_routes"))
    }

    @Test
    fun `FARP05 freeze participates in the caller transaction and rolls back without run transitions`() {
        val before = F.historicalSnapshot(jdbc)
        TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).executeWithoutResult { tx ->
            routes.freeze(F.ir.run, 1, F.choice())
            assertNotNull(routes.find(F.ir.run))
            tx.setRollbackOnly()
        }
        assertNull(routes.find(F.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
    }

    @Test
    fun `FARP06 same run retry grows attempt version while route identity remains fixed`() {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        val first = F.invocation()
        invocations.insert(first)
        val oldBinding = routes.bindAttempt(frozen, first)
        routes.requireDispatchCurrent(oldBinding)
        val original = F.rows(jdbc, "final_analysis_routes")
        val run = requireNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        val delayed = run.delayAnalysis()
        runs.update(delayed)
        val queued = delayed.retryAnalysis()
        runs.update(queued)
        val retried = queued.startStructuring()
        runs.update(retried)
        assertTrue(retried.version > first.runVersion)
        assertEquals(frozen, routes.freeze(F.ir.run, retried.version, F.choice()))
        assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(oldBinding) }
        assertFailsWith<RuntimeException> { routes.bindAttempt(frozen, first) }
        val next = F.invocation(retried.version)
        invocations.insert(next)
        val newBinding = routes.bindAttempt(frozen, next)
        routes.requireDispatchCurrent(newBinding)
        assertEquals(frozen, newBinding.route)
        assertEquals(next, newBinding.invocation)
        assertEquals(original, F.rows(jdbc, "final_analysis_routes"))
        assertEquals(2, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
    }

    @Test
    fun `FARP07 arbitrary invocation value without an existing durable row grants nothing`() {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        assertFailsWith<RuntimeException> { routes.bindAttempt(frozen, F.invocation()) }
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["id", "owner", "run", "version", "started", "deadline"])
    fun `FARP08 every attempt ownership identity is checked against persisted invocation`(field: String) {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        val invocation = F.invocation()
        invocations.insert(invocation)
        val changed =
            when (field) {
                "id" -> invocation.copy(id = UUID(0, 9001))
                "owner" -> invocation.copy(ownerToken = UUID(0, 9002))
                "run" -> invocation.copy(runId = CoordinationRunId(UUID(0, 9003)))
                "version" -> invocation.copy(runVersion = 2)
                "started" -> invocation.copy(startedAt = invocation.startedAt.minusSeconds(1))
                else -> invocation.copy(deadlineAt = invocation.deadlineAt.plusSeconds(1))
            }
        assertFailsWith<RuntimeException> { routes.bindAttempt(frozen, changed) }
        val binding = routes.bindAttempt(frozen, invocation)
        assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(binding.copy(invocation = changed)) }
        assertEquals(invocation, invocations.findByRunVersion(invocation.runId, invocation.runVersion))
    }

    @ParameterizedTest
    @ValueSource(strings = ["owner", "finished", "deadline", "version", "active", "revision", "matching", "completed"])
    fun `FARP09 dispatch rechecks durable ownership and current state after binding`(change: String) {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        val invocation = F.invocation()
        invocations.insert(invocation)
        val binding = routes.bindAttempt(frozen, invocation)
        when (change) {
            "owner" -> jdbc.update("UPDATE analysis_invocations SET owner_token=? WHERE id=?", UUID(0, 9901), invocation.id)
            "finished" -> invocations.update(invocation.copy(finishedAt = F.selectedAt.plusSeconds(1)))
            "deadline" -> {
                assertFailsWith<RuntimeException> {
                    fresh(invocation.deadlineAt).requireDispatchCurrent(binding)
                }
                return
            }
            "version" -> jdbc.update("UPDATE coordination_runs SET version=version+1 WHERE id=?", F.ir.run)
            "active" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", F.ir.room)
            "revision" -> openRevision()
            "matching" -> jdbc.update("UPDATE coordination_runs SET status='MATCHING',version=2 WHERE id=?", F.ir.run)
            "completed" ->
                jdbc.update(
                    "UPDATE coordination_runs SET status='COMPLETED',candidate_quality='PARTIAL',version=2 WHERE id=?",
                    F.ir.run,
                )
        }
        val before = F.historicalSnapshot(jdbc)
        assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(binding) }
        assertEquals(before, F.historicalSnapshot(jdbc))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MATCHING", "COMPLETED", "ANALYSIS_DELAYED", "DEAD_LETTERED"])
    fun `FARP10 stored route discovery never makes non-dispatch states eligible for new freeze`(status: String) {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        jdbc.update(
            """
            UPDATE coordination_runs SET status=?,
                candidate_quality=CASE WHEN ?='COMPLETED' THEN 'PARTIAL' ELSE NULL END,version=2 WHERE id=?
            """.trimIndent(),
            status,
            status,
            F.ir.run,
        )
        assertEquals(frozen, fresh().find(F.ir.run))
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 2, F.choice()) }
        assertFailsWith<RuntimeException> { routes.bindAttempt(frozen, F.invocation(2)) }
        assertEquals(0, count("analysis_invocations"))
    }

    @Test
    fun `FARP11 queued freeze does not advance the run`() {
        jdbc.update("UPDATE coordination_runs SET status='QUEUED',version=0 WHERE id=?", F.ir.run)
        val before = F.historicalSnapshot(jdbc)
        val frozen = routes.freeze(F.ir.run, 0, F.choice())
        assertEquals(F.choice(), frozen.choice)
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertFailsWith<RuntimeException> { routes.bindAttempt(frozen, F.invocation(0)) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "version", "active", "revision", "selected-before-batch", "input-content", "locale",
            "input-owner", "source-answer", "zone", "search", "mode", "membership",
        ],
    )
    fun `FARP12 first DSL freeze checks current native bindings rather than trusting a plausible context`(change: String) {
        val choice = F.choice()
        var expectedVersion = 1L
        var registry = routes
        when (change) {
            "version" -> expectedVersion = 0
            "active" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", F.ir.room)
            "revision" -> openRevision()
            "zone" -> jdbc.update("UPDATE meeting_rooms SET time_zone_id='UTC' WHERE id=?", F.ir.room)
            "search" -> jdbc.update("UPDATE meeting_rooms SET search_end_date='2027-01-12' WHERE id=?", F.ir.room)
            "mode" -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", F.ir.room)
            "membership" ->
                jdbc.update(
                    "DELETE FROM submission_batch_items WHERE batch_id=? AND submission_version_id=?",
                    F.ir.batch,
                    F.ir.second,
                )
            "selected-before-batch" ->
                registry =
                    fresh(
                        F.q
                            .input2()
                            .batchFixedAt
                            .minusSeconds(1),
                    )
            "input-content" -> jdbc.update("UPDATE submission_versions SET raw_text='Changed synthetic source' WHERE id=?", F.ir.first)
            "locale" -> jdbc.update("UPDATE submission_versions SET locale='en-US' WHERE id=?", F.ir.first)
            "input-owner" -> {
                val forged =
                    choice.context.inputs
                        .first()
                        .copy(participantId = UUID(0, 9992))
                val altered = choice.copy(context = choice.context.copy(inputs = listOf(forged) + choice.context.inputs.drop(1)))
                assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, altered) }
                assertNull(routes.find(F.ir.run))
                return
            }
            "source-answer" ->
                jdbc.update(
                    """
                    UPDATE submission_versions
                    SET input_evidence=jsonb_set(input_evidence,'{supplement,answerText}','"Changed synthetic answer"') WHERE id=?
                    """.trimIndent(),
                    F.ir.first,
                )
        }
        assertFailsWith<RuntimeException> { registry.freeze(F.ir.run, expectedVersion, choice) }
        assertNull(routes.find(F.ir.run))
    }

    @Test
    fun `FARP13 existing format1 ticket remains byte-identical and cannot become a new route`() {
        val old = JdbcScheduleIrExecutionRegistry(jdbc, mapper)
        val ticket = F.oldTicket()
        old.bind(ticket)
        val before = F.rows(jdbc, "schedule_ir_execution_bindings")
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, F.choice()) }
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1) }
        assertNull(routes.find(F.ir.run))
        assertEquals(ticket, old.find(F.ir.run))
        old.requireCurrent(ticket)
        jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", F.ir.run)
        assertFailsWith<RuntimeException> { old.requireCurrent(ticket) }
        assertEquals(before, F.rows(jdbc, "schedule_ir_execution_bindings"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["IR1", "IR2", "DSL3"])
    fun `FARP14 real unbound historical envelope cannot be adopted by either new route`(kind: String) {
        val snapshot = F.q.snapshot()
        when (kind) {
            "IR1" -> {
                val proposal =
                    ScheduleIrProposal(
                        SCHEDULE_IR_VERSION,
                        snapshot.context.inputs.map {
                            ProposedIrResult(
                                it.submissionVersionId,
                                IrAvailabilityState.UNKNOWN,
                                emptyList(),
                            )
                        },
                    )
                val envelope = ScheduleIrValidator().validate(proposal, snapshot)
                val repository = JdbcScheduleIrRepository(jdbc, mapper)
                repository.publish(envelope, 1, F.selectedAt)
                assertEquals(envelope, repository.find(snapshot.context))
            }
            "IR2" -> {
                val proposal =
                    ScheduleIrV2Proposal(
                        SCHEDULE_IR_V2_VERSION,
                        snapshot.context.inputs.map {
                            ProposedIrV2Result(it.submissionVersionId, emptyList())
                        },
                    )
                val envelope = ScheduleIrV2Validator().validate(proposal, snapshot)
                val repository = JdbcScheduleIrV2Repository(jdbc, mapper)
                repository.publishV2(envelope, 1, F.selectedAt)
                assertEquals(envelope, repository.findV2(snapshot.context))
            }
            else -> {
                val envelope =
                    ScheduleDslProviderContract(mapper)
                        .parseProviderResponse(
                            F.q.wire.json(),
                            snapshot,
                            SCHEDULE_DSL_SEMANTIC_VERSION,
                            SCHEDULE_AST_VERSION,
                        )
                val repository = JdbcScheduleDslRepository(jdbc, mapper)
                repository.publish(envelope, 1, F.selectedAt)
                assertEquals(envelope, repository.find(snapshot.context))
            }
        }
        val before = F.historicalSnapshot(jdbc)
        assertEquals(1, count("schedule_ir_envelopes"))
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, F.choice()) }
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1) }
        assertNull(routes.find(F.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
    }

    @ParameterizedTest
    @ValueSource(strings = ["invocation", "structured"])
    fun `FARP15 legacy history stays legacy and cannot be relabeled DSL`(history: String) {
        if (history == "invocation") {
            invocations.insert(F.invocation())
        } else {
            jdbc.update(
                """
                INSERT INTO structured_submission_results(batch_id,submission_version_id,conditions,rejection_code,processed_at)
                VALUES (?,?,'[]'::jsonb,'AMBIGUOUS_INPUT',?)
                """.trimIndent(),
                F.ir.batch,
                F.ir.first,
                Timestamp.from(F.selectedAt),
            )
        }
        val before = F.historicalSnapshot(jdbc)
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, F.choice()) }
        assertNull(routes.find(F.ir.run))
        val legacyRoute = routes.freeze(F.ir.run, 1)
        assertEquals(FinalAnalysisRouteChoice.Legacy, legacyRoute.choice)
        assertEquals(before, F.historicalSnapshot(jdbc))
    }

    @Test
    fun `FARP16 concurrent conflicting freezes choose exactly one immutable route`() {
        val outcomes =
            parallel(
                { routes.freeze(F.ir.run, 1, F.choice()) },
                { routes.freeze(F.ir.run, 1) },
            )
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is RuntimeException })
        val winner = outcomes.single { it.isSuccess }.getOrThrow()
        assertEquals(winner, fresh().find(F.ir.run))
        assertEquals(1, count("final_analysis_routes"))
        assertEquals(0, count("analysis_invocations"))
    }

    @Test
    fun `FARP17 concurrent equal freezes return the same original route`() {
        val outcomes =
            parallel(
                { routes.freeze(F.ir.run, 1, F.choice()) },
                { routes.freeze(F.ir.run, 1, F.choice()) },
            )
        assertTrue(outcomes.all { it.isSuccess })
        assertEquals(outcomes[0].getOrThrow(), outcomes[1].getOrThrow())
        assertEquals(1, count("final_analysis_routes"))
    }

    @Test
    fun `FARP18 DSL selection prevents legacy start and claim before any state or invocation change`() {
        jdbc.update("UPDATE coordination_runs SET status='QUEUED',version=0 WHERE id=?", F.ir.run)
        routes.freeze(F.ir.run, 0, F.choice())
        val queued = requireNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        val before = F.historicalSnapshot(jdbc)
        assertNull(legacy.start(queued))
        assertEquals(before, F.historicalSnapshot(jdbc))
        jdbc.update("UPDATE coordination_runs SET status='STRUCTURING',version=1 WHERE id=?", F.ir.run)
        val current = requireNotNull(runs.findById(queued.id))
        val started = F.historicalSnapshot(jdbc)
        assertNull(legacy.claimInvocation(current, UUID(0, 9501), Duration.ofSeconds(40)))
        assertEquals(started, F.historicalSnapshot(jdbc))
        assertEquals(0, count("coordination_attempts"))
    }

    @Test
    fun `FARP19 unchanged legacy caller freezes legacy then claims exactly the existing invocation budget`() {
        val run = requireNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        val invocation = assertNotNull(legacy.claimInvocation(run, UUID(0, 9502), Duration.ofSeconds(40)))
        assertEquals(FinalAnalysisRouteChoice.Legacy, routes.find(F.ir.run)!!.choice)
        assertEquals(run.version, invocation.runVersion)
        assertEquals(1, count("analysis_invocations"))
        assertEquals(0, invocation.geminiAttempts)
        assertEquals(0, invocation.lunaAttempts)
        assertNull(legacy.claimInvocation(run, UUID(0, 9503), Duration.ofSeconds(40)))
        assertEquals(1, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
    }

    @Test
    fun `FARP20 real legacy admission races explicit DSL freeze under the same current-run lock`() {
        val run = requireNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        val outcomes =
            parallel<Any?>(
                { routes.freeze(F.ir.run, 1, F.choice()) },
                { legacy.claimInvocation(run, UUID(0, 9601), Duration.ofSeconds(40)) },
            )
        val final = assertNotNull(routes.find(F.ir.run))
        when (final.choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> error("Unexpected structural route in the legacy context2 race")
            is FinalAnalysisRouteChoice.DslContextModeV4 -> TODO("TDD RED: C4 FinalAnalysisRoutePostgresTest")
            FinalAnalysisRouteChoice.Legacy -> {
                assertTrue(outcomes[0].isFailure)
                assertNotNull(outcomes[1].getOrThrow())
                assertEquals(1, count("analysis_invocations"))
            }
            is FinalAnalysisRouteChoice.DslContextV2 -> {
                assertTrue(outcomes[0].isSuccess)
                assertNull(outcomes[1].getOrThrow())
                assertEquals(0, count("analysis_invocations"))
            }
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> error("Unexpected approved route in the legacy context2 race")
        }
        assertEquals(1, count("final_analysis_routes"))
        assertEquals(0, count("coordination_attempts"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["format", "kind", "fingerprint", "unknown", "scalar"])
    fun `FARP21 corrupt stored route is rejected without legacy fallback`(change: String) {
        routes.freeze(F.ir.run, 1, F.choice())
        when (change) {
            "format" -> jdbc.update("UPDATE final_analysis_routes SET format_version=99")
            "kind" -> jdbc.update("UPDATE final_analysis_routes SET route_kind='LEGACY'")
            "fingerprint" -> jdbc.update("UPDATE final_analysis_routes SET route_fingerprint=?", "f".repeat(64))
            "unknown" -> jdbc.update("UPDATE final_analysis_routes SET selection=selection || '{\"unexpected\":true}'::jsonb")
            "scalar" -> {
                assertEquals(
                    "string",
                    jdbc.queryForObject(
                        "SELECT jsonb_typeof(selection #> '{context,runId}') FROM final_analysis_routes",
                        String::class.java,
                    ),
                )
                jdbc.update("UPDATE final_analysis_routes SET selection=jsonb_set(selection,'{context,runId}','17'::jsonb)")
            }
        }
        assertFailsWith<RuntimeException> { fresh().find(F.ir.run) }
        assertEquals(0, count("analysis_invocations"))
    }

    @Test
    fun `FARP22 same owned invocation remains current after existing budget counters advance`() {
        val route = routes.freeze(F.ir.run, 1, F.choice())
        val invocation = F.invocation()
        invocations.insert(invocation)
        val binding = routes.bindAttempt(route, invocation)
        invocations.update(invocation.copy(geminiAttempts = 1))
        routes.requireDispatchCurrent(binding)
        assertEquals(1, invocations.findByRunVersion(invocation.runId, invocation.runVersion)!!.geminiAttempts)
        assertEquals(0, count("coordination_attempts"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["input", "zone", "mode"])
    fun `FARP23 native context drift after binding prevents dispatch with no replacement route`(change: String) {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        val invocation = F.invocation()
        invocations.insert(invocation)
        val binding = routes.bindAttempt(frozen, invocation)
        when (change) {
            "input" -> jdbc.update("UPDATE submission_versions SET raw_text='Changed synthetic source' WHERE id=?", F.ir.first)
            "zone" -> jdbc.update("UPDATE meeting_rooms SET time_zone_id='UTC' WHERE id=?", F.ir.room)
            else -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", F.ir.room)
        }
        val before = F.historicalSnapshot(jdbc)
        assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(binding) }
        assertEquals(frozen, routes.find(F.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
    }

    private fun openRevision() {
        val revision = UUID(0, 9801)
        jdbc.update(
            """
            INSERT INTO input_revision_rounds(id,room_id,generation,source_run_id,reopen_request_id,
                expected_generation,status,opened_at) VALUES (?,?,1,?,?,0,'OPEN',?)
            """.trimIndent(),
            revision,
            F.ir.room,
            F.ir.run,
            UUID(0, 9802),
            Timestamp.from(F.selectedAt),
        )
        jdbc.update("UPDATE meeting_rooms SET active_revision_round_id=?,revision_generation=1 WHERE id=?", revision, F.ir.room)
    }

    private fun fresh(at: java.time.Instant = F.selectedAt): FinalAnalysisRouteRegistry =
        JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(at, ZoneOffset.UTC))

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun <T> parallel(
        first: () -> T,
        second: () -> T,
    ): List<Result<T>> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val futures =
                listOf(first, second).map { work ->
                    pool.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(30, TimeUnit.SECONDS)) { "Synthetic race start was not released" }
                            runCatching(work)
                        },
                    )
                }
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RouteTestClock {
        @Bean @Primary
        fun routeUnitTestClock(): Clock = Clock.fixed(F.selectedAt, ZoneOffset.UTC)

        @Bean
        fun routeUnitRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
            clock: Clock,
        ): FinalAnalysisRouteRegistry = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, clock)
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
            registry.add("meetme.gemini.api-key") { "" }
            registry.add("meetme.openai.api-key") { "" }
            registry.add("meetme.typesafe.api-key") { "" }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
