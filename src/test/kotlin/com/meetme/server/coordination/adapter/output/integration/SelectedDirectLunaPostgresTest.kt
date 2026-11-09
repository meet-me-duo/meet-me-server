package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.application.service.SelectedScheduleDslBatchProcessor
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.physicalProfile
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchProfiles
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Synthetic native admission, real PostgreSQL fences and loopback HTTP only; no live provider or model-quality claim. */
@SpringBootTest
@Import(C5ResidualLunaBoundaryPostgresTest.TimeConfiguration::class, SelectedApprovedTermsProviderPostgresTest.AuthorityConfig::class)
class SelectedDirectLunaPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var clock: C5ResidualLunaBoundaryPostgresTest.BoundaryClock

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var persistence: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Autowired private lateinit var fence: JdbcAiBudgetExecutionFence

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        clock.set(F.selectedAt)
    }

    @Test
    fun `DL01 explicit C5 Luna admission starts attempt one with zero Gemini and sends exactly one guarded native request`() {
        seed()
        val native = nativeRows()
        val admitted = direct()
        val invocation = admitted.binding.invocation
        assertEquals(AnalysisInvocation.C5_POLICY_VERSION, invocation.policyVersion)
        assertEquals(F.selectedAt, invocation.startedAt)
        assertEquals(F.selectedAt.plusSeconds(60), invocation.deadlineAt)
        assertEquals(0, invocation.geminiAttempts)
        assertEquals(1, invocation.lunaAttempts)
        assertEquals(AnalysisProvider.OPENAI, admitted.attempt.provider)
        assertEquals("gpt-6-luna", admitted.attempt.model)
        assertEquals(1, admitted.attempt.attemptNumber)
        assertEquals(1, count("coordination_attempts"))
        val request = request(admitted)
        assertEquals(Duration.ofSeconds(40), request.timeout)
        assertEquals(F.selectedAt.plusSeconds(40), fence.authorize(reservation(request), clock.instant()))

        Loopback().use { http ->
            val adapter = adapter(http)
            val response = adapter.generate(request)
            assertEquals("{}", response.responseJson)
            assertEquals(AnalysisProvider.OPENAI, response.provider)
            assertEquals(1, http.requests.size)
            assertEquals(listOf("/v1/responses"), http.paths.toList())
            assertEquals(1, count("ai_call_reservations"))
            assertEquals(admitted.attempt.id, jdbc.queryForObject("SELECT attempt_id FROM ai_call_reservations", UUID::class.java))
            assertEquals("FINAL_FALLBACK", jdbc.queryForObject("SELECT operation FROM ai_call_reservations", String::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT ordinal FROM ai_call_reservations", Int::class.java))
            assertEquals(
                hash(http.requests.single()),
                jdbc.queryForObject("SELECT whole_payload_fingerprint FROM ai_call_reservations", String::class.java),
            )
            val body = mapper.readTree(http.requests.single())
            assertEquals("gpt-6-luna", body.path("model").asString())
            assertEquals("low", body.path("reasoning").path("effort").asString())
            val choice = request.binding.route.choice as FinalAnalysisRouteChoice.DslStructuralV5
            val neutralBytes = ScheduleDslStructuralProviderContract(mapper).requestBytes(request.inputCapsule, choice.meetingMode)
            val neutral = mapper.readValue(neutralBytes, Map::class.java)
            assertEquals(mapper.readTree(mapper.writeValueAsBytes(neutral["prompt"])), body.path("instructions"))
            assertEquals(
                mapper.writeValueAsString(neutral - "prompt"),
                body.path("input").asString(),
            )
            assertFailsWith<RuntimeException> { adapter.generate(request) }
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
        }
        assertEquals(0, currentInvocation().geminiAttempts)
        assertEquals(1, currentInvocation().lunaAttempts)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM coordination_attempts WHERE provider='GEMINI'", Int::class.java))
        assertEquals(native, nativeRows())
    }

    @Test
    fun `DL02 owner native source forty second cap and completion reserve still reject direct Luna before HTTP`() {
        for (fault in listOf("OWNER", "SOURCE", "CALL_LIMIT", "COMPLETION_RESERVE")) {
            reset()
            seed()
            val admitted = direct(if (fault == "COMPLETION_RESERVE") 5 else 60)
            val request = request(admitted)
            val seconds = if (fault == "COMPLETION_RESERVE") 2L else 40L
            assertEquals(Duration.ofSeconds(seconds), request.timeout)
            assertEquals(F.selectedAt.plusSeconds(seconds), fence.authorize(reservation(request), clock.instant()))
            when (fault) {
                "OWNER" -> jdbc.update("UPDATE analysis_invocations SET owner_token=?", UUID(0, 9951))
                "SOURCE" -> jdbc.update("UPDATE submission_versions SET raw_text=? WHERE id=?", "changed synthetic source", F.ir.first)
                else -> clock.set(F.selectedAt.plusSeconds(seconds))
            }
            assertNull(fence.authorize(reservation(request), clock.instant()), fault)
            Loopback().use { http ->
                assertFailsWith<RuntimeException>(fault) { adapter(http).generate(request) }
                assertTrue(http.requests.isEmpty(), fault)
                assertEquals(0, count("ai_call_reservations"), fault)
                assertEquals(0, count("schedule_ir_envelopes"), fault)
            }
        }
    }

    @Test
    fun `DL03 retryable direct Luna failure ends one attempt without Gemini retry delay second Luna or redelivery dispatch`() {
        seed()
        Loopback(status = 503).use { http ->
            val processor =
                SelectedScheduleDslBatchProcessor(
                    persistence,
                    SelectedScheduleDslProviderPort { error("Direct Luna must never invoke Gemini") },
                    adapter(http),
                    RetryDelayPort { error("Direct Luna must never schedule a retry") },
                    JitterPort { error("Direct Luna must never request retry jitter") },
                    MonotonicTimePort(System::nanoTime),
                )
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            processor.processLunaOnlyBounded(SubmissionBatchId(F.ir.batch), deadline)
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
            assertEquals(1, count("coordination_attempts"))
            assertEquals("OPENAI", jdbc.queryForObject("SELECT provider FROM coordination_attempts", String::class.java))
            assertEquals("SERVER", jdbc.queryForObject("SELECT failure_kind FROM coordination_attempts", String::class.java))
            assertNotNull(jdbc.queryForObject("SELECT finished_at FROM coordination_attempts", Timestamp::class.java))
            assertNotNull(currentInvocation().finishedAt)
            assertEquals(0, currentInvocation().geminiAttempts)
            assertEquals(1, currentInvocation().lunaAttempts)
            assertEquals("ANALYSIS_DELAYED", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
            processor.processLunaOnlyBounded(SubmissionBatchId(F.ir.batch), deadline)
            assertEquals(1, http.requests.size)
            assertEquals(1, count("coordination_attempts"))
            assertEquals(1, count("analysis_invocations"))
            assertEquals(0, count("schedule_ir_envelopes"))
        }
    }

    @Test
    fun `DL04 direct selection refuses C4 while ordinary C5 admission keeps Gemini first and cannot be adopted by Luna`() {
        seed(structural = false)
        val oldRun = assertNotNull(persistence.start(SubmissionBatchId(F.ir.batch)))
        assertNull(persistence.admitLunaOnly(oldRun, UUID(0, 9952), Duration.ofSeconds(60)))
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
        val old = assertNotNull(persistence.admit(oldRun, UUID(0, 9952), Duration.ofSeconds(60)))
        assertEquals(AnalysisInvocation.POLICY_VERSION, old.binding.invocation.policyVersion)
        val oldRows = F.rows(jdbc, "analysis_invocations")
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE analysis_invocations SET gemini_attempts=0,luna_attempts=1 WHERE id=?", old.binding.invocation.id)
        }
        assertEquals(oldRows, F.rows(jdbc, "analysis_invocations"))
        reset()
        seed()
        val run = assertNotNull(persistence.start(SubmissionBatchId(F.ir.batch)))
        val ordinary = assertNotNull(persistence.admit(run, UUID(0, 9953), Duration.ofSeconds(60)))
        assertEquals(AnalysisProvider.GEMINI, ordinary.attempt.provider)
        assertEquals(1, ordinary.attempt.attemptNumber)
        assertEquals(1, ordinary.binding.invocation.geminiAttempts)
        assertEquals(0, ordinary.binding.invocation.lunaAttempts)
        val before = F.rows(jdbc, "analysis_invocations") + F.rows(jdbc, "coordination_attempts")
        assertNull(persistence.admitLunaOnly(run, UUID(0, 9954), Duration.ofSeconds(60)))
        assertEquals(before, F.rows(jdbc, "analysis_invocations") + F.rows(jdbc, "coordination_attempts"))
        assertEquals(0, count("ai_call_reservations"))
    }

    private fun seed(structural: Boolean = true) {
        val input =
            SelectedDslPhysicalInputFixture.contextInput().copy(
                responseContractVersion = "schedule-dsl-response-4",
                semanticVersion = "schedule-ir-product-5",
                policy = ScheduleDslContextModePolicy.contextual(),
            )
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val context = ScheduleDslOfflinePipeline.snapshotContextMode(input).snapshot.context
        val choice =
            if (structural) {
                FinalAnalysisRouteChoice.DslStructuralV5(
                    IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", input.policy.version),
                    input.policy,
                    context,
                    input.meetingMode,
                    "ko-time-terms-1",
                )
            } else {
                FinalAnalysisRouteChoice.DslContextModeV4(
                    IrExecutionIdentity(input.responseContractVersion, input.semanticVersion, input.policy.version),
                    input.policy,
                    context,
                    input.meetingMode,
                    "ko-time-terms-1",
                )
            }
        routes.freeze(F.ir.run, 1, choice)
        jdbc.update(
            """
            UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,
                cap=100,settled=0,held=0,policy_fingerprint=?,coverage_started_at=? WHERE room_id=?
            """.trimIndent(),
            UUID(0, 9955),
            "c".repeat(64),
            Timestamp.from(F.ir.reference.minusSeconds(1)),
            F.ir.room,
        )
    }

    private fun direct(seconds: Long = 60): SelectedScheduleDslInvocation =
        assertNotNull(
            persistence.admitLunaOnly(
                assertNotNull(persistence.start(SubmissionBatchId(F.ir.batch))),
                UUID(0, 9956),
                Duration.ofSeconds(seconds),
            ),
        )

    private fun request(admitted: SelectedScheduleDslInvocation) =
        persistence.request(admitted.binding, admitted.attempt, System.nanoTime() + Duration.ofSeconds(60).toNanos())

    private fun currentInvocation() = assertNotNull(invocations.findLatestByRun(CoordinationRunId(F.ir.run)))

    private fun nativeRows() = F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items")

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private fun reservation(request: SelectedScheduleDslProviderRequest): AiBudgetReserveRequest {
        val operation = AiPhysicalOperation.FINAL_FALLBACK
        return AiBudgetReserveRequest(
            request.dispatchContext.execution,
            listOf(
                AiPhysicalCallSpec(
                    operation,
                    1,
                    "a".repeat(64),
                    requireNotNull(AiRuntimeDispatchProfiles.fingerprints[operation]),
                    request.attempt.id,
                ),
            ),
        )
    }

    private fun adapter(http: Loopback): SelectedScheduleDslProviderPort {
        val operation = AiPhysicalOperation.FINAL_FALLBACK
        val expected = physicalProfile(operation, "gpt-6-luna", http.endpoint, 32 * 1024 * 1024).profileFingerprint
        val canonical = requireNotNull(AiRuntimeDispatchProfiles.fingerprints[operation])
        // Translate only this literal loopback profile; all payload/owner/source/attempt/deadline data reaches the real fence.
        val runtime =
            AiRuntimeDispatchGuardPort { request, at ->
                val call = request.calls.singleOrNull()
                if (call == null || call.operation != operation || call.profileFingerprint != expected) {
                    null
                } else {
                    fence.authorize(request.copy(calls = listOf(call.copy(profileFingerprint = canonical))), at)
                }
            }
        return OpenAiLunaSelectedScheduleDslProviderAdapter(
            OpenAiProperties(apiKey = "synthetic-loopback-only"),
            mapper,
            AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, clock, runtimeGuard = runtime),
            http.endpoint,
        )
    }

    private class Loopback(
        status: Int = 200,
    ) : AutoCloseable {
        val requests: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI("http://127.0.0.1:${server.address.port}/v1/responses")

        init {
            val bytes =
                if (status == 200) {
                    """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{}"}]}],"usage":{"input_tokens":17,"output_tokens":23}}"""
                } else {
                    """{"error":{"type":"server_error","code":"server_error"}}"""
                }.toByteArray(Charsets.UTF_8)
            server.createContext("/") { exchange ->
                paths += exchange.requestURI.path
                requests += exchange.requestBody.use { it.readAllBytes() }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
        fun stopDatabase() {
            postgres.stop()
        }
    }
}
