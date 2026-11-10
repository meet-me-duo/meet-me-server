package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.support.mappedPortReadyPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Explicit registry selection only; no provider, physical budget admission, fake ticket or model response. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class)
class ApprovedTermsFinalAnalysisRoutePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @BeforeEach
    fun immutableNativeBatch() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper)
    }

    private fun choice(): FinalAnalysisRouteChoice.DslApprovedTermsV3 {
        val policy = ApprovedScheduleDslMatchingPolicy.contextual()
        val input =
            F.q.input1().copy(
                responseContractVersion = "schedule-dsl-response-3",
                semanticVersion = "schedule-ir-product-4",
                policy = policy,
            )
        // Actual native bindings/capsule validation, not a copied/fabricated context with a new policy label.
        val snapshot = ScheduleDslOfflinePipeline.snapshotApprovedTerms(input).snapshot
        return FinalAnalysisRouteChoice.DslApprovedTermsV3(
            IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", ApprovedScheduleDslMatchingPolicy.VERSION),
            policy,
            snapshot.context,
            input.meetingMode,
            "ko-time-terms-1",
        )
    }

    private fun fresh(at: Instant = F.selectedAt): FinalAnalysisRouteRegistry =
        JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(at, ZoneOffset.UTC))

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun selectionRows() = F.rows(jdbc, "final_analysis_routes")

    private fun noAdmission() {
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("ai_call_reservations"))
    }

    @Test
    fun `AV3R01 V27 is applied while existing route shape constraints remain strict`() {
        assertEquals(31, jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Int::class.java))
        routes.freeze(F.ir.run, 1)
        val before = selectionRows()
        for (invalidAssignment in listOf("format_version=0", "route_fingerprint='invalid'", "selection='[]'::jsonb")) {
            assertFailsWith<RuntimeException> { jdbc.update("UPDATE final_analysis_routes SET $invalidAssignment") }
            assertEquals(before, selectionRows())
        }
        assertEquals(FinalAnalysisRouteChoice.Legacy, fresh().find(F.ir.run)!!.choice)
    }

    @Test
    fun `AV3R02 approved choice rolls back with caller then round trips immutably without side effects`() {
        val selected = choice()
        val before = F.historicalSnapshot(jdbc)
        val marker = IllegalStateException("synthetic route rollback")
        val thrown =
            assertFailsWith<IllegalStateException> {
                TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).executeWithoutResult {
                    routes.freeze(F.ir.run, 1, selected)
                    assertNotNull(routes.find(F.ir.run))
                    throw marker
                }
            }
        assertSame(marker, thrown)
        assertNull(routes.find(F.ir.run))
        val frozen = routes.freeze(F.ir.run, 1, selected)
        assertEquals(selected, frozen.choice)
        assertEquals(frozen, fresh().find(F.ir.run))
        assertEquals("DSL_APPROVED_TERMS_V3", jdbc.queryForObject("SELECT route_kind FROM final_analysis_routes", String::class.java))
        val selection =
            mapper.readTree(
                requireNotNull(jdbc.queryForObject("SELECT selection::text FROM final_analysis_routes", String::class.java)),
            )
        assertEquals(setOf("identity", "policy", "context", "meetingMode", "vocabularyVersion"), selection.propertyNames().toSet())
        assertEquals("ko-time-terms-1", selection.path("vocabularyVersion").asString())
        val immutable = selectionRows()
        assertEquals(frozen, fresh(F.selectedAt.plusSeconds(60)).freeze(F.ir.run, 1, selected))
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, F.choice()) }
        assertEquals(immutable, selectionRows())
        assertNotEquals(F.route().fingerprint(), frozen.fingerprint())
        assertEquals(before, F.historicalSnapshot(jdbc))
        noAdmission()
    }

    @Test
    fun `AV3R03 unsupported vocabulary cannot acquire a selected route`() {
        val selected = choice().copy(vocabularyVersion = "ko-time-terms-unapproved")
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, selected) }
        assertNull(routes.find(F.ir.run))
        noAdmission()
    }

    @Test
    fun `AV3R04 response2 identity cannot masquerade as approved response3`() {
        val selected = choice().let { it.copy(identity = it.identity.copy(responseContractVersion = "schedule-dsl-response-2")) }
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, selected) }
        assertNull(routes.find(F.ir.run))
        noAdmission()
    }

    @Test
    fun `AV3R05 existing old pilot format1 binding cannot become approved route`() {
        val old = JdbcScheduleIrExecutionRegistry(jdbc, mapper)
        val ticket = F.oldTicket()
        old.bind(ticket)
        val before = F.rows(jdbc, "schedule_ir_execution_bindings")
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, choice()) }
        assertNull(routes.find(F.ir.run))
        assertEquals(ticket, old.find(F.ir.run))
        old.requireCurrent(ticket)
        assertEquals(before, F.rows(jdbc, "schedule_ir_execution_bindings"))
        noAdmission()
    }

    @Test
    fun `AV3R06 existing legacy invocation history cannot be adopted as approved route`() {
        val invocation = F.invocation()
        invocations.insert(invocation)
        val before = F.historicalSnapshot(jdbc)
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, choice()) }
        assertNull(routes.find(F.ir.run))
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(invocation, invocations.findByRunVersion(invocation.runId, invocation.runVersion))
    }

    @ParameterizedTest
    @ValueSource(strings = ["owner", "deadline"])
    fun `AV3R07 approved dispatch binding rechecks persisted owner and deadline`(change: String) {
        val frozen = routes.freeze(F.ir.run, 1, choice())
        val invocation = F.invocation()
        invocations.insert(invocation)
        val binding = routes.bindAttempt(frozen, invocation)
        routes.requireDispatchCurrent(binding)
        val immutable = selectionRows()
        if (change == "owner") {
            jdbc.update("UPDATE analysis_invocations SET owner_token=? WHERE id=?", UUID(0, 9991), invocation.id)
            assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(binding) }
        } else {
            assertFailsWith<RuntimeException> { fresh(invocation.deadlineAt).requireDispatchCurrent(binding) }
        }
        assertEquals(immutable, selectionRows())
        assertEquals(0, count("coordination_attempts"))
        assertEquals(0, count("ai_call_reservations"))
    }

    @Test
    fun `AV3R08 native question source drift cannot bypass guards through the new route subtype`() {
        val frozen = routes.freeze(F.ir.run, 1, choice())
        val invocation = F.invocation()
        invocations.insert(invocation)
        val binding = routes.bindAttempt(frozen, invocation)
        routes.requireDispatchCurrent(binding)
        val immutable = selectionRows()
        jdbc.update(
            """
            UPDATE submission_versions SET input_evidence=jsonb_set(input_evidence,
                '{supplement,prompt}',to_jsonb('Changed synthetic route question'::text)) WHERE id=?
            """.trimIndent(),
            F.ir.first,
        )
        assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(binding) }
        assertEquals(immutable, selectionRows())
        assertEquals(0, count("coordination_attempts"))
        assertEquals(0, count("ai_call_reservations"))
    }

    @Test
    fun `AV3R09 context2 retains its old closed JSON and rejects a vocabulary extension`() {
        val frozen = routes.freeze(F.ir.run, 1, F.choice())
        val before = selectionRows()
        val json =
            mapper.readTree(
                requireNotNull(jdbc.queryForObject("SELECT selection::text FROM final_analysis_routes", String::class.java)),
            )
        assertEquals(setOf("identity", "policy", "context", "meetingMode"), json.propertyNames().toSet())
        assertEquals("DSL_CONTEXT_V2", jdbc.queryForObject("SELECT route_kind FROM final_analysis_routes", String::class.java))
        assertEquals(F.route().fingerprint(), frozen.fingerprint())
        assertEquals(frozen, fresh().find(F.ir.run))
        assertEquals(frozen, routes.freeze(F.ir.run, 1, F.choice()))
        assertEquals(before, selectionRows())
        jdbc.update("UPDATE final_analysis_routes SET selection=selection || '{\"vocabularyVersion\":\"ko-time-terms-1\"}'::jsonb")
        assertFailsWith<RuntimeException> { fresh().find(F.ir.run) }
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    companion object {
        private val postgres = mappedPortReadyPostgres().apply { start() }

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("meetme.reliability.enabled") { false }
            registry.add("meetme.data-retention.enabled") { false }
            registry.add("meetme.rate-limit.enabled") { false }
        }

        @JvmStatic @AfterAll
        fun stop() {
            postgres.stop()
        }
    }
}
