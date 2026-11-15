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
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslLocatedEvidence
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.WeeklyAnchorFilter
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
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S

/** TRP: generation admission is frozen on the route, never retrofitted onto historical format12. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class TemporalRangeEvidenceRoutePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var rooms: MeetingRoomRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var actualSelector: FinalAnalysisRouteSelectionService

    private val native get() = S.input(RANGE)

    @BeforeEach
    fun seed() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper, native)
    }

    @Test
    fun `TRP01 new generation identity is frozen and cannot be changed by runtime option updates`() {
        val route = freeze(admission = true)
        val selected = route.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(NEW_RESPONSE, selected.identity.responseContractVersion)
        val old = selected.copy(identity = selected.identity.copy(responseContractVersion = OLD_RESPONSE))
        assertNotEquals(route.fingerprint(), route.copy(choice = old).fingerprint())
        val before = historicalRows()

        selector(admission = false).requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        assertFailsWith<RuntimeException> { freeze(admission = false) }

        assertEquals(route, freshRoutes().find(H.ir.run))
        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @Test
    fun `TRP02 enabling the new option cannot rewrite an existing generation five`() {
        val route = freeze(admission = false)
        assertEquals(OLD_RESPONSE, (route.choice as FinalAnalysisRouteChoice.DslStructuralV5).identity.responseContractVersion)
        val before = historicalRows()

        selector(admission = true).requireExisting(H.ir.run, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        assertFailsWith<RuntimeException> { freeze(admission = true) }

        assertEquals(route, freshRoutes().find(H.ir.run))
        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `TRP03 retry successor inherits its source generation despite reversed runtime options`(admission: Boolean) {
        val source = currentRun()
        val route = freeze(admission)
        val beforeSource = routeRow(H.ir.run)
        val nextRun = UUID(0, 93201)
        val nextBatch = UUID(0, 93202)
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

        TransactionTemplate(transactions).executeWithoutResult {
            selector(!admission).propagate(
                source,
                successor,
                currentRoom(),
                FrozenSubmissionReader.read(submissions, successor.batch),
            )
        }

        val inherited = assertNotNull(freshRoutes().find(nextRun))
        val choice = inherited.choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals((route.choice as FinalAnalysisRouteChoice.DslStructuralV5).identity, choice.identity)
        assertEquals(if (admission) NEW_RESPONSE else OLD_RESPONSE, choice.identity.responseContractVersion)
        assertEquals(nextRun, choice.context.runId)
        assertEquals(nextBatch, choice.context.batchId)
        assertNotEquals(route.fingerprint(), inherited.fingerprint())
        assertEquals(beforeSource, routeRow(H.ir.run))
        noProviderAdmission()
    }

    @Test
    fun `TRP04 admission without weekly capability rolls back before freezing a route`() {
        val before = historicalRows()
        assertFailsWith<RuntimeException> {
            TransactionTemplate(transactions).executeWithoutResult {
                selector(admission = true, weekly = false).freeze(
                    currentRun(),
                    currentRoom(),
                    FrozenSubmissionReader.read(submissions, currentRun().batch),
                    FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
                )
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_analysis_routes", Int::class.java))
        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `TRP05 complete all search rule restores unchanged format12 from a fresh connection`(admission: Boolean) {
        val route = freeze(admission)
        val envelope = envelope("COMPLETE")
        publish(envelope)
        completeRun()
        val before = historicalRows()

        assertEquals(12, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        val fresh = freshEnvelope()
        assertEquals(envelope, fresh)
        assertEquals(
            (4..10).map { LocalDate.of(2027, 1, it) },
            fresh.weeklyApplications
                .single()
                .occurrences
                .map { it.anchorDate },
        )
        val stored = reader.readStored(CoordinationRunId(H.ir.run), 2, route.fingerprint())
        assertEquals(route, stored.route)
        assertEquals(
            listOf(OVERLAP),
            stored.calculation.recommendations.options
                .map { it.window },
        )
        assertEquals(emptyMap(), reader.readInputDiagnostics(CoordinationRunId(H.ir.run), 2, route.fingerprint()))
        assertEquals(emptyList(), reader.readPlaceDescriptions(CoordinationRunId(H.ir.run), 2, route.fingerprint()))
        assertFalse(saved().contains(RANGE))
        assertFalse(saved().contains("temporal_range_admission_version"), "A provider-only admission marker does not change format12")
        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ONE_DAY", "MISSING_UPPER", "MISSING_NATIVE_UPPER", "DAY_FILTER"])
    fun `TRP06 historical generation five retains original incomplete declaration and exact bytes`(shape: String) {
        val route = freeze(admission = false)
        val envelope = envelope(shape)
        publish(envelope)
        completeRun()
        val before = historicalRows()

        assertEquals(envelope, freshEnvelope())
        val first = reader.readStored(CoordinationRunId(H.ir.run), 2, route.fingerprint())
        val second = reader.readStored(CoordinationRunId(H.ir.run), 2, route.fingerprint())
        assertEquals(first, second)
        assertEquals(route, first.route)
        assertEquals(emptyMap(), reader.readInputDiagnostics(CoordinationRunId(H.ir.run), 2, route.fingerprint()))
        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @ParameterizedTest
    @ValueSource(strings = ["ONE_DAY", "MISSING_UPPER", "MISSING_NATIVE_UPPER", "DAY_FILTER"])
    fun `TRP07 new generation rejects contradictory format12 declarations on every authorized reader without repairing rows`(
        shape: String,
    ) {
        val route = freeze(admission = true)
        val envelope = envelope(shape)
        // Publish a structurally valid historical declaration directly to exercise read-side semantic fencing.
        // This is a synthetic corruption fixture, never a fabricated actual-model response.
        publish(envelope)
        completeRun()
        val before = historicalRows()
        assertEquals(envelope, freshEnvelope(), "The generic format12 codec retains historical structure semantics")
        val run = CoordinationRunId(H.ir.run)
        val fingerprint = route.fingerprint()

        assertFailsWith<RuntimeException> { reader.readStored(run, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readParticipantStatusInputs(run, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readInputDiagnostics(run, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readPlaceDescriptions(run, 2, fingerprint) }
        assertFailsWith<RuntimeException> { reader.readPlaceDescriptionsForSelection(run, 2, fingerprint) }

        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @Test
    fun `TRP08 generation six cannot read a stored envelope lacking its prerequisite weekly marker`() {
        val route = freeze(admission = true)
        publish(envelope("COMPLETE").copy(weeklyApplications = emptyList(), weeklyRulesVersion = null))
        completeRun()
        val before = historicalRows()

        assertEquals(11, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertFailsWith<RuntimeException> {
            reader.readStored(CoordinationRunId(H.ir.run), 2, route.fingerprint())
        }

        assertEquals(before, historicalRows())
        noProviderAdmission()
    }

    @Test
    fun `TRP09 actual configured selector bean forwards the enabled generation capability`() {
        val run = currentRun()
        TransactionTemplate(transactions).executeWithoutResult {
            actualSelector.freeze(
                run,
                currentRoom(),
                FrozenSubmissionReader.read(submissions, run.batch),
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
            )
        }

        val selected = assertNotNull(freshRoutes().find(H.ir.run)).choice as FinalAnalysisRouteChoice.DslStructuralV5
        assertEquals(NEW_RESPONSE, selected.identity.responseContractVersion)
        assertEquals("schedule-structural-union-1", selected.identity.policyVersion)
        noProviderAdmission()
    }

    private fun selector(
        admission: Boolean,
        weekly: Boolean = true,
    ) = FinalAnalysisRouteSelectionService(
        runs,
        routes,
        serverEvidenceReferences = true,
        searchRelativeDates = true,
        calendarMonthFacts = true,
        indexedEvidenceSelections = true,
        weeklyScheduleRules = weekly,
        temporalRangeAdmission = admission,
    )

    private fun freeze(admission: Boolean) =
        currentRun().let { run ->
            TransactionTemplate(transactions).executeWithoutResult {
                selector(admission).freeze(
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

    /** Independent numeric oracle for Jan4..Jan10; it never invokes the expansion implementation. */
    private fun envelope(shape: String): ScheduleDslStructuralEnvelope {
        val source = native
        val snapshot = H.snapshot(source)
        val singleDate = shape == "ONE_DAY"
        val claim =
            H.claim(
                source,
                "clock-range",
                scope = if (singleDate) H.scope("2027-01-04T16:20", "2027-01-04T20:40") else null,
            )
        val program = H.program(listOf(H.ref(source, "allow-range", "clock-range")), "allow-range")
        val approved = ScheduleDslApprovedTermsValidator().validate(H.proposal(source, listOf(claim), program, emptyList()), snapshot)
        val context = ScheduleDslContextModeValidator().validateDeclaration(approved, emptyList(), snapshot)
        val catalog = ServerEvidenceCatalog.create(snapshot)

        fun proof(
            text: String,
            owner: UUID = H.ir.first,
        ) = catalog.entries.single { it.inputId == owner && it.text == text }.let { catalog.resolve(owner, it.id) }
        val original = proof(RANGE)
        val peer = proof(H.SECOND, H.ir.second)
        val lower = proof("16:20")
        val upper = proof("20:40")
        val clocks = if (shape == "MISSING_NATIVE_UPPER") listOf(lower) else listOf(lower, upper)
        val applications =
            if (singleDate) {
                emptyList()
            } else {
                val filter =
                    if (shape == "DAY_FILTER") {
                        WeeklyAnchorFilter(fromInclusive = LocalDate.of(2027, 1, 8), evidence = listOf(original))
                    } else {
                        WeeklyAnchorFilter()
                    }
                val rule =
                    WeeklyScheduleRule(
                        DayOfWeek.entries.toSet(),
                        WeeklyTimePattern(
                            WeeklyTimeKind.BOUNDS,
                            lower = LocalTime.of(16, 20),
                            upper = if (shape == "MISSING_UPPER") null else LocalTime.of(20, 40),
                            clockEvidence = clocks,
                        ),
                        listOf(original),
                        filter,
                    )
                val anchors = if (shape == "DAY_FILTER") 8..10 else 4..10
                val occurrences =
                    anchors.map { day ->
                        val date = LocalDate.of(2027, 1, day)
                        val start = date.atTime(16, 20)
                        val end = if (shape == "MISSING_UPPER") date.plusDays(1).atStartOfDay() else date.atTime(20, 40)
                        WeeklyScheduleOccurrence(
                            date,
                            H.scope(start.toString(), end.toString()),
                            InstantTimeRange(start.atZone(ZONE).toInstant(), end.atZone(ZONE).toInstant()),
                        )
                    }
                listOf(WeeklyScheduleApplication(H.ir.first, "clock-range", rule, occurrences, true))
            }
        return ScheduleDslStructuralEnvelope(
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            snapshot.context,
            context,
            listOf(ScheduleDslLocatedEvidence(H.ir.first, original), ScheduleDslLocatedEvidence(H.ir.second, peer)) +
                clocks.map { ScheduleDslLocatedEvidence(H.ir.first, it) },
            emptyList(),
            context.calculationProjection,
            evidenceCatalogVersion = ServerEvidenceCatalog.VERSION,
            weeklyApplications = applications,
            weeklyRulesVersion = WeeklyScheduleRules.VERSION,
        )
    }

    private fun publish(envelope: ScheduleDslStructuralEnvelope) =
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(envelope, 1, F.selectedAt)

    private fun completeRun() =
        jdbc.update("UPDATE coordination_runs SET status='COMPLETED',version=2,candidate_quality='COMPLETE' WHERE id=?", H.ir.run)

    private fun saved() = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))

    private fun freshEnvelope(): ScheduleDslStructuralEnvelope =
        dataSource.connection.use { connection ->
            val independent = JdbcTemplate(SingleConnectionDataSource(connection, true))
            assertNotNull(JdbcScheduleDslStructuralRepository(independent, mapper).find(H.snapshot(native).context))
        }

    private fun routeRow(run: UUID) =
        jdbc.queryForObject("SELECT to_jsonb(t)::text FROM final_analysis_routes t WHERE run_id=?", String::class.java, run)

    private fun historicalRows() = F.historicalSnapshot(jdbc) + ("final_analysis_routes" to F.rows(jdbc, "final_analysis_routes"))

    private fun noProviderAdmission() {
        for (table in listOf("analysis_invocations", "coordination_attempts", "ai_call_reservations")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))
        }
    }

    companion object {
        private const val NEW_RESPONSE = "schedule-dsl-evidence-response-6"
        private const val OLD_RESPONSE = "schedule-dsl-evidence-response-5"
        private const val RANGE = "16:20부터 20:40까지 가능합니다."
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val OVERLAP = InstantTimeRange(Instant.parse("2027-01-08T07:20:00Z"), Instant.parse("2027-01-08T09:00:00Z"))
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
            registry.add("meetme.experiment.union-preferred-availability") { true }
            registry.add("meetme.experiment.server-evidence-references") { true }
            registry.add("meetme.experiment.search-relative-dates") { true }
            registry.add("meetme.experiment.calendar-month-facts") { true }
            registry.add("meetme.experiment.indexed-evidence-selections") { true }
            registry.add("meetme.experiment.weekly-schedule-rules") { true }
            registry.add("meetme.experiment.temporal-range-admission") { true }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
