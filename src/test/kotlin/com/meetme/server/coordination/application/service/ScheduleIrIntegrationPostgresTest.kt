package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.ScheduleIrProviderContract
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleIrResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.ScheduleIrResponseParser
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.MatchOutcome
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrRepository
import com.meetme.server.submission.application.port.output.ScheduleIrRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.support.mappedPortReadyPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
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
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Explicit fixture-only entry point: actual parser, database, fresh readers and unchanged matching kernels. */
@SpringBootTest
@Import(ScheduleIrIntegrationPostgresTest.InactiveIntegrationConfiguration::class)
class ScheduleIrIntegrationPostgresTest {
    @Autowired private lateinit var service: ScheduleIrIntegrationService

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var registry: ScheduleIrExecutionRegistry

    @Autowired private lateinit var irRepository: ScheduleIrRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager
    private var fixtureName = "explicit-remote"
    private lateinit var legacyBefore: Map<String, List<String>>

    @BeforeEach
    fun seedFrozenSyntheticRemoteCohort() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        fixtureName = "explicit-remote"
        seed(fixtureName)
        legacyBefore = legacySnapshot()
    }

    @Test
    fun `I01 I02 JSON parser storage fresh reader and both matching outputs agree on exact explicit remote overlap`() {
        val ticket = prepare()
        val result = service.consume(ticket, response(fixtureName), PROCESSED)
        val freshRegistry = JdbcScheduleIrExecutionRegistry(jdbc, mapper)
        val freshIr = JdbcScheduleIrRepository(jdbc, mapper)

        assertEquals(ticket, result.ticket)
        assertEquals(ticket, freshRegistry.find(RUN))
        assertEquals(result.envelope, freshIr.find(ticket.context))
        assertEquals(IrCalculationStatus.CALCULATED, result.status)
        assertEquals(SCHEDULE_IR_VERSION, result.envelope.semanticVersion)
        assertEquals(
            setOf(FIRST_VERSION, SECOND_VERSION),
            result.envelope.results
                .map { it.submissionVersionId }
                .toSet(),
        )
        assertEquals(
            listOf(commonFriday()),
            result.candidates.candidates
                .single()
                .timeRanges,
        )
        assertEquals(
            PlanType.B,
            result.candidates.candidates
                .single()
                .planType,
        )
        assertEquals(
            MeetingMode.REMOTE,
            result.candidates.candidates
                .single()
                .meetingMode,
        )
        assertEquals(
            listOf(ParticipantId(FIRST_PARTICIPANT), ParticipantId(SECOND_PARTICIPANT)),
            result.candidates.candidates
                .single()
                .participantIds,
        )
        assertEquals(MatchOutcome.READY, result.candidates.outcome)
        assertEquals(listOf(0), result.recommendations.primaryOptionIndexes)
        assertEquals(
            commonFriday(),
            result.recommendations.options
                .single()
                .window,
        )
        val variant =
            result.recommendations.options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.REMOTE, variant.meetingMode)
        assertEquals(listOf(ParticipantId(FIRST_PARTICIPANT), ParticipantId(SECOND_PARTICIPANT)), variant.participantIds)
        assertTrue(variant.unverifiedPlaceParticipantIds.isEmpty())
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
        val stored = jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java)!!
        val rawSources = sources(fixtureName)
        assertTrue(rawSources.values.none { stored.contains(it) })
        val selected = jdbc.queryForObject("SELECT selection::text FROM schedule_ir_execution_bindings", String::class.java)!!
        assertTrue(rawSources.values.none { selected.contains(it) })
        assertTrue(!mapper.readTree(selected).toString().contains("\"quote\""))
        assertTrue(!mapper.readTree(stored).toString().contains("\"quote\""))
        assertTrue(result.selectionFingerprint.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun `I03 identical prepare and consume preserve the first immutable ticket time and replay deterministically`() {
        val first = prepare()
        val repeated = service.prepare(CoordinationRunId(RUN), 1, identity(), policy(), PREPARED.plusSeconds(10))
        assertEquals(first, repeated)
        assertEquals(PREPARED, repeated.preparedAt)
        val result = service.consume(first, response(fixtureName), PROCESSED)
        assertEquals(result, service.consume(first, response(fixtureName), PROCESSED.plusSeconds(1)))
        assertEquals(result, service.replay(first))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `I31 prepare choice survives an enclosing caller rollback and remains readable from a fresh registry`() {
        var ticket: IrExecutionTicket? = null
        assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactions).executeWithoutResult {
                ticket = prepare()
                throw IllegalStateException("synthetic outer rollback")
            }
        }
        assertEquals(assertNotNull(ticket), JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `I25 failed strict JSON leaves prepared identity but no envelope or references`() {
        val ticket = prepare()
        assertFailsWith<RuntimeException> { service.consume(ticket, "{}", PROCESSED) }
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `I34 reference write failure rolls back consumed IR while independently prepared binding survives`() {
        val ticket = prepare()
        jdbc.execute(
            "CREATE FUNCTION fail_integration_reference() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.submission_version_id = ''00000000-0000-0000-0000-000000000102''::uuid THEN RAISE EXCEPTION ''synthetic reference failure''; END IF; RETURN NEW; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_integration_reference BEFORE INSERT ON schedule_ir_inputs FOR EACH ROW EXECUTE FUNCTION fail_integration_reference()",
        )
        try {
            assertFailsWith<RuntimeException> { service.consume(ticket, response(fixtureName), PROCESSED) }
            assertEquals(ticket, registry.find(RUN))
            assertEquals(1, count("schedule_ir_execution_bindings"))
            assertEquals(0, count("schedule_ir_envelopes"))
            assertEquals(0, count("schedule_ir_inputs"))
            assertEquals(legacyBefore, legacySnapshot())
        } finally {
            jdbc.execute("DROP TRIGGER fail_integration_reference ON schedule_ir_inputs")
            jdbc.execute("DROP FUNCTION fail_integration_reference()")
        }
    }

    @Test
    fun `I30 foreign branch choice fails calculation with no consumed publication and prepared policy still fixed`() {
        val selected = policy(choices = listOf(IrAlternativeChoice(UUID(0, 999), "clock", "AM")))
        val ticket = prepare(selected)
        assertFailsWith<RuntimeException> { service.consume(ticket, response(fixtureName), PROCESSED) }
        assertEquals(ticket, registry.find(RUN))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["response_contract", "semantic", "policy_id"])
    fun `I30 unsupported or inconsistent execution identity never creates a binding`(field: String) {
        val changed =
            when (field) {
                "response_contract" -> identity().copy(responseContractVersion = "unsupported-contract")
                "semantic" -> identity().copy(irSemanticVersion = "unsupported-ir")
                else -> identity().copy(policyVersion = "different-policy")
            }
        assertFailsWith<RuntimeException> { service.prepare(CoordinationRunId(RUN), 1, changed, policy(), PREPARED) }
        assertEquals(0, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["labels", "unconstrained", "alternative_choices"])
    fun `I31 same policy version with a different complete selection cannot replace the original`(field: String) {
        val first = prepare()
        val changed =
            when (field) {
                "labels" -> policy(labels = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED))
                "unconstrained" -> policy(unconstrained = true)
                else -> policy(choices = listOf(IrAlternativeChoice(FIRST_VERSION, "clock", "AM")))
            }
        assertFailsWith<RuntimeException> { prepare(changed) }
        assertEquals(first, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(1, count("schedule_ir_execution_bindings"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "contract", "policy", "source_revision", "prepared_at", "meeting_mode"])
    fun `I31 caller ticket cannot substitute any originally bound identity policy revision or time`(field: String) {
        val first = prepare()
        val changed =
            when (field) {
                "version" -> first.copy(expectedRunVersion = 2)
                "contract" -> first.copy(identity = first.identity.copy(responseContractVersion = "other"))
                "policy" -> first.copy(policy = policy(unconstrained = true))
                "prepared_at" -> first.copy(preparedAt = first.preparedAt.plusSeconds(1))
                "meeting_mode" -> first.copy(meetingMode = MeetingMode.IN_PERSON)
                else ->
                    first.copy(
                        context =
                            first.context.copy(
                                inputs =
                                    first.context.inputs.mapIndexed { index, input ->
                                        if (index ==
                                            0
                                        ) {
                                            input.copy(submissionRevision = 8)
                                        } else {
                                            input
                                        }
                                    },
                            ),
                    )
            }
        assertFailsWith<RuntimeException> { service.consume(changed, response(fixtureName), PROCESSED) }
        assertEquals(first, registry.find(RUN))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `I31 a legacy unbound IR envelope cannot be retroactively adopted by preparing a choice`() {
        val ticket = prepare()
        service.consume(ticket, response(fixtureName), PROCESSED)
        jdbc.update("DELETE FROM schedule_ir_execution_bindings WHERE run_id=?", RUN)
        assertFailsWith<RuntimeException> { prepare() }
        assertEquals(0, count("schedule_ir_execution_bindings"))
        assertEquals(1, count("schedule_ir_envelopes"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["run_version", "inactive", "completed", "open_round", "confirmed", "meeting_mode"])
    fun `I26 stale execution between prepare and consume cannot publish an envelope`(field: String) {
        val ticket = prepare()
        when (field) {
            "run_version" -> jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", RUN)
            "inactive" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", ROOM)
            "meeting_mode" -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", ROOM)
            "open_round", "confirmed" -> protectExecution(field)
            else -> jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE' WHERE id=?", RUN)
        }
        assertFailsWith<RuntimeException> { service.consume(ticket, response(fixtureName), PROCESSED) }
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["raw", "submission_revision", "original_revision", "owner", "zone", "range"])
    fun `I28 stored frozen context corruption after prepare is not accepted merely because input IDs match`(field: String) {
        val ticket = prepare()
        when (field) {
            "raw" -> jdbc.update("UPDATE submission_versions SET raw_text='different synthetic source' WHERE id=?", FIRST_VERSION)
            "submission_revision" -> jdbc.update("UPDATE submission_versions SET revision=8 WHERE id=?", FIRST_VERSION)
            "original_revision" ->
                jdbc.update(
                    "UPDATE submission_versions SET input_evidence=jsonb_set(input_evidence,'{originalRevision}','4') WHERE id=?",
                    FIRST_VERSION,
                )
            "owner" -> {
                val guest = UUID(0, 503)
                val otherOwner = UUID(0, 303)
                jdbc.update(
                    "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,'synthetic-third-guest',?,?)",
                    guest,
                    Timestamp.from(PROCESSED.plusSeconds(86400)),
                    Timestamp.from(REFERENCE),
                )
                jdbc.update(
                    "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,'MEMBER','synthetic-third',?)",
                    otherOwner,
                    ROOM,
                    guest,
                    Timestamp.from(REFERENCE),
                )
                jdbc.update("UPDATE submission_heads SET participant_id=? WHERE id=?", otherOwner, FIRST_HEAD)
            }
            "zone" -> jdbc.update("UPDATE meeting_rooms SET time_zone_id='UTC' WHERE id=?", ROOM)
            else -> jdbc.update("UPDATE meeting_rooms SET search_end_date='2027-01-12' WHERE id=?", ROOM)
        }
        assertFailsWith<RuntimeException> { service.consume(ticket, response(fixtureName), PROCESSED) }
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `I29 a later mutable head does not substitute sources for frozen version IDs`() {
        val ticket = prepare()
        val newer = UUID(0, 999)
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES (?,?,8,'later synthetic source','ko-KR',?)",
            newer,
            FIRST_HEAD,
            Timestamp.from(PROCESSED),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", newer, FIRST_HEAD)
        val result = service.consume(ticket, response(fixtureName), PROCESSED)
        assertEquals(
            listOf(commonFriday()),
            result.candidates.candidates
                .single()
                .timeRanges,
        )
        assertEquals(
            setOf(FIRST_VERSION, SECOND_VERSION),
            result.envelope.results
                .map { it.submissionVersionId }
                .toSet(),
        )
        assertEquals(result, service.replay(ticket))
    }

    @ParameterizedTest
    @ValueSource(strings = ["IN_PERSON", "EITHER"])
    fun `unsupported typed place and mode do not acquire remote calculation or confirmation semantics`(mode: String) {
        jdbc.update("UPDATE meeting_rooms SET meeting_mode=? WHERE id=?", mode, ROOM)
        legacyBefore = legacySnapshot()
        val result = service.consume(prepare(), response(fixtureName), PROCESSED)
        assertEquals(IrCalculationStatus.REQUIRES_DECISION, result.status)
        assertTrue(result.candidates.candidates.isEmpty())
        assertTrue(result.recommendations.options.isEmpty())
        assertEquals(MeetingMode.valueOf(mode), result.ticket.meetingMode)
        assertEquals(legacyBefore, legacySnapshot())
    }

    @ParameterizedTest
    @ValueSource(strings = ["bounded-saturday-unknown", "invalid-negative-global-influence"])
    fun `I05 I06 scoped unknown and corrupt evidence have different influence after the same storage path`(name: String) {
        replaceFixture(name)
        val result = service.consume(prepare(), response(name), PROCESSED)
        val evidence = result.prepared.evidence.getValue(ParticipantId(FIRST_PARTICIPANT))
        assertTrue(evidence.unresolvedTime)
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        if (name == "bounded-saturday-unknown") {
            assertEquals(listOf(commonFriday()), result.recommendations.options.map { it.window })
            assertEquals(0, evidence.quarantineCount)
            assertEquals(
                "2027-01-09T00:00",
                evidence.conditions
                    .single { it.conditionId == "sat-unknown" }
                    .scope!!
                    .start
                    .toString(),
            )
        } else {
            assertTrue(result.recommendations.options.isEmpty())
            assertEquals(1, evidence.quarantineCount)
            assertTrue(
                result.envelope.results
                    .first()
                    .quarantined
                    .all { it.scope == null },
            )
        }
        assertEquals(result.envelope, JdbcScheduleIrRepository(jdbc, mapper).find(result.ticket.context))
        assertEquals(result, service.replay(result.ticket))
    }

    @ParameterizedTest
    @ValueSource(strings = ["known-empty", "unrestricted", "unknown-no-positive"])
    fun `I15 I16 I17 no result conflates empty unknown and explicitly unconstrained states`(name: String) {
        replaceFixture(name)
        val result = service.consume(prepare(policy(unconstrained = true)), response(name), PROCESSED)
        val state =
            when (name) {
                "known-empty" -> IrAvailabilityState.EMPTY
                "unrestricted" -> IrAvailabilityState.UNCONSTRAINED
                else -> IrAvailabilityState.UNKNOWN
            }
        assertEquals(
            state,
            result.envelope.results
                .first()
                .availability,
        )
        assertEquals(
            if (state ==
                IrAvailabilityState.UNKNOWN
            ) {
                IrCalculationStatus.PARTIAL
            } else {
                IrCalculationStatus.CALCULATED
            },
            result.status,
        )
        assertEquals(
            state,
            result.prepared.evidence
                .getValue(ParticipantId(FIRST_PARTICIPANT))
                .declaredAvailability,
        )
        if (name == "unrestricted") {
            assertEquals(listOf(instant("2027-01-08T10:00:00Z", "2027-01-08T13:00:00Z")), result.recommendations.options.map { it.window })
        } else {
            assertTrue(result.recommendations.options.isEmpty())
        }
        assertEquals(result, service.replay(result.ticket))
    }

    @ParameterizedTest
    @ValueSource(strings = ["AM", "PM", "UNSELECTED"])
    fun `I12 I13 alternative choices remain exclusive through parser storage fresh read and replay`(choice: String) {
        replaceFixture("exclusive-am-pm")
        val choices = if (choice == "UNSELECTED") emptyList() else listOf(IrAlternativeChoice(FIRST_VERSION, "clock", choice))
        val result =
            service.consume(
                prepare(policy(labels = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED), choices = choices)),
                response(fixtureName),
                PROCESSED,
            )
        val expected =
            when (choice) {
                "AM" -> listOf(instant("2027-01-07T22:00:00Z", "2027-01-07T23:00:00Z"))
                "PM" -> listOf(instant("2027-01-08T10:00:00Z", "2027-01-08T11:00:00Z"))
                else -> emptyList()
            }
        assertEquals(expected, result.recommendations.options.map { it.window })
        assertEquals(
            setOf("AM", "PM"),
            result.envelope.results
                .first()
                .conditions
                .map { it.alternative }
                .toSet(),
        )
        assertEquals(result, service.replay(result.ticket))
    }

    @ParameterizedTest
    @ValueSource(strings = ["asserted-correction", "assertion-retracted", "assertion-quoted_other"])
    fun `I20 I21 I22 correction and nonasserted history survive the full parser and storage path`(name: String) {
        replaceFixture(name)
        val result = service.consume(prepare(), response(name), PROCESSED)
        if (name == "asserted-correction") {
            assertEquals(listOf(instant("2027-01-08T11:00:00Z", "2027-01-08T13:00:00Z")), result.recommendations.options.map { it.window })
            assertEquals(
                listOf("old"),
                result.envelope.results
                    .first()
                    .conditions
                    .single { it.id == "corrected" }
                    .supersedes,
            )
        } else {
            assertTrue(result.recommendations.options.isEmpty())
            val assertion = if (name.endsWith("retracted")) IrAssertion.RETRACTED else IrAssertion.QUOTED_OTHER
            assertEquals(
                assertion,
                result.envelope.results
                    .first()
                    .conditions
                    .single()
                    .assertion,
            )
        }
        assertEquals(result, service.replay(result.ticket))
    }

    @Test
    fun `I09 inferred meaning is retained but evaluation uses the exact separately selected eligibility`() {
        replaceFixture("available-label-inferred")
        val result = service.consume(prepare(), response(fixtureName), PROCESSED)
        assertEquals(
            IrInterpretation.INFERRED,
            result.envelope.results
                .first()
                .conditions
                .single()
                .interpretation,
        )
        assertTrue(result.recommendations.options.isEmpty())
        assertEquals(setOf(IrInterpretation.EXPLICIT), registry.find(RUN)!!.policy.eligibleInterpretations)
    }

    @ParameterizedTest
    @ValueSource(strings = ["identical", "different"])
    fun `I32 I33 concurrent first preparation persists one whole immutable policy and rejects a different selection`(kind: String) {
        val alternate = if (kind == "identical") policy() else policy(unconstrained = true)
        val executor = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val tasks =
                listOf(policy(), alternate).map { selected ->
                    executor.submit<Any> {
                        check(gate.await(20, TimeUnit.SECONDS))
                        try {
                            prepare(selected)
                        } catch (failure: RuntimeException) {
                            failure
                        }
                    }
                }
            gate.countDown()
            val outcomes = tasks.map { it.get(30, TimeUnit.SECONDS) }
            val tickets = outcomes.filterIsInstance<IrExecutionTicket>()
            assertEquals(if (kind == "identical") 2 else 1, tickets.size)
            val stored = assertNotNull(JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
            assertTrue(tickets.all { it == stored })
            assertEquals(if (kind == "identical") 0 else 1, outcomes.count { it is RuntimeException })
            assertEquals(1, count("schedule_ir_execution_bindings"))
            assertEquals(0, count("schedule_ir_envelopes"))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `I37 deleting a prepared run removes only new dependent bindings while frozen source versions remain`() {
        val ticket = prepare()
        service.consume(ticket, response(fixtureName), PROCESSED)
        jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", ROOM)
        jdbc.update("DELETE FROM coordination_runs WHERE id=?", RUN)
        assertEquals(0, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(2, count("submission_versions"))
        assertEquals(2, count("submission_batch_items"))
    }

    @Test
    fun `I36 V24 upgrades add a separate empty choice table without inventing identity or rewriting existing data`() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        flyway("24").migrate()
        seed("explicit-remote")
        val before = legacySnapshot()
        flyway().migrate()
        flyway().validate()
        assertEquals(
            "31",
            flyway()
                .info()
                .current()!!
                .version.version,
        )
        assertEquals(before, legacySnapshot())
        assertEquals(0, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `inactive integration service is actually transaction proxied in this explicit test configuration`() {
        assertTrue(AopUtils.isAopProxy(service))
    }

    @Test
    fun `a prepared ticket alone is not a consumed IR result or an implicit replay fallback`() {
        val ticket = prepare()
        assertNull(irRepository.find(ticket.context))
        assertFailsWith<RuntimeException> { service.replay(ticket) }
        assertEquals(ticket, registry.find(RUN))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `prepare makes a defensive immutable selection rather than retaining caller mutable collections`() {
        val labels = mutableSetOf(IrInterpretation.EXPLICIT)
        val choices = mutableListOf<IrAlternativeChoice>()
        val supplied = IrMatchingPolicy(POLICY, labels, choices, false)
        val ticket = prepare(supplied)
        labels += IrInterpretation.INFERRED
        choices += IrAlternativeChoice(FIRST_VERSION, "clock", "AM")

        assertEquals(setOf(IrInterpretation.EXPLICIT), ticket.policy.eligibleInterpretations)
        assertTrue(ticket.policy.alternativeChoices.isEmpty())
        val stored = assertNotNull(JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(setOf(IrInterpretation.EXPLICIT), stored.policy.eligibleInterpretations)
        assertTrue(stored.policy.alternativeChoices.isEmpty())
        assertEquals(ticket, stored)
    }

    @ParameterizedTest
    @ValueSource(strings = ["format", "semantic_column", "scalar_selection", "unknown_selection_field"])
    fun `I35 fresh execution registry refuses inconsistent versions or lossy selection JSON`(defect: String) {
        prepare()
        when (defect) {
            "format" -> jdbc.update("UPDATE schedule_ir_execution_bindings SET format_version=99 WHERE run_id=?", RUN)
            "semantic_column" ->
                jdbc.update(
                    "UPDATE schedule_ir_execution_bindings SET semantic_version='unsupported-ir' WHERE run_id=?",
                    RUN,
                )
            "scalar_selection" ->
                jdbc.update(
                    "UPDATE schedule_ir_execution_bindings SET selection=jsonb_set(selection,'{expectedRunVersion}','1.9') WHERE run_id=?",
                    RUN,
                )
            else ->
                jdbc.update(
                    "UPDATE schedule_ir_execution_bindings SET selection=selection || '{\"unknown_field\":true}'::jsonb WHERE run_id=?",
                    RUN,
                )
        }
        assertFailsWith<RuntimeException> { JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN) }
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `I03 conflicting second model response cannot replace a published envelope under the same ticket`() {
        val ticket = prepare()
        val first = service.consume(ticket, response(fixtureName), PROCESSED)
        val changed = response(fixtureName).replace("\"interpretation\": \"EXPLICIT\"", "\"interpretation\": \"INFERRED\"")
        assertTrue(changed != response(fixtureName))

        assertFailsWith<RuntimeException> { service.consume(ticket, changed, PROCESSED.plusSeconds(1)) }
        assertEquals(first, service.replay(ticket))
        assertEquals(first.envelope, JdbcScheduleIrRepository(jdbc, mapper).find(ticket.context))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "inactive", "completed", "open_round", "confirmed"])
    fun `I26 invalid first execution cannot claim a new persistent selection`(defect: String) {
        if (defect == "inactive") jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", ROOM)
        if (defect ==
            "completed"
        ) {
            jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE' WHERE id=?", RUN)
        }
        if (defect in setOf("open_round", "confirmed")) protectExecution(defect)
        assertFailsWith<RuntimeException> {
            service.prepare(
                CoordinationRunId(RUN),
                if (defect ==
                    "version"
                ) {
                    2
                } else {
                    1
                },
                identity(),
                policy(),
                PREPARED,
            )
        }
        assertEquals(0, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `I28 accepted supplement digest remains frozen even when model evidence cites only the original source`() {
        val original = sources(fixtureName).getValue(FIRST_VERSION)
        val start = original.codePointCount(0, original.indexOf("금요일"))
        val answeredAt = REFERENCE.plusSeconds(60).toString()
        val evidence =
            mapOf(
                "originalRevision" to 3,
                "referenceAt" to REFERENCE.toString(),
                "supplement" to
                    mapOf(
                        "questionId" to UUID(0, 603).toString(),
                        "sourceRevision" to 3,
                        "sourceExcerpt" to "금요일",
                        "sourceStartCodePoint" to start,
                        "prompt" to "합성 보완 질문",
                        "answerText" to "합성 보완 답변",
                        "choiceId" to null,
                        "answeredAt" to answeredAt,
                        "referenceAt" to answeredAt,
                    ),
                "interpretation" to "PENDING",
                "unresolvedReason" to null,
                "protocol" to "PRE_SUBMIT_V2",
            )
        jdbc.update(
            "UPDATE submission_versions SET input_evidence=CAST(? AS jsonb) WHERE id=?",
            mapper.writeValueAsString(evidence),
            FIRST_VERSION,
        )
        val ticket = prepare()
        assertEquals(
            2,
            ticket.context.inputs
                .first()
                .sources.size,
        )
        assertEquals(
            setOf("original", "supplement:00000000-0000-0000-0000-00000000025b"),
            ticket.context.inputs
                .first()
                .sources
                .map {
                    it.sourceId
                }.toSet(),
        )
        jdbc.update(
            "UPDATE submission_versions SET input_evidence=jsonb_set(input_evidence,'{supplement,answerText}','\"different synthetic answer\"') WHERE id=?",
            FIRST_VERSION,
        )

        assertFailsWith<RuntimeException> { service.consume(ticket, response(fixtureName), PROCESSED) }
        assertEquals(ticket, registry.find(RUN))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    @Test
    fun `I36 upgrading existing V24 typed IR does not backfill a run identity or rewrite stored evidence`() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        flyway("24").migrate()
        seed("explicit-remote")
        val oldRun = assertNotNull(runs.findById(CoordinationRunId(RUN)))
        val frozen = FrozenSubmissionReader.read(submissions, oldRun.batch)
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                oldRun,
                frozen,
                ZoneId.of("Asia/Seoul"),
                LocalDate.parse("2027-01-04"),
                LocalDate.parse("2027-01-11"),
                POLICY,
            )
        // Existing foundation boundary creates historical V24 data, not the oracle for new integration behavior.
        val oldEnvelope =
            ScheduleIrProviderContract(
                mapper,
            ).parseProviderResponse(response("explicit-remote"), snapshot, SCHEDULE_IR_VERSION)
        JdbcScheduleIrRepository(jdbc, mapper).publish(oldEnvelope, 1, PROCESSED)
        val oldRows = jdbc.queryForList("SELECT to_jsonb(t)::text FROM schedule_ir_envelopes t", String::class.java)
        val oldReferences =
            jdbc.queryForList(
                "SELECT to_jsonb(t)::text FROM schedule_ir_inputs t ORDER BY submission_version_id",
                String::class.java,
            )
        val oldBusiness = legacySnapshot()

        flyway().migrate()
        flyway().validate()
        assertEquals(
            "31",
            flyway()
                .info()
                .current()!!
                .version.version,
        )
        assertEquals(oldRows, jdbc.queryForList("SELECT to_jsonb(t)::text FROM schedule_ir_envelopes t", String::class.java))
        assertEquals(
            oldReferences,
            jdbc.queryForList("SELECT to_jsonb(t)::text FROM schedule_ir_inputs t ORDER BY submission_version_id", String::class.java),
        )
        assertEquals(oldBusiness, legacySnapshot())
        assertEquals(0, count("schedule_ir_execution_bindings"))
        assertFailsWith<RuntimeException> { prepare() }
        assertEquals(0, count("schedule_ir_execution_bindings"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "run_version", "inactive", "completed", "open_round", "confirmed",
            "meeting_mode", "source_revision", "source_digest", "room_zone",
        ],
    )
    fun `I26 I28 successful consumption cannot make stale or protected replay current again`(reason: String) {
        val ticket = prepare()
        service.consume(ticket, response(fixtureName), PROCESSED)
        val envelopeRows = jdbc.queryForList("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java)
        val selectionRows = jdbc.queryForList("SELECT selection::text FROM schedule_ir_execution_bindings", String::class.java)
        when (reason) {
            "run_version" -> jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", RUN)
            "inactive" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", ROOM)
            "completed" -> jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE' WHERE id=?", RUN)
            "open_round", "confirmed" -> protectExecution(reason)
            "meeting_mode" -> jdbc.update("UPDATE meeting_rooms SET meeting_mode='IN_PERSON' WHERE id=?", ROOM)
            "source_revision" -> jdbc.update("UPDATE submission_versions SET revision=8 WHERE id=?", FIRST_VERSION)
            "source_digest" ->
                jdbc.update(
                    "UPDATE submission_versions SET raw_text='different synthetic frozen source' WHERE id=?",
                    FIRST_VERSION,
                )
            else -> jdbc.update("UPDATE meeting_rooms SET time_zone_id='UTC' WHERE id=?", ROOM)
        }

        assertFailsWith<RuntimeException> { service.replay(ticket) }
        assertEquals(envelopeRows, jdbc.queryForList("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        assertEquals(selectionRows, jdbc.queryForList("SELECT selection::text FROM schedule_ir_execution_bindings", String::class.java))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "contract", "policy", "source_revision", "prepared_at", "meeting_mode"])
    fun `I31 replay rejects a tampered caller ticket even after a valid envelope exists`(field: String) {
        val ticket = prepare()
        val original = service.consume(ticket, response(fixtureName), PROCESSED)
        val changed =
            when (field) {
                "version" -> ticket.copy(expectedRunVersion = 2)
                "contract" -> ticket.copy(identity = ticket.identity.copy(responseContractVersion = "other"))
                "policy" -> ticket.copy(policy = policy(unconstrained = true))
                "prepared_at" -> ticket.copy(preparedAt = ticket.preparedAt.plusSeconds(1))
                "meeting_mode" -> ticket.copy(meetingMode = MeetingMode.IN_PERSON)
                else ->
                    ticket.copy(
                        context =
                            ticket.context.copy(
                                inputs =
                                    ticket.context.inputs.mapIndexed { index, input ->
                                        if (index ==
                                            0
                                        ) {
                                            input.copy(submissionRevision = 8)
                                        } else {
                                            input
                                        }
                                    },
                            ),
                    )
            }

        assertFailsWith<RuntimeException> { service.replay(changed) }
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(original, service.replay(ticket))
        assertEquals(original.envelope, JdbcScheduleIrRepository(jdbc, mapper).find(ticket.context))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["identical", "different"])
    fun `I33 concurrent consume has one complete result and exact idempotence or conflict`(kind: String) {
        val ticket = prepare()
        val firstJson = response(fixtureName)
        val alternateJson =
            if (kind ==
                "identical"
            ) {
                firstJson
            } else {
                firstJson.replace("\"interpretation\": \"EXPLICIT\"", "\"interpretation\": \"INFERRED\"")
            }
        if (kind == "different") assertTrue(firstJson != alternateJson)
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "ir-concurrent-consume-test").apply { isDaemon = true } }
        try {
            val workers =
                listOf(firstJson, alternateJson).map { json ->
                    executor.submit<Any> {
                        check(gate.await(20, TimeUnit.SECONDS))
                        try {
                            service.consume(ticket, json, PROCESSED)
                        } catch (failure: RuntimeException) {
                            failure
                        }
                    }
                }
            gate.countDown()
            val outcomes = workers.map { it.get(30, TimeUnit.SECONDS) }
            val results = outcomes.filterIsInstance<com.meetme.server.coordination.domain.ir.IrCalculationResult>()
            assertEquals(if (kind == "identical") 2 else 1, results.size)
            assertEquals(if (kind == "identical") 0 else 1, outcomes.count { it is RuntimeException })
            val winner = results.first()
            assertTrue(results.all { it == winner })
            assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
            assertEquals(winner.envelope, JdbcScheduleIrRepository(jdbc, mapper).find(ticket.context))
            assertEquals(winner, service.replay(ticket))
            assertEquals(1, count("schedule_ir_execution_bindings"))
            assertEquals(1, count("schedule_ir_envelopes"))
            assertEquals(2, count("schedule_ir_inputs"))
            assertEquals(legacyBefore, legacySnapshot())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Concurrent consume workers must terminate before fixture cleanup")
        }
    }

    @Test
    fun `I34 consume joins the caller transaction while independently prepared binding survives its rollback`() {
        val ticket = prepare()
        val marker = IllegalStateException("synthetic consume outer rollback")
        var consumedInsideCaller = false
        val failure =
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(transactions).executeWithoutResult {
                    val result = service.consume(ticket, response(fixtureName), PROCESSED)
                    assertEquals(IrCalculationStatus.CALCULATED, result.status)
                    assertEquals(listOf(commonFriday()), result.recommendations.options.map { it.window })
                    assertEquals(1, count("schedule_ir_envelopes"))
                    assertEquals(2, count("schedule_ir_inputs"))
                    consumedInsideCaller = true
                    throw marker
                }
            }
        assertTrue(failure === marker, "The outer test rollback marker, not an earlier service exception, must escape")
        assertTrue(consumedInsideCaller)
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(1, count("schedule_ir_execution_bindings"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())

        val recovered = service.consume(ticket, response(fixtureName), PROCESSED.plusSeconds(1))
        assertEquals(IrCalculationStatus.CALCULATED, recovered.status)
        assertEquals(recovered, service.replay(ticket))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @Test
    fun `prepared nanosecond identity survives JSON fresh reads and replay while SQL timestamp is explicitly truncated`() {
        val precisePreparedAt = Instant.parse("2027-01-04T02:00:00.123456789Z")
        val expectedSqlPreparedAt = Instant.parse("2027-01-04T02:00:00.123456Z")
        val ticket = service.prepare(CoordinationRunId(RUN), 1, identity(), policy(), precisePreparedAt)

        assertEquals(precisePreparedAt, ticket.preparedAt)
        assertEquals(
            expectedSqlPreparedAt,
            jdbc
                .queryForObject(
                    "SELECT prepared_at FROM schedule_ir_execution_bindings WHERE run_id=?",
                    Timestamp::class.java,
                    RUN,
                )!!
                .toInstant(),
        )
        val storedSelection =
            assertNotNull(
                jdbc.queryForObject("SELECT selection::text FROM schedule_ir_execution_bindings WHERE run_id=?", String::class.java, RUN),
            )
        val jsonTicket = assertNotNull(mapper.readValue(storedSelection, IrExecutionTicket::class.java))
        assertEquals(ticket, jsonTicket)
        assertEquals(precisePreparedAt, jsonTicket.preparedAt)
        val freshTicket = assertNotNull(JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(ticket, freshTicket)
        assertEquals(precisePreparedAt, freshTicket.preparedAt)
        val repeated = service.prepare(CoordinationRunId(RUN), 1, identity(), policy(), precisePreparedAt.plusSeconds(1))
        assertEquals(ticket, repeated)
        assertEquals(precisePreparedAt, repeated.preparedAt)
        assertEquals(1, count("schedule_ir_execution_bindings"))

        val consumed = service.consume(freshTicket, response(fixtureName), PROCESSED)
        val replayed = service.replay(repeated)
        assertEquals(ticket, consumed.ticket)
        assertEquals(precisePreparedAt, consumed.ticket.preparedAt)
        assertEquals(consumed, replayed)
        assertEquals(consumed.selectionFingerprint, replayed.selectionFingerprint)
        assertTrue(consumed.selectionFingerprint.matches(Regex("[a-f0-9]{64}")))
        assertEquals(listOf(commonFriday()), consumed.recommendations.options.map { it.window })
        assertEquals(ticket, JdbcScheduleIrExecutionRegistry(jdbc, mapper).find(RUN))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacySnapshot())
    }

    private fun protectExecution(reason: String) {
        if (reason == "open_round") {
            val round = UUID(0, 701)
            jdbc.update(
                "INSERT INTO input_revision_rounds(id,room_id,generation,source_run_id,reopen_request_id,expected_generation,status,opened_at) VALUES (?,?,1,?,?,0,'OPEN',?)",
                round,
                ROOM,
                RUN,
                UUID(0, 702),
                Timestamp.from(PROCESSED),
            )
            jdbc.update("UPDATE meeting_rooms SET active_revision_round_id=?,revision_generation=1 WHERE id=?", round, ROOM)
        } else {
            val candidate = UUID(0, 703)
            jdbc.update("INSERT INTO candidates(id,coordination_run_id,rank) VALUES (?,?,1)", candidate, RUN)
            jdbc.update(
                "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,?)",
                RUN,
                candidate,
                Timestamp.from(PROCESSED),
            )
        }
    }

    private fun prepare(selected: IrMatchingPolicy = policy()) = service.prepare(CoordinationRunId(RUN), 1, identity(), selected, PREPARED)

    private fun identity() = IrExecutionIdentity(CONTRACT, SCHEDULE_IR_VERSION, POLICY)

    private fun policy(
        labels: Set<IrInterpretation> = setOf(IrInterpretation.EXPLICIT),
        choices: List<IrAlternativeChoice> = emptyList(),
        unconstrained: Boolean = false,
    ) = IrMatchingPolicy(POLICY, labels, choices, unconstrained)

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun commonFriday() = instant("2027-01-08T10:00:00Z", "2027-01-08T12:00:00Z")

    private fun instant(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/schedule-ir-integration/$name")!!.use {
            String(it.readAllBytes(), StandardCharsets.UTF_8)
        }

    private fun response(name: String) = resource("$name.provider.json")

    private fun sources(name: String): Map<UUID, String> {
        val fixture = mapper.readTree(resource("sources-and-oracles.json")).path("fixtures").single { it.path("name").asText() == name }
        return listOf(FIRST_VERSION, SECOND_VERSION).associateWith { version ->
            val original = fixture.path("sources").path(version.toString()).path("original")
            require(original.isString) { "Synthetic fixture original source is missing or not text" }
            val text = requireNotNull(original.asString())
            require(text.isNotBlank()) { "Synthetic fixture original source is blank" }
            text
        }
    }

    private fun replaceFixture(name: String) {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        fixtureName = name
        seed(name)
        legacyBefore = legacySnapshot()
    }

    private fun seed(name: String) {
        jdbc.update(
            "INSERT INTO meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date,search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at) VALUES (?,'syntheticir00000000001','synthetic IR integration','REMOTE','Asia/Seoul','2027-01-04','2027-01-11','HOST_SPECIFIED',true,'CLOSED','MANUAL',?,?)",
            ROOM,
            Timestamp.from(FIXED),
            Timestamp.from(REFERENCE),
        )
        val raw = sources(name)
        for ((index, id) in listOf(FIRST_VERSION, SECOND_VERSION).withIndex()) {
            val participant = if (index == 0) FIRST_PARTICIPANT else SECOND_PARTICIPANT
            val head = if (index == 0) FIRST_HEAD else SECOND_HEAD
            val guest = UUID(0, 501L + index)
            jdbc.update(
                "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,? ,?,?)",
                guest,
                "synthetic-guest-$index",
                Timestamp.from(PROCESSED.plusSeconds(86400)),
                Timestamp.from(REFERENCE),
            )
            jdbc.update(
                "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                participant,
                ROOM,
                guest,
                if (index ==
                    0
                ) {
                    "HOST"
                } else {
                    "MEMBER"
                },
                "synthetic-$index",
                Timestamp.from(REFERENCE),
            )
            jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, ROOM, participant)
            val evidence =
                "{\"originalRevision\":3,\"referenceAt\":\"2027-01-04T00:00:00Z\",\"supplement\":null,\"interpretation\":\"PENDING\",\"unresolvedReason\":null,\"protocol\":\"PRE_SUBMIT_V2\"}"
            jdbc.update(
                "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) VALUES (?,?,7,?,'ko-KR',?,CAST(? AS jsonb))",
                id,
                head,
                raw.getValue(id),
                Timestamp.from(REFERENCE.plusSeconds(300)),
                evidence,
            )
            jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", id, head)
        }
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", BATCH, ROOM, Timestamp.from(FIXED))
        for (id in listOf(FIRST_VERSION, SECOND_VERSION)) {
            jdbc.update("INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)", BATCH, id)
            jdbc.update(
                "INSERT INTO structured_submission_results(batch_id,submission_version_id,conditions,rejection_code,processed_at) VALUES (?,?,'[]'::jsonb,'AMBIGUOUS_INPUT',?)",
                BATCH,
                id,
                Timestamp.from(FIXED),
            )
        }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES (?,?,?,'STRUCTURING',?,1)",
            RUN,
            ROOM,
            BATCH,
            Timestamp.from(FIXED),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", RUN, ROOM)
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
                ).map { requireNotNull(it) }
        }

    @TestConfiguration(proxyBeanMethods = false)
    class InactiveIntegrationConfiguration {
        @Bean fun inactiveIrRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrExecutionRegistry = JdbcScheduleIrExecutionRegistry(jdbc, mapper)

        @Bean fun inactiveIrParser(mapper: ObjectMapper): ScheduleIrResponseParser = StrictScheduleIrResponseParser(mapper, CONTRACT)

        @Bean fun inactiveIrService(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            registry: ScheduleIrExecutionRegistry,
            irRepository: ScheduleIrRepository,
            parser: ScheduleIrResponseParser,
        ) = ScheduleIrIntegrationService(runs, rooms, submissions, registry, irRepository, parser)
    }

    companion object {
        private const val CONTRACT = "synthetic-response-contract-1"
        private const val POLICY = "synthetic-policy-1"
        private val ROOM = UUID(0, 203)
        private val BATCH = UUID(0, 202)
        private val RUN = UUID(0, 201)
        private val FIRST_VERSION = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val SECOND_VERSION = UUID.fromString("00000000-0000-0000-0000-000000000102")
        private val FIRST_PARTICIPANT = UUID(0, 301)
        private val SECOND_PARTICIPANT = UUID(0, 302)
        private val FIRST_HEAD = UUID(0, 401)
        private val SECOND_HEAD = UUID(0, 402)
        private val REFERENCE = Instant.parse("2027-01-04T00:00:00Z")
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

        private fun flyway(target: String? = null): Flyway {
            val builder = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            if (target != null) builder.target(target)
            return builder.load()
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
