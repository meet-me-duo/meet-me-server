package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleIrV2Repository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Actual parser/normalizer -> PostgreSQL -> fresh replay -> existing math, entirely synthetic and offline. */
@SpringBootTest
class ScheduleDslApprovedTermsPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun nativeFrozenInput() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper)
    }

    private fun parser() = StrictScheduleDslApprovedTermsResponseParser(mapper)

    private fun repository() = JdbcScheduleDslApprovedTermsRepository(jdbc, mapper)

    private fun parse(json: String = F.json()) =
        parser().parse(
            json,
            F.snapshot(),
            "schedule-dsl-response-3",
            "schedule-ir-product-4",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    private fun rows() = FinalAnalysisRouteTestFixture.rows(jdbc, "schedule_ir_envelopes")

    @Test
    fun `AHP01 afternoon survives actual parsing storage fresh replay and two-person calculation`() {
        val parsed = parse()
        repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val restored = assertNotNull(repository().find(F.snapshot().context))
        assertEquals(parsed, restored)
        assertEquals(
            "schedule-ir-product-4",
            jdbc.queryForObject("SELECT semantic_version FROM schedule_ir_envelopes", String::class.java),
        )
        assertEquals(5, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        val prepared = ScheduleDslInputPolicy.project(restored.calculationProjection, F.policy)
        val afternoon = InstantTimeRange(Instant.parse("2027-01-08T03:00:00Z"), Instant.parse("2027-01-08T15:00:00Z"))
        val overlap = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T09:00:00Z"))
        assertEquals(
            listOf(afternoon),
            prepared.participants.single { it.participantId == ParticipantId(F.ir.firstParticipant) }.availableTimes,
        )
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates
        assertTrue(candidates.any { it.participantIds.size == 2 && it.timeRanges == listOf(overlap) })
        val recommendations = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul"))
        assertTrue(
            recommendations.options.any { option ->
                option.window == overlap && option.variants.any { it.participantIds.size == 2 }
            },
        )
        assertEquals(
            "NON_PRECISE",
            restored.declaration.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution.name,
        )
        assertEquals(
            "PRECISE_INTERVAL",
            restored.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution.name,
        )
        assertEquals(1, restored.termApplications.size)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
    }

    @Test
    fun `AHP02 format5 stores both declarations and witnesses without raw quotes or duplicate context getters`() {
        repository().publish(F.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        val json = requireNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        val root = mapper.readValue(json, Map::class.java)
        assertEquals(
            setOf("semanticVersion", "astVersion", "vocabularyVersion", "declaration", "calculationProjection", "termApplications"),
            root.keys,
        )
        for (name in listOf("declaration", "calculationProjection")) {
            val nested = root[name] as Map<*, *>
            assertEquals(setOf("semanticVersion", "astVersion", "claims", "programs", "rejectedPreferenceWitnesses"), nested.keys)
        }
        assertFalse(json.contains(F.FIRST))
        assertFalse(json.contains(F.SECOND))
        assertFalse(json.contains("\"quote\""))
        assertFalse(json.contains("\"rawText\""))
        assertFalse(json.contains("\"answerText\""))
        assertTrue(json.contains("\"contentSha256\""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["IR1", "IR2", "DSL3"])
    fun `AHP03 old readers reject the new wrapper rather than exposing a bare numeric result`(reader: String) {
        repository().publish(F.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        assertFailsWith<RuntimeException> {
            when (reader) {
                "IR1" -> JdbcScheduleIrRepository(jdbc, mapper).find(F.snapshot().context)
                "IR2" -> JdbcScheduleIrV2Repository(jdbc, mapper).findV2(F.snapshot().context)
                else -> JdbcScheduleDslRepository(jdbc, mapper).find(F.snapshot().context)
            }
        }
    }

    @Test
    fun `AHP04 old DSL row is not silently adopted or rewritten as term-derived data`() {
        val new = F.expectedEnvelope()
        val old = JdbcScheduleDslRepository(jdbc, mapper)
        old.publish(new.declaration, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val before = rows()
        assertFailsWith<RuntimeException> { repository().find(F.snapshot().context) }
        assertFailsWith<RuntimeException> { repository().publish(new, 1, FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1)) }
        assertEquals(before, rows())
        assertEquals(new.declaration, old.find(F.snapshot().context))
    }

    @Test
    fun `AHP05 identical wrapper is idempotent and caller rollback removes the complete write`() {
        val parsed = F.expectedEnvelope()
        TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).executeWithoutResult { tx ->
            repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt)
            assertNotNull(repository().find(F.snapshot().context))
            tx.setRollbackOnly()
        }
        assertNull(repository().find(F.snapshot().context))
        repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val before = rows()
        repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1))
        assertEquals(before, rows())
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-witness", "scope", "digest", "vocabulary"])
    fun `AHP06 fresh reader recomputes stored provenance instead of trusting numeric endpoints`(change: String) {
        repository().publish(F.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        when (change) {
            "missing-witness" -> jdbc.update("UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{termApplications}','[]')")
            "scope" ->
                jdbc.update(
                    """
                    UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,
                        '{termApplications,0,resolvedScope,start}','"2027-01-08T17:00:00"')
                    """.trimIndent(),
                )
            "digest" ->
                jdbc.update(
                    """
                    UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,
                        '{termApplications,0,termEvidence,contentSha256}',to_jsonb(CAST(? AS text)))
                    """.trimIndent(),
                    "0".repeat(64),
                )
            else ->
                jdbc.update(
                    "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{vocabularyVersion}','\"unapproved-meals-1\"')",
                )
        }
        val before = rows()
        assertFailsWith<RuntimeException> { repository().find(F.snapshot().context) }
        assertEquals(before, rows())
    }

    @Test
    fun `AHP07 native revision and raw-content drift invalidate fresh replay`() {
        repository().publish(F.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        jdbc.update("UPDATE submission_versions SET raw_text='Changed synthetic statement' WHERE id=?", F.ir.first)
        val before = rows()
        assertFailsWith<RuntimeException> { repository().find(F.snapshot().context) }
        assertEquals(before, rows())
    }

    @Test
    fun `AHP08 current-version write fence is not replaced by successful term normalization`() {
        val parsed = F.expectedEnvelope()
        jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", F.ir.run)
        assertFailsWith<RuntimeException> { repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt) }
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `AHP09 oversized raw response creates no stored winner`() {
        val error =
            assertFailsWith<NaturalLanguageParserException> {
                val parsed = parse(F.json() + " ".repeat(262_145))
                repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt)
            }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, error.kind)
        assertTrue(rows().isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
    }

    @Test
    fun `AHP10 isolated bad term can be stored and replayed while the peer remains usable`() {
        val document = F.document()
        F.firstTerm(document)["term"] = "SIESTA"
        val parsed = parse(F.json(document))
        repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val restored = assertNotNull(repository().find(F.snapshot().context))
        assertEquals(parsed, restored)
        assertTrue(restored.termApplications.isEmpty())
        val prepared = ScheduleDslInputPolicy.project(restored.calculationProjection, F.policy)
        assertTrue(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
        assertFalse(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.secondParticipant) }
                .availableTimes
                .isEmpty(),
        )
    }

    @Test
    fun `AHP11 stored wrapper remains closed instead of discarding an unknown context field`() {
        repository().publish(F.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        jdbc.update("UPDATE schedule_ir_envelopes SET envelope=envelope || '{\"context\":{}}'::jsonb")
        val before = rows()
        assertFailsWith<RuntimeException> { repository().find(F.snapshot().context) }
        assertEquals(before, rows())
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
