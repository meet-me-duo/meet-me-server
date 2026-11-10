package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic actual PG/strict parser/fresh reader/calculator. No provider, service activation or language accuracy claim. */
@SpringBootTest
class ScheduleDslStructuralPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun frozenNativeRows() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        N.seed(jdbc, mapper)
    }

    private fun repository() = JdbcScheduleDslStructuralRepository(jdbc, mapper)

    private fun rows() = FinalAnalysisRouteTestFixture.rows(jdbc, "schedule_ir_envelopes")

    private fun parse(): ScheduleDslStructuralEnvelope =
        StrictScheduleDslStructuralResponseParser(mapper).parse(
            mapper.writeValueAsString(document()),
            N.snapshot(),
            "schedule-dsl-response-5",
            "schedule-ir-product-6",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    /** Preserve all C4 meaning; only new contract identity, exact literal locators and required empty side declarations differ. */
    private fun document(): Map<String, Any?> {
        fun translate(value: Any?): Any? =
            when (value) {
                is Map<*, *> -> {
                    if (value.keys == setOf("source_id", "revision", "start_code_point", "end_code_point", "quote")) {
                        linkedMapOf("source_id" to value["source_id"], "revision" to value["revision"], "quote" to value["quote"])
                    } else {
                        value.entries.associate { (key, child) -> key.toString() to translate(child) }.toMutableMap()
                    }
                }
                is List<*> -> value.map { translate(it) }
                else -> value
            }

        @Suppress("UNCHECKED_CAST")
        val root = translate(N.document()) as MutableMap<String, Any?>
        root["semantic_version"] = "schedule-ir-product-6"
        @Suppress("UNCHECKED_CAST")
        (root.getValue("results") as List<MutableMap<String, Any?>>).forEach { it["directional_times"] = emptyList<Any>() }
        return root
    }

    @Test
    fun `SPG01 strict C5 format7 fresh roundtrip preserves independent exact mode windows and provenance without raw duplication`() {
        val declared = parse()
        repository().publish(declared, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val restored = assertNotNull(repository().find(N.snapshot().context))
        assertEquals(declared, restored)
        assertEquals("schedule-ir-product-6", jdbc.queryForObject("SELECT semantic_version FROM schedule_ir_envelopes", String::class.java))
        assertEquals(7, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
        assertTrue(restored.locatedEvidence.isNotEmpty())
        assertTrue(restored.locatedEvidence.all { it.evidence.derivation.name == "SERVER_LITERAL_QUOTE" })
        assertTrue(restored.directionalApplications.isEmpty())
        assertEquals(N.snapshot().context, restored.context)
        val original =
            restored.base.base.declaration.claims.results
                .first { it.submissionVersionId == F.ir.first }
        val contextual = original.conditions.single { it.id == "context" }
        assertEquals("INFERRED", contextual.interpretation.name)
        assertEquals("NON_PRECISE", contextual.temporalResolution.name)
        assertTrue(restored.base.contextApplications.isNotEmpty())
        val stored = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        for (privateValue in listOf(N.FIRST, N.SECOND, N.MODE_QUOTE, F.q.PROMPT, F.q.answer.answerText)) {
            assertFalse(stored.contains(privateValue))
        }
        for (key in listOf("\"quote\"", "\"rawText\"", "\"answerText\"", "\"prompt\"")) assertFalse(stored.contains(key))
        val result = ScheduleDslStructuralCalculator.calculate(restored, N.snapshot(), N.policy, MeetingMode.EITHER)
        val first = ParticipantId(F.ir.firstParticipant)
        assertEquals(
            listOf(N.thursday, N.friday),
            result.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertEquals(
            listOf(N.friday),
            result.preparedByMode
                .getValue(MeetingMode.IN_PERSON)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertEquals(IrCalculationStatus.PARTIAL, result.status)
        assertEquals(
            setOf(N.thursday, N.friday),
            result.recommendations.options
                .map { it.window }
                .toSet(),
        )
        val thursday = result.recommendations.options.single { it.window == N.thursday }
        val friday = result.recommendations.options.single { it.window == N.friday }
        assertEquals(setOf(MeetingMode.REMOTE), thursday.variants.map { it.meetingMode }.toSet())
        assertEquals(setOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON), friday.variants.map { it.meetingMode }.toSet())
        assertTrue(
            result.recommendations.options.flatMap { it.variants }.all {
                it.participantIds.size == 2 &&
                    it.representativePlace == null
            },
        )
        // Auxiliary old C4 parity, after the independent numeric/mode assertions above.
        assertEquals(ScheduleDslContextModeCalculator.calculate(N.expectedEnvelope(), N.snapshot(), N.policy, MeetingMode.EITHER), result)
        assertFailsWith<RuntimeException> { JdbcScheduleDslContextModeRepository(jdbc, mapper).find(N.snapshot().context) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
    }

    @Test
    fun `SPG02 fresh restoration refuses stale native context revision and safe invariant witness or projection tampering`() {
        val envelope = parse()
        repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val originalRows = rows()
        assertFailsWith<RuntimeException> { repository().find(N.snapshot().context.copy(timeZone = "UTC")) }
        assertEquals(originalRows, rows())
        val input = N.input().inputs.first()
        for (change in listOf("source", "revision")) {
            if (change == "source") {
                jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", N.FIRST + " Changed.", F.ir.first)
            } else {
                jdbc.update("UPDATE submission_versions SET revision=? WHERE id=?", input.submissionRevision + 1, F.ir.first)
            }
            assertFailsWith<RuntimeException> { repository().find(N.snapshot().context) }
            assertFailsWith<RuntimeException> { repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt) }
            assertEquals(originalRows, rows())
            jdbc.update("UPDATE submission_versions SET raw_text=?,revision=? WHERE id=?", N.FIRST, input.submissionRevision, F.ir.first)
        }
        val saved = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        for (change in listOf("witness", "projection")) {
            jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb)", saved)
            if (change == "witness") {
                val path = "{locatedEvidence,0,evidence,span,contentSha256}"
                assertNotNull(jdbc.queryForObject("SELECT envelope #>> '$path' FROM schedule_ir_envelopes", String::class.java))
                jdbc.update(
                    "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'$path',to_jsonb(CAST(? AS text)))",
                    "0".repeat(64),
                )
            } else {
                val path = "{calculationProjection,claims,results,0,conditions,0,scope,end}"
                assertNotNull(jdbc.queryForObject("SELECT envelope #>> '$path' FROM schedule_ir_envelopes", String::class.java))
                jdbc.update(
                    "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'$path',to_jsonb(CAST(? AS text)))",
                    "2027-01-07T23:00",
                )
            }
            val corrupted = rows()
            assertFailsWith<RuntimeException> { repository().find(N.snapshot().context) }
            assertEquals(corrupted, rows())
        }
    }

    @Test
    fun `SPG03 idempotent winner cannot bypass current run fences or resurrect deleted IR in matching phase`() {
        val envelope = parse()
        repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val before = rows()
        repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1))
        assertEquals(before, rows())
        jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", F.ir.run)
        assertFailsWith<RuntimeException> { repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt) }
        assertEquals(before, rows())
        jdbc.update("UPDATE coordination_runs SET status='MATCHING' WHERE id=?", F.ir.run)
        assertFailsWith<RuntimeException> { repository().publish(envelope, 2, FinalAnalysisRouteTestFixture.selectedAt) }
        assertEquals(before, rows())
        jdbc.update("DELETE FROM schedule_ir_envelopes WHERE run_id=?", F.ir.run)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
        assertNull(repository().find(N.snapshot().context))
        assertFailsWith<RuntimeException> { repository().publish(envelope, 2, FinalAnalysisRouteTestFixture.selectedAt) }
        assertTrue(rows().isEmpty())
    }

    companion object {
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
