package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.EvidenceLocationDerivation
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as F

/** Synthetic actual PostgreSQL persistence and fresh-connection calculation, without any provider call. */
@SpringBootTest
class ScopedTermEvidencePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource

    @BeforeEach
    fun seedSyntheticNativeRows() {
        seed()
    }

    private fun seed(raw: String = F.RAW) {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        H.seed(jdbc, mapper, F.input(raw))
    }

    private fun parse(
        document: MutableMap<String, Any?> = F.document(),
        raw: String = F.RAW,
    ): ScheduleDslStructuralEnvelope =
        StrictScheduleDslStructuralResponseParser(mapper, allowScopedTermEvidence = true).parse(
            H.json(document),
            F.snapshot(raw),
            "schedule-dsl-response-5",
            "schedule-ir-product-6",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    private fun saved() = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))

    private fun fresh(raw: String = F.RAW): ScheduleDslStructuralEnvelope =
        dataSource.connection.use { connection ->
            val independent = JdbcTemplate(SingleConnectionDataSource(connection, true))
            assertNotNull(JdbcScheduleDslStructuralRepository(independent, mapper).find(F.snapshot(raw).context))
        }

    @Test
    fun `STPG01 format9 new connection preserves scoped occurrence and independently expected overlap without raw quotes`() {
        val declared = parse()
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(declared, 1, FinalAnalysisRouteTestFixture.selectedAt)
        assertEquals(9, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        val restored = fresh()
        assertEquals(declared, restored)
        assertFalse(declared === restored)
        val witness = restored.locatedEvidence.single { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE }
        assertEquals(F.span("오후", 20, 22), witness.evidence.span)
        assertEquals(F.span(F.CONTEXT, 9, 26), witness.evidence.contextSpan)
        val stored = saved()
        assertTrue(stored.contains("contextSpan"))
        assertFalse(stored.contains(F.RAW))
        assertFalse(stored.contains(F.CONTEXT))
        assertFalse(stored.contains("\"quote\""))
        val result = ScheduleDslStructuralCalculator.calculate(restored, F.snapshot(), F.policy, MeetingMode.REMOTE)
        val expected = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T09:00:00Z"))
        assertEquals(listOf(expected), result.recommendations.options.map { it.window })
        assertTrue(
            result.recommendations.options
                .single()
                .variants
                .all { it.participantIds.size == 2 },
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
    }

    @Test
    fun `STPG02 fresh restore refuses altered native source context position and downgraded format without rewriting`() {
        val envelope = parse()
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val original = saved()
        val index =
            envelope.locatedEvidence.indexOfFirst {
                it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE
            }
        require(index >= 0)
        for (change in listOf("context-hash", "term-position", "missing-context", "format")) {
            jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb),format_version=9", original)
            when (change) {
                "context-hash" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(CAST(? AS text)))",
                        "{locatedEvidence,$index,evidence,contextSpan,contentSha256}",
                        "0".repeat(64),
                    )
                "term-position" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(5))",
                        "{locatedEvidence,$index,evidence,span,startCodePoint}",
                    )
                "missing-context" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=envelope #- CAST(? AS text[])",
                        "{locatedEvidence,$index,evidence,contextSpan}",
                    )
                "format" -> jdbc.update("UPDATE schedule_ir_envelopes SET format_version=7")
            }
            val corrupted = saved()
            assertFailsWith<RuntimeException> { fresh() }
            assertEquals(corrupted, saved())
        }
        jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb),format_version=9", original)
        jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", F.RAW.replace("안내", "알림"), F.first)
        assertFailsWith<RuntimeException> { fresh() }
        assertEquals(original, saved())
    }

    @Test
    fun `STPG03 old format7 and8 retain old witness JSON without scoped fields and remain freshly readable`() {
        for (format in listOf(7, 8)) {
            val raw = if (format == 7) H.FIRST else "2027-01-08 가능."
            seed(raw)
            val document =
                if (format == 7) {
                    F.document(F.input(raw), listOf(raw))
                } else {
                    F.document().also { result ->
                        val first = F.results(result).first()
                        first["approved_terms"] = emptyList<Any>()
                        @Suppress("UNCHECKED_CAST")
                        val condition = (first["conditions"] as List<MutableMap<String, Any?>>).single()
                        val evidence = listOf(mapOf("source_id" to "original", "revision" to 3, "quote" to raw))
                        condition["scope"] = mapOf("dimension" to "TIME", "start" to "2027-01-08", "end" to null)
                        condition["evidence"] = evidence
                        @Suppress("UNCHECKED_CAST")
                        val nodes = F.fields(first["program"])["nodes"] as List<MutableMap<String, Any?>>
                        nodes.forEach { it["evidence"] = evidence }
                    }
                }
            val envelope = parse(document, raw)
            assertTrue(envelope.locatedEvidence.all { it.evidence.derivation == EvidenceLocationDerivation.SERVER_LITERAL_QUOTE })
            JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt)
            assertEquals(format, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
            assertFalse(saved().contains("contextSpan"))
            assertEquals(envelope, fresh(raw))
        }
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
