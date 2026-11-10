package com.meetme.server.coordination.adapter.output.persistence

import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.submission.domain.ir.IrInterpretation
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
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** New route admission invariant only; no global policy/default or old format1 behavior change. */
@SpringBootTest
@Import(FinalAnalysisRoutePolicyPostgresTest.ExplicitRouteTestConfiguration::class)
class FinalAnalysisRoutePolicyPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @BeforeEach
    fun pristineNativeBatch() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        F.seed(jdbc, mapper)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `FARP-P01 unresolved interpretation cannot be eligible even in a mixed injected policy`(includeExplicit: Boolean) {
        val before = F.historicalSnapshot(jdbc)
        val interpretations =
            if (includeExplicit) {
                setOf(IrInterpretation.EXPLICIT, IrInterpretation.UNRESOLVED)
            } else {
                setOf(IrInterpretation.UNRESOLVED)
            }
        val base = F.choice()
        val choice = base.copy(policy = base.policy.copy(eligibleInterpretations = interpretations))
        assertFailsWith<RuntimeException> { routes.freeze(F.ir.run, 1, choice) }
        assertNull(routes.find(F.ir.run))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM final_analysis_routes", Int::class.java))
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(interpretations, choice.policy.eligibleInterpretations)
    }

    @Test
    fun `FARP-P02 matching fingerprint does not make unsafe stored eligibility valid`() {
        val route = routes.freeze(F.ir.run, 1, F.choice())
        val selected = route.choice as FinalAnalysisRouteChoice.DslContextV2
        val unsafe =
            selected.copy(
                policy =
                    selected.policy.copy(
                        eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.UNRESOLVED),
                    ),
            )
        // Use the public identity function only to construct a hash-consistent tampered row.
        // The independent oracle is policy rejection despite that consistency, not a copied expected hash.
        val matchingFingerprint = route.copy(choice = unsafe).fingerprint()
        val stored =
            mapper.writeValueAsString(
                linkedMapOf(
                    "identity" to unsafe.identity,
                    "policy" to unsafe.policy,
                    "context" to unsafe.context,
                    "meetingMode" to unsafe.meetingMode,
                ),
            )
        jdbc.update(
            "UPDATE final_analysis_routes SET selection=CAST(? AS jsonb),route_fingerprint=? WHERE run_id=?",
            stored,
            matchingFingerprint,
            F.ir.run,
        )
        assertEquals(
            matchingFingerprint,
            jdbc.queryForObject("SELECT route_fingerprint FROM final_analysis_routes WHERE run_id=?", String::class.java, F.ir.run),
        )
        val before = F.historicalSnapshot(jdbc)
        val storedBefore = F.rows(jdbc, "final_analysis_routes")
        assertFailsWith<RuntimeException> {
            JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(F.selectedAt, ZoneOffset.UTC)).find(F.ir.run)
        }
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(storedBefore, F.rows(jdbc, "final_analysis_routes"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["EXPLICIT", "INFERRED", "EXPLICIT_AND_INFERRED"])
    fun `FARP-P03 structural controls preserve an explicit caller set without approving a product default`(selection: String) {
        val interpretations =
            when (selection) {
                "EXPLICIT" -> setOf(IrInterpretation.EXPLICIT)
                "INFERRED" -> setOf(IrInterpretation.INFERRED)
                else -> setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED)
            }
        val base = F.choice()
        val choice = base.copy(policy = base.policy.copy(eligibleInterpretations = interpretations))
        val frozen = routes.freeze(F.ir.run, 1, choice)
        assertEquals(choice, frozen.choice)
        val restored = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, Clock.fixed(F.selectedAt, ZoneOffset.UTC)).find(F.ir.run)
        assertEquals(frozen, restored)
        assertEquals(interpretations, (restored!!.choice as FinalAnalysisRouteChoice.DslContextV2).policy.eligibleInterpretations)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM analysis_invocations", Int::class.java))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ExplicitRouteTestConfiguration {
        @Bean @Primary
        fun policyTestClock(): Clock = Clock.fixed(F.selectedAt, ZoneOffset.UTC)

        @Bean
        fun policyTestRoutes(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
            clock: Clock,
        ): FinalAnalysisRouteRegistry = JdbcFinalAnalysisRouteRegistry(jdbc, mapper, clock)
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
