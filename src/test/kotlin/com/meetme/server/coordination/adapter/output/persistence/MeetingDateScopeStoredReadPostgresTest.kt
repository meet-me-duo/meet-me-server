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
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** Frozen response2 adds owner-bound date checks; response1 and the format11 representation retain their semantics. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class MeetingDateScopeStoredReadPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @ParameterizedTest
    @ValueSource(strings = ["legacy-outside", "scoped-outside", "scoped-inside", "own-explicit-outside", "peer-explicit-outside"])
    fun `MDSR01 every stored read uses its frozen response date policy before projecting native declarations`(caseName: String) {
        val scenario = scenario(caseName)
        val input =
            S.input(scenario.firstText).let { original ->
                original.copy(
                    inputs =
                        original.inputs.mapIndexed { index, item ->
                            if (index ==
                                0
                            ) {
                                item
                            } else {
                                item.copy(rawText = scenario.secondText)
                            }
                        },
                )
            }
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper, input)
        val run = assertNotNull(runs.findById(CoordinationRunId(H.ir.run)))
        val room = assertNotNull(rooms.findById(MeetingRoomId(H.ir.room)))
        TransactionTemplate(transactions).executeWithoutResult {
            FinalAnalysisRouteSelectionService(
                runs,
                routes,
                serverEvidenceReferences = true,
                searchRelativeDates = scenario.scoped,
            ).freeze(run, room, FrozenSubmissionReader.read(submissions, run.batch), FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        }
        val route = assertNotNull(routes.find(H.ir.run))
        val choice = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(
            if (scenario.scoped) SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT else SERVER_EVIDENCE_RESPONSE_CONTRACT,
            choice.identity.responseContractVersion,
        )

        val snapshot = H.snapshot(input)
        val first = H.claim(input, "possible", scope = H.scope("${scenario.firstDate}T14:00", "${scenario.firstDate}T16:00"))
        val program = H.program(listOf(H.ref(input, "allow-ref", "possible")), "allow-ref")
        val proposed =
            H.proposal(input, listOf(first), program, emptyList()).let { original ->
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
                                                        result.conditions.map { claim ->
                                                            claim.copy(
                                                                scope =
                                                                    H.scope(
                                                                        "${scenario.secondDate}T14:00",
                                                                        "${scenario.secondDate}T18:00",
                                                                    ),
                                                            )
                                                        },
                                                )
                                            }
                                        },
                                ),
                        ),
                )
            }
        val approved = ScheduleDslApprovedTermsValidator().validate(proposed, snapshot)
        val contextual = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), snapshot)
        val catalog = ServerEvidenceCatalog.create(snapshot)
        val envelope =
            ScheduleDslStructuralEnvelope(
                SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                snapshot.context,
                contextual,
                input.inputs.map { item ->
                    val entry = catalog.entries.single { it.inputId == item.submissionVersionId && it.text == item.rawText }
                    ScheduleDslLocatedEvidence(item.submissionVersionId, catalog.resolve(item.submissionVersionId, entry.id))
                },
                emptyList(),
                contextual.calculationProjection,
                evidenceCatalogVersion = ServerEvidenceCatalog.VERSION,
            )
        // The repository representation is valid in either contract; the frozen route chooses the additional semantic guard.
        val repository = JdbcScheduleDslStructuralRepository(jdbc, mapper)
        repository.publish(envelope, 1, F.selectedAt)
        assertEquals(11, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(envelope, repository.find(snapshot.context))
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=2,candidate_quality='COMPLETE' WHERE id=?", H.ir.run)
        val before = F.historicalSnapshot(jdbc)
        val id = CoordinationRunId(H.ir.run)
        val fingerprint = route.fingerprint()

        if (scenario.reject) {
            assertFailsWith<RuntimeException>(caseName) { reader.readStored(id, 2, fingerprint) }
            assertFailsWith<RuntimeException>(caseName) { reader.readInputDiagnostics(id, 2, fingerprint) }
            assertFailsWith<RuntimeException>(caseName) { reader.readPlaceDescriptions(id, 2, fingerprint) }
            assertFailsWith<RuntimeException>(caseName) { reader.readPlaceDescriptionsForSelection(id, 2, fingerprint) }
        } else {
            val result = reader.readStored(id, 2, fingerprint)
            assertEquals(route, result.route)
            assertEquals(
                scenario.expectedOverlap,
                result.calculation.recommendations.options
                    .map { it.window },
            )
            reader.readInputDiagnostics(id, 2, fingerprint)
            assertEquals(emptyList(), reader.readPlaceDescriptions(id, 2, fingerprint))
            assertEquals(emptyList(), reader.readPlaceDescriptionsForSelection(id, 2, fingerprint))
        }

        assertEquals(before, F.historicalSnapshot(jdbc), "A failed or successful read cannot rewrite native dates or contract history")
        for (table in listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    private data class Scenario(
        val scoped: Boolean,
        val firstText: String,
        val firstDate: String,
        val secondText: String = H.SECOND,
        val secondDate: String = "2027-01-08",
        val reject: Boolean = false,
        val expectedOverlap: List<InstantTimeRange> = emptyList(),
    )

    private fun scenario(name: String): Scenario =
        when (name) {
            "legacy-outside" -> Scenario(false, RELATIVE_TEXT, OUTSIDE)
            "scoped-outside" -> Scenario(true, RELATIVE_TEXT, OUTSIDE, reject = true)
            "scoped-inside" -> Scenario(true, RELATIVE_TEXT, "2027-01-08", expectedOverlap = listOf(OVERLAP))
            "own-explicit-outside" -> Scenario(true, "🙂 $OUTSIDE 14:00~16:00 가능.", OUTSIDE)
            "peer-explicit-outside" ->
                Scenario(true, RELATIVE_TEXT, OUTSIDE, "$OUTSIDE 14:00~18:00 가능.", OUTSIDE, reject = true)
            else -> error("Unknown independent date-scope scenario")
        }

    companion object {
        private const val RELATIVE_TEXT = "🙂 마지막 주 금요일 오후 2시부터 4시까지 가능."
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
