package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser
import com.meetme.server.coordination.adapter.output.persistence.JdbcScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import com.meetme.server.support.ScheduleDslWireFixture
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
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Synthetic safety-invariant storage proofs, not signatures or detection of every possible byte edit. */
@SpringBootTest
@Import(ScheduleDslRejectedPreferenceStoragePostgresTest.InactiveDslConfiguration::class)
class ScheduleDslRejectedPreferenceStoragePostgresTest {
    @Autowired private lateinit var service: ScheduleDslAnalysisService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper
    private val q = ScheduleDslQuestionContextFixture
    private val f = ScheduleIrV2SyntheticFixture
    private val w = ScheduleDslWireFixture
    private lateinit var legacyBefore: Map<String, List<String>>

    @BeforeEach
    fun seedTrustedFrozenInput() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        seed()
        legacyBefore = legacySnapshot()
    }

    private fun prepare() =
        service.prepare(
            CoordinationRunId(f.run),
            1,
            IrExecutionIdentity(SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, f.POLICY),
            w.f.policy,
            PREPARED,
        )

    private fun fresh() = JdbcScheduleDslRepository(jdbc, mapper)

    private fun stored() =
        requireNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes WHERE run_id=?", String::class.java, f.run))

    private fun format() = jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes WHERE run_id=?", Int::class.java, f.run)

    private fun friday() = InstantTimeRange(Instant.parse("2027-01-08T09:00:00Z"), Instant.parse("2027-01-08T13:00:00Z"))

    @Suppress("UNCHECKED_CAST")
    private fun storedRootFields() = (mapper.readValue(stored(), Map::class.java) as Map<String, Any?>).keys

    private fun proofJson(structure: String): String {
        val base = w.f.claim("base", 18, 22)
        val bad = w.f.claim("bad", 19, 20, IrEffect.PREFERRED).copy(scope = f.preference.copy(dimension = IrDimension.PLACE))
        val nodes = listOf(w.f.ref("base-ref", base.id), w.f.ref("bad-ref", bad.id))
        val program =
            if (structure ==
                "wrapper"
            ) {
                w.f.program(nodes + w.f.node("root", ScheduleDslOperator.PREFER, "base-ref", "bad-ref"), "root")
            } else {
                w.f.program(nodes, "base-ref", prefer = "bad-ref")
            }
        val document = w.document()
        w.firstResult(document)["conditions"] = listOf(f.wire(base), f.wire(bad))
        w.firstResult(document)["program"] = w.program(program)
        return w.encode(document)
    }

    private fun publishProof(structure: String): Pair<IrExecutionTicket, ScheduleDslEnvelope> {
        val ticket = prepare()
        val envelope =
            StrictScheduleDslContextResponseParser(
                mapper,
            ).parse(
                proofJson(structure),
                service.input(ticket),
                SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        assertEquals(1, envelope.rejectedPreferenceWitnesses.size)
        fresh().publish(envelope, ticket.expectedRunVersion, PROCESSED)
        // The old format3 writer must fail this assertion before its lost witness can mask the failure at restore.
        assertEquals(4, format())
        return ticket to envelope
    }

    @ParameterizedTest
    @ValueSource(strings = ["separate", "wrapper"])
    fun `RPS01 rejected preference proof uses explicit format4 and survives fresh repository projection and service replay`(
        structure: String,
    ) {
        val (ticket, envelope) = publishProof(structure)
        assertEquals(setOf("semanticVersion", "astVersion", "claims", "programs", "rejectedPreferenceWitnesses"), storedRootFields())
        val restored = requireNotNull(fresh().find(ticket.context))
        assertEquals(envelope, restored)
        val proof = restored.rejectedPreferenceWitnesses.single()
        assertEquals(f.first, proof.submissionVersionId)
        assertEquals("bad", proof.conditionId)
        assertEquals(IrEffect.PREFERRED, proof.effect)
        assertEquals(f.preference.copy(dimension = IrDimension.PLACE), proof.declaredScope)
        assertEquals(f.verified(w.f.claim("bad", 19, 20, IrEffect.PREFERRED)).evidence, proof.evidence)
        assertTrue(
            restored.claims.results
                .first()
                .conditions
                .none { it.id == "bad" },
        )
        val quarantine =
            restored.claims.results
                .first()
                .quarantined
                .single()
        assertEquals("bad", quarantine.conditionId)
        assertEquals(IrProblem.SCOPE, quarantine.problem)
        assertEquals(null, quarantine.scope)
        val prepared = ScheduleDslInputPolicy.project(restored, ticket.policy)
        assertEquals(
            listOf(friday()),
            prepared.participants.single { it.participantId == ParticipantId(f.firstParticipant) }.availableTimes,
        )
        assertTrue(
            prepared.participants
                .single { it.participantId == ParticipantId(f.firstParticipant) }
                .explicitPreferences.timeRanges
                .isEmpty(),
        )
        val result = service.consume(ticket, proofJson(structure), PROCESSED.plusSeconds(1))
        assertEquals(prepared, result.prepared)
        assertEquals(listOf(friday()), result.recommendations.options.map { it.window })
        assertEquals(result, service.replay(ticket))
        assertEquals(legacyBefore, legacySnapshot())
    }

    @ParameterizedTest
    @ValueSource(strings = ["root_proof", "nested_unknown"])
    fun `RPS02 proofless format3 retains its original closed shape and rejects root or nested proof additions`(addition: String) {
        val ticket = prepare()
        val result = service.consume(ticket, w.json(), PROCESSED)
        assertEquals(3, format())
        assertTrue(result.envelope.rejectedPreferenceWitnesses.isEmpty())
        assertEquals(setOf("semanticVersion", "astVersion", "claims", "programs"), storedRootFields())
        val before = stored()
        assertEquals(result.envelope, fresh().find(ticket.context))
        assertEquals(result, service.replay(ticket))
        assertEquals(before, stored())
        val path = if (addition == "root_proof") "{rejectedPreferenceWitnesses}" else "{claims,results,0,unexpectedWitnesses}"
        jdbc.update(
            "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),'[]'::jsonb,true) WHERE run_id=?",
            path,
            f.run,
        )
        assertFailsWith<RuntimeException> { fresh().find(ticket.context) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["effect", "digest", "unknown_field"])
    fun `RPS03 format4 witness edits violating polarity source evidence or closed shape are rejected`(defect: String) {
        val (ticket, _) = publishProof("separate")
        when (defect) {
            "effect" ->
                jdbc.update(
                    "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{rejectedPreferenceWitnesses,0,effect}',to_jsonb(CAST(? AS text)),false) WHERE run_id=?",
                    "UNAVAILABLE",
                    f.run,
                )
            "digest" ->
                jdbc.update(
                    "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{rejectedPreferenceWitnesses,0,evidence,0,contentSha256}',to_jsonb(CAST(? AS text)),false) WHERE run_id=?",
                    "0".repeat(64),
                    f.run,
                )
            else ->
                jdbc.update(
                    "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{rejectedPreferenceWitnesses,0,unexpected}','true'::jsonb,true) WHERE run_id=?",
                    f.run,
                )
        }
        assertFailsWith<RuntimeException> { fresh().find(ticket.context) }
    }

    @Test
    fun `RPS04 hard unknown quarantine stays format3 conservative and cannot acquire a preference witness`() {
        val ticket = prepare()
        val base = w.f.claim("base", 18, 22)
        val negative = w.f.claim("bad", 19, 20, IrEffect.UNAVAILABLE).copy(scope = IrScope(IrDimension.TIME))
        val document = w.document()
        w.firstResult(document)["conditions"] = listOf(f.wire(base), f.wire(negative))
        w.firstResult(document)["program"] =
            w.program(w.f.program(listOf(w.f.ref("base-ref", base.id), w.f.ref("bad-ref", negative.id)), "base-ref", block = "bad-ref"))
        val result = service.consume(ticket, w.encode(document), PROCESSED)
        assertEquals(3, format())
        assertTrue(result.envelope.rejectedPreferenceWitnesses.isEmpty())
        assertTrue(result.recommendations.options.isEmpty())
        assertEquals(
            negative.id,
            result.envelope.claims.results
                .first()
                .quarantined
                .single()
                .conditionId,
        )
        val restored = requireNotNull(fresh().find(ticket.context))
        assertEquals(result.envelope, restored)
        assertEquals(result.prepared, ScheduleDslInputPolicy.project(restored, ticket.policy))
        assertEquals(legacyBefore, legacySnapshot())
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
    class InactiveDslConfiguration {
        @Bean fun dslRegistry(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrExecutionRegistry = JdbcScheduleIrExecutionRegistry(jdbc, mapper)

        @Bean fun dslParser(mapper: ObjectMapper): ScheduleDslResponseParser = StrictScheduleDslContextResponseParser(mapper)

        @Bean fun dslRepository(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleDslRepository = JdbcScheduleDslRepository(jdbc, mapper)

        @Bean fun dslService(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            registry: ScheduleIrExecutionRegistry,
            repository: ScheduleDslRepository,
            parser: ScheduleDslResponseParser,
        ) = ScheduleDslAnalysisService(runs, rooms, submissions, registry, repository, parser)
    }

    companion object {
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
