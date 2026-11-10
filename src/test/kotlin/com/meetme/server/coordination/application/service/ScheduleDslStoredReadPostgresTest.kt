package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcFinalAnalysisRouteRegistry
import com.meetme.server.coordination.adapter.output.persistence.JdbcScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import com.meetme.server.support.ScheduleDslWireFixture
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pending independent contract tests: no application code or JVM was changed/run by the author. */
@SpringBootTest
@Import(ScheduleDslStoredReadPostgresTest.InactiveConfiguration::class)
class ScheduleDslStoredReadPostgresTest {
    @Autowired private lateinit var reader: ScheduleDslStoredReadService

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var envelopes: ObservedEnvelopeRepository

    @Autowired private lateinit var oldService: ScheduleDslAnalysisService

    @Autowired private lateinit var oldRegistry: ScheduleIrExecutionRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager
    private val q = ScheduleDslQuestionContextFixture
    private val f = ScheduleIrV2SyntheticFixture
    private val w = ScheduleDslWireFixture
    private val policy get() = IrMatchingPolicy(f.POLICY, setOf(IrInterpretation.EXPLICIT), emptyList(), false)
    private val identity get() = IrExecutionIdentity(SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, f.POLICY)

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        seed()
        envelopes.observations.clear()
    }

    private fun choice(selectedPolicy: IrMatchingPolicy = policy) =
        FinalAnalysisRouteChoice.DslContextV2(identity, selectedPolicy, q.snapshot().context, MeetingMode.REMOTE)

    private fun freeze(selectedPolicy: IrMatchingPolicy = policy) = routes.freeze(f.run, 1, choice(selectedPolicy))

    private fun persist(
        format: Int = 3,
        selectedPolicy: IrMatchingPolicy = policy,
        json: String? = null,
    ): FrozenFinalAnalysisRoute {
        val route = freeze(selectedPolicy)
        val envelope =
            StrictScheduleDslContextResponseParser(mapper).parse(
                json ?: if (format == 3) w.json() else proofJson(),
                q.snapshot(),
                SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        envelopes.publish(envelope, 1, PROCESSED)
        assertEquals(format, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes WHERE run_id=?", Int::class.java, f.run))
        assertEquals(envelope, JdbcScheduleDslRepository(jdbc, mapper).find(q.snapshot().context))
        assertEquals(route, routes.find(f.run))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM structured_submission_results", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_execution_bindings", Int::class.java))
        return route
    }

    private fun phase(
        status: String,
        version: Long,
    ) {
        jdbc.update(
            "UPDATE coordination_runs SET status=?,version=?,candidate_quality=? WHERE id=?",
            status,
            version,
            if (status == "COMPLETED") "COMPLETE" else null,
            f.run,
        )
    }

    private fun read(
        route: FrozenFinalAnalysisRoute,
        version: Long = 2,
    ) = reader.readStored(CoordinationRunId(f.run), version, route.fingerprint())

    @ParameterizedTest
    @CsvSource("MATCHING,3", "MATCHING,4", "COMPLETED,3", "COMPLETED,4")
    fun `SR01 stored read joins actual outer readonly RR and preserves format and exact frozen windows`(
        status: String,
        format: Int,
    ) {
        val route = persist(format)
        val version = if (status == "MATCHING") 2L else 3L
        phase(status, version)
        val before = allRows()
        envelopes.observations.clear()
        val outer =
            TransactionTemplate(transactions).apply {
                isReadOnly = true
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }
        outer.executeWithoutResult {
            val transaction = observe(jdbc)
            assertEquals("on", transaction.readOnly)
            assertEquals("repeatable read", transaction.isolation)
            val result = read(route, version)
            assertEquals(route, result.route)
            assertEquals(version, result.currentRunVersion)
            val core = result.calculation
            val expected =
                if (format == 3) {
                    range("2027-01-08T10:00:00Z", "2027-01-08T12:00:00Z")
                } else {
                    range("2027-01-08T09:00:00Z", "2027-01-08T13:00:00Z")
                }
            assertEquals(listOf(expected), core.recommendations.options.map { it.window })
            assertTrue(core.candidates.candidates.isNotEmpty())
            assertTrue(core.candidates.candidates.all { it.timeRanges == listOf(expected) && it.meetingMode == MeetingMode.REMOTE })
            assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, core.precisionOutcome)
            assertEquals(if (format == 3) IrCalculationStatus.CALCULATED else IrCalculationStatus.PARTIAL, core.status)
            assertTrue(core.prepared.participants.all { it.offlineArea == null && it.offlineRegion == null })
            if (format ==
                4
            ) {
                assertTrue(
                    core.prepared.participants
                        .single {
                            it.participantId == ParticipantId(f.firstParticipant)
                        }.explicitPreferences.timeRanges
                        .isEmpty(),
                )
            }
            assertTrue(envelopes.observations.isNotEmpty())
            // An inner repository observation detects REQUIRES_NEW; outer-before/after alone cannot.
            assertTrue(envelopes.observations.all { it == transaction })
            assertEquals(transaction, observe(jdbc))
            assertEquals(result, read(route, version))
        }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR02 legitimate matching retry version growth never changes selection or reparses`() {
        val route = persist()
        phase("MATCHING", 2)
        val first = read(route)
        phase("MATCHING", 9)
        val before = allRows()
        assertFailsWith<RuntimeException> { read(route, 2) }
        val latest = read(route, 9)
        assertEquals(first.route, latest.route)
        assertEquals(first.calculation, latest.calculation)
        assertEquals(9L, latest.currentRunVersion)
        assertEquals(before, allRows())
    }

    @ParameterizedTest
    @ValueSource(strings = ["QUEUED", "STRUCTURING", "ANALYSIS_DELAYED", "DEAD_LETTERED"])
    fun `SR03 stored read is not a dispatch or delayed-state bypass`(status: String) {
        val route = persist()
        phase("MATCHING", 2)
        read(route)
        phase(status, 3)
        val before = allRows()
        assertFailsWith<RuntimeException> { read(route, 3) }
        assertEquals(before, allRows())
    }

    @ParameterizedTest
    @ValueSource(strings = ["raw", "locale", "supplement", "mode", "zone", "search", "inactive", "reference", "format"])
    fun `SR04 changed frozen sources context or stored shape fail without mutation`(defect: String) {
        val route = persist()
        phase("MATCHING", 2)
        read(route)
        when (defect) {
            "raw" -> jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", "Synthetic changed original", f.first)
            "locale" -> jdbc.update("UPDATE submission_versions SET locale='en-US' WHERE id=?", f.first)
            "supplement" -> jdbc.update("UPDATE submission_versions SET input_evidence='{}'::jsonb WHERE id=?", f.first)
            "mode" -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='EITHER' WHERE id=?", f.room)
            "zone" -> jdbc.update("UPDATE meeting_rooms SET time_zone_id='UTC' WHERE id=?", f.room)
            "search" -> jdbc.update("UPDATE meeting_rooms SET search_end_date='2027-01-12' WHERE id=?", f.room)
            "inactive" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", f.room)
            "reference" -> jdbc.update("DELETE FROM schedule_ir_inputs WHERE run_id=? AND submission_version_id=?", f.run, f.first)
            "format" -> jdbc.update("UPDATE schedule_ir_envelopes SET format_version=999 WHERE run_id=?", f.run)
        }
        val before = allRows()
        assertFailsWith<RuntimeException> { read(route) }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR05 newer latest head cannot replace exact historical frozen cohort`() {
        val route = persist()
        phase("MATCHING", 2)
        val expected = read(route)
        val newer = UUID(0, 8701)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,8,?,'ko-KR',?)",
            newer,
            UUID(0, 401),
            "Synthetic later input must not replace frozen input",
            Timestamp.from(PROCESSED.plusSeconds(60)),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", newer, UUID(0, 401))
        val before = allRows()
        assertEquals(expected, read(route))
        assertEquals(before, allRows())
    }

    @ParameterizedTest
    @ValueSource(strings = ["same-version-different-policy", "different-fingerprint"])
    fun `SR06 caller must pin whole frozen selection not only policy version`(defect: String) {
        val route = persist()
        phase("MATCHING", 2)
        val forged =
            if (defect == "different-fingerprint") {
                "0".repeat(64)
            } else {
                route.copy(choice = choice(policy.copy(allowExplicitUnconstrained = true))).fingerprint()
            }
        assertTrue(forged != route.fingerprint())
        val before = allRows()
        assertFailsWith<RuntimeException> { reader.readStored(CoordinationRunId(f.run), 2, forged) }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR07 selected route without envelope is not an empty success or new dispatch`() {
        val route = freeze()
        phase("MATCHING", 2)
        val before = allRows()
        assertFailsWith<RuntimeException> { read(route) }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR08 legacy choice is never inferred as DSL from a stored envelope`() {
        val route = routes.freeze(f.run, 1, FinalAnalysisRouteChoice.Legacy)
        val envelope =
            StrictScheduleDslContextResponseParser(mapper).parse(
                w.json(),
                q.snapshot(),
                SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        // Deliberately inconsistent persisted fixture; loader must not choose by whichever result row exists.
        envelopes.publish(envelope, 1, PROCESSED)
        phase("MATCHING", 2)
        val before = allRows()
        assertFailsWith<RuntimeException> { read(route) }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR09 old format1 selection alone is not a new route and old write guards stay strict`() {
        val ticket = oldService.prepare(CoordinationRunId(f.run), 1, identity, policy, PREPARED)
        val old = oldService.consume(ticket, w.json(), PROCESSED)
        assertEquals(null, routes.find(f.run))
        phase("MATCHING", 2)
        val before = allRows()
        assertFailsWith<RuntimeException> { reader.readStored(CoordinationRunId(f.run), 2, ticket.fingerprint()) }
        assertFailsWith<RuntimeException> { oldRegistry.requireCurrent(ticket) }
        assertFailsWith<RuntimeException> { oldService.replay(ticket) }
        assertFailsWith<RuntimeException> { oldService.input(ticket) }
        assertFailsWith<RuntimeException> { oldService.consume(ticket, w.json(), PROCESSED.plusSeconds(1)) }
        assertFailsWith<RuntimeException> { envelopes.publish(old.envelope, 2, PROCESSED.plusSeconds(1)) }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR10 completed confirmed stored read is permitted but does not publish or dispatch`() {
        val route = persist()
        phase("COMPLETED", 3)
        val expected = read(route, 3).calculation
        val candidate = UUID(0, 8801)
        jdbc.update(
            "INSERT INTO candidates(id,coordination_run_id,rank,plan_type,meeting_mode,total_participants) VALUES (?,?,1,'PLAN_A','REMOTE',2)",
            candidate,
            f.run,
        )
        jdbc.update(
            "INSERT INTO candidate_time_ranges(candidate_id,range_order,start_at,end_at) VALUES (?,0,?,?)",
            candidate,
            Timestamp.from(Instant.parse("2027-01-08T10:00:00Z")),
            Timestamp.from(Instant.parse("2027-01-08T12:00:00Z")),
        )
        jdbc.update(
            "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,?)",
            f.run,
            candidate,
            Timestamp.from(PROCESSED.plusSeconds(60)),
        )
        jdbc.update("UPDATE coordination_runs SET version=4 WHERE id=?", f.run)
        val before = allRows()
        assertEquals(expected, read(route, 4).calculation)
        val envelope = assertNotNull(JdbcScheduleDslRepository(jdbc, mapper).find(q.snapshot().context))
        assertFailsWith<RuntimeException> { envelopes.publish(envelope, 4, PROCESSED.plusSeconds(61)) }
        assertEquals(before, allRows())
    }

    @Test
    fun `SR11 standalone reader establishes one readonly RR snapshot itself`() {
        val route = persist()
        phase("MATCHING", 2)
        val before = allRows()
        envelopes.observations.clear()
        read(route)
        assertTrue(envelopes.observations.isNotEmpty())
        assertTrue(envelopes.observations.all { it.readOnly == "on" && it.isolation == "repeatable read" })
        assertEquals(
            1,
            envelopes.observations
                .map { it.transactionId }
                .distinct()
                .size,
        )
        assertEquals(before, allRows())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `SR12 frozen full policy governs inferred claim calculation without a reader default`(eligible: Boolean) {
        val selectedPolicy =
            policy.copy(
                eligibleInterpretations =
                    if (eligible) {
                        setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED)
                    } else {
                        setOf(IrInterpretation.EXPLICIT)
                    },
            )
        val document = w.document()
        val claim = w.f.claim("inferred", 19, 21).copy(interpretation = IrInterpretation.INFERRED)
        w.firstResult(document)["conditions"] = listOf(f.wire(claim))
        w.firstResult(document)["program"] = w.program(w.f.program(listOf(w.f.ref("inferred-ref", claim.id)), "inferred-ref"))
        val route = persist(selectedPolicy = selectedPolicy, json = w.encode(document))
        phase("MATCHING", 2)
        val before = allRows()
        val result = read(route)
        val expected = if (eligible) listOf(range("2027-01-08T10:00:00Z", "2027-01-08T12:00:00Z")) else emptyList()
        assertEquals(
            expected,
            result.calculation.recommendations.options
                .map { it.window },
        )
        assertEquals(selectedPolicy, (result.route.choice as FinalAnalysisRouteChoice.DslContextV2).policy)
        assertEquals(before, allRows())
    }

    private fun proofJson(): String {
        val base = w.f.claim("base", 18, 22)
        val bad = w.f.claim("bad", 19, 20, IrEffect.PREFERRED).copy(scope = f.preference.copy(dimension = IrDimension.PLACE))
        val document = w.document()
        w.firstResult(document)["conditions"] = listOf(f.wire(base), f.wire(bad))
        w.firstResult(document)["program"] =
            w.program(w.f.program(listOf(w.f.ref("base-ref", "base"), w.f.ref("bad-ref", "bad")), "base-ref", prefer = "bad-ref"))
        return w.encode(document)
    }

    private fun allRows(): Map<String, List<String>> =
        jdbc
            .queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
                String::class.java,
            ).map { requireNotNull(it) }
            .associateWith { table ->
                require(table.matches(Regex("[a-z_][a-z0-9_]*")))
                jdbc
                    .queryForList(
                        "SELECT to_jsonb(t)::text FROM \"$table\" t ORDER BY to_jsonb(t)::text",
                        String::class.java,
                    ).map { requireNotNull(it) }
            }

    private fun seed() {
        jdbc.update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at) VALUES (?,'syntheticir00000000001','synthetic IR2','REMOTE','Asia/Seoul','2027-01-04','2027-01-11','HOST_SPECIFIED',true,'CLOSED','MANUAL',?,?)",
            f.room,
            Timestamp.from(FIXED),
            Timestamp.from(f.reference),
        )
        for ((index, version) in listOf(f.first, f.second).withIndex()) {
            val participant = if (index == 0) f.firstParticipant else f.secondParticipant
            val text = if (index == 0) f.FIRST_TEXT else f.SECOND_TEXT
            require(text.isNotBlank())
            val guest = UUID(0, 501L + index)
            val head = UUID(0, 401L + index)
            jdbc.update(
                "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                guest,
                "synthetic-v2-guest-$index",
                Timestamp.from(PROCESSED.plusSeconds(86400)),
                Timestamp.from(f.reference),
            )
            jdbc.update(
                "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                participant,
                f.room,
                guest,
                if (index ==
                    0
                ) {
                    "HOST"
                } else {
                    "MEMBER"
                },
                "synthetic-$index",
                Timestamp.from(f.reference),
            )
            jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, f.room, participant)
            val evidence = mapper.writeValueAsString(q.input1().inputs[index].evidence)
            jdbc.update(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,7,?,'ko-KR',?,CAST(? AS jsonb))",
                version,
                head,
                text,
                Timestamp.from(f.reference.plusSeconds(300)),
                evidence,
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version, head)
        }
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", f.batch, f.room, Timestamp.from(FIXED))
        for (version in listOf(f.first, f.second)) {
            jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", f.batch, version)
        }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            f.run,
            f.room,
            f.batch,
            Timestamp.from(FIXED),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", f.run, f.room)
    }

    data class Observation(
        val transactionId: Long,
        val readOnly: String,
        val isolation: String,
    )

    class ObservedEnvelopeRepository(
        private val jdbc: JdbcTemplate,
        mapper: ObjectMapper,
    ) : ScheduleDslRepository {
        private val delegate = JdbcScheduleDslRepository(jdbc, mapper)
        val observations = mutableListOf<Observation>()

        override fun publish(
            envelope: ScheduleDslEnvelope,
            expectedRunVersion: Long,
            processedAt: Instant,
        ) = delegate.publish(envelope, expectedRunVersion, processedAt)

        override fun find(context: ScheduleIrContext): ScheduleDslEnvelope? {
            observations += observe(jdbc)
            return delegate.find(context)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class InactiveConfiguration {
        @Bean fun routes(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): FinalAnalysisRouteRegistry = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(PREPARED, ZoneOffset.UTC))

        @Bean fun envelopes(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ) = ObservedEnvelopeRepository(jdbc, mapper)

        @Bean fun oldRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrExecutionRegistry = JdbcScheduleIrExecutionRegistry(jdbc, mapper)

        @Bean fun parser(mapper: ObjectMapper): ScheduleDslResponseParser = StrictScheduleDslContextResponseParser(mapper)

        @Bean fun oldService(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            oldRegistry: ScheduleIrExecutionRegistry,
            envelopes: ScheduleDslRepository,
            parser: ScheduleDslResponseParser,
        ) = ScheduleDslAnalysisService(runs, rooms, submissions, oldRegistry, envelopes, parser)

        @Bean fun storedReader(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            routes: FinalAnalysisRouteRegistry,
            envelopes: ScheduleDslRepository,
        ) = ScheduleDslStoredReadService(runs, rooms, submissions, routes, envelopes)
    }

    companion object {
        private val FIXED = Instant.parse("2027-01-04T01:00:00Z")
        private val PREPARED = Instant.parse("2027-01-04T02:00:00Z")
        private val PROCESSED = Instant.parse("2027-01-04T02:01:00Z")
        private val postgres = mappedPortReadyPostgres().apply { start() }

        private fun range(
            start: String,
            end: String,
        ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

        private fun observe(jdbc: JdbcTemplate) =
            Observation(
                requireNotNull(jdbc.queryForObject("SELECT txid_current()", Long::class.java)),
                requireNotNull(jdbc.queryForObject("SHOW transaction_read_only", String::class.java)),
                requireNotNull(jdbc.queryForObject("SHOW transaction_isolation", String::class.java)),
            )

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
