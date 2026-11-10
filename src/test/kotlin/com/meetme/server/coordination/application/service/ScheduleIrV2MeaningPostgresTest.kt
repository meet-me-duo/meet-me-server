package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleIrResponseParser
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleIrV2ResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.ScheduleIrResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleIrV2ResponseParser
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrV2Repository
import com.meetme.server.submission.application.port.output.ScheduleIrRepository
import com.meetme.server.submission.application.port.output.ScheduleIrV2Repository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual strict decoding/JDBC/fresh reader/calculation using synthetic responses, no model or public route. */
@SpringBootTest
@Import(ScheduleIrV2MeaningPostgresTest.InactivePrecisionConfiguration::class)
class ScheduleIrV2MeaningPostgresTest {
    @Autowired private lateinit var service: ScheduleIrV2IntegrationService

    @Autowired private lateinit var v1Service: ScheduleIrIntegrationService

    @Autowired private lateinit var registry: ScheduleIrExecutionRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private val f = ScheduleIrV2SyntheticFixture
    private lateinit var legacyBefore: Map<String, List<String>>

    @BeforeEach
    fun seedKnownImmutableSources() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        seed()
        legacyBefore = legacySnapshot()
    }

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED"])
    fun `V2G01 known nonprecise claim survives strict parsing storage fresh reading and calculation without precise windows`(name: String) {
        val effect = IrEffect.valueOf(name)
        val ticket = prepare()
        val result = service.consume(ticket, f.json(listOf(f.condition(effect = effect))), PROCESSED)
        val fresh = assertNotNull(JdbcScheduleIrV2Repository(jdbc, mapper).findV2(ticket.context))

        assertEquals(result.envelope, fresh)
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(f.run))
        assertEquals(
            effect,
            fresh.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            fresh.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        val claim =
            result.prepared.evidence
                .getValue(ParticipantId(f.firstParticipant))
                .conditions
                .single()
        assertEquals(effect, claim.effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(IrInterpretation.EXPLICIT, claim.interpretation)
        assertEquals(IrPrecisionOutcome.NO_PRECISE_CANDIDATES, result.precisionOutcome)
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        assertTrue(result.candidates.candidates.isEmpty())
        assertTrue(result.recommendations.options.isEmpty())
        assertTrue(
            result.prepared.participants
                .single { it.participantId == ParticipantId(f.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
        assertEquals(result, service.replay(ticket))
        val stored =
            assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run))
        assertTrue(mapper.readTree(stored).path("results").all { !it.has("availability") })
        assertTrue(!stored.contains(f.FIRST_TEXT) && !stored.contains(f.SECOND_TEXT))
        assertEquals(
            SCHEDULE_IR_V2_VERSION,
            jdbc.queryForObject("SELECT semantic_version FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run),
        )
        assertEquals(legacyBefore, legacySnapshot())
    }

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED"])
    fun `V2G02 independent precise Friday survives an unscoped nonprecise positive claim after DB restoration`(name: String) {
        val effect = IrEffect.valueOf(name)
        val result = service.consume(prepare(), f.json(listOf(f.precise(), f.condition("nonprecise", effect))), PROCESSED)

        assertEquals(listOf(friday()), result.recommendations.options.map { it.window })
        assertEquals(
            listOf(friday()),
            result.candidates.candidates
                .single()
                .timeRanges,
        )
        assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, result.precisionOutcome)
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        assertEquals(
            0,
            result.recommendations.options
                .single()
                .variants
                .single()
                .preferenceCount,
        )
        assertEquals(
            effect,
            result.envelope.results
                .first()
                .conditions
                .single { it.id == "nonprecise" }
                .effect,
        )
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            result.prepared.evidence
                .getValue(ParticipantId(f.firstParticipant))
                .conditions
                .single {
                    it.conditionId ==
                        "nonprecise"
                }.temporalResolution,
        )
        assertEquals(result, service.replay(result.ticket))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `V2G03 an eligible known positive correction is not withheld merely because numeric precision is absent`() {
        val oldNegative = f.precise("old-negative", f.preference).copy(effect = IrEffect.UNAVAILABLE)
        val correction = f.condition("correction", supersedes = listOf(oldNegative.id))
        val result = service.consume(prepare(), f.json(listOf(f.precise("base"), oldNegative, correction)), PROCESSED)
        val claims =
            result.prepared.evidence
                .getValue(ParticipantId(f.firstParticipant))
                .conditions
                .associateBy { it.conditionId }

        assertEquals(listOf(friday()), result.recommendations.options.map { it.window })
        assertEquals(IrConditionDisposition.SUPERSEDED, claims.getValue("old-negative").disposition)
        assertEquals(IrEffect.AVAILABLE, claims.getValue("correction").effect)
        assertEquals(IrTemporalResolution.NON_PRECISE, claims.getValue("correction").temporalResolution)
        val restored = assertNotNull(JdbcScheduleIrV2Repository(jdbc, mapper).findV2(result.ticket.context))
        assertEquals(
            listOf("old-negative"),
            restored.results
                .first()
                .conditions
                .single { it.id == "correction" }
                .supersedes,
        )
        assertEquals(result, service.replay(result.ticket))
    }

    @Test
    fun `V2G04 eligibility can withhold calculation but cannot rewrite stored effect interpretation or resolution`() {
        val condition = f.condition(interpretation = IrInterpretation.INFERRED)
        val result = service.consume(prepare(), f.json(listOf(condition)), PROCESSED)
        val stored =
            assertNotNull(
                JdbcScheduleIrV2Repository(jdbc, mapper).findV2(result.ticket.context),
            ).results.first().conditions.single()
        val claim =
            result.prepared.evidence
                .getValue(ParticipantId(f.firstParticipant))
                .conditions
                .single()

        assertEquals(IrEffect.AVAILABLE, stored.effect)
        assertEquals(IrInterpretation.INFERRED, stored.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, stored.temporalResolution)
        assertEquals(IrEffect.AVAILABLE, claim.effect)
        assertEquals(IrInterpretation.INFERRED, claim.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(IrConditionDisposition.WITHHELD, claim.disposition)
        assertEquals(IrPrecisionOutcome.NO_PRECISE_CANDIDATES, result.precisionOutcome)
        assertTrue(result.recommendations.options.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["run_version", "source_revision", "response_contract", "full_policy"])
    fun `V2G05 existing run revision contract and full policy fences remain mandatory for IR2`(defect: String) {
        val original = prepare()
        val ticket =
            when (defect) {
                "run_version" -> {
                    jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", f.run)
                    original
                }
                "source_revision" -> {
                    jdbc.update("UPDATE submission_versions SET revision=8 WHERE id=?", f.first)
                    original
                }
                "response_contract" -> original.copy(identity = original.identity.copy(responseContractVersion = CONTRACT_V1))
                else -> original.copy(policy = policy().copy(allowExplicitUnconstrained = true))
            }
        assertFailsWith<RuntimeException> { service.consume(ticket, f.json(listOf(f.condition())), PROCESSED) }
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    @Test
    fun `V2G06 same run context policy and selection cannot switch only semantic and response contract identities`() {
        val v2 = prepare()
        val onlyIdentityChanged = v2.copy(identity = identityV1())
        assertEquals(v2.context, onlyIdentityChanged.context)
        assertEquals(v2.policy, onlyIdentityChanged.policy)
        assertFailsWith<RuntimeException> { registry.bind(onlyIdentityChanged) }
        assertFailsWith<RuntimeException> { v1Service.prepare(CoordinationRunId(f.run), 1, identityV1(), policy(), PREPARED) }
        assertEquals(v2, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(f.run))
        assertEquals(SCHEDULE_IR_V2_VERSION, v2.identity.irSemanticVersion)
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `V2G07 V1 and V2 coexist on separate runs and wrong semantic readers reject with matching context`() {
        val v1Ticket = v1Service.prepare(CoordinationRunId(f.run), 1, identityV1(), policy(), PREPARED)
        val v1 = v1Service.consume(v1Ticket, v1Json(), PROCESSED)
        val originalV1 = jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run)
        val secondRun = UUID(0, 801)
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            secondRun,
            f.room,
            f.batch,
            Timestamp.from(FIXED),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", secondRun, f.room)
        val v2Ticket = service.prepare(CoordinationRunId(secondRun), 1, identityV2(), policy(), PREPARED)
        val v2 = service.consume(v2Ticket, f.json(listOf(f.condition())), PROCESSED)

        assertEquals(2, count("schedule_ir_envelopes"))
        assertEquals(v1.envelope, JdbcScheduleIrRepository(jdbc, mapper).find(v1Ticket.context))
        assertEquals(v2.envelope, JdbcScheduleIrV2Repository(jdbc, mapper).findV2(v2Ticket.context))
        assertEquals(
            originalV1,
            jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run),
        )
        // Each wrong reader receives the exact stored row's own context, not a mismatching fingerprint.
        assertFailsWith<RuntimeException> { JdbcScheduleIrV2Repository(jdbc, mapper).findV2(v1Ticket.context) }
        assertFailsWith<RuntimeException> { JdbcScheduleIrRepository(jdbc, mapper).find(v2Ticket.context) }
        assertEquals(SCHEDULE_IR_VERSION, v1.envelope.semanticVersion)
        assertEquals(SCHEDULE_IR_V2_VERSION, v2.envelope.semanticVersion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["semantic", "format"])
    fun `V2G08 unknown persisted version is rejected without changing context or decoding as V1`(field: String) {
        val result = service.consume(prepare(), f.json(listOf(f.condition())), PROCESSED)
        if (field ==
            "semantic"
        ) {
            jdbc.update("UPDATE schedule_ir_envelopes SET semantic_version='schedule-ir-product-99' WHERE run_id=?", f.run)
        } else {
            jdbc.update("UPDATE schedule_ir_envelopes SET format_version=99 WHERE run_id=?", f.run)
        }
        assertFailsWith<RuntimeException> { JdbcScheduleIrV2Repository(jdbc, mapper).findV2(result.ticket.context) }
        assertEquals(1, count("schedule_ir_envelopes"))
    }

    @Test
    fun `V2G09 failed calculation preserves prepared semantic selection and rolls back consumed IR2`() {
        val selected = policy().copy(alternativeChoices = listOf(IrAlternativeChoice(UUID(0, 999), "foreign", "A")))
        val ticket = service.prepare(CoordinationRunId(f.run), 1, identityV2(), selected, PREPARED)
        assertFailsWith<RuntimeException> { service.consume(ticket, f.json(listOf(f.condition())), PROCESSED) }
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(f.run))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `V2G10 failed source reference write cannot leave a V2 envelope or erase the prepared binding`() {
        val ticket = prepare()
        jdbc.execute(
            "CREATE FUNCTION fail_v2_reference() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.submission_version_id = ''00000000-0000-0000-0000-000000000102''::uuid THEN RAISE EXCEPTION ''synthetic V2 reference failure''; END IF; RETURN NEW; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_v2_reference BEFORE INSERT ON schedule_ir_inputs FOR EACH ROW EXECUTE FUNCTION fail_v2_reference()",
        )
        try {
            assertFailsWith<RuntimeException> { service.consume(ticket, f.json(listOf(f.condition())), PROCESSED) }
            assertEquals(ticket, registry.find(f.run))
            assertEquals(0, count("schedule_ir_envelopes"))
            assertEquals(0, count("schedule_ir_inputs"))
            assertEquals(legacyBefore, legacySnapshot())
        } finally {
            jdbc.execute("DROP TRIGGER fail_v2_reference ON schedule_ir_inputs")
            jdbc.execute("DROP FUNCTION fail_v2_reference()")
        }
    }

    @Test
    fun `V2G11 consume joins the caller transaction while independently prepared binding survives rollback`() {
        val ticket = prepare()
        val marker = IllegalStateException("synthetic V2 consume outer rollback")
        var consumedInsideCaller = false
        val failure =
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(transactions).executeWithoutResult {
                    val result = service.consume(ticket, f.json(listOf(f.precise())), PROCESSED)
                    assertEquals(IrCalculationStatus.CALCULATED, result.status)
                    assertEquals(IrPrecisionOutcome.PRECISE_CANDIDATES, result.precisionOutcome)
                    assertEquals(listOf(friday()), result.recommendations.options.map { it.window })
                    assertEquals(1, count("schedule_ir_execution_bindings"))
                    assertEquals(1, count("schedule_ir_envelopes"))
                    assertEquals(2, count("schedule_ir_inputs"))
                    consumedInsideCaller = true
                    throw marker
                }
            }
        assertTrue(failure === marker, "The outer rollback marker, not an earlier service exception, must escape")
        assertTrue(consumedInsideCaller)
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(f.run))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `V2G12 V1 prepared identity cannot switch to V2 with identical context policy and selection`() {
        val v1 = v1Service.prepare(CoordinationRunId(f.run), 1, identityV1(), policy(), PREPARED)
        val onlyIdentityChanged = v1.copy(identity = identityV2())
        assertEquals(v1.context, onlyIdentityChanged.context)
        assertEquals(v1.policy, onlyIdentityChanged.policy)
        assertFailsWith<RuntimeException> { registry.bind(onlyIdentityChanged) }
        assertFailsWith<RuntimeException> { service.prepare(CoordinationRunId(f.run), 1, identityV2(), policy(), PREPARED) }
        assertEquals(v1, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(f.run))
        assertEquals(SCHEDULE_IR_VERSION, v1.identity.irSemanticVersion)
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    private fun prepare() = service.prepare(CoordinationRunId(f.run), 1, identityV2(), policy(), PREPARED)

    private fun identityV1() = IrExecutionIdentity(CONTRACT_V1, SCHEDULE_IR_VERSION, f.POLICY)

    private fun identityV2() = IrExecutionIdentity(CONTRACT_V2, SCHEDULE_IR_V2_VERSION, f.POLICY)

    private fun policy() = IrMatchingPolicy(f.POLICY, setOf(IrInterpretation.EXPLICIT), emptyList(), false)

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun friday() = InstantTimeRange(Instant.parse("2027-01-08T09:00:00Z"), Instant.parse("2027-01-08T13:00:00Z"))

    private fun v1Json(): String {
        val first = f.wire(f.precise()).apply { remove("temporal_resolution") }
        val second = f.wire(f.precise(firstSource = false)).apply { remove("temporal_resolution") }
        return f.encode(
            mapOf(
                "semantic_version" to SCHEDULE_IR_VERSION,
                "results" to
                    listOf(
                        mapOf("input_ref" to f.first.toString(), "availability" to "KNOWN_WINDOWS", "conditions" to listOf(first)),
                        mapOf(
                            "input_ref" to f.second.toString(),
                            "availability" to "KNOWN_WINDOWS",
                            "conditions" to listOf(second),
                        ),
                    ),
            ),
        )
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
            val evidence =
                "{\"originalRevision\":3,\"referenceAt\":\"2027-01-04T00:00:00Z\",\"supplement\":null,\"interpretation\":\"PENDING\",\"unresolvedReason\":null,\"protocol\":\"PRE_SUBMIT_V2\"}"
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
            jdbc.update(
                "INSERT INTO structured_submission_results(batch_id,submission_version_id,conditions,rejection_code,processed_at) VALUES (?,?,'[]'::jsonb,'AMBIGUOUS_INPUT',?)",
                f.batch,
                version,
                Timestamp.from(FIXED),
            )
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

    private fun legacySnapshot(): Map<String, List<String>> =
        listOf(
            "meeting_rooms",
            "participants",
            "submission_heads",
            "submission_versions",
            "submission_batches",
            "submission_batch_items",
            "structured_submission_results",
            "coordination_runs",
            "candidates",
            "recommendation_analyses",
            "recommendation_options",
            "recommendation_variants",
            "recommendation_selections",
        ).associateWith { table ->
            jdbc
                .queryForList(
                    "SELECT to_jsonb(t)::text FROM $table t ORDER BY to_jsonb(t)::text",
                    String::class.java,
                ).map {
                    requireNotNull(it)
                }
        }

    @TestConfiguration(proxyBeanMethods = false)
    class InactivePrecisionConfiguration {
        @Bean fun precisionRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrExecutionRegistry = JdbcScheduleIrExecutionRegistry(jdbc, mapper)

        @Bean fun precisionV1Parser(mapper: ObjectMapper): ScheduleIrResponseParser = StrictScheduleIrResponseParser(mapper, CONTRACT_V1)

        @Bean fun precisionV2Parser(mapper: ObjectMapper): ScheduleIrV2ResponseParser =
            StrictScheduleIrV2ResponseParser(
                mapper,
                CONTRACT_V2,
            )

        @Bean fun precisionV2Repository(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrV2Repository = JdbcScheduleIrV2Repository(jdbc, mapper)

        @Bean fun precisionV1Service(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            registry: ScheduleIrExecutionRegistry,
            irRepository: ScheduleIrRepository,
            parser: ScheduleIrResponseParser,
        ) = ScheduleIrIntegrationService(runs, rooms, submissions, registry, irRepository, parser)

        @Bean fun precisionV2Service(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            registry: ScheduleIrExecutionRegistry,
            irRepository: ScheduleIrV2Repository,
            parser: ScheduleIrV2ResponseParser,
        ) = ScheduleIrV2IntegrationService(runs, rooms, submissions, registry, irRepository, parser)
    }

    companion object {
        private const val CONTRACT_V1 = "synthetic-response-v1"
        private const val CONTRACT_V2 = "synthetic-response-v2"
        private val FIXED = Instant.parse("2027-01-04T01:00:00Z")
        private val PREPARED = Instant.parse("2027-01-04T02:00:00Z")
        private val PROCESSED = Instant.parse("2027-01-04T02:01:00Z")
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
