package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.adapter.output.integration.DateScopeInlineFixture
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslStructuralRepository
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
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
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

/** Same native seed, JDBC repository and mapped PostgreSQL fixture as the existing C5 storage suite. */
@SpringBootTest
class ScheduleDslDateScopeStoragePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun cleanSyntheticRows() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
    }

    @Test
    fun `DS01 whole-day format8 survives fresh read and rejects metadata proof and projection tampering`() {
        val fixture = DateScopeInlineFixture.dateOnly()
        FinalAnalysisRouteTestFixture.seed(jdbc, mapper, fixture.input)
        val declared = fixture.parse()
        repository().publish(declared, 1, FinalAnalysisRouteTestFixture.selectedAt)
        assertEquals(8, format())
        val restored = assertNotNull(repository().find(F.snapshot(fixture.input).context))
        assertEquals(declared, restored)
        assertEquals(restored, ScheduleDslStructuralValidator().validateRestored(restored, F.snapshot(fixture.input)))
        assertEquals(fixture.available(declared), fixture.available(restored))
        assertEquals("schedule-ir-product-6", jdbc.queryForObject("SELECT semantic_version FROM schedule_ir_envelopes", String::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM schedule_ir_inputs", Int::class.java))
        val saved = savedJson()
        assertTrue(saved.contains("WHOLE_DAY"))
        assertTrue(saved.contains("SERVER_CALENDAR_DAY"))
        assertFalse(
            saved.contains(
                fixture.input.inputs
                    .first()
                    .rawText,
            ),
        )
        assertFalse(saved.contains("\"quote\""))
        jdbc.update("UPDATE schedule_ir_envelopes SET format_version=7")
        val wrongVersion = rows()
        assertFailsWith<RuntimeException> { repository().find(F.snapshot(fixture.input).context) }
        assertEquals(wrongVersion, rows())
        jdbc.update("UPDATE schedule_ir_envelopes SET format_version=8")
        for ((path, value) in listOf(
            "{directionalApplications,0,declaration,clock}" to "16:00",
            "{directionalApplications,0,derivation}" to "SERVER_DATE_SCOPE",
            "{calculationProjection,claims,results,0,conditions,0,scope,end}" to "2027-01-08T23:00",
        )) {
            jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb)", saved)
            jdbc.update(
                "UPDATE schedule_ir_envelopes SET envelope=jsonb_set(envelope,CAST(? AS text[]),to_jsonb(CAST(? AS text)))",
                path,
                value,
            )
            val corrupted = rows()
            assertFailsWith<RuntimeException> { repository().find(F.snapshot(fixture.input).context) }
            assertEquals(corrupted, rows())
        }
        jdbc.update("UPDATE schedule_ir_envelopes SET envelope=CAST(? AS jsonb)", saved)
        jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", "Changed native source.", F.ir.first)
        assertFailsWith<RuntimeException> { repository().find(F.snapshot(fixture.input).context) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts", Int::class.java))
    }

    @Test
    fun `DS02 old flat format7 stays readable and idempotent while a false format8 label is refused`() {
        val fixture = DateScopeInlineFixture.numeric(inline = false)
        FinalAnalysisRouteTestFixture.seed(jdbc, mapper, fixture.input)
        val declared = fixture.parse()
        assertTrue(declared.directionalApplications.isEmpty())
        repository().publish(declared, 1, FinalAnalysisRouteTestFixture.selectedAt)
        assertEquals(7, format())
        val saved = savedJson()
        val restored = assertNotNull(repository().find(F.snapshot(fixture.input).context))
        assertEquals(declared, restored)
        assertEquals(fixture.available(declared), fixture.available(restored))
        val before = rows()
        repository().publish(declared, 1, FinalAnalysisRouteTestFixture.selectedAt.plusSeconds(1))
        assertEquals(before, rows())
        assertEquals(saved, savedJson())
        assertFalse(saved.contains("WHOLE_DAY"))
        assertFalse(saved.contains("SERVER_CALENDAR_DAY"))
        assertFalse(saved.contains("producer_schema_revision"))
        jdbc.update("UPDATE schedule_ir_envelopes SET format_version=8")
        val wrongVersion = rows()
        assertFailsWith<RuntimeException> { repository().find(F.snapshot(fixture.input).context) }
        assertEquals(wrongVersion, rows())
        jdbc.update("UPDATE schedule_ir_envelopes SET format_version=7")
        assertEquals(declared, repository().find(F.snapshot(fixture.input).context))
        assertEquals(saved, savedJson())
    }

    private fun repository() = JdbcScheduleDslStructuralRepository(jdbc, mapper)

    private fun format() = jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java)

    private fun savedJson() = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))

    private fun rows() = FinalAnalysisRouteTestFixture.rows(jdbc, "schedule_ir_envelopes")

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
