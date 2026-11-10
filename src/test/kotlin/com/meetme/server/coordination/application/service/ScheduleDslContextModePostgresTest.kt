package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextModeResponseParser
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Real shared document store and frozen native rows; no service activation, provider HTTP, live data or model call. */
@SpringBootTest
class ScheduleDslContextModePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun frozenNativeInput() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        N.seed(jdbc, mapper)
    }

    private fun repository() = JdbcScheduleDslContextModeRepository(jdbc, mapper)

    private fun rows() = FinalAnalysisRouteTestFixture.rows(jdbc, "schedule_ir_envelopes")

    @Test
    fun `CMP01 format6 roundtrip preserves contextual and mode evidence without raw text or changing an idempotent winner`() {
        val expected = N.expectedEnvelope()
        // Repository RED reaches publish directly; the already-GREEN domain prepares the valid input.
        repository().publish(expected, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val restored = assertNotNull(repository().find(N.snapshot().context))
        assertEquals(expected, restored)
        assertEquals(fingerprint(expected), fingerprint(restored))
        assertEquals("schedule-ir-product-5", jdbc.queryForObject("SELECT semantic_version FROM schedule_ir_envelopes", String::class.java))
        assertEquals(6, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
        val stored = requireNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        val root = mapper.readValue(stored, Map::class.java)
        assertEquals(
            setOf("semanticVersion", "astVersion", "base", "calculationProjection", "contextApplications", "modeRestrictions"),
            root.keys,
        )
        val base = root["base"] as Map<*, *>
        assertEquals(
            setOf("semanticVersion", "astVersion", "vocabularyVersion", "declaration", "calculationProjection", "termApplications"),
            base.keys,
        )
        val dslFields = setOf("semanticVersion", "astVersion", "claims", "programs", "rejectedPreferenceWitnesses")
        assertEquals(dslFields, (root["calculationProjection"] as Map<*, *>).keys)
        assertEquals(dslFields, (base["declaration"] as Map<*, *>).keys)
        assertEquals(dslFields, (base["calculationProjection"] as Map<*, *>).keys)
        for (privateValue in listOf(N.FIRST, N.SECOND, N.MODE_QUOTE, F.q.PROMPT, F.q.answer.answerText)) {
            assertFalse(stored.contains(privateValue))
        }
        for (key in listOf("\"quote\"", "\"rawText\"", "\"answerText\"", "\"prompt\"")) assertFalse(stored.contains(key))
        assertTrue(stored.contains("\"contentSha256\""))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
        assertFailsWith<RuntimeException> { JdbcScheduleDslApprovedTermsRepository(jdbc, mapper).find(N.snapshot().context) }

        val parsed =
            StrictScheduleDslContextModeResponseParser(mapper).parse(
                F.json(N.document()),
                N.snapshot(),
                "schedule-dsl-response-4",
                "schedule-ir-product-5",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        assertEquals(expected, parsed)
        val before = rows()
        repository().publish(parsed, 1, FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1))
        assertEquals(before, rows())
        val reloaded = assertNotNull(repository().find(N.snapshot().context))
        assertEquals(fingerprint(parsed), fingerprint(reloaded))
        val calculated = ScheduleDslContextModeCalculator.calculate(reloaded, N.snapshot(), N.policy, MeetingMode.EITHER)
        val first = ParticipantId(F.ir.firstParticipant)
        assertEquals(
            listOf(N.thursday, N.friday),
            calculated.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertEquals(
            listOf(N.friday),
            calculated.preparedByMode
                .getValue(MeetingMode.IN_PERSON)
                .participants
                .single { it.participantId == first }
                .availableTimes,
        )
        assertTrue(calculated.candidates.candidates.all { it.participantIds.size == 2 && it.representativePlace == null })
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
    }

    @Test
    fun `CMP02 stale context and changed native source cannot be read or republished as the frozen IR5 winner`() {
        val envelope = N.expectedEnvelope()
        repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt)
        val before = rows()
        assertFailsWith<RuntimeException> { repository().find(N.snapshot().context.copy(timeZone = "UTC")) }
        assertFailsWith<RuntimeException> {
            repository().find(N.snapshot().context.copy(policyVersion = "schedule-approved-availability-1"))
        }
        assertEquals(before, rows())
        jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", N.FIRST + " Synthetic changed source.", F.ir.first)
        assertFailsWith<RuntimeException> { repository().find(N.snapshot().context) }
        assertFailsWith<RuntimeException> { repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1)) }
        assertEquals(before, rows())
    }

    @Test
    fun `CMP03 unsupported stored identities and extra wrapper fields fail closed without repair or fallback`() {
        repository().publish(N.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        val stored = requireNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        for (change in listOf("format", "semantic", "extra")) {
            jdbc.update(
                "UPDATE schedule_ir_envelopes SET format_version=6,semantic_version='schedule-ir-product-5',envelope=CAST(? AS jsonb)",
                stored,
            )
            when (change) {
                "format" -> jdbc.update("UPDATE schedule_ir_envelopes SET format_version=999")
                "semantic" -> jdbc.update("UPDATE schedule_ir_envelopes SET semantic_version='schedule-ir-unapproved'")
                else -> jdbc.update("UPDATE schedule_ir_envelopes SET envelope=envelope || '{\"context\":{}}'::jsonb")
            }
            val corrupted = rows()
            assertFailsWith<RuntimeException> { repository().find(N.snapshot().context) }
            assertEquals(corrupted, rows())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-context-witness", "mode-digest"])
    fun `CMP04 fresh restoration recomputes contextual derivation and native mode evidence`(damage: String) {
        repository().publish(N.expectedEnvelope(), 1, FinalAnalysisRouteTestFixture.selectedAt)
        if (damage == "missing-context-witness") {
            jdbc.update("UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,'{contextApplications}','[]')")
        } else {
            jdbc.update(
                "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope," +
                    "'{modeRestrictions,0,evidence,0,contentSha256}',to_jsonb(CAST(? AS text)))",
                "0".repeat(64),
            )
        }
        val corrupted = rows()
        assertFailsWith<RuntimeException> { repository().find(N.snapshot().context) }
        assertEquals(corrupted, rows())
    }

    @Test
    fun `CMP05 valid IR5 cannot bypass stale attempt or late matching write fences`() {
        val envelope = N.expectedEnvelope()
        jdbc.update("UPDATE coordination_runs SET version=2 WHERE id=?", F.ir.run)
        assertFailsWith<RuntimeException> { repository().publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt) }
        assertTrue(rows().isEmpty())
        jdbc.update("UPDATE coordination_runs SET status='MATCHING' WHERE id=?", F.ir.run)
        assertFailsWith<RuntimeException> { repository().publish(envelope, 2, FinalAnalysisRouteTestFixture.selectedAt) }
        assertTrue(rows().isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
    }

    private fun fingerprint(envelope: ScheduleDslContextModeEnvelope): Map<String, String> =
        mapOf(
            "base" to envelope.base,
            "projection" to envelope.calculationProjection,
            "contextApplications" to envelope.contextApplications,
            "modeRestrictions" to envelope.modeRestrictions,
        ).mapValues { (_, value) ->
            MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)).joinToString("") { "%02x".format(it) }
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
