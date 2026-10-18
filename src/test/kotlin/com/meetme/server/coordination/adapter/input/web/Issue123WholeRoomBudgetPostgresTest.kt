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
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.service.GeminiBatchProcessor
import com.meetme.server.coordination.application.service.GeminiProcessingPersistenceService
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierOptions
import com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier
import com.meetme.server.precheck.adapter.output.provider.GeminiPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.application.service.PrecheckProcessingService
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.DispatchEndpoint
import com.meetme.server.shared.adapter.output.aihttp.dispatchHash
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import jakarta.servlet.http.Cookie
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** One real PG room from actual personal worker to automatic close and actual final SDK; five loopback HTTP requests. */
@Import(DurableAnalysisInvocationPostgresTest.ControlledTime::class, Issue123FinalDispatchLifecyclePostgresTest.SyntheticPolicy::class)
class Issue123WholeRoomBudgetPostgresTest : CorrectionRoundPostgresFixture() {
    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var prechecks: PrecheckRepository

    @Autowired private lateinit var processing: PrecheckProcessingService

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var submissions: SubmissionRepository

    @Autowired private lateinit var structured: StructuredSubmissionRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var persistence: GeminiProcessingPersistenceService

    @Test
    fun `AD04 actual personal A count B and last CLEAR close plus final all share one unchanged durable room epoch`() {
        val host = accounts.newAccount("whole-room-host")
        val created =
            mvc
                .perform(
                    post("/api/rooms")
                        .cookie(host)
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"purpose":"synthetic common budget","meeting_mode":"REMOTE","host_display_name":"host","manual_only":false,"expected_participants":2,"search_start_date":"2026-10-07","search_end_date":"2026-10-12"}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
        val code = document(created).getValue("invite_code").toString()
        val roomId = assertNotNull(jdbc.queryForObject("SELECT id FROM meeting_rooms WHERE invite_code=?", UUID::class.java, code))
        assertEquals(
            "PRE_SUBMIT_V1",
            jdbc.queryForObject("SELECT submission_protocol FROM meeting_rooms WHERE id=?", String::class.java, roomId),
        )
        val member = accounts.newAccount("whole-room-member")
        mvc
            .perform(
                post(
                    "/api/rooms/{code}/participants",
                    code,
                ).cookie(member).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content("""{"display_name":"member"}"""),
            ).andExpect(status().isCreated)
        val epoch = UUID.randomUUID()
        // Test-only explicit proven-zero synthetic coverage; no monetary policy or production activation.
        jdbc.update(
            "UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,cap=?,settled=0,held=0,policy_fingerprint=?,coverage_started_at=?,version=version+1 WHERE room_id=?",
            epoch,
            BigDecimal(1000),
            dispatchHash("synthetic-policy".toByteArray()),
            clock.instant().minusSeconds(1).atOffset(ZoneOffset.UTC),
            roomId,
        )
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(true), budget, clock)
        val aCalls = AtomicInteger()
        val firstRaw = "첫  합성 원문 😀"
        val secondRaw = "둘째 합성 원문 그대로"
        DispatchEndpoint(response = { path, _ ->
            when {
                path == "/v1/decisions" -> 200 to decision(if (aCalls.incrementAndGet() == 1)"NEEDS_REVIEW" else "CLEAR")
                path.endsWith(":countTokens") -> 200 to "{\"totalTokens\":256}"
                else -> 200 to generation("{\"verdict\":\"CLEAR\"}")
            }
        }).use { personalEndpoint ->
            val classifier =
                OpenAiAmbiguityClassifier(
                    "synthetic-key",
                    mapper,
                    AmbiguityClassifierOptions(personalEndpoint.uri("/v1/decisions"), true, 0.8),
                    dispatchGate = gate,
                )
            val generator =
                GeminiPreSubmissionCheckProvider.runtime(
                    GeminiProperties(apiKey = "synthetic-key"),
                    mapper,
                    PrecheckProviderRuntimeProperties(true),
                    endpoint = personalEndpoint.origin,
                    dispatchGate = gate,
                )
            val provider = TwoStagePreSubmissionCheckProvider(classifier, generator, physicalDispatch = gate)
            val worker = PreSubmissionCheckWorker(prechecks, processing, provider, clock, dispatchMode = AiDispatchModePort { true })
            submit(code, host, firstRaw)
            assertEquals(BigInteger.ZERO, assertNotNull(budget.snapshot(roomId)).held)
            assertTrue(worker.runOne())
            assertEquals(3, personalEndpoint.requests.size)
            assertEquals(BigInteger.valueOf(30), assertNotNull(budget.snapshot(roomId)).held)
            assertEquals(
                "COLLECTING",
                jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, roomId),
            )
            submit(code, member, secondRaw)
            assertTrue(worker.runOne())
            assertEquals(4, personalEndpoint.requests.size)
            assertFalse(worker.runOne())
            assertEquals(BigInteger.valueOf(40), assertNotNull(budget.snapshot(roomId)).held)
            assertEquals(
                "CLOSED",
                jdbc.queryForObject("SELECT collection_status FROM meeting_rooms WHERE id=?", String::class.java, roomId),
            )
            val active = assertNotNull(jdbc.queryForObject("SELECT active_run_id FROM meeting_rooms WHERE id=?", UUID::class.java, roomId))
            val run = assertNotNull(runs.findById(CoordinationRunId(active)))
            val accepted = submissions.findLatestByRoom(MeetingRoomId(roomId))
            assertEquals(setOf(firstRaw, secondRaw), accepted.map { it.latest.rawText }.toSet())
            assertEquals(accepted.map { it.latest.id }.toSet(), run.batch.submissionVersionIds.toSet())
            jdbc.queryForList("SELECT id FROM participants WHERE room_id=?", UUID::class.java, roomId).forEach {
                assertEquals(1, prechecks.callCount(roomId, assertNotNull(it)))
            }
            val finalPayload =
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
            DispatchEndpoint(response = { _, _ -> 200 to generation(finalPayload) }).use { finalEndpoint ->
                val primary =
                    GeminiNaturalLanguageParserAdapter(
                        GeminiProperties(apiKey = "synthetic-key"),
                        mapper,
                        finalEndpoint.origin,
                        dispatchGate = gate,
                    )
                val fallback =
                    OpenAiLunaNaturalLanguageParserAdapter(
                        OpenAiProperties(apiKey = ""),
                        mapper,
                        OkHttpClient(),
                        finalEndpoint.uri("/v1/responses"),
                        dispatchGate = gate,
                    )
                GeminiBatchProcessor(
                    runs,
                    submissions,
                    structured,
                    attempts,
                    primary,
                    IdGenerator {
                        UUID.randomUUID()
                    },
                    RetryDelayPort {
                    },
                    JitterPort {
                        0
                    },
                    MonotonicTimePort {
                        System.nanoTime()
                    },
                    clock,
                    persistence,
                    fallbackParser = fallback,
                    invocationRepository = invocations,
                    prechecks = prechecks,
                    dispatchMode =
                        AiDispatchModePort {
                            true
                        },
                ).process(run.batch.id)
                assertEquals(1, finalEndpoint.requests.size)
                assertEquals(5, personalEndpoint.requests.size + finalEndpoint.requests.size)
                val complete = assertNotNull(budget.snapshot(roomId))
                assertEquals(epoch, complete.epochId)
                assertEquals(BigInteger.valueOf(50), complete.held)
                assertEquals(BigInteger.ZERO, complete.settled)
                assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, roomId))
                assertEquals(
                    3,
                    jdbc.queryForObject(
                        "SELECT count(DISTINCT execution_id) FROM ai_call_reservations WHERE room_id=?",
                        Int::class.java,
                        roomId,
                    ),
                )
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_room_budgets WHERE room_id=?", Int::class.java, roomId))
                assertEquals(CoordinationStatus.MATCHING, assertNotNull(runs.findById(run.id)).status)
                assertEquals(accepted.map { it.latest.id }.toSet(), assertNotNull(runs.findById(run.id)).batch.submissionVersionIds.toSet())
            }
        }
    }

    private fun submit(
        code: String,
        owner: Cookie,
        raw: String,
    ) {
        val response =
            mvc
                .perform(
                    post(
                        "/api/rooms/{code}/submission/checks",
                        code,
                    ).cookie(
                        owner,
                    ).header("Origin", ORIGIN)
                        .header("Accept-Language", "ko-KR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            mapper.writeValueAsString(
                                mapOf(
                                    "request_id" to UUID.randomUUID(),
                                    "expected_revision" to 0,
                                    "raw_text" to raw,
                                ),
                            ),
                        ),
                ).andExpect(status().isAccepted)
                .andReturn()
                .response
        assertEquals(false, document(response)["submission_complete"])
    }

    private fun decision(choice: String) =
        mapper.writeValueAsString(
            mapOf(
                "model" to "gpt-6-luna",
                "answers" to
                    listOf(
                        mapOf(
                            "type" to "choice",
                            "name" to "material_ambiguity",
                            "choice" to choice,
                            "confidence" to 0.9,
                            "probabilities" to
                                listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").map {
                                    mapOf(
                                        "value" to it,
                                        "probability" to
                                            if (it ==
                                                choice
                                            ) {
                                                0.9
                                            } else {
                                                0.05
                                            },
                                    )
                                },
                        ),
                    ),
                "usage" to mapOf("input_tokens" to 137, "output_tokens" to 7, "total_tokens" to 144),
            ),
        )

    private fun generation(payload: String) =
        mapper.writeValueAsString(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf(
                            "finishReason" to "STOP",
                            "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to payload))),
                        ),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 256, "candidatesTokenCount" to 7, "thoughtsTokenCount" to 0),
            ),
        )

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
