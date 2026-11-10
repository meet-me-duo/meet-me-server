package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.coordination.application.service.ScheduleEvidenceSnapshotFactory
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.output.ScheduleIrRepository
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionVersion
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrQuarantine
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrTestFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.EnableTransactionManagement
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB-01..10/FENCE: real migrations and transactions, no provider or application scheduler. */
class ScheduleIrRepositoryPostgresTest {
    private val jdbc = context.getBean(JdbcTemplate::class.java)
    private val repository = context.getBean(ScheduleIrRepository::class.java)
    private val mapper = context.getBean(ObjectMapper::class.java)
    private val fixture = ScheduleIrTestFixture
    private lateinit var expected: ScheduleIrEnvelope
    private lateinit var legacyBefore: List<Map<String, Any?>>

    @BeforeEach
    fun migrateLegacyFixtureThenUpgrade() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        flyway("23").migrate()
        expected = seed()
        legacyBefore = legacyRows()
        flyway().migrate()
    }

    @Test
    fun `missing new envelope does not fall back to existing legacy structured rows`() {
        assertEquals(2, legacyBefore.size)
        assertNull(repository.find(expected.context))
        assertEquals(legacyBefore, legacyRows())
    }

    @Test
    fun `V23 legacy data upgrades through V24 into separate typed storage without rewriting existing rows`() {
        val migrations = flyway().info()
        assertEquals(31, migrations.applied().size)
        assertEquals("31", migrations.current()!!.version.version)
        val envelopeColumns =
            jdbc
                .queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='schedule_ir_envelopes'",
                    String::class.java,
                ).toSet()
        val inputColumns =
            jdbc
                .queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='schedule_ir_inputs'",
                    String::class.java,
                ).toSet()

        assertTrue(envelopeColumns.containsAll(setOf("run_id", "batch_id", "room_id", "semantic_version", "envelope", "processed_at")))
        assertTrue(inputColumns.containsAll(setOf("run_id", "batch_id", "submission_version_id")))
        assertEquals(
            "jsonb",
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name='schedule_ir_envelopes' AND column_name='envelope'",
                String::class.java,
            ),
        )
        assertEquals(2, legacyBefore.size)
        assertEquals(legacyBefore, legacyRows())
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @EnumSource(IrAvailabilityState::class)
    fun `explicit typed state and evidence survive actual postgres and a separate reader`(state: IrAvailabilityState) {
        val original = expected.results.first()
        val condition = original.conditions.single()
        val conditions =
            when (state) {
                IrAvailabilityState.EMPTY -> listOf(condition.copy(effect = IrEffect.UNAVAILABLE, scope = fixture.fullSearch))
                IrAvailabilityState.UNCONSTRAINED -> listOf(condition.copy(effect = IrEffect.UNCONSTRAINED, scope = fixture.fullSearch))
                IrAvailabilityState.UNKNOWN -> listOf(condition.copy(interpretation = IrInterpretation.INFERRED))
                IrAvailabilityState.KNOWN_WINDOWS -> original.conditions
            }
        val envelope =
            expected.copy(results = listOf(original.copy(availability = state, conditions = conditions), expected.results.last()))

        repository.publish(envelope, 1, NOW)
        val separateReader = JdbcScheduleIrRepository(jdbc, mapper)

        assertEquals(envelope, separateReader.find(envelope.context))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacyRows(), "IR writes must not rewrite legacy conditions or rejection")
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM submission_batch_items", Int::class.java))
    }

    @Test
    fun `same payload replay is idempotent and conflicting replay cannot replace the original`() {
        repository.publish(expected, 1, NOW)
        repository.publish(expected.copy(), 1, NOW.plusSeconds(1))
        val other = changedPayload()

        assertFailsWith<RuntimeException> { repository.publish(other, 1, NOW.plusSeconds(2)) }
        assertEquals(expected, repository.find(expected.context))
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(NOW, jdbc.queryForObject("SELECT processed_at FROM schedule_ir_envelopes", Timestamp::class.java)?.toInstant())
    }

    @Test
    fun `typed relations assertions alternatives and quarantine survive without flattening`() {
        val original = expected.results.first()
        val condition = original.conditions.single()
        val history = condition.copy(id = "historical", assertion = IrAssertion.RETRACTED)
        val correction =
            condition.copy(
                id = "correction",
                effect = IrEffect.PREFERRED,
                interpretation = IrInterpretation.INFERRED,
                supersedes = listOf(history.id),
            )
        val quoted = condition.copy(id = "quoted", assertion = IrAssertion.QUOTED_OTHER)
        val unresolved =
            fixture.expectedCondition(
                fixture.condition("unresolved").copy(
                    effect = IrEffect.UNRESOLVED,
                    interpretation = IrInterpretation.UNRESOLVED,
                    scope = fixture.window.copy(start = fixture.window.start!!.plusDays(1), end = fixture.window.end!!.plusDays(1)),
                    evidence = listOf(fixture.evidence("금요일 7시는 미정")),
                ),
            )
        val alternatives =
            listOf(7, 19).map { hour ->
                unresolved.copy(
                    id = "alternative-$hour",
                    effect = IrEffect.AVAILABLE,
                    interpretation = IrInterpretation.INFERRED,
                    scope =
                        fixture.window.copy(
                            start =
                                fixture.window.start!!
                                    .plusDays(1)
                                    .withHour(hour),
                            end =
                                fixture.window.end!!
                                    .plusDays(1)
                                    .withHour(hour + 1),
                        ),
                    alternativeGroup = "friday-ambiguous-seven",
                    alternative = if (hour == 7) "AM" else "PM",
                )
            }
        val rich =
            expected.copy(
                results =
                    listOf(
                        original.copy(
                            conditions = listOf(condition, history, correction, quoted, unresolved) + alternatives,
                            quarantined = listOf(IrQuarantine("untrusted-negative", IrProblem.EVIDENCE, null)),
                        ),
                        expected.results.last(),
                    ),
            )

        repository.publish(rich, 1, NOW)

        assertEquals(rich, JdbcScheduleIrRepository(jdbc, mapper).find(rich.context))
        assertEquals(legacyBefore, legacyRows())
    }

    @Test
    fun `failure while writing source references rolls back the envelope and all references`() {
        jdbc.execute(
            """
            CREATE FUNCTION reject_ir_input() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'synthetic independent rollback probe'; END
            ${'$'}${'$'};
            CREATE TRIGGER reject_ir_input BEFORE INSERT ON schedule_ir_inputs
            FOR EACH ROW EXECUTE FUNCTION reject_ir_input();
            """.trimIndent(),
        )

        try {
            assertFailsWith<RuntimeException> { repository.publish(expected, 1, NOW) }
            assertEquals(0, count("schedule_ir_envelopes"))
            assertEquals(0, count("schedule_ir_inputs"))
            assertEquals(legacyBefore, legacyRows())
        } finally {
            jdbc.execute("DROP TRIGGER reject_ir_input ON schedule_ir_inputs")
        }
        repository.publish(expected, 1, NOW)
        assertEquals(expected, repository.find(expected.context))
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "inactive", "open_round", "completed", "confirmed"])
    fun `stale or protected execution cannot publish even a structurally valid envelope`(reason: String) {
        val c = expected.context
        when (reason) {
            "version" -> jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", c.runId)
            "inactive" -> jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", c.roomId)
            "open_round" -> {
                val round = UUID(0, 701)
                jdbc.update(
                    "INSERT INTO " +
                        "input_revision_rounds(id,room_id,generation,source_run_id," +
                        "reopen_request_id,expected_generation,status,opened_at) " +
                        "VALUES (?,?,1,?,?,0,'OPEN',?)",
                    round,
                    c.roomId,
                    c.runId,
                    UUID(0, 702),
                    Timestamp.from(NOW),
                )
                jdbc.update("UPDATE meeting_rooms SET active_revision_round_id=?,revision_generation=1 WHERE id=?", round, c.roomId)
            }
            "completed" -> jdbc.update("UPDATE coordination_runs SET status='COMPLETED',candidate_quality='COMPLETE' WHERE id=?", c.runId)
            else -> {
                val candidate = UUID(0, 703)
                jdbc.update("INSERT INTO candidates(id,coordination_run_id,rank) VALUES (?,?,1)", candidate, c.runId)
                jdbc.update(
                    "INSERT INTO final_confirmations(coordination_run_id,candidate_id,confirmed_at) VALUES (?,?,?)",
                    c.runId,
                    candidate,
                    Timestamp.from(NOW),
                )
            }
        }

        assertFailsWith<RuntimeException> { repository.publish(expected, 1, NOW) }
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacyRows())
    }

    @Test
    fun `exact frozen membership rejects missing extra and duplicated references before writes`() {
        val c = expected.context
        val variations =
            listOf(
                expected.copy(context = c.copy(inputs = c.inputs.take(1)), results = expected.results.take(1)),
                expected.copy(
                    context = c.copy(inputs = c.inputs + c.inputs.first()),
                    results = expected.results + expected.results.first(),
                ),
                expected.copy(
                    context = c.copy(inputs = listOf(c.inputs.first().copy(submissionVersionId = UUID(0, 999)), c.inputs.last())),
                ),
            )

        for (changed in variations) {
            assertFailsWith<RuntimeException> { repository.publish(changed, 1, NOW) }
            assertEquals(0, count("schedule_ir_envelopes"))
            assertEquals(0, count("schedule_ir_inputs"))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "input_digest", "original_digest", "answer_digest", "accepted_revision", "original_revision",
            "source_revision", "owner", "source_reference", "timezone", "search_start", "search_end",
        ],
    )
    fun `publish verifies actual frozen sources and room context even when all version IDs match`(field: String) {
        val context = expected.context
        val input = context.inputs.first()
        val changedInput =
            when (field) {
                "input_digest" -> input.copy(contentSha256 = "0".repeat(64))
                "original_digest" ->
                    input.copy(
                        sources = input.sources.map { if (it.sourceId == "original") it.copy(contentSha256 = "0".repeat(64)) else it },
                    )
                "answer_digest" ->
                    input.copy(
                        sources =
                            input.sources.map {
                                if (it.sourceId.startsWith("supplement:")) it.copy(contentSha256 = "0".repeat(64)) else it
                            },
                    )
                "accepted_revision" -> input.copy(submissionRevision = input.submissionRevision + 1)
                "original_revision" -> input.copy(originalRevision = input.originalRevision!! + 1)
                "source_revision" -> input.copy(sources = input.sources.map { it.copy(revision = it.revision + 1) })
                "owner" -> input.copy(participantId = context.inputs.last().participantId)
                "source_reference" -> input.copy(sources = input.sources.map { it.copy(referenceAt = it.referenceAt.plusSeconds(1)) })
                else -> input
            }
        val changedContext =
            when (field) {
                "timezone" -> context.copy(timeZone = "UTC")
                "search_start" -> context.copy(searchStart = context.searchStart.plusDays(1))
                "search_end" -> context.copy(searchEnd = context.searchEnd.plusDays(1))
                else -> context.copy(inputs = listOf(changedInput, context.inputs.last()))
            }
        val changed = expected.copy(context = changedContext)

        assertEquals(context.inputs.map { it.submissionVersionId }, changedContext.inputs.map { it.submissionVersionId })
        assertFailsWith<RuntimeException> { repository.publish(changed, 1, NOW) }
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(legacyBefore, legacyRows())
        repository.publish(expected, 1, NOW)
        assertEquals(expected, repository.find(expected.context))
    }

    @Test
    fun `advancing the mutable submission head never substitutes its sources for the frozen version`() {
        val newerId = UUID(0, 998)
        val head = jdbc.queryForObject("SELECT submission_id FROM submission_versions WHERE id=?", UUID::class.java, fixture.firstId)!!
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES " +
                "(?,?,8,'different later source','en-US',?)",
            newerId,
            head,
            Timestamp.from(NOW.plusSeconds(1)),
        )
        jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", newerId, head)

        repository.publish(expected, 1, NOW)

        assertEquals(expected, repository.find(expected.context))
        assertEquals(2, count("schedule_ir_inputs"))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs WHERE submission_version_id=?", Int::class.java, newerId),
        )
        assertEquals(legacyBefore, legacyRows())
    }

    @Test
    fun `unsupported persisted envelope version is rejected rather than decoded as legacy or current`() {
        repository.publish(expected, 1, NOW)
        val future = expected.copy(semanticVersion = "schedule-ir-future-unsupported")
        jdbc.update(
            "UPDATE schedule_ir_envelopes SET semantic_version=?,envelope=CAST(? AS jsonb) WHERE run_id=?",
            future.semanticVersion,
            mapper.writeValueAsString(future),
            expected.context.runId,
        )

        assertFailsWith<RuntimeException> { repository.find(expected.context) }
        assertEquals(legacyBefore, legacyRows())
    }

    @ParameterizedTest
    @ValueSource(strings = ["room", "batch", "policy", "input_content", "search", "source_reference"])
    fun `reader requires exact immutable context and cannot silently adapt stored data`(field: String) {
        repository.publish(expected, 1, NOW)
        val c = expected.context
        val input = c.inputs.first()
        val changed =
            when (field) {
                "room" -> c.copy(roomId = UUID(0, 888))
                "batch" -> c.copy(batchId = UUID(0, 888))
                "policy" -> c.copy(policyVersion = "unreviewed-other-policy")
                "input_content" -> c.copy(inputs = listOf(input.copy(contentSha256 = "0".repeat(64)), c.inputs.last()))
                "search" -> c.copy(searchEnd = c.searchEnd.plusDays(1))
                else ->
                    c.copy(
                        inputs =
                            listOf(
                                input.copy(sources = input.sources.map { it.copy(referenceAt = it.referenceAt.plusSeconds(1)) }),
                                c.inputs.last(),
                            ),
                    )
            }

        assertFailsWith<RuntimeException> { repository.find(changed) }
        assertEquals(expected, repository.find(c))
        assertEquals(legacyBefore, legacyRows())
    }

    @Test
    fun `database membership foreign key rejects a version not frozen in the referenced batch`() {
        repository.publish(expected, 1, NOW)
        val id = UUID(0, 999)
        val head = jdbc.queryForObject("SELECT submission_id FROM submission_versions WHERE id=?", UUID::class.java, fixture.firstId)!!
        jdbc.update(
            "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at) VALUES " +
                "(?,?,8,'newer synthetic','ko-KR',?)",
            id,
            head,
            Timestamp.from(NOW),
        )

        assertFailsWith<DataAccessException> {
            jdbc.update(
                "INSERT INTO schedule_ir_inputs(run_id,batch_id,submission_version_id) VALUES (?,?,?)",
                expected.context.runId,
                expected.context.batchId,
                id,
            )
        }
        assertEquals(2, count("schedule_ir_inputs"))
    }

    @Test
    fun `run deletion cascades to separate IR storage without deleting frozen source versions`() {
        repository.publish(expected, 1, NOW)
        jdbc.update("UPDATE meeting_rooms SET active_run_id=NULL WHERE id=?", expected.context.roomId)
        jdbc.update("DELETE FROM coordination_runs WHERE id=?", expected.context.runId)

        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM submission_versions", Int::class.java))
        assertEquals(legacyBefore, legacyRows())
    }

    @Test
    fun `concurrent different payloads admit one complete envelope and never mix input references`() {
        val alternatives = listOf(expected, changedPayload())
        val gate = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val outcomes =
                alternatives.map { envelope ->
                    workers.submit<Boolean> {
                        check(gate.await(30, TimeUnit.SECONDS))
                        try {
                            repository.publish(envelope, 1, NOW)
                            true
                        } catch (_: RuntimeException) {
                            false
                        }
                    }
                }
            gate.countDown()
            assertEquals(1, outcomes.count { it.get(30, TimeUnit.SECONDS) })
            assertTrue(assertNotNull(repository.find(expected.context)) in alternatives)
            assertEquals(1, count("schedule_ir_envelopes"))
            assertEquals(2, count("schedule_ir_inputs"))
        } finally {
            workers.shutdownNow()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["evidence_float", "evidence_string", "context_float", "context_string", "null_start", "numeric_enum"])
    fun `persisted envelope scalar corruption is rejected without exposing source text`(defect: String) {
        assertEquals(fixture.firstId, expected.results.first().submissionVersionId)
        assertEquals(
            3,
            expected.results
                .first()
                .conditions
                .single()
                .evidence
                .single()
                .revision,
        )
        assertEquals(
            7,
            expected.context.inputs
                .first()
                .submissionRevision,
        )
        repository.publish(expected, 1, NOW)
        val (path, value) =
            when (defect) {
                "evidence_float" -> "{results,0,conditions,0,evidence,0,revision}" to "3.9"
                "evidence_string" -> "{results,0,conditions,0,evidence,0,revision}" to "\"3\""
                "context_float" -> "{context,inputs,0,submissionRevision}" to "7.9"
                "context_string" -> "{context,inputs,0,submissionRevision}" to "\"7\""
                "null_start" -> "{results,0,conditions,0,evidence,0,startCodePoint}" to "null"
                else -> "{results,0,conditions,0,effect}" to "0"
            }
        assertEquals(
            1,
            jdbc.update(
                "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),CAST(? AS jsonb),false) WHERE run_id=?",
                path,
                value,
                expected.context.runId,
            ),
        )
        assertEquals(
            value,
            jdbc.queryForObject(
                "SELECT (envelope #> CAST(? AS text[]))::text FROM schedule_ir_envelopes WHERE run_id=?",
                String::class.java,
                path,
                expected.context.runId,
            ),
        )

        val failure = assertFailsWith<RuntimeException> { repository.find(expected.context) }
        val messages = generateSequence<Throwable>(failure) { it.cause }.joinToString("\n") { it.toString() }
        assertTrue(!messages.contains(ScheduleIrTestFixture.FIRST_TEXT))
        assertTrue(!messages.contains(ScheduleIrTestFixture.SECOND_TEXT))
        assertTrue(!messages.contains("오전인가요 오후인가요?"))
        assertTrue(!messages.contains("오후"))
        assertEquals(legacyBefore, legacyRows())
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.9", "\"3\""])
    fun `reader rejects scalar corruption of frozen original revision without exposing answers`(value: String) {
        repository.publish(expected, 1, NOW)
        assertEquals(
            1,
            jdbc.update(
                "UPDATE submission_versions SET input_evidence=jsonb_set(input_evidence,'{originalRevision}',CAST(? AS jsonb),false) WHERE id=?",
                value,
                fixture.firstId,
            ),
        )
        assertEquals(
            value,
            jdbc.queryForObject(
                "SELECT (input_evidence->'originalRevision')::text FROM submission_versions WHERE id=?",
                String::class.java,
                fixture.firstId,
            ),
        )

        val failure = assertFailsWith<RuntimeException> { repository.find(expected.context) }
        val messages = generateSequence<Throwable>(failure) { it.cause }.joinToString("\n") { it.toString() }
        assertTrue(!messages.contains(ScheduleIrTestFixture.FIRST_TEXT))
        assertTrue(!messages.contains("오전인가요 오후인가요?"))
        assertTrue(!messages.contains("오후"))
        assertEquals(legacyBefore, legacyRows())
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["3.9", "\"3\""])
    fun `publication rejects scalar corruption of frozen original revision without partial writes`(value: String) {
        assertEquals(
            1,
            jdbc.update(
                "UPDATE submission_versions SET input_evidence=jsonb_set(input_evidence,'{originalRevision}',CAST(? AS jsonb),false) WHERE id=?",
                value,
                fixture.firstId,
            ),
        )
        assertEquals(
            value,
            jdbc.queryForObject(
                "SELECT (input_evidence->'originalRevision')::text FROM submission_versions WHERE id=?",
                String::class.java,
                fixture.firstId,
            ),
        )

        val failure = assertFailsWith<RuntimeException> { repository.publish(expected, 1, NOW) }
        val messages = generateSequence<Throwable>(failure) { it.cause }.joinToString("\n") { it.toString() }
        assertTrue(!messages.contains(ScheduleIrTestFixture.FIRST_TEXT))
        assertTrue(!messages.contains("오전인가요 오후인가요?"))
        assertTrue(!messages.contains("오후"))
        assertEquals(legacyBefore, legacyRows())
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
    }

    private fun changedPayload(): ScheduleIrEnvelope {
        val first = expected.results.first()
        val changed = first.copy(conditions = first.conditions.map { it.copy(interpretation = IrInterpretation.INFERRED) })
        return expected.copy(results = listOf(changed, expected.results.last()))
    }

    private fun seed(): ScheduleIrEnvelope {
        val base = fixture.context()
        val source = ScheduleIrTestFixture.FIRST_TEXT
        val excerpt = "금요일 7시는 미정"
        val start = source.codePointCount(0, source.indexOf(excerpt))
        val evidence =
            InputEvidence(
                3,
                fixture.reference,
                InputSupplement(
                    UUID(0, 601),
                    3,
                    excerpt,
                    start,
                    "오전인가요 오후인가요?",
                    "오후",
                    "PM",
                    fixture.reference.plusSeconds(120),
                    fixture.reference,
                ),
                InputInterpretation.CONFIRMED,
            )
        val versions =
            listOf(
                SubmissionVersion(
                    SubmissionVersionId(fixture.firstId),
                    7,
                    source,
                    emptyList(),
                    Locale.KOREAN,
                    fixture.reference.plusSeconds(300),
                    evidence,
                ),
                SubmissionVersion(
                    SubmissionVersionId(fixture.secondId),
                    4,
                    ScheduleIrTestFixture.SECOND_TEXT,
                    emptyList(),
                    Locale.KOREAN,
                    fixture.reference.plusSeconds(300),
                ),
            )
        jdbc.update(
            "INSERT INTO " +
                "meeting_rooms(id,invite_code,purpose,meeting_mode,time_zone_id,search_start_date,search_end_date," +
                "search_range_source,manual_only,collection_status,closure_reason,closed_at,created_at) " +
                "VALUES (?,'abcdefghijklmnopqrstuv','synthetic IR " +
                "storage','REMOTE','Asia/Seoul',?,?,'HOST_SPECIFIED',true,'CLOSED','MANUAL',?,?)",
            base.roomId,
            base.searchStart,
            base.searchEnd,
            Timestamp.from(NOW),
            Timestamp.from(fixture.reference),
        )
        val submissions =
            versions.mapIndexed { index, version ->
                val participant = base.inputs[index].participantId
                val guest = UUID(0, 501L + index)
                val head = UUID(0, 401L + index)
                jdbc.update(
                    "INSERT INTO guest_browser_sessions(id,credential_digest,expires_at,created_at) VALUES (?,?,?,?)",
                    guest,
                    fixture.sha("synthetic guest $index"),
                    Timestamp.from(NOW.plusSeconds(86400)),
                    Timestamp.from(fixture.reference),
                )
                jdbc.update(
                    "INSERT INTO participants(id,room_id,guest_session_id,role,display_name,joined_at) VALUES (?,?,?,?,?,?)",
                    participant,
                    base.roomId,
                    guest,
                    if (index == 0) "HOST" else "MEMBER",
                    "synthetic $index",
                    Timestamp.from(fixture.reference),
                )
                jdbc.update("INSERT INTO submission_heads(id,room_id,participant_id) VALUES (?,?,?)", head, base.roomId, participant)
                jdbc.update(
                    "INSERT INTO submission_versions(id,submission_id,revision,raw_text,locale,created_at,input_evidence) " +
                        "VALUES (?,?,?,?,?,?,CAST(? AS jsonb))",
                    version.id.value,
                    head,
                    version.revision,
                    version.rawText,
                    version.locale.toLanguageTag(),
                    Timestamp.from(version.createdAt),
                    version.evidence?.let(mapper::writeValueAsString),
                )
                jdbc.update("UPDATE submission_heads SET latest_version_id=? WHERE id=?", version.id.value, head)
                Submission.restore(SubmissionId(head), MeetingRoomId(base.roomId), ParticipantId(participant), version)
            }
        val batch = SubmissionBatch(SubmissionBatchId(base.batchId), MeetingRoomId(base.roomId), versions.map { it.id }, NOW)
        val run = CoordinationRun.queued(CoordinationRunId(base.runId), batch).startStructuring()
        jdbc.update("INSERT INTO submission_batches(id,room_id,fixed_at) VALUES (?,?,?)", base.batchId, base.roomId, Timestamp.from(NOW))
        for (version in versions) {
            jdbc.update(
                "INSERT INTO submission_batch_items(batch_id,submission_version_id) VALUES (?,?)",
                base.batchId,
                version.id.value,
            )
        }
        jdbc.update(
            "INSERT INTO coordination_runs(id,room_id,batch_id,status,created_at,version) VALUES " +
                "(?,?,?,'STRUCTURING',?,1)",
            base.runId,
            base.roomId,
            base.batchId,
            Timestamp.from(NOW),
        )
        jdbc.update("UPDATE meeting_rooms SET active_run_id=? WHERE id=?", base.runId, base.roomId)
        for (version in versions) {
            jdbc.update(
                "INSERT INTO " +
                    "structured_submission_results(batch_id,submission_version_id,conditions,rejection_code,processed_at) " +
                    "VALUES (?,?,'[]'::jsonb,'AMBIGUOUS_INPUT',?)",
                base.batchId,
                version.id.value,
                Timestamp.from(NOW),
            )
        }
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                run,
                submissions,
                ZoneId.of(base.timeZone),
                base.searchStart,
                base.searchEnd,
                base.policyVersion,
            )
        return fixture.envelope(snapshot.context)
    }

    private fun legacyRows(): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT batch_id,submission_version_id,conditions::text,rejection_code,processed_at FROM " +
                "structured_submission_results ORDER BY submission_version_id",
        )

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class IrStorageTestConfiguration {
        @Bean fun dataSource(): DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        @Bean fun jdbc(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

        @Bean fun transactionManager(dataSource: DataSource): DataSourceTransactionManager = DataSourceTransactionManager(dataSource)

        @Bean fun mapper(): ObjectMapper = JsonMapper.builder().findAndAddModules().build()

        @Bean fun repository(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleIrRepository = JdbcScheduleIrRepository(jdbc, mapper)
    }

    companion object {
        private val NOW = Instant.parse("2027-01-04T03:00:00Z")
        private val postgres = mappedPortReadyPostgres().apply { start() }
        private val context = AnnotationConfigApplicationContext(IrStorageTestConfiguration::class.java)

        private fun flyway(target: String? = null): Flyway {
            val builder = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            target?.let(builder::target)
            return builder.load()
        }

        @JvmStatic
        @AfterAll
        fun close() {
            context.close()
            postgres.stop()
        }
    }
}
