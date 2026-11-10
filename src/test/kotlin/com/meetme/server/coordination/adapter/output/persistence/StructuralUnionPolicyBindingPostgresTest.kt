package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.application.service.ScheduleEvidenceSnapshotFactory
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_CONTEXT_MODE_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Frozen-version behavior, actual native rows and fresh PG reads; no model response or operational flag mutation. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class StructuralUnionPolicyBindingPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @BeforeEach
    fun seedNativeCohort() {
        seed()
    }

    @Test
    fun `SUPB01 default V5 selection freezes the old policy and enabling the new selector cannot relabel it`() {
        val selected = freezeWith(FinalAnalysisRouteSelectionService(runs, routes))
        val choice = selected.choice as FinalAnalysisRouteChoice.DslStructuralV5
        val immutable = routeRows()

        assertEquals(SCHEDULE_CONTEXT_MODE_POLICY_VERSION, choice.policy.version)
        assertEquals(choice.policy.version, choice.identity.policyVersion)
        assertEquals(choice.policy.version, choice.context.policyVersion)
        selector(true).requireExisting(F.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        assertEquals(selected, fresh().find(F.ir.run))
        assertEquals(immutable, routeRows())
        noProviderAdmission()
    }

    @Test
    fun `SUPB02 opt in V5 freezes and reloads the union policy in identity context and route fingerprint`() {
        val selected = freezeWith(selector(true))
        val choice = selected.choice as FinalAnalysisRouteChoice.DslStructuralV5
        val old = ScheduleDslContextModePolicy.contextual()
        val oldChoice =
            choice.copy(
                identity = choice.identity.copy(policyVersion = old.version),
                policy = old,
                context = snapshot(old).context,
            )

        assertEquals(SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION, choice.policy.version)
        assertEquals(choice.policy.version, choice.identity.policyVersion)
        assertEquals(choice.policy.version, choice.context.policyVersion)
        assertEquals(ScheduleDslStructuralMatchingPolicy.union(), choice.policy)
        assertEquals(choice.policy, ScheduleDslStructuralMatchingPolicy.forVersion(choice.policy.version))
        assertNotEquals(selected.fingerprint(), selected.copy(choice = oldChoice).fingerprint())
        assertEquals(selected, fresh().find(F.ir.run))
        assertEquals("DSL_STRUCTURAL_V5", jdbc.queryForObject("SELECT route_kind FROM final_analysis_routes", String::class.java))
        noProviderAdmission()
    }

    @Test
    fun `SUPB03 disabling the new selector preserves an already frozen union selection during closed room replay`() {
        val selected = freezeWith(selector(true))
        val immutable = routeRows()

        selector(false).requireExisting(F.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)

        assertEquals(selected, fresh().find(F.ir.run))
        assertEquals(immutable, routeRows())
        assertEquals(SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION, (selected.choice as FinalAnalysisRouteChoice.DslStructuralV5).policy.version)
        noProviderAdmission()
    }

    @Test
    fun `SUPB04 opting into V5 union leaves legacy untouched and V4 keeps its strict old policy boundary`() {
        val run = currentRun()
        val room = currentRoom()
        write {
            selector(true).freeze(run, room, FrozenSubmissionReader.read(submissions, run.batch), FinalAnalysisContractSelection.LEGACY)
        }
        assertNull(routes.find(F.ir.run))
        val selected = freezeWith(selector(true), FinalAnalysisContractSelection.DSL_CONTEXT_MODE_V4)
        val choice = selected.choice as FinalAnalysisRouteChoice.DslContextModeV4
        assertEquals(ScheduleDslContextModePolicy.contextual(), choice.policy)
        val union = ScheduleDslStructuralMatchingPolicy.union()
        val substituted =
            choice.copy(identity = choice.identity.copy(policyVersion = union.version), policy = union, context = snapshot(union).context)
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, substituted) }
        assertEquals(selected, fresh().find(F.ir.run))
        val unionEnvelope = envelope(union)
        assertFailsWith<RuntimeException> { ScheduleDslContextModeValidator().validateRestored(unionEnvelope.base, snapshot(union)) }
        noProviderAdmission()
    }

    @Test
    fun `SUPB05 unknown versions and noncanonical union policy parameters cannot acquire a source bound route`() {
        val union = ScheduleDslStructuralMatchingPolicy.union()
        assertFailsWith<RuntimeException> { ScheduleDslStructuralMatchingPolicy.forVersion("schedule-structural-union-unknown") }
        val invalid =
            listOf(
                union.copy(version = "schedule-structural-union-unknown"),
                union.copy(eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.UNRESOLVED)),
                union.copy(allowExplicitUnconstrained = false),
            )
        for (policy in invalid) {
            assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, choice(policy)) }
            assertNull(routes.find(F.ir.run))
        }
        noProviderAdmission()
    }

    @Test
    fun `SUPB06 mismatched identity context policy or substituted native binding cannot freeze despite a valid union policy`() {
        val selected = choice(ScheduleDslStructuralMatchingPolicy.union())
        val first = selected.context.inputs.first()
        val substitutions =
            listOf(
                selected.copy(identity = selected.identity.copy(policyVersion = SCHEDULE_CONTEXT_MODE_POLICY_VERSION)),
                selected.copy(context = selected.context.copy(policyVersion = SCHEDULE_CONTEXT_MODE_POLICY_VERSION)),
                selected.copy(
                    context =
                        selected.context.copy(
                            inputs =
                                listOf(first.copy(contentSha256 = "0".repeat(64))) + selected.context.inputs.drop(1),
                        ),
                ),
            )
        for (substitution in substitutions) {
            assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, substitution) }
            assertNull(routes.find(F.ir.run))
        }
        noProviderAdmission()
    }

    @Test
    fun `SUPB07 a recomputed matching route hash cannot approve corrupted stored union policy parameters`() {
        val route = freezeWith(selector(true))
        val selected = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        val changed = selected.copy(policy = selected.policy.copy(allowExplicitUnconstrained = false))
        val fingerprint = route.copy(choice = changed).fingerprint()
        val json =
            mapper.writeValueAsString(
                linkedMapOf(
                    "identity" to changed.identity,
                    "policy" to changed.policy,
                    "context" to changed.context,
                    "meetingMode" to changed.meetingMode,
                    "vocabularyVersion" to changed.vocabularyVersion,
                ),
            )
        jdbc.update(
            "UPDATE final_analysis_routes SET selection=CAST(? AS jsonb),route_fingerprint=? WHERE run_id=?",
            json,
            fingerprint,
            F.ir.run,
        )
        val corrupted = routeRows()

        assertFailsWith<RuntimeException> { fresh().find(F.ir.run) }

        assertEquals(corrupted, routeRows(), "Reading cannot repair or reinterpret an invalid stored policy")
        noProviderAdmission()
    }

    @Test
    fun `SUPB08 fresh stored calculation uses each frozen policy after the selector flag changes without rewriting either document`() {
        val old = ScheduleDslContextModePolicy.contextual()
        val union = ScheduleDslStructuralMatchingPolicy.union()
        for (policy in listOf(old, union)) {
            seed()
            val route = freezeWith(selector(policy.version == union.version))
            val declaration = envelope(policy)
            JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(declaration, 1, F.selectedAt)
            jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=2,candidate_quality='COMPLETE' WHERE id=?", F.ir.run)
            val immutable = storedRows()
            selector(policy.version != union.version).requireExisting(F.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
            val restored = assertNotNull(JdbcScheduleDslStructuralRepository(jdbc, mapper).find(snapshot(policy).context))
            val calculated = reader.readStored(CoordinationRunId(F.ir.run), 2, route.fingerprint())
            val actual =
                calculated.calculation.prepared.participants
                    .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                    .availableTimes
                    .toSet()
            val expected = if (policy.version == union.version) setOf(MORNING, AFTERNOON) else setOf(MORNING)

            assertEquals(expected, actual)
            assertEquals(
                expected,
                calculated.calculation.recommendations.options
                    .map { it.window }
                    .toSet(),
            )
            assertEquals(route, calculated.route)
            assertEquals(declaration, restored)
            assertEquals(policy.version, restored.context.policyVersion)
            assertEquals(calculated, reader.readStored(CoordinationRunId(F.ir.run), 2, route.fingerprint()))
            assertEquals(immutable, storedRows())
            val other = if (policy.version == union.version) old else union
            assertFailsWith<RuntimeException> { JdbcScheduleDslStructuralRepository(jdbc, mapper).find(snapshot(other).context) }
            noProviderAdmission()
        }
    }

    private fun seed() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper, nativeInput(ScheduleDslContextModePolicy.contextual()))
    }

    private fun nativeInput(policy: IrMatchingPolicy): ScheduleDslOfflineInput =
        H.input(FIRST_TEXT).let { source ->
            source.copy(
                policy = policy,
                meetingMode = MeetingMode.REMOTE,
                inputs = source.inputs.mapIndexed { index, value -> if (index == 0) value else value.copy(rawText = SECOND_TEXT) },
            )
        }

    private fun currentRun() = assertNotNull(runs.findById(CoordinationRunId(F.ir.run)))

    private fun currentRoom() = assertNotNull(rooms.findById(MeetingRoomId(F.ir.room)))

    private fun snapshot(policy: IrMatchingPolicy): ScheduleEvidenceSnapshot =
        ScheduleEvidenceSnapshotFactory().create(
            currentRun(),
            FrozenSubmissionReader.read(submissions, currentRun().batch),
            currentRoom().timeZone.value,
            currentRoom().searchRange.startInclusive,
            currentRoom().searchRange.endExclusive,
            policy.version,
        )

    private fun selector(enabled: Boolean) = FinalAnalysisRouteSelectionService(runs, routes, unionPreferredAvailability = enabled)

    private fun freezeWith(
        service: FinalAnalysisRouteSelectionService,
        selection: FinalAnalysisContractSelection = FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
    ): FrozenFinalAnalysisRoute {
        val run = currentRun()
        write { service.freeze(run, currentRoom(), FrozenSubmissionReader.read(submissions, run.batch), selection) }
        return assertNotNull(routes.find(F.ir.run))
    }

    private fun choice(policy: IrMatchingPolicy): FinalAnalysisRouteChoice.DslStructuralV5 =
        FinalAnalysisRouteChoice.DslStructuralV5(
            IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", policy.version),
            policy,
            snapshot(policy).context,
            MeetingMode.REMOTE,
            "ko-time-terms-1",
        )

    private fun envelope(policy: IrMatchingPolicy): ScheduleDslStructuralEnvelope {
        val input = nativeInput(policy)
        val allow = H.claim(input, "morning", scope = H.scope("2027-01-08T09:00", "2027-01-08T10:00"))
        val prefer = H.claim(input, "afternoon", effect = IrEffect.PREFERRED, scope = H.scope("2027-01-08T14:00", "2027-01-08T15:00"))
        val program =
            H.program(
                listOf(H.ref(input, "allow-ref", "morning"), H.ref(input, "prefer-ref", "afternoon")),
                "allow-ref",
                prefer = "prefer-ref",
            )
        val proposed = H.proposal(input, listOf(allow, prefer), program, emptyList())
        val peer = H.claim(input, "peer", scope = H.scope("2027-01-08T08:00", "2027-01-08T18:00"), index = 1)
        val complete =
            proposed.copy(
                base =
                    proposed.base.copy(
                        claims =
                            proposed.base.claims.copy(
                                results =
                                    listOf(
                                        proposed.base.claims.results
                                            .first(),
                                        ProposedIrV2Result(F.ir.second, listOf(peer)),
                                    ),
                            ),
                    ),
            )
        val document = H.document(complete)
        document["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        val results = document.getValue("results") as List<MutableMap<String, Any?>>
        results.forEach {
            it["mode_restrictions"] = emptyList<Any>()
            it["directional_times"] = emptyList<Any>()
        }
        return StrictScheduleDslStructuralResponseParser(mapper).parse(
            H.json(requireNotNull(locators(document))),
            snapshot(policy),
            "schedule-dsl-response-5",
            "schedule-ir-product-6",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )
    }

    private fun locators(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                if ("start_code_point" in value) {
                    value.filterKeys { it in setOf("source_id", "revision", "quote") }
                } else {
                    value.entries.associate { it.key.toString() to locators(it.value) }
                }
            is List<*> -> value.map(::locators)
            else -> value
        }

    private fun fresh(): FinalAnalysisRouteRegistry =
        JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(F.selectedAt, ZoneOffset.UTC))

    private fun write(action: () -> Unit) = TransactionTemplate(transactions).executeWithoutResult { action() }

    private fun routeRows() = F.rows(jdbc, "final_analysis_routes")

    private fun storedRows() =
        listOf("final_analysis_routes", "schedule_ir_envelopes", "schedule_ir_inputs").associateWith { F.rows(jdbc, it) }

    private fun noProviderAdmission() {
        for (table in listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations")) {
            assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java),
                "Version binding must not admit a provider call",
            )
        }
    }

    companion object {
        private const val FIRST_TEXT = "🙂 2027-01-08 09:00~10:00 가능. 2027-01-08 14:00~15:00 선호."
        private const val SECOND_TEXT = "2027-01-08 08:00~18:00 가능."
        private val MORNING = InstantTimeRange(Instant.parse("2027-01-08T00:00:00Z"), Instant.parse("2027-01-08T01:00:00Z"))
        private val AFTERNOON = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T06:00:00Z"))
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
