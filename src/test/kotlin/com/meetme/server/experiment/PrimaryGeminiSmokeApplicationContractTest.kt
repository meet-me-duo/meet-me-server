package com.meetme.server.experiment

import com.meetme.server.MeetMeServerApplication
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Real local HTTP, PG and Redis, explicit synthetic precheck and empty provider keys. Zero provider calls. */
@SpringBootTest(
    classes = [MeetMeServerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "meetme.reliability.enabled=true", "meetme.rate-limit.enabled=false", "meetme.data-retention.enabled=false",
        "meetme.precheck.provider.enabled=true", "meetme.precheck.decision-routing.enabled=false",
        "meetme.precheck.classifier.enabled=false", "meetme.ai.budget.enabled=false", "meetme.precheck.evidence-enabled=true",
        "meetme.gemini.api-key=", "meetme.openai.api-key=", "meetme.typesafe.api-key=",
        "meetme.anonymous.allowed-origins=http://localhost:18931", "meetme.anonymous.cookie-secure=false", "server.address=127.0.0.1",
    ],
)
@Import(PrimaryGeminiSmokeApplication::class, PrimaryGeminiSmokeFixtures::class)
class PrimaryGeminiSmokeApplicationContractTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tokens: AccessTokenPort

    @Autowired private lateinit var json: ObjectMapper

    @Autowired private lateinit var safety: LiveEvaluationSafety

    @Autowired private lateinit var observations: PrimaryGeminiSmokeObservations

    @TempDir lateinit var output: Path

    @Test
    fun `diagnostic context freezes received HTTP evidence using original authority and zero provider calls`() {
        val originalAuthority = context.getBean(JdbcAiBudgetExecutionFence::class.java)
        val resolvedRuntime = context.getBean(AiRuntimeDispatchGuardPort::class.java)
        assertFalse(mockingDetails(originalAuthority).isMock)
        assertNotSame(originalAuthority, resolvedRuntime)
        assertEquals(1, context.getBeansOfType(LiveEvaluationSafety::class.java).size)
        assertEquals(1, context.getBeansOfType(LiveEvaluationAdmission::class.java).size)

        val artifact = output.resolve("primary-smoke-results.json")
        val diagnostic =
            PrimaryGeminiSmokeDiagnostic(artifact, json, safety, observations) {
                mapOf("attempts" to jdbc.queryForList("SELECT provider,failure_code FROM coordination_attempts"))
            }
        var flowReturned = false
        assertFailsWith<LiveEvaluationTechnicalStop> {
            diagnostic.execute {
                PrimaryGeminiSmokeHttpFlow(port, jdbc, tokens, json, safety).run().also { flowReturned = true }
            }
        }
        assertFalse(flowReturned, "The actual HTTP poll must stop on its shared CONFIGURATION barrier")
        assertTrue(Files.exists(artifact), "The actual failed HTTP flow must retain a report")
        val report = json.readTree(Files.readString(artifact))
        assertEquals("CONFIGURATION", report.path("failureCode").asText())
        assertFalse(report.path("analysisCompleted").asBoolean())
        Files.list(output).use { assertEquals(1, it.count()) }
        val settled = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (count("SELECT count(*) FROM coordination_attempts WHERE failure_code='CONFIGURATION'") == 0 && System.nanoTime() < settled) {
            Thread.sleep(20)
        }

        assertEquals(3, count("SELECT count(*) FROM precheck_executions WHERE model='TEST_DOUBLE' AND finished_at IS NOT NULL"))
        assertEquals(3, count("SELECT count(*) FROM precheck_checks WHERE status='CLEAR'"))
        assertEquals(3, count("SELECT count(*) FROM submission_batch_items"))
        assertEquals(
            3,
            count(
                "SELECT count(*) FROM submission_batch_items b JOIN submission_versions v ON v.id=b.submission_version_id " +
                    "WHERE v.input_evidence->>'protocol'='PRE_SUBMIT_V2' AND v.input_evidence->>'interpretation'='CONFIRMED'",
            ),
        )
        assertEquals(
            1,
            count("SELECT count(*) FROM coordination_attempts WHERE provider='GEMINI' AND failure_code='CONFIGURATION'"),
        )
        assertEquals(0, count("SELECT count(*) FROM parser_transport_client_calls"))
        assertEquals(0, count("SELECT count(*) FROM coordination_attempts WHERE provider='OPENAI'"))
    }

    private fun count(sql: String): Int = requireNotNull(jdbc.queryForObject(sql, Int::class.java))

    companion object {
        @JvmStatic @DynamicPropertySource
        fun infrastructure(registry: DynamicPropertyRegistry) = PrimaryGeminiSmokeInfrastructure.properties(registry)

        @JvmStatic @AfterAll
        fun stopInfrastructure() = PrimaryGeminiSmokeInfrastructure.stop()
    }
}
