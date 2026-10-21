package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.CoordinationDeadLetterPersistence
import com.meetme.server.coordination.application.service.DeadLetterReplayService
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.DispatchEndpoint
import com.meetme.server.shared.adapter.output.aihttp.dispatchHash
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real PG/fence/ledger + actual loopback SDK. Synthetic policy is not a live monetary authority. */
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class, Issue123FinalDispatchLifecyclePostgresTest.SyntheticPolicy::class)
class Issue123FinalDispatchLifecyclePostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Autowired private lateinit var prechecks: PrecheckRepository

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var replay: DeadLetterReplayService

    @Autowired private lateinit var deadLetters: CoordinationDeadLetterPersistence

    @Test
    fun `AD04 real final primary records exact invocation generation attempt and same room financial hold`() {
        val fixture = structuring()
        ready(fixture.roomId)
        val run = run(fixture.sourceRun)
        DispatchEndpoint(response = { _, _ -> 200 to envelope(run, false) }).use { server ->
            processor(server).process(run.batch.id)
            assertEquals(1, server.requests.size)
            val snapshot = assertNotNull(budget.snapshot(fixture.roomId))
            assertEquals(BigInteger.TEN, snapshot.held)
            assertEquals(BigInteger.ZERO, snapshot.settled, "Known tokens are not a complete bill")
            val stored =
                jdbc.queryForMap(
                    "SELECT execution_id,generation,execution_version,attempt_id FROM ai_call_reservations WHERE room_id=?",
                    fixture.roomId,
                )
            val invocation = assertNotNull(invocations.findLatestByRun(run.id))
            assertEquals(invocation.id, stored["execution_id"])
            assertEquals(invocation.runVersion, (stored["generation"] as Number).toLong())
            assertEquals(invocation.runVersion, (stored["execution_version"] as Number).toLong())
            assertNotNull(stored["attempt_id"])
            assertEquals(CoordinationStatus.MATCHING, run(fixture.sourceRun).status)
        }
    }

    @Test
    fun `TM03 real four primary retries and one fallback all consume one durable room account`() {
        val fixture = structuring()
        ready(fixture.roomId)
        val run = run(fixture.sourceRun)
        DispatchEndpoint(response = { path, _ ->
            if (path ==
                "/v1/responses"
            ) {
                200 to envelope(run, true)
            } else {
                503 to "{}"
            }
        }, retryAfter = true).use { server ->
            processor(server, true).process(run.batch.id)
            assertEquals(5, server.requests.size)
            assertEquals(4, server.requests.count { it.path.endsWith(":generateContent") })
            assertEquals(1, server.requests.count { it.path == "/v1/responses" })
            assertEquals(BigInteger.valueOf(50), assertNotNull(budget.snapshot(fixture.roomId)).held)
            assertEquals(5, countReservations(fixture.roomId))
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(DISTINCT execution_id) FROM ai_call_reservations WHERE room_id=?",
                    Int::class.java,
                    fixture.roomId,
                ),
            )
        }
    }

    @Test
    fun `TM04 real HOST retry keeps previous UNKNOWN holds and queued duplicate issues no extra work`() {
        val fixture = structuring()
        ready(fixture.roomId)
        val first = run(fixture.sourceRun)
        DispatchEndpoint(response = { _, _ -> 503 to "{}" }, retryAfter = true).use { server ->
            processor(server).process(first.batch.id)
            val original = assertNotNull(budget.snapshot(fixture.roomId))
            assertEquals(BigInteger.valueOf(40), original.held)
            assertEquals(CoordinationStatus.ANALYSIS_DELAYED, run(fixture.sourceRun).status)
            retry(fixture)
            retry(fixture)
            assertEquals(4, server.requests.size)
            val next = run(fixture.sourceRun)
            assertEquals(first.batch, next.batch)
            processor(server).process(next.batch.id)
            assertEquals(8, server.requests.size)
            val after = assertNotNull(budget.snapshot(fixture.roomId))
            assertEquals(original.epochId, after.epochId)
            assertEquals(BigInteger.valueOf(80), after.held)
            assertEquals(8, countReservations(fixture.roomId))
            assertEquals(
                2,
                jdbc.queryForObject(
                    "SELECT count(DISTINCT execution_id) FROM ai_call_reservations WHERE room_id=?",
                    Int::class.java,
                    fixture.roomId,
                ),
            )
        }
    }

    @Test
    fun `TM05 real changed correction new batch preserves room budget and source version isolation`() {
        val fixture = structuring()
        ready(fixture.roomId)
        var active = run(fixture.sourceRun)
        DispatchEndpoint(response = { _, _ -> 200 to envelope(active, false) }).use { server ->
            processor(server).process(active.batch.id)
            assertEquals(BigInteger.TEN, assertNotNull(budget.snapshot(fixture.roomId)).held)
            completePartial(fixture.sourceRun)
            val round = openRound(fixture)
            mvc.perform(save(fixture.host, "H1 revised synthetic source", round, 1)).andExpect(status().isOk)
            mvc.perform(analyze(fixture, round, force = true)).andExpect(status().isAccepted)
            active = run(activeRun(fixture.host))
            assertNotEquals(fixture.sourceRun, active.id.value)
            processor(server).process(active.batch.id)
            assertEquals(2, server.requests.size)
            assertEquals(BigInteger.valueOf(20), assertNotNull(budget.snapshot(fixture.roomId)).held)
            assertEquals(2, countReservations(fixture.roomId))
            assertEquals(
                2,
                jdbc.queryForObject(
                    "SELECT count(DISTINCT execution_id) FROM ai_call_reservations WHERE room_id=?",
                    Int::class.java,
                    fixture.roomId,
                ),
            )
        }
    }

    @Test
    fun `TM06 real deadletter replay keeps previous physical holds after failure rows are deleted`() {
        val fixture = structuring()
        ready(fixture.roomId)
        DispatchEndpoint(response = { _, _ -> 503 to "{}" }, retryAfter = true).use { server ->
            processor(server).process(run(fixture.sourceRun).batch.id)
            assertEquals(BigInteger.valueOf(40), assertNotNull(budget.snapshot(fixture.roomId)).held)
            retry(fixture)
            val event =
                assertNotNull(
                    jdbc.queryForObject(
                        "SELECT id FROM outbox_events WHERE aggregate_id=? AND status='PENDING' ORDER BY occurred_at DESC LIMIT 1",
                        UUID::class.java,
                        fixture.sourceRun,
                    ),
                )
            jdbc.update(
                "UPDATE outbox_events SET status='PUBLISHED',published_at=? WHERE id=?",
                clock.instant().atOffset(ZoneOffset.UTC),
                event,
            )
            deadLetters.persist(OutboxEventId(event), "SYNTHETIC_INTERNAL", 5)
            assertEquals(CoordinationStatus.DEAD_LETTERED, run(fixture.sourceRun).status)
            assertTrue(replay.replay(OutboxEventId(event)))
            assertFalse(replay.replay(OutboxEventId(event)))
            assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM coordination_worker_failures WHERE event_id=?", Int::class.java, event),
            )
            processor(server).process(run(fixture.sourceRun).batch.id)
            assertEquals(8, server.requests.size)
            assertEquals(BigInteger.valueOf(80), assertNotNull(budget.snapshot(fixture.roomId)).held)
            assertEquals(8, countReservations(fixture.roomId))
        }
    }

    @Test
    fun `AD02 real final budget denial records one nonretryable attempt without primary or fallback HTTP`() {
        val fixture = structuring()
        ready(fixture.roomId, 10)
        val run = run(fixture.sourceRun)
        DispatchEndpoint(response = { _, _ -> 200 to envelope(run, false) }).use { server ->
            processor(server, true).process(run.batch.id)
            assertTrue(server.requests.isEmpty())
            assertEquals(0, countReservations(fixture.roomId))
            assertEquals(BigInteger.ZERO, assertNotNull(budget.snapshot(fixture.roomId)).held)
            assertEquals(CoordinationStatus.ANALYSIS_DELAYED, run(fixture.sourceRun).status)
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM coordination_attempts WHERE coordination_run_id=?",
                    Int::class.java,
                    fixture.sourceRun,
                ),
            )
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["fallback", "invocation-repository"])
    fun `AD02 unsupported legacy final wiring with ON has no physical dispatch`(missing: String) {
        val fixture = structuring()
        ready(fixture.roomId)
        val active = run(fixture.sourceRun)
        DispatchEndpoint(response = { _, _ -> 200 to envelope(active, false) }).use { server ->
            val outcome =
                runCatching {
                    processor(
                        server,
                        missingFallback = missing == "fallback",
                        missingInvocationRepository =
                            missing == "invocation-repository",
                    ).process(active.batch.id)
                }
            val error = outcome.exceptionOrNull()
            if (error != null) {
                assertTrue(error is NaturalLanguageParserException, "Unsupported ON wiring needs a bounded typed configuration outcome")
                assertEquals(ParserFailureKind.CONFIGURATION, error.kind)
                assertFalse(error.retryable)
            } else {
                assertEquals(CoordinationStatus.ANALYSIS_DELAYED, run(fixture.sourceRun).status)
            }
            assertTrue(server.requests.isEmpty())
            assertEquals(0, countReservations(fixture.roomId))
            assertEquals(BigInteger.ZERO, assertNotNull(budget.snapshot(fixture.roomId)).held)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["fallback"])
    fun `AD01 unsupported legacy final wiring remains legacy compatible with OFF`(missing: String) {
        val fixture = structuring()
        ready(fixture.roomId)
        val active = run(fixture.sourceRun)
        DispatchEndpoint(response = { _, _ -> 200 to envelope(active, false) }).use { server ->
            processor(
                server,
                enabled = false,
                missingFallback = missing == "fallback",
                missingInvocationRepository =
                    missing == "invocation-repository",
            ).process(active.batch.id)
            assertEquals(1, server.requests.size)
            assertEquals(0, countReservations(fixture.roomId))
        }
    }

    private fun structuring(): CompletedCorrectionFixture {
        val fixture = completedRoom()
        jdbc.update(
            "UPDATE coordination_runs SET status='STRUCTURING',candidate_quality=NULL,version=version+1 WHERE id=?",
            fixture.sourceRun,
        )
        return fixture
    }

    private fun run(id: UUID) = assertNotNull(runs.findById(CoordinationRunId(id)))

    private fun retry(fixture: CompletedCorrectionFixture) {
        mvc
            .perform(
                post("/api/rooms/{code}/analysis/retry", fixture.host.code).header("Origin", ORIGIN).cookie(fixture.host.cookie()),
            ).andExpect(status().isAccepted)
    }

    private fun countReservations(room: UUID) =
        jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, room)

    private fun ready(
        room: UUID,
        cap: Int = 1000,
    ) {
        // Explicit synthetic policy/coverage only; no production activation or inferred historical zero.
        jdbc.update(
            "UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,cap=?,settled=0,held=0,policy_fingerprint=?,coverage_started_at=?,version=version+1 WHERE room_id=?",
            UUID.randomUUID(),
            BigDecimal(cap),
            dispatchHash("synthetic-policy".toByteArray()),
            clock.instant().minusSeconds(60).atOffset(ZoneOffset.UTC),
            room,
        )
    }

    private fun processor(
        server: DispatchEndpoint,
        fallback: Boolean = false,
        enabled: Boolean = true,
        missingFallback: Boolean = false,
        missingInvocationRepository: Boolean = false,
    ): GeminiBatchProcessor {
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled), budget, clock)
        val primary =
            GeminiNaturalLanguageParserAdapter(GeminiProperties(apiKey = "synthetic-key"), mapper, server.origin, dispatchGate = gate)
        // Production always has a fallback bean. A blank fallback key may not reject a successful primary.
        val secondary =
            if (missingFallback) {
                null
            } else {
                OpenAiLunaNaturalLanguageParserAdapter(
                    OpenAiProperties(apiKey = if (fallback) "synthetic-key" else ""),
                    mapper,
                    OkHttpClient(),
                    server.uri("/v1/responses"),
                    dispatchGate = gate,
                )
            }
        return GeminiBatchProcessor(
            runs,
            submissions,
            structured,
            attempts,
            primary,
            IdGenerator { UUID.randomUUID() },
            RetryDelayPort {},
            JitterPort { 0 },
            MonotonicTimePort { System.nanoTime() },
            clock,
            persistence,
            fallbackParser = secondary,
            invocationRepository = if (missingInvocationRepository) null else invocations,
            prechecks = prechecks,
            dispatchMode = AiDispatchModePort { enabled },
        )
    }

    private fun envelope(
        run: CoordinationRun,
        luna: Boolean,
    ): String {
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        run.batch.submissionVersionIds.map {
                            mapOf(
                                "input_ref" to it.value.toString(),
                                "rejection_code" to null,
                                "conditions" to
                                    listOf(
                                        mapOf(
                                            "type" to "TIME_WINDOW",
                                            "polarity" to "AVAILABLE",
                                            "date" to "2026-10-07",
                                            "day_of_week" to null,
                                            "start_time" to "19:00",
                                            "end_time" to "21:00",
                                        ),
                                    ),
                            )
                        },
                ),
            )
        return if (luna) {
            mapper.writeValueAsString(
                mapOf(
                    "status" to "completed",
                    "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload)))),
                    "usage" to mapOf("input_tokens" to 137, "output_tokens" to 7),
                ),
            )
        } else {
            mapper.writeValueAsString(
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "finishReason" to "STOP",
                                "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to payload))),
                            ),
                        ),
                    "usageMetadata" to mapOf("promptTokenCount" to 137, "candidatesTokenCount" to 7, "thoughtsTokenCount" to 0),
                ),
            )
        }
    }

    class SyntheticAuthority(
        private val clock: Clock,
    ) : AiBudgetAuthorityRegistryPort {
        override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote =
            AiVerifiedBudgetQuote(
                dispatchHash((request.execution.toString() + request.call.toString() + request.policyFingerprint).toByteArray()),
                request.call.profileFingerprint,
                request.call.wholePayloadFingerprint,
                request.policyFingerprint,
                dispatchHash("synthetic-complete-cap-proof".toByteArray()),
                BigInteger.TEN,
                AiBudgetUnit("TEST_SUBUNIT", 0),
                clock.instant().plusSeconds(120),
            )

        override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null

        // Explicit synthetic zero floor preserves this fixture's unrelated existing contract.
        override fun protectedFinalReserve(request: AiBudgetQuoteRequest): AiVerifiedFinalReserve =
            AiVerifiedFinalReserve(
                request.policyFingerprint,
                BigInteger.ZERO,
                AiBudgetUnit("TEST_SUBUNIT", 0),
                clock.instant().plusSeconds(120),
            )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SyntheticPolicy {
        @Bean @Primary
        fun syntheticAuthority(clock: Clock) = SyntheticAuthority(clock)
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            CorrectionRoundPostgresFixture.registerCorrectionDatabase(registry, postgres)
            registry.add("meetme.ai.budget.enabled") { false }
        }

        @JvmStatic @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
