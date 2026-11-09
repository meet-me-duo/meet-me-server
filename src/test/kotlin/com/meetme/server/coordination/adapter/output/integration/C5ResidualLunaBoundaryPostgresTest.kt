package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.persistence.JdbcAnalysisInvocationRepository
import com.meetme.server.coordination.adapter.output.persistence.JdbcCoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchProfiles
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.sun.net.httpserver.HttpServer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Independent fixed-clock PG authority and one bounded slow loopback response; no paid provider. */
@SpringBootTest
@Import(C5ResidualLunaBoundaryPostgresTest.TimeConfiguration::class)
class C5ResidualLunaBoundaryPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var time: BoundaryClock

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var selected: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var legacy: GeminiProcessingPersistenceService

    @Autowired private lateinit var fence: JdbcAiBudgetExecutionFence

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        time.set(F.selectedAt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["C5", "C4", "C3", "C2", "LEGACY"])
    fun `CRB01 only newly admitted C5 persists residual policy while all old routes keep v1`(kind: String) {
        val fixture = seed(kind)
        val route = routes.freeze(F.ir.run, 1, fixture.choice)
        val run = assertNotNull(runs.findById(CoordinationRunId(F.ir.run)))
        val admitted =
            if (kind == "LEGACY") {
                assertNotNull(legacy.claimInvocation(run, UUID(0, 9811), Duration.ofSeconds(60)))
            } else {
                val started = assertNotNull(selected.start(SubmissionBatchId(F.ir.batch)))
                assertNotNull(selected.admit(started, UUID(0, 9811), Duration.ofSeconds(60))).binding.invocation
            }
        val expected = if (kind == "C5") V2 else V1
        assertEquals(expected, admitted.policyVersion)
        val fresh = assertNotNull(JdbcAnalysisInvocationRepository(jdbc).findByRunVersion(run.id, run.version))
        assertEquals(admitted, fresh)
        assertEquals(expected, jdbc.queryForObject("SELECT policy_version FROM analysis_invocations", String::class.java))
        assertEquals(F.selectedAt, fresh.startedAt)
        assertEquals(F.selectedAt.plusSeconds(60), fresh.deadlineAt)
        assertEquals(route, routes.find(F.ir.run))
        if (kind != "LEGACY") {
            assertEquals(expected, jdbc.queryForObject("SELECT policy_version FROM coordination_attempts", String::class.java))
        }
    }

    @ParameterizedTest
    @CsvSource("C5,V2,0,40", "C5,V2,25,57", "C5,V1,0,27", "C4,V1,0,27", "C3,V1,0,27", "C2,V1,0,27", "LEGACY,V1,0,27")
    fun `CRB02 persisted attempt and invocation fix the exact runtime expiry without refresh`(
        kind: String,
        policy: String,
        attemptOffset: Long,
        expiryOffset: Long,
    ) {
        val f = claim(kind, if (policy == "V2") V2 else V1, attemptOffset)
        val expected = F.selectedAt.plusSeconds(expiryOffset)
        val start = f.request.attempt.startedAt
        val nativeBefore = nativeRows()
        val invocationBefore = F.rows(jdbc, "analysis_invocations")
        val request = reservation(f.request)
        assertEquals(expected, fence.authorize(request, start))
        assertEquals(expected, fence.authorize(request, expected.minusNanos(1)))
        if (policy == "V2") assertEquals(expected, fence.authorize(request, start.plusSeconds(28)))
        assertNull(fence.authorize(request, expected))
        assertNull(fence.authorize(request, expected.plusNanos(1)))
        if (policy == "V2") {
            time.set(expected.minusSeconds(1))
            repeat(2) {
                val prepared =
                    selected.request(
                        f.request.binding,
                        f.request.attempt,
                        System.nanoTime() + Duration.ofSeconds(50).toNanos(),
                    )
                assertEquals(Duration.ofSeconds(1), prepared.timeout)
                assertTrue(prepared.dispatchContext.deadlineNanos - System.nanoTime() <= Duration.ofSeconds(1).toNanos())
            }
            time.set(expected)
            assertFailsWith<RuntimeException> {
                selected.request(f.request.binding, f.request.attempt, System.nanoTime() + Duration.ofSeconds(50).toNanos())
            }
        }
        assertEquals(invocationBefore, F.rows(jdbc, "analysis_invocations"))
        assertEquals(nativeBefore, nativeRows())
        assertEquals(0, count("ai_call_reservations"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["OWNER", "ATTEMPT_POLICY", "C4_ROUTE", "MISSING_ROUTE", "UNKNOWN_CALLER_POLICY"])
    fun `CRB03 residual authority rejects changed owner policy and non C5 route without adoption`(fault: String) {
        val f = claim(if (fault == "C4_ROUTE") "C4" else "C5", V2)
        var request = reservation(f.request)
        when (fault) {
            "OWNER" -> request = request.copy(execution = request.execution.copy(ownerToken = UUID(0, 9991)))
            "ATTEMPT_POLICY" -> {
                val before = F.historicalSnapshot(jdbc)
                assertFailsWith<DataIntegrityViolationException> {
                    jdbc.update("UPDATE coordination_attempts SET policy_version=? WHERE id=?", V1, f.request.attempt.id)
                }
                assertEquals(before, F.historicalSnapshot(jdbc))
                assertEquals(F.selectedAt.plusSeconds(40), fence.authorize(request, F.selectedAt))
                Loopback().use { http ->
                    val failure =
                        assertFailsWith<NaturalLanguageParserException> {
                            adapter(http).generate(f.request.copy(attempt = f.request.attempt.copy(policyVersion = V1)))
                        }
                    assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
                    assertEquals(0, http.calls.get())
                }
                assertEquals(before, F.historicalSnapshot(jdbc))
                assertEquals(0, count("ai_call_reservations"))
                return
            }
            "C4_ROUTE" -> {
                // A valid frozen C4 identity/shape with internally consistent v2 invocation/attempt rows.
                // No FK-violating policy rewrite: the runtime route-policy guard must reject this combination.
                assertTrue(f.route.choice is FinalAnalysisRouteChoice.DslContextModeV4)
                assertEquals(f.route, routes.find(F.ir.run))
                assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(f.request.binding) }
            }
            "MISSING_ROUTE" -> jdbc.update("DELETE FROM final_analysis_routes")
            "UNKNOWN_CALLER_POLICY" -> {
                val bad = f.request.binding.copy(invocation = f.invocation.copy(policyVersion = "unpublished-v99"))
                val before = F.historicalSnapshot(jdbc)
                assertFailsWith<RuntimeException> { routes.requireDispatchCurrent(bad) }
                assertEquals(before, F.historicalSnapshot(jdbc))
                Loopback().use { http ->
                    assertFailsWith<NaturalLanguageParserException> { adapter(http).generate(f.request.copy(binding = bad)) }
                    assertEquals(0, http.calls.get())
                }
                return
            }
        }
        val before = F.historicalSnapshot(jdbc)
        assertNull(fence.authorize(request, F.selectedAt))
        assertEquals(before, F.historicalSnapshot(jdbc))
        assertEquals(0, count("ai_call_reservations"))
    }

    @Test
    fun `CRB04 repository roundtrip retains policy and cannot rewrite admitted policy identity`() {
        val f = claim("C5", V2)
        val repository = JdbcAnalysisInvocationRepository(jdbc)
        val fresh = assertNotNull(repository.findByRunVersion(f.invocation.runId, f.invocation.runVersion))
        assertEquals(f.invocation, fresh)
        repository.update(fresh.copy(geminiAttempts = 2))
        val advanced = assertNotNull(repository.findByRunVersion(fresh.runId, fresh.runVersion))
        assertEquals(V2, advanced.policyVersion)
        assertEquals(fresh.startedAt, advanced.startedAt)
        assertEquals(fresh.deadlineAt, advanced.deadlineAt)
        val before = F.rows(jdbc, "analysis_invocations")
        assertFailsWith<RuntimeException> { repository.update(advanced.copy(policyVersion = V1)) }
        assertFailsWith<RuntimeException> { repository.update(advanced.copy(policyVersion = "unpublished-v99")) }
        assertEquals(before, F.rows(jdbc, "analysis_invocations"))
        assertFailsWith<RuntimeException> {
            routes.requireDispatchCurrent(
                f.request.binding.copy(invocation = fresh.copy(policyVersion = V1)),
            )
        }
    }

    @Test
    fun `CRB05 actual public Luna transport crosses old twenty seven cap only for v2 and preserves prepared budget`() {
        val f = claim("C5", V2)
        val nativeBefore = nativeRows()
        // One intentionally slow response only: old hard27 must TIMEOUT; new fixed40 permits this response.
        Loopback(holdSeconds = 28).use { http ->
            val response =
                adapter(http).generate(
                    f.request.copy(
                        timeout = Duration.ofSeconds(40),
                        dispatchContext =
                            f.request.dispatchContext.copy(
                                deadlineNanos =
                                    System.nanoTime() + Duration.ofSeconds(40).toNanos(),
                            ),
                    ),
                )
            assertEquals(AnalysisProvider.OPENAI, response.provider)
            assertEquals("{}", response.responseJson)
            assertTrue(http.delayElapsed.get())
            assertEquals(1, http.calls.get())
            val request = mapper.readTree(assertNotNull(http.body))
            assertEquals(32768, request.path("max_output_tokens").asInt())
            val neutral = request.path("input").asText()
            f.fixture.input.inputs
                .forEach { assertTrue(neutral.contains(it.rawText)) }
            assertTrue(neutral.contains("schedule-dsl-response-5"))
        }
        // Preparation consumed28s of the fixed40s allowance. Request reconstruction must leave12, not40 or27.
        time.set(F.selectedAt.plusSeconds(28))
        val prepared = selected.request(f.request.binding, f.request.attempt, System.nanoTime() + Duration.ofSeconds(50).toNanos())
        assertEquals(Duration.ofSeconds(12), prepared.timeout)
        val monotonicRemaining = prepared.dispatchContext.deadlineNanos - System.nanoTime()
        assertTrue(monotonicRemaining > 0 && monotonicRemaining <= Duration.ofSeconds(12).toNanos())
        assertEquals(nativeBefore, nativeRows())
        assertEquals(0, count("ai_call_reservations"), "Runtime-only fence does not manufacture monetary coverage")
        reset()
        val old = claim("C5", V1)
        time.set(F.selectedAt.plusSeconds(28))
        Loopback().use { http ->
            assertFailsWith<NaturalLanguageParserException> { adapter(http).generate(old.request) }
            assertEquals(0, http.calls.get())
        }
    }

    @Test
    fun `CRB06 V29 rows and checksums survive V30 while only the new closed policy is added`() {
        val schema = "residual_v30_probe"
        jdbc.execute("CREATE SCHEMA $schema")
        val url = postgres.jdbcUrl + (if ('?' in postgres.jdbcUrl) "&" else "?") + "currentSchema=$schema"
        val dataSource = DriverManagerDataSource(url, postgres.username, postgres.password)
        val local = JdbcTemplate(dataSource)

        fun migration(target: String) =
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
                .load()
        try {
            migration("29").migrate()
            F.seed(local, mapper)
            val old = F.invocation().copy(geminiAttempts = 1)
            val repo = JdbcAnalysisInvocationRepository(local)
            repo.insert(old)
            val oldAttempt =
                CoordinationAttempt(
                    UUID(0, 9891),
                    old.runId,
                    1,
                    old.startedAt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    AnalysisProvider.GEMINI,
                    "gemini-3.8-flash",
                    V1,
                    old.id,
                )
            val localAttempts = JdbcCoordinationAttemptRepository(local)
            localAttempts.insert(oldAttempt)
            val history =
                local.queryForList(
                    "SELECT version,script,checksum FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                )
            val tables =
                listOf(
                    "meeting_rooms",
                    "submission_versions",
                    "submission_batch_items",
                    "coordination_runs",
                    "analysis_invocations",
                    "coordination_attempts",
                )
            val before = tables.associateWith { F.rows(local, it) }
            migration("30").migrate()
            assertEquals(
                "30",
                migration("30")
                    .info()
                    .current()
                    .version.version,
            )
            assertEquals(
                history,
                local.queryForList(
                    "SELECT version,script,checksum FROM flyway_schema_history WHERE success AND version::int<=29 ORDER BY installed_rank",
                ),
            )
            assertEquals(before, tables.associateWith { F.rows(local, it) })
            assertEquals(V1, assertNotNull(repo.findByRunVersion(old.runId, old.runVersion)).policyVersion)
            val added = old.copy(id = UUID(0, 9892), runVersion = 2, policyVersion = V2)
            repo.insert(added)
            assertEquals(added, repo.findByRunVersion(added.runId, 2))
            val addedAttempt = oldAttempt.copy(id = UUID(0, 9893), attemptNumber = 2, policyVersion = V2, invocationId = added.id)
            localAttempts.insert(addedAttempt)
            assertEquals(addedAttempt, localAttempts.findById(addedAttempt.id))
            assertFailsWith<RuntimeException> {
                local.update("UPDATE coordination_attempts SET policy_version='unpublished-v99' WHERE id=?", addedAttempt.id)
            }
            assertFailsWith<RuntimeException> {
                local.update(
                    "UPDATE analysis_invocations SET policy_version='unpublished-v99' WHERE id=?",
                    added.id,
                )
            }
        } finally {
            jdbc.execute("DROP SCHEMA $schema CASCADE")
        }
    }

    private data class Fixture(
        val input: ScheduleDslOfflineInput,
        val capsule: ScheduleDslProviderInputCapsule,
        val choice: FinalAnalysisRouteChoice,
    )

    private data class Claim(
        val fixture: Fixture,
        val invocation: AnalysisInvocation,
        val route: FrozenFinalAnalysisRoute,
        val request: SelectedScheduleDslProviderRequest,
    )

    private fun seed(kind: String): Fixture {
        val input =
            when (kind) {
                "C2", "LEGACY" -> SelectedDslPhysicalInputFixture.contextInput()
                "C3" -> SelectedDslPhysicalInputFixture.approvedInput()
                else ->
                    SelectedDslPhysicalInputFixture.approvedInput().copy(
                        responseContractVersion = "schedule-dsl-response-4",
                        semanticVersion = "schedule-ir-product-5",
                        policy = ScheduleDslContextModePolicy.contextual(),
                    )
            }
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        val capsule =
            when (kind) {
                "C2", "LEGACY" -> ScheduleDslOfflinePipeline.snapshotContext(input)
                "C3" -> ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
                else -> ScheduleDslOfflinePipeline.snapshotContextMode(input)
            }
        val context = capsule.snapshot.context
        val identity = IrExecutionIdentity(input.responseContractVersion, input.semanticVersion, input.policy.version)
        val choice =
            when (kind) {
                "LEGACY" -> FinalAnalysisRouteChoice.Legacy
                "C2" -> FinalAnalysisRouteChoice.DslContextV2(identity, input.policy, context, input.meetingMode)
                "C3" -> FinalAnalysisRouteChoice.DslApprovedTermsV3(identity, input.policy, context, input.meetingMode, "ko-time-terms-1")
                "C4" -> FinalAnalysisRouteChoice.DslContextModeV4(identity, input.policy, context, input.meetingMode, "ko-time-terms-1")
                "C5" ->
                    FinalAnalysisRouteChoice.DslStructuralV5(
                        IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", input.policy.version),
                        input.policy,
                        context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
                else -> error("Unknown test route")
            }
        return Fixture(input, capsule, choice)
    }

    private fun claim(
        kind: String,
        policy: String,
        attemptOffset: Long = 0,
    ): Claim {
        val fixture = seed(kind)
        val route = routes.freeze(F.ir.run, 1, fixture.choice)
        val invocation =
            F.invocation().copy(
                deadlineAt = F.selectedAt.plusSeconds(60),
                geminiAttempts = 1,
                lunaAttempts = 1,
                policyVersion = policy,
            )
        invocations.insert(invocation)
        assertEquals(policy, assertNotNull(invocations.findByRunVersion(invocation.runId, invocation.runVersion)).policyVersion)
        val primary =
            CoordinationAttempt(
                UUID(0, 9821),
                invocation.runId,
                1,
                F.selectedAt,
                F.selectedAt,
                "TIMEOUT",
                null,
                null,
                null,
                null,
                AnalysisProvider.GEMINI,
                "gemini-3.8-flash",
                policy,
                invocation.id,
            )
        val attempt =
            primary.copy(
                id = UUID(0, 9822),
                attemptNumber = 2,
                startedAt = F.selectedAt.plusSeconds(attemptOffset),
                finishedAt = null,
                failureKind = null,
                provider = AnalysisProvider.OPENAI,
                model = "gpt-6-luna",
            )
        attempts.insert(primary)
        attempts.insert(attempt)
        val input = fixture.input
        val zone = ZoneId.of(input.timeZone)
        val snapshots =
            input.inputs.sortedBy { it.participantId }.map { item ->
                AiDispatchInputSnapshot(
                    item.submissionVersionId.toString(),
                    item.rawText,
                    item.locale,
                    requireNotNull(item.evidence).referenceAt.atZone(zone).toLocalDate(),
                    item.evidence,
                )
            }
        val execution =
            AiBudgetExecution(
                input.roomId,
                AiBudgetExecutionKind.FINAL_ANALYSIS,
                invocation.id,
                invocation.runVersion,
                invocation.ownerToken,
                invocation.runVersion,
                AiDispatchFingerprints.batchSource(snapshots),
                AiDispatchFingerprints.batchContext(zone, input.searchStart, input.searchEnd, snapshots),
            )
        val request =
            SelectedScheduleDslProviderRequest(
                FinalAnalysisAttemptBinding(route, invocation),
                attempt,
                fixture.capsule,
                snapshots,
                AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(50).toNanos(), attempt.id, 1),
                Duration.ofSeconds(40),
            )
        return Claim(fixture, invocation, route, request)
    }

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

    private fun adapter(http: Loopback) =
        OpenAiLunaSelectedScheduleDslProviderAdapter(
            OpenAiProperties(apiKey = "synthetic-only"),
            mapper,
            AiHttpDispatchGate(
                AiDispatchGateProperties(true),
                budget,
                time,
                runtimeGuard = exactLoopbackGuard(http),
                enforceBudget = false,
            ),
            http.endpoint,
        )

    /** Test-only endpoint translation; every execution/payload/owner/attempt/deadline field goes to the real JDBC guard unchanged. */
    private fun exactLoopbackGuard(http: Loopback): AiRuntimeDispatchGuardPort {
        val operation = AiPhysicalOperation.FINAL_FALLBACK
        val canonical = requireNotNull(AiRuntimeDispatchProfiles.fingerprints[operation])
        // Existing wire-profile framing; only this fixture's literal loopback endpoint is accepted.
        val material = "${operation.name}\ngpt-6-luna\nPOST\n${http.endpoint.toASCIIString()}\n33554432\nv1"
        val expected =
            MessageDigest
                .getInstance("SHA-256")
                .digest(material.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        require(expected != canonical)
        return AiRuntimeDispatchGuardPort { request, at ->
            val call = request.calls.singleOrNull()
            if (call == null || call.operation != operation || call.profileFingerprint != expected) {
                null
            } else {
                fence.authorize(request.copy(calls = listOf(call.copy(profileFingerprint = canonical))), at)
            }
        }
    }

    private fun nativeRows() = F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items")

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private class Loopback(
        private val holdSeconds: Long = 0,
    ) : AutoCloseable {
        val calls = AtomicInteger()
        val delayElapsed = AtomicBoolean()

        @Volatile var body: String? = null
        private val release = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = URI("http://127.0.0.1:${server.address.port}/v1/responses")

        init {
            server.createContext("/") { exchange ->
                try {
                    body = exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
                    calls.incrementAndGet()
                    delayElapsed.set(!release.await(holdSeconds, TimeUnit.SECONDS))
                    val bytes =
                        """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{}"}]}],"usage":{"input_tokens":17,"output_tokens":23}}"""
                            .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // Client cancellation is observable at the adapter, never retried by this endpoint.
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        override fun close() {
            release.countDown()
            server.stop(0)
        }
    }

    class BoundaryClock : Clock() {
        @Volatile private var value = F.selectedAt

        fun set(value: Instant) {
            this.value = value
        }

        override fun instant(): Instant = value

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfiguration {
        @Bean @Primary
        fun residualBoundaryClock() = BoundaryClock()
    }

    companion object {
        private const val V1 = "bounded-luna-v1"
        private const val V2 = "c5-residual-luna-v2"
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
