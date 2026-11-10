package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** Month facts change the frozen producer contract; canonical evidence and owner-bound date guards remain intact. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class CalendarFactsRoutePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private fun input(foreignOutside: Boolean = false) =
        S.input(RELATIVE_TEXT).let { native ->
            native.copy(
                inputs =
                    native.inputs.mapIndexed { index, item ->
                        if (index == 0 || !foreignOutside) item else item.copy(rawText = "$OUTSIDE 14:00~18:00 가능.")
                    },
            )
        }

    @BeforeEach
    fun nativeRows() {
        seed()
    }

    private fun seed(foreignOutside: Boolean = false) {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper, input(foreignOutside))
    }

    private fun selector(
        calendar: Boolean,
        serverReferences: Boolean = true,
        scopedDates: Boolean = true,
    ) = FinalAnalysisRouteSelectionService(
        runs,
        routes,
        serverEvidenceReferences = serverReferences,
        searchRelativeDates = scopedDates,
        calendarMonthFacts = calendar,
    )

    private fun freeze(calendar: Boolean) =
        currentRun().let { run ->
            TransactionTemplate(transactions).executeWithoutResult {
                selector(calendar).freeze(
                    run,
                    currentRoom(),
                    FrozenSubmissionReader.read(submissions, run.batch),
                    FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
                )
            }
            assertNotNull(routes.find(H.ir.run))
        }

    private fun currentRun() = assertNotNull(runs.findById(CoordinationRunId(H.ir.run)))

    private fun currentRoom() = assertNotNull(rooms.findById(MeetingRoomId(H.ir.room)))

    private fun freshRoutes() = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(F.selectedAt, ZoneOffset.UTC))

    @Test
    fun `CFR01 enabled month facts freeze response3 in identity and fingerprint while flags cannot relabel that selection`() {
        val route = freeze(calendar = true)
        val choice = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(CALENDAR_RESPONSE, choice.identity.responseContractVersion)
        val previous = choice.copy(identity = choice.identity.copy(responseContractVersion = SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT))
        assertNotEquals(route.fingerprint(), route.copy(choice = previous).fingerprint())
        val before = F.historicalSnapshot(jdbc)
        val frozenRows = F.rows(jdbc, "final_analysis_routes")

        selector(calendar = false, serverReferences = false, scopedDates = false)
            .requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)

        assertEquals(route, freshRoutes().find(H.ir.run))
        assertEquals(frozenRows, F.rows(jdbc, "final_analysis_routes"))
        assertEquals(before, F.historicalSnapshot(jdbc))
        noProviderAdmission()
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false")
    fun `CFR02 month facts cannot freeze a V5 contract without both source references and scoped dates`(
        serverReferences: Boolean,
        scopedDates: Boolean,
    ) {
        val run = currentRun()
        val before = F.historicalSnapshot(jdbc)
        assertFailsWith<RuntimeException> {
            TransactionTemplate(transactions).executeWithoutResult {
                selector(calendar = true, serverReferences = serverReferences, scopedDates = scopedDates).freeze(
                    run,
                    currentRoom(),
                    FrozenSubmissionReader.read(submissions, run.batch),
                    FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
                )
            }
        }

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_analysis_routes", Int::class.java))
        assertEquals(before, F.historicalSnapshot(jdbc))
        noProviderAdmission()
    }

    @Test
    fun `CFR03 enabling month facts cannot rewrite a previously frozen response2 contract`() {
        val old = freeze(calendar = false)
        assertEquals(
            SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
            (old.choice as FinalAnalysisRouteChoice.DslStructuralV5).identity.responseContractVersion,
        )
        val before = F.historicalSnapshot(jdbc)
        val frozenRows = F.rows(jdbc, "final_analysis_routes")

        selector(calendar = true).requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        assertFailsWith<RuntimeException> { freeze(calendar = true) }

        assertEquals(old, freshRoutes().find(H.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(frozenRows, F.rows(jdbc, "final_analysis_routes"))
        noProviderAdmission()
    }

    @Test
    fun `CFR04 a successor retains response3 when current selector flags are all disabled`() {
        val source = currentRun()
        val sourceRoute = freeze(calendar = true)
        val oldChoice = sourceRoute.choice as FinalAnalysisRouteChoice.DslStructuralV5
        val sourceRows = sourceRouteRow(H.ir.run)
        val nextRun = UUID(0, 8401)
        val nextBatch = UUID(0, 8402)
        val fixedAt = source.batch.fixedAt.plusSeconds(1)
        jdbc.update(
            "INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)",
            nextBatch,
            H.ir.room,
            Timestamp.from(fixedAt),
        )
        jdbc.update(
            "INSERT INTO submission_batch_items(batch_id,submission_version_id) SELECT ?,submission_version_id FROM submission_batch_items WHERE batch_id=?",
            nextBatch,
            source.batch.id.value,
        )
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            nextRun,
            H.ir.room,
            nextBatch,
            Timestamp.from(fixedAt),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", nextRun, H.ir.room)
        val successor = assertNotNull(runs.findById(CoordinationRunId(nextRun)))
        val native = FrozenSubmissionReader.read(submissions, successor.batch)

        TransactionTemplate(transactions).executeWithoutResult {
            selector(calendar = false, serverReferences = false, scopedDates = false)
                .propagate(source, successor, currentRoom(), native)
        }

        val propagated = assertNotNull(freshRoutes().find(nextRun))
        val propagatedChoice = propagated.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(oldChoice.identity, propagatedChoice.identity)
        assertEquals(CALENDAR_RESPONSE, propagatedChoice.identity.responseContractVersion)
        assertEquals(oldChoice.policy, propagatedChoice.policy)
        assertEquals(nextRun, propagatedChoice.context.runId)
        assertEquals(nextBatch, propagatedChoice.context.batchId)
        assertNotEquals(sourceRoute.fingerprint(), propagated.fingerprint())
        assertEquals(sourceRoute, freshRoutes().find(H.ir.run))
        assertEquals(sourceRows, sourceRouteRow(H.ir.run))
        noProviderAdmission()
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `CFR05 response2 and response3 use the same native format11 and owner guard in every stored read`(
        calendar: Boolean,
        foreignOutside: Boolean,
    ) {
        seed(foreignOutside)
        val route = freeze(calendar)
        val native = input(foreignOutside)
        val snapshot = H.snapshot(native)
        val day = if (foreignOutside) OUTSIDE else "2027-01-08"
        val claim = H.claim(native, "possible", scope = H.scope("${day}T14:00", "${day}T16:00"))
        val program = H.program(listOf(H.ref(native, "allow-ref", "possible")), "allow-ref")
        val proposal =
            H.proposal(native, listOf(claim), program, emptyList()).let { original ->
                original.copy(
                    base =
                        original.base.copy(
                            claims =
                                original.base.claims.copy(
                                    results =
                                        original.base.claims.results.map { result ->
                                            if (result.submissionVersionId != H.ir.second) {
                                                result
                                            } else {
                                                result.copy(
                                                    conditions =
                                                        result.conditions.map { it.copy(scope = H.scope("${day}T14:00", "${day}T18:00")) },
                                                )
                                            }
                                        },
                                ),
                        ),
                )
            }
        val approved = ScheduleDslApprovedTermsValidator().validate(proposal, snapshot)
        val context = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), snapshot)
        val catalog = ServerEvidenceCatalog.create(snapshot)
        val envelope =
            ScheduleDslStructuralEnvelope(
                SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                snapshot.context,
                context,
                native.inputs.map { item ->
                    val entry = catalog.entries.single { it.inputId == item.submissionVersionId && it.text == item.rawText }
                    ScheduleDslLocatedEvidence(item.submissionVersionId, catalog.resolve(item.submissionVersionId, entry.id))
                },
                emptyList(),
                context.calculationProjection,
                evidenceCatalogVersion = ServerEvidenceCatalog.VERSION,
            )
        val repository = JdbcScheduleDslStructuralRepository(jdbc, mapper)
        repository.publish(envelope, 1, F.selectedAt)
        assertEquals(envelope, repository.find(snapshot.context))
        assertEquals(11, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=2,candidate_quality='COMPLETE' WHERE id=?", H.ir.run)
        val before = F.historicalSnapshot(jdbc)
        val fingerprint = route.fingerprint()
        val id = CoordinationRunId(H.ir.run)

        if (foreignOutside) {
            assertFailsWith<RuntimeException> { reader.readStored(id, 2, fingerprint) }
            assertFailsWith<RuntimeException> { reader.readInputDiagnostics(id, 2, fingerprint) }
            assertFailsWith<RuntimeException> { reader.readPlaceDescriptions(id, 2, fingerprint) }
            assertFailsWith<RuntimeException> { reader.readPlaceDescriptionsForSelection(id, 2, fingerprint) }
        } else {
            val calculated = reader.readStored(id, 2, fingerprint)
            assertEquals(route, calculated.route)
            assertEquals(
                listOf(OVERLAP),
                calculated.calculation.recommendations.options
                    .map { it.window },
            )
            assertEquals(emptyMap(), reader.readInputDiagnostics(id, 2, fingerprint))
            assertEquals(emptyList(), reader.readPlaceDescriptions(id, 2, fingerprint))
            assertEquals(emptyList(), reader.readPlaceDescriptionsForSelection(id, 2, fingerprint))
        }

        assertEquals(before, F.historicalSnapshot(jdbc))
        noProviderAdmission()
    }

    private fun sourceRouteRow(runId: UUID) =
        jdbc.queryForObject("SELECT to_jsonb(t)::text FROM final_analysis_routes t WHERE run_id=?", String::class.java, runId)

    private fun noProviderAdmission() {
        for (table in listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    companion object {
        private const val CALENDAR_RESPONSE = "schedule-dsl-evidence-response-3"
        private const val RELATIVE_TEXT = "🙂 둘째 금요일 오후 2시부터 4시까지 가능."
        private const val OUTSIDE = "2030-02-06"
        private val OVERLAP = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T07:00:00Z"))
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
