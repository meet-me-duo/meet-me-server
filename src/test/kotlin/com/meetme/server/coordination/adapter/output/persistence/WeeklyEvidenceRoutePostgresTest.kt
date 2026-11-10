package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.application.service.FrozenSubmissionReader
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.WeeklyScheduleApplication
import com.meetme.server.submission.domain.dsl.WeeklyScheduleOccurrence
import com.meetme.server.submission.domain.dsl.WeeklyScheduleRule
import com.meetme.server.submission.domain.dsl.WeeklyScheduleRules
import com.meetme.server.submission.domain.dsl.WeeklyTimeKind
import com.meetme.server.submission.domain.dsl.WeeklyTimePattern
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** Weekly declarations persist separately from native unknown conditions and expand only through validated derivation. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class WeeklyEvidenceRoutePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource

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
        weekly: Boolean,
        serverReferences: Boolean = true,
        scopedDates: Boolean = true,
        calendarFacts: Boolean = true,
        indexed: Boolean = true,
    ) = FinalAnalysisRouteSelectionService(
        runs,
        routes,
        serverEvidenceReferences = serverReferences,
        searchRelativeDates = scopedDates,
        calendarMonthFacts = calendarFacts,
        indexedEvidenceSelections = indexed,
        weeklyScheduleRules = weekly,
    )

    private fun freeze(weekly: Boolean) =
        currentRun().let { run ->
            TransactionTemplate(transactions).executeWithoutResult {
                selector(weekly).freeze(
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
    fun `WER01 response5 identity and fingerprint remain frozen after selector flags change`() {
        val route = freeze(weekly = true)
        val choice = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(WEEKLY_RESPONSE, choice.identity.responseContractVersion)
        val previous = choice.copy(identity = choice.identity.copy(responseContractVersion = SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT))
        assertNotEquals(route.fingerprint(), route.copy(choice = previous).fingerprint())
        val before = F.historicalSnapshot(jdbc)
        val frozenRows = F.rows(jdbc, "final_analysis_routes")

        selector(weekly = false, serverReferences = false, scopedDates = false, calendarFacts = false, indexed = false)
            .requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)

        assertEquals(route, freshRoutes().find(H.ir.run))
        assertEquals(frozenRows, F.rows(jdbc, "final_analysis_routes"))
        assertEquals(before, F.historicalSnapshot(jdbc))
        noProviderAdmission()
    }

    @ParameterizedTest
    @CsvSource(
        "false,true,true,true",
        "true,false,true,true",
        "true,true,false,true",
        "true,true,true,false",
        "false,false,false,false",
    )
    fun `WER02 weekly rules cannot freeze a V5 contract without all four prerequisite contracts`(
        serverReferences: Boolean,
        scopedDates: Boolean,
        calendarFacts: Boolean,
        indexed: Boolean,
    ) {
        val run = currentRun()
        val before = F.historicalSnapshot(jdbc)
        assertFailsWith<RuntimeException> {
            TransactionTemplate(transactions).executeWithoutResult {
                selector(
                    weekly = true,
                    serverReferences = serverReferences,
                    scopedDates = scopedDates,
                    calendarFacts = calendarFacts,
                    indexed = indexed,
                ).freeze(
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
    fun `WER03 enabling weekly rules cannot rewrite a previously frozen response4 contract`() {
        val old = freeze(weekly = false)
        assertEquals(
            SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
            (old.choice as FinalAnalysisRouteChoice.DslStructuralV5).identity.responseContractVersion,
        )
        val before = F.historicalSnapshot(jdbc)
        val frozenRows = F.rows(jdbc, "final_analysis_routes")

        selector(weekly = true).requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        assertFailsWith<RuntimeException> { freeze(weekly = true) }

        assertEquals(old, freshRoutes().find(H.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(frozenRows, F.rows(jdbc, "final_analysis_routes"))
        noProviderAdmission()
    }

    @Test
    fun `WER04 a successor retains response5 when current selector flags are all disabled`() {
        val source = currentRun()
        val sourceRoute = freeze(weekly = true)
        val oldChoice = sourceRoute.choice as FinalAnalysisRouteChoice.DslStructuralV5
        val sourceRows = sourceRouteRow(H.ir.run)
        val nextRun = UUID(0, 8601)
        val nextBatch = UUID(0, 8602)
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
            selector(weekly = false, serverReferences = false, scopedDates = false, calendarFacts = false, indexed = false)
                .propagate(source, successor, currentRoom(), native)
        }

        val propagated = assertNotNull(freshRoutes().find(nextRun))
        val propagatedChoice = propagated.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(oldChoice.identity, propagatedChoice.identity)
        assertEquals(WEEKLY_RESPONSE, propagatedChoice.identity.responseContractVersion)
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
    fun `WER05 frozen response4 and response5 retain marker and date ownership on every read`(
        weekly: Boolean,
        foreignOutside: Boolean,
    ) {
        seed(foreignOutside)
        val route = freeze(weekly)
        assertEquals(
            if (weekly) WEEKLY_RESPONSE else SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
            (route.choice as FinalAnalysisRouteChoice.DslStructuralV5).identity.responseContractVersion,
        )
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
                weeklyRulesVersion = if (weekly) WeeklyScheduleRules.VERSION else null,
            )
        val repository = JdbcScheduleDslStructuralRepository(jdbc, mapper)
        repository.publish(envelope, 1, F.selectedAt)
        assertEquals(envelope, repository.find(snapshot.context))
        assertEquals(if (weekly) 12 else 11, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        val storedShape = mapper.readTree(saved())
        assertEquals(weekly, storedShape.has("weeklyRulesVersion"))
        assertEquals(weekly, storedShape.has("weeklyApplications"))
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

    @Test
    fun `WER06 format12 restores original weekly rule occurrences and native unknown declaration`() {
        val envelope = weeklyEnvelope()
        val expected = envelope.weeklyApplications.single()
        publish(envelope)

        assertEquals(12, storedFormat())
        val restored = freshEnvelope()
        assertEquals(envelope, restored)
        assertEquals(WeeklyScheduleRules.VERSION, restored.weeklyRulesVersion)
        assertEquals(expected, restored.weeklyApplications.single())
        assertNull(
            restored.base.base.declaration.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertEquals(
            "NON_PRECISE",
            restored.base.base.declaration.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution.name,
        )
        assertEquals(listOf(LocalDate.parse("2027-01-08")), expected.occurrences.map { it.anchorDate })
        assertTrue(expected.resolved)
        val calculated = ScheduleDslStructuralCalculator.calculate(restored, H.snapshot(input()), S.policy, MeetingMode.REMOTE)
        assertEquals(listOf(OVERLAP), calculated.recommendations.options.map { it.window })
        assertEquals(
            2,
            calculated.recommendations.options
                .single()
                .variants
                .single()
                .participantIds.size,
        )
        assertFalse(saved().contains(RELATIVE_TEXT))
        assertFalse(saved().contains("\"quote\""))
        assertTrue(saved().contains("catalogId"))
        noProviderAdmission()
    }

    @Test
    fun `WER07 resolved occurrences marker and derived bounds cannot be changed on stored restore`() {
        publish(weeklyEnvelope())
        val original = saved()
        for (fault in listOf("anchor", "scope", "support", "resolved", "version", "marker", "missing-list", "downgrade")) {
            val root = mapper.readTree(original) as ObjectNode
            val app = root.path("weeklyApplications").path(0) as ObjectNode
            val occurrence = app.path("occurrences").path(0) as ObjectNode
            when (fault) {
                "anchor" -> occurrence.put("anchorDate", "2027-01-09")
                "scope" -> (occurrence.path("localScope") as ObjectNode).put("start", "2027-01-08T15:00:00")
                "support" -> (occurrence.path("searchSupport") as ObjectNode).put("endExclusive", "2027-01-08T08:00:00Z")
                "resolved" -> app.put("resolved", false)
                "version" -> app.put("version", "weekly-schedule-rule-unknown")
                "marker" -> root.remove("weeklyRulesVersion")
                "missing-list" -> root.remove("weeklyApplications")
            }
            jdbc.update(
                "UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb),format_version=?",
                mapper.writeValueAsString(root),
                if (fault == "downgrade") 11 else 12,
            )
            val changed = saved()
            assertFailsWith<RuntimeException>(fault) { freshEnvelope() }
            assertEquals(changed, saved(), "A read cannot repair a weekly derivation")
        }
        noProviderAdmission()
    }

    @Test
    fun `WER08 weekly application cannot borrow another participant source evidence`() {
        val envelope = weeklyEnvelope()
        val app = envelope.weeklyApplications.single()
        val foreign = envelope.locatedEvidence.single { it.submissionVersionId == H.ir.second }.evidence
        val invalid =
            envelope.copy(
                weeklyApplications = listOf(app.copy(rule = app.rule.copy(weekdayEvidence = listOf(foreign)))),
            )

        assertFailsWith<RuntimeException> { publish(invalid) }

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_envelopes", Int::class.java))
        noProviderAdmission()
    }

    @Test
    fun `WER09 unknown weekly boundary remains unresolved and never becomes full search availability`() {
        val envelope = weeklyEnvelope(unknownTime = true)
        publish(envelope)
        val restored = freshEnvelope()
        assertEquals(envelope, restored)
        assertFalse(restored.weeklyApplications.single().resolved)
        assertTrue(
            restored.weeklyApplications
                .single()
                .occurrences
                .isEmpty(),
        )
        val calculated = ScheduleDslStructuralCalculator.calculate(restored, H.snapshot(input()), S.policy, MeetingMode.REMOTE)
        val prepared = calculated.preparedByMode.getValue(MeetingMode.REMOTE)
        val participant = prepared.participants.single { it.participantId.value == H.ir.firstParticipant }
        assertTrue(participant.availableTimes.isEmpty())
        assertTrue(prepared.evidence.getValue(participant.participantId).unresolvedTime)
        assertTrue(calculated.recommendations.options.isEmpty())
        assertEquals(12, storedFormat())
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `WER10 weekly marker and frozen response contract must agree before every read`(weeklyInRoute: Boolean) {
        val route = freeze(weekly = weeklyInRoute)
        val envelope = if (weeklyInRoute) plainEnvelope() else weeklyEnvelope()
        publish(envelope)
        assertEquals(envelope, freshEnvelope())
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=2,candidate_quality='COMPLETE' WHERE id=?", H.ir.run)
        val before = F.historicalSnapshot(jdbc)
        val id = CoordinationRunId(H.ir.run)
        val fingerprint = route.fingerprint()

        assertFailsWith<RuntimeException> { reader.readStored(id, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readInputDiagnostics(id, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readPlaceDescriptions(id, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readPlaceDescriptionsForSelection(id, 2, fingerprint) }

        assertEquals(before, F.historicalSnapshot(jdbc))
        noProviderAdmission()
    }

    /** Manually declared weekly oracle; fixture construction does not call the new expansion implementation. */
    private fun weeklyEnvelope(unknownTime: Boolean = false): ScheduleDslStructuralEnvelope {
        val native = input()
        val snapshot = H.snapshot(native)
        val claim = H.claim(native, "weekly")
        val program = H.program(listOf(H.ref(native, "weekly-ref", "weekly")), "weekly-ref")
        val approved = ScheduleDslApprovedTermsValidator().validate(H.proposal(native, listOf(claim), program, emptyList()), snapshot)
        val context = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), snapshot)
        val catalog = ServerEvidenceCatalog.create(snapshot)
        val sourceEntry = catalog.entries.single { it.inputId == H.ir.first && it.text == RELATIVE_TEXT }
        val peerEntry = catalog.entries.single { it.inputId == H.ir.second && it.text == H.SECOND }
        val sourceProof = catalog.resolve(H.ir.first, sourceEntry.id)
        val peerProof = catalog.resolve(H.ir.second, peerEntry.id)
        val clockProofs =
            if (unknownTime) {
                emptyList()
            } else {
                listOf("14:00", "16:00").map { text ->
                    val entry = catalog.entries.single { it.inputId == H.ir.first && it.text == text }
                    catalog.resolve(H.ir.first, entry.id)
                }
            }
        val rule =
            WeeklyScheduleRule(
                setOf(DayOfWeek.FRIDAY),
                if (unknownTime) {
                    WeeklyTimePattern(WeeklyTimeKind.UNKNOWN)
                } else {
                    WeeklyTimePattern(
                        WeeklyTimeKind.BOUNDS,
                        lower = LocalTime.of(14, 0),
                        upper = LocalTime.of(16, 0),
                        clockEvidence = clockProofs,
                    )
                },
                listOf(sourceProof),
            )
        val app =
            WeeklyScheduleApplication(
                H.ir.first,
                "weekly",
                rule,
                if (unknownTime) {
                    emptyList()
                } else {
                    listOf(
                        WeeklyScheduleOccurrence(
                            LocalDate.parse("2027-01-08"),
                            H.scope("2027-01-08T14:00", "2027-01-08T16:00"),
                            OVERLAP,
                        ),
                    )
                },
                resolved = !unknownTime,
            )
        return ScheduleDslStructuralEnvelope(
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            snapshot.context,
            context,
            listOf(
                ScheduleDslLocatedEvidence(H.ir.first, sourceProof),
                ScheduleDslLocatedEvidence(H.ir.second, peerProof),
            ) + clockProofs.map { ScheduleDslLocatedEvidence(H.ir.first, it) },
            emptyList(),
            context.calculationProjection,
            evidenceCatalogVersion = ServerEvidenceCatalog.VERSION,
            weeklyApplications = listOf(app),
            weeklyRulesVersion = WeeklyScheduleRules.VERSION,
        )
    }

    private fun plainEnvelope(): ScheduleDslStructuralEnvelope {
        val native = input()
        val snapshot = H.snapshot(native)
        val claim = H.claim(native, "possible", scope = H.scope("2027-01-08T14:00", "2027-01-08T16:00"))
        val program = H.program(listOf(H.ref(native, "allow-ref", "possible")), "allow-ref")
        val approved = ScheduleDslApprovedTermsValidator().validate(H.proposal(native, listOf(claim), program, emptyList()), snapshot)
        val context = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), snapshot)
        val catalog = ServerEvidenceCatalog.create(snapshot)
        return ScheduleDslStructuralEnvelope(
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
    }

    private fun publish(envelope: ScheduleDslStructuralEnvelope) =
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(envelope, 1, F.selectedAt)

    private fun storedFormat() = jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java)

    private fun saved() = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))

    private fun freshEnvelope(): ScheduleDslStructuralEnvelope =
        dataSource.connection.use { connection ->
            val independent = JdbcTemplate(SingleConnectionDataSource(connection, true))
            assertNotNull(JdbcScheduleDslStructuralRepository(independent, mapper).find(H.snapshot(input()).context))
        }

    private fun sourceRouteRow(runId: UUID) =
        jdbc.queryForObject("SELECT to_jsonb(t)::text FROM final_analysis_routes t WHERE run_id=?", String::class.java, runId)

    private fun noProviderAdmission() {
        for (table in listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    companion object {
        private const val WEEKLY_RESPONSE = "schedule-dsl-evidence-response-5"
        private const val RELATIVE_TEXT = "🙂 매주 금요일 14:00부터 16:00까지 가능."
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
