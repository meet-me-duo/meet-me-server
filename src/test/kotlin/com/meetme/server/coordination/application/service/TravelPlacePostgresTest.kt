package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslPlaceConstraintPolicy
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.SchedulePlaceReferenceKind
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScopedTermEvidenceTestFixture as S
import com.meetme.server.support.TravelPlaceTestFixture as F

/** Real PostgreSQL/fresh connection restore: typed constraints survive, raw place expressions do not duplicate. */
@SpringBootTest
class TravelPlacePostgresTest {
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

    private fun publish(envelope: ScheduleDslStructuralEnvelope) {
        JdbcScheduleDslStructuralRepository(jdbc, mapper).publish(envelope, 1, FinalAnalysisRouteTestFixture.selectedAt)
    }

    private fun saved() = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))

    private fun format() = jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java)

    private fun fresh(raw: String = F.RAW): ScheduleDslStructuralEnvelope =
        dataSource.connection.use { connection ->
            val independent = JdbcTemplate(SingleConnectionDataSource(connection, true))
            assertNotNull(JdbcScheduleDslStructuralRepository(independent, mapper).find(F.snapshot(raw).context))
        }

    @Test
    fun `TPPG01 format10 restores exact source bound travel from independent connection and calculates unchanged overlap`() {
        val envelope = F.parse(objectMapper = mapper)
        publish(envelope)
        assertEquals(10, format())
        val restored = fresh()
        assertEquals(envelope, restored)
        assertFalse(envelope === restored)
        assertEquals(SCHEDULE_PLACE_CONSTRAINT_VERSION, restored.placeConstraintsVersion)
        assertEquals(listOf(F.constraint()), restored.placeConstraints)
        assertNull(restored.placeConstraints.single().travelMode)
        val publicDescription =
            ScheduleDslPlaceConstraintPolicy
                .descriptions(
                    restored.placeConstraints,
                    F.snapshot(),
                    F.publicNames,
                ).single()
        assertEquals(F.NAME, publicDescription.publicPlaceName)
        assertEquals(45, publicDescription.maxMinutes)
        val stored = saved()
        assertFalse(stored.contains(F.RAW))
        assertFalse(stored.contains(F.PLACE))
        assertFalse(stored.contains(F.NAME))
        assertFalse(stored.contains("\"quote\""))
        assertTrue(stored.contains("contentSha256"))
        val result = ScheduleDslStructuralCalculator.calculate(restored, F.snapshot(), F.policy, MeetingMode.EITHER)
        val expected = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T09:00:00Z"))
        assertEquals(listOf(expected), result.recommendations.options.map { it.window })
        val variants =
            result.recommendations.options
                .single()
                .variants
                .associateBy { it.meetingMode }
        assertEquals(listOf(ParticipantId(F.firstParticipant)), variants.getValue(MeetingMode.IN_PERSON).unverifiedPlaceParticipantIds)
        assertNull(variants.getValue(MeetingMode.IN_PERSON).representativePlace)
        assertTrue(variants.getValue(MeetingMode.REMOTE).unverifiedPlaceParticipantIds.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
    }

    @Test
    fun `TPPG02 forged source hash owner position format or missing declaration marker fails without rewriting stored data`() {
        publish(F.parse(objectMapper = mapper))
        val original = saved()
        for (change in listOf("hash", "position", "owner", "minutes", "positive-minutes", "mode", "marker", "missing-list", "format")) {
            jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb),format_version=10", original)
            when (change) {
                "hash" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(CAST(? AS text)))",
                        "{placeConstraints,0,evidence,span,contentSha256}",
                        "0".repeat(64),
                    )
                "position" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(0))",
                        "{placeConstraints,0,placeEvidence,span,startCodePoint}",
                    )
                "owner" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(CAST(? AS text)))",
                        "{placeConstraints,0,submissionVersionId}",
                        F.second.toString(),
                    )
                "minutes" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(0))",
                        "{placeConstraints,0,maxMinutes}",
                    )
                "positive-minutes" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(120))",
                        "{placeConstraints,0,maxMinutes}",
                    )
                "mode" ->
                    jdbc.update(
                        "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(CAST(? AS text)))",
                        "{placeConstraints,0,travelMode}",
                        "TRANSIT",
                    )
                "marker" ->
                    jdbc.update("UPDATE schedule_ir_envelopes SET envelope=envelope - 'placeConstraintsVersion'")
                "missing-list" ->
                    jdbc.update("UPDATE schedule_ir_envelopes SET envelope=envelope - 'placeConstraints'")
                "format" ->
                    jdbc.update("UPDATE schedule_ir_envelopes SET format_version=9")
            }
            val corrupted = saved()
            assertFailsWith<RuntimeException> { fresh() }
            assertEquals(corrupted, saved())
        }
        jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb),format_version=10", original)
        jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", F.RAW.replace(F.NAME, "가상붉은역"), F.first)
        assertFailsWith<RuntimeException> { fresh() }
        assertEquals(original, saved())
    }

    @Test
    fun `TPPG03 format10 empty declaration remains explicit while old7 old8 and old9 keep their original shapes`() {
        val empty = F.parse(F.document(declarations = emptyList()), objectMapper = mapper)
        publish(empty)
        assertEquals(10, format())
        assertEquals(SCHEDULE_PLACE_CONSTRAINT_VERSION, fresh().placeConstraintsVersion)
        assertTrue(fresh().placeConstraints.isEmpty())
        for (expectedFormat in listOf(7, 8, 9)) {
            val raw =
                when (expectedFormat) {
                    8 -> "2027-01-08 가능."
                    9 -> S.RAW
                    else -> F.RAW
                }
            seed(raw)
            val document =
                when (expectedFormat) {
                    8 -> wholeDayDocument(raw)
                    9 -> S.document()
                    else -> F.legacyDocument()
                }
            val envelope = F.parse(document, raw, enabled = false, objectMapper = mapper, scopedTerms = expectedFormat == 9)
            assertNull(envelope.placeConstraintsVersion)
            assertTrue(envelope.placeConstraints.isEmpty())
            publish(envelope)
            assertEquals(expectedFormat, format())
            assertFalse(saved().contains("placeConstraints"))
            assertFalse(saved().contains("placeConstraintsVersion"))
            assertEquals(envelope, fresh(raw))
        }
    }

    @Test
    fun `TPPG04 private origin remains internal across fresh restore and produces only generic public description`() {
        seed(F.PRIVATE_RAW)
        val declaration = F.declaration(F.PRIVATE_PLACE, F.PRIVATE_NAME).also { it["reference_kind"] = "PRIVATE_REFERENCE" }
        val envelope = F.parse(F.document(F.PRIVATE_RAW, listOf(declaration)), F.PRIVATE_RAW, objectMapper = mapper)
        publish(envelope)
        val restored = fresh(F.PRIVATE_RAW)
        assertEquals(envelope, restored)
        val native = restored.placeConstraints.single()
        assertEquals(SchedulePlaceReferenceKind.PRIVATE_REFERENCE, native.referenceKind)
        assertEquals(45, native.maxMinutes)
        val description =
            ScheduleDslPlaceConstraintPolicy
                .descriptions(
                    restored.placeConstraints,
                    F.snapshot(F.PRIVATE_RAW),
                    F.publicNames,
                ).single()
        assertNull(description.publicPlaceName)
        assertNull(description.maxMinutes)
        assertNull(description.travelMode)
        assertFalse(saved().contains(F.PRIVATE_NAME))
        assertFalse(saved().contains(F.PRIVATE_RAW))
    }

    private fun wholeDayDocument(raw: String): MutableMap<String, Any?> =
        S.document().also { document ->
            val result = S.results(document).first()
            result["approved_terms"] = emptyList<Any>()
            @Suppress("UNCHECKED_CAST")
            val condition = (result["conditions"] as List<MutableMap<String, Any?>>).single()
            val evidence = listOf(F.locator(raw))
            condition["scope"] = mapOf("dimension" to "TIME", "start" to "2027-01-08", "end" to null)
            condition["evidence"] = evidence
            @Suppress("UNCHECKED_CAST")
            val nodes = F.fields(result["program"])["nodes"] as List<MutableMap<String, Any?>>
            nodes.forEach { it["evidence"] = evidence }
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
