package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.LocatedLiteralEvidence
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** The independently valid route and document must also agree on their frozen generation contract. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class ServerEvidenceStoredRouteBindingPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private fun input() = S.input("🙂 2027-01-08 14:00~16:00 가능.")

    private fun snapshot() = H.snapshot(input())

    @BeforeEach
    fun nativeRows() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper, input())
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,true", "false,true", "true,false")
    fun `SERB01 all stored read entry points require the envelope generation contract to match its frozen route`(
        referencesInRoute: Boolean,
        referencesInDocument: Boolean,
    ) {
        val run = assertNotNull(runs.findById(CoordinationRunId(H.ir.run)))
        val room = assertNotNull(rooms.findById(MeetingRoomId(H.ir.room)))
        TransactionTemplate(transactions).executeWithoutResult {
            FinalAnalysisRouteSelectionService(runs, routes, serverEvidenceReferences = referencesInRoute).freeze(
                run,
                room,
                FrozenSubmissionReader.read(submissions, run.batch),
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
            )
        }
        val route = assertNotNull(routes.find(H.ir.run))
        val document = envelope(referencesInDocument)
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(document, 1, F.selectedAt)
        assertEquals(document, JdbcScheduleDslStructuralRepository(jdbc, mapper).find(snapshot().context))
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=2,candidate_quality='COMPLETE' WHERE id=?", H.ir.run)
        val before = F.historicalSnapshot(jdbc)
        val id = CoordinationRunId(H.ir.run)
        val fingerprint = route.fingerprint()

        if (referencesInRoute == referencesInDocument) {
            val result = reader.readStored(id, 2, fingerprint)
            assertEquals(
                listOf(OVERLAP),
                result.calculation.recommendations.options
                    .map { it.window },
            )
            assertEquals(route, result.route)
            assertTrue(reader.readInputDiagnostics(id, 2, fingerprint).isEmpty())
            assertTrue(reader.readPlaceDescriptions(id, 2, fingerprint).isEmpty())
            assertTrue(reader.readPlaceDescriptionsForSelection(id, 2, fingerprint).isEmpty())
        } else {
            assertFailsWith<RuntimeException> { reader.readStored(id, 2, fingerprint) }
            assertFailsWith<RuntimeException> { reader.readInputDiagnostics(id, 2, fingerprint) }
            assertFailsWith<RuntimeException> { reader.readPlaceDescriptions(id, 2, fingerprint) }
            assertFailsWith<RuntimeException> { reader.readPlaceDescriptionsForSelection(id, 2, fingerprint) }
        }

        assertEquals(before, F.historicalSnapshot(jdbc), "Stored reads must not relabel contracts or modify historical data")
        for (table in listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    /** Both independently valid document forms have the same native evidence and matching semantics. */
    private fun envelope(references: Boolean): ScheduleDslStructuralEnvelope {
        val native = input()
        val source = snapshot()
        val claim = H.claim(native, "possible", scope = H.scope("2027-01-08T14:00", "2027-01-08T16:00"))
        val program = H.program(listOf(H.ref(native, "allow-ref", "possible")), "allow-ref")
        val approved = ScheduleDslApprovedTermsValidator().validate(H.proposal(native, listOf(claim), program, emptyList()), source)
        val context = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), source)
        val catalog = ServerEvidenceCatalog.create(source)
        val witnesses =
            native.inputs.map { input ->
                val entry = catalog.entries.single { it.inputId == input.submissionVersionId && it.text == input.rawText }
                ScheduleDslLocatedEvidence(
                    input.submissionVersionId,
                    if (references) catalog.resolve(input.submissionVersionId, entry.id) else LocatedLiteralEvidence(entry.span),
                )
            }
        return ScheduleDslStructuralEnvelope(
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            source.context,
            context,
            witnesses,
            emptyList(),
            context.calculationProjection,
            evidenceCatalogVersion = if (references) ServerEvidenceCatalog.VERSION else null,
        )
    }

    companion object {
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
