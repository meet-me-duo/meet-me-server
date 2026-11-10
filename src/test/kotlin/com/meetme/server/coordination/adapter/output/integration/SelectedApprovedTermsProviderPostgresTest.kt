package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.adapter.output.persistence.FinalAnalysisRoutePostgresTest
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.application.service.ScheduleDslStoredReadService
import com.meetme.server.coordination.application.service.SelectedScheduleDslBatchProcessor
import com.meetme.server.coordination.application.service.SelectedScheduleDslProcessingPersistenceService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculator
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
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
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Real monetary coordinator/JDBC fence and loopback only. Runtime-only canonical HTTP is a separate proof. */
@SpringBootTest
@Import(
    FinalAnalysisRoutePostgresTest.RouteTestClock::class,
    SelectedApprovedTermsProviderPostgresTest.AuthorityConfig::class,
    SelectedApprovedTermsProviderPostgresTest.ApprovedPublicationConfig::class,
)
class SelectedApprovedTermsProviderPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Autowired private lateinit var publication: SelectedScheduleDslProcessingPersistenceService

    @Autowired private lateinit var storedRead: ScheduleDslStoredReadService

    @BeforeEach
    fun realNativeRowsAndTestCoverage() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, SelectedDslPhysicalInputFixture.approvedInput())
        jdbc.update(
            """
            UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,
                cap=100,settled=0,held=0,policy_fingerprint=?,coverage_started_at=? WHERE room_id=?
            """.trimIndent(),
            UUID(0, 9701),
            POLICY,
            Timestamp.from(F.ir.reference.minusSeconds(1)),
            F.ir.room,
        )
    }

    private fun request(provider: String): SelectedScheduleDslProviderRequest {
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        val policy = input.policy
        val capsule = ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
        val selected =
            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", policy.version),
                policy,
                capsule.snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            )
        val route = routes.freeze(F.ir.run, 1, selected)
        val luna = provider == "LUNA"
        val invocation = F.invocation().copy(geminiAttempts = 1, lunaAttempts = if (luna) 1 else 0)
        invocations.insert(invocation)

        fun attempt(
            id: Long,
            number: Int,
            fallback: Boolean,
            finished: Boolean = false,
        ) = CoordinationAttempt(
            UUID(0, id),
            invocation.runId,
            number,
            F.selectedAt,
            if (finished) F.selectedAt else null,
            if (finished) "SERVER" else null,
            null,
            null,
            null,
            null,
            if (fallback) AnalysisProvider.OPENAI else AnalysisProvider.GEMINI,
            if (fallback) "gpt-6-luna" else "gemini-3.8-flash",
            "bounded-luna-v1",
            invocation.id,
        )
        if (luna) attempts.insert(attempt(9702, 1, false, true))
        val attempt = attempt(9703, if (luna) 2 else 1, luna)
        attempts.insert(attempt)
        val binding = routes.bindAttempt(route, invocation)
        routes.requireDispatchCurrent(binding)
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
        return SelectedScheduleDslProviderRequest(
            binding,
            attempt,
            capsule,
            snapshots,
            AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(5).toNanos(), attempt.id, 1),
            Duration.ofSeconds(5),
        )
    }

    private fun adapter(
        provider: String,
        http: Loopback,
    ): SelectedScheduleDslProviderPort {
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, clock)
        return if (provider == "LUNA") {
            OpenAiLunaSelectedScheduleDslProviderAdapter(OpenAiProperties(apiKey = "synthetic-only"), mapper, gate, http.endpoint)
        } else {
            GeminiSelectedScheduleDslProviderAdapter(GeminiProperties(apiKey = "synthetic-only"), mapper, gate, http.endpoint)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun response3(): String {
        // Independently supplied typed precise AST: no approved term reference is needed to test transport schema closure.
        val root = mapper.readValue(F.q.wire.json(), MutableMap::class.java) as MutableMap<String, Any?>
        root["semantic_version"] = "schedule-ir-product-4"
        root["vocabulary_version"] = "ko-time-terms-1"
        (root["results"] as List<MutableMap<String, Any?>>).forEach { it["approved_terms"] = emptyList<Any>() }
        return mapper.writeValueAsString(root)
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun decode(
        json: String,
        request: SelectedScheduleDslProviderRequest,
    ) = StrictScheduleDslApprovedTermsResponseParser(mapper).parse(
        json,
        request.inputCapsule.snapshot,
        "schedule-dsl-response-3",
        "schedule-ir-product-4",
        SCHEDULE_AST_VERSION,
        "ko-time-terms-1",
    )

    @ParameterizedTest
    @ValueSource(strings = ["GEMINI", "LUNA"])
    fun `C3TP01 exact approved wire and native questions survive actual gated transport`(provider: String) {
        val request = request(provider)
        Loopback(provider, 200, response3(), mapper).use { http ->
            val response = adapter(provider, http).generate(request)
            assertEquals(1, http.requests.size)
            assertEquals(if (provider == "LUNA") AnalysisProvider.OPENAI else AnalysisProvider.GEMINI, response.provider)
            assertEquals(if (provider == "LUNA") "gpt-6-luna" else "gemini-3.8-flash", response.model)
            assertEquals(17, response.usage.inputTokens?.toInt())
            assertEquals(23, response.usage.outputTokens?.toInt())
            assertEquals(response3(), response.responseJson)
            val body = mapper.readTree(http.requests.single())
            val neutral =
                ScheduleDslApprovedTermsProviderContract(mapper).requestBytes(
                    request.inputCapsule,
                    (request.binding.route.choice as FinalAnalysisRouteChoice.DslApprovedTermsV3).meetingMode,
                )
            val schema = mapper.readTree(mapper.writeValueAsBytes(ScheduleDslApprovedTermsProviderContract(mapper).responseSchema()))
            val embedded =
                if (provider == "LUNA") {
                    assertEquals("/v1/responses", http.paths.single())
                    assertEquals("gpt-6-luna", body.path("model").asString())
                    assertEquals(false, body.path("store").asBoolean())
                    assertEquals(32768, body.path("max_output_tokens").asInt())
                    val format = body.path("text").path("format")
                    assertEquals("json_schema", format.path("type").asString())
                    assertEquals("meet_me_schedule_dsl", format.path("name").asString())
                    assertEquals(true, format.path("strict").asBoolean())
                    assertEquals(schema, format.path("schema"))
                    assertTrue(body.path("reasoning").isMissingNode)
                    requireNotNull(body.path("input").asString())
                } else {
                    assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", http.paths.single())
                    val config = body.path("generationConfig")
                    assertEquals("application/json", config.path("responseMimeType").asString())
                    assertEquals(schema, config.path("responseJsonSchema"))
                    assertEquals(1, config.path("candidateCount").asInt())
                    assertEquals(32768, config.path("maxOutputTokens").asInt())
                    assertEquals("LOW", config.path("thinkingConfig").path("thinkingLevel").asString())
                    val content = body.path("contents").single()
                    assertEquals("user", content.path("role").asString())
                    requireNotNull(
                        content
                            .path("parts")
                            .single()
                            .path("text")
                            .asString(),
                    )
                }
            assertContentEquals(neutral, embedded.toByteArray(Charsets.UTF_8))
            val termEnum =
                schema
                    .path("properties")
                    .path("results")
                    .path("items")
                    .path("properties")
                    .path("approved_terms")
                    .path("items")
                    .path("properties")
                    .path("term")
                    .path("enum")
            assertEquals(
                setOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY"),
                termEnum.toList().map { requireNotNull(it.asString()) }.toSet(),
            )
            val neutralRoot = mapper.readTree(embedded)
            assertEquals("schedule-dsl-request-3", neutralRoot.path("request_contract_version").asString())
            assertEquals("schedule-dsl-response-3", neutralRoot.path("response_contract_version").asString())
            assertEquals("schedule-ir-product-4", neutralRoot.path("semantic_version").asString())
            assertEquals("ko-time-terms-1", neutralRoot.path("vocabulary_version").asString())
            for (lexeme in listOf("오전", "오후", "새벽", "아침", "점심", "낮", "한낮", "저녁", "밤", "심야", "한밤중", "하루종일")) {
                assertTrue(embedded.contains(lexeme))
            }
            assertTrue(embedded.contains(F.q.PROMPT))
            assertTrue(embedded.contains(F.q.CHOICE))
            val envelope = decode(response.responseJson, request)
            assertEquals("schedule-ir-product-4", envelope.semanticVersion)
            assertEquals("ko-time-terms-1", envelope.vocabularyVersion)
            assertEquals(1, count("ai_call_reservations"))
            assertEquals(request.attempt.id, jdbc.queryForObject("SELECT attempt_id FROM ai_call_reservations", UUID::class.java))
            assertEquals(
                if (provider == "LUNA") "FINAL_FALLBACK" else "FINAL_PRIMARY",
                jdbc.queryForObject("SELECT operation FROM ai_call_reservations", String::class.java),
            )
            assertEquals(1, jdbc.queryForObject("SELECT ordinal FROM ai_call_reservations", Int::class.java))
            val selected = request.binding.route.choice as FinalAnalysisRouteChoice.DslApprovedTermsV3
            val expected =
                ScheduleDslCalculator.calculateApprovedTerms(
                    envelope,
                    request.inputCapsule.snapshot,
                    selected.policy,
                    selected.meetingMode,
                )
            assertTrue(AopUtils.isAopProxy(publication))
            assertTrue(AopUtils.isAopProxy(storedRead))
            assertTrue(publication.completeDsl(request.binding, request.attempt, response) { true })
            assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
            assertEquals(
                request.attempt.id,
                jdbc.queryForObject("SELECT winner_attempt_id FROM analysis_invocations", UUID::class.java),
            )
            assertEquals(5, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
            val fresh = assertNotNull(JdbcScheduleDslApprovedTermsRepository(jdbc, mapper).find(selected.context))
            assertEquals(envelope, fresh)
            val version = assertNotNull(jdbc.queryForObject("SELECT version FROM coordination_runs", Long::class.java))
            assertEquals(request.binding.invocation.runVersion + 1, version)
            // Successful public call also verifies the service's read-only REPEATABLE_READ transaction checks.
            val restored = storedRead.readStored(CoordinationRunId(F.ir.run), version, request.binding.route.fingerprint())
            assertEquals(request.binding.route, restored.route)
            assertEquals(version, restored.currentRunVersion)
            assertEquals(expected, restored.calculation) // Wiring/evidence restoration, not new language-accuracy proof.
            assertEquals(
                listOf(InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))),
                restored.calculation.candidates.candidates
                    .single()
                    .timeRanges,
            ) // Independently established synthetic AST AND19-21 ground truth from the existing Q/wire fixture.
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["GEMINI", "LUNA"])
    fun `C3TP02 provider raw legacy response is not silently accepted by the approved server parser`(provider: String) {
        val request = request(provider)
        Loopback(provider, 200, F.q.wire.json(), mapper).use { http ->
            val response = adapter(provider, http).generate(request)
            val failure = assertFailsWith<NaturalLanguageParserException> { decode(response.responseJson, request) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(1, http.requests.size)
            assertEquals(0, count("schedule_ir_envelopes"))
        }
    }

    @ParameterizedTest
    @CsvSource("GEMINI,503", "GEMINI,529", "LUNA,503", "LUNA,529")
    fun `C3TP03 transient provider status is typed SERVER without adapter retries`(
        provider: String,
        status: Int,
    ) {
        val request = request(provider)
        Loopback(provider, status, "", mapper).use { http ->
            val failure = assertFailsWith<NaturalLanguageParserException> { adapter(provider, http).generate(request) }
            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertTrue(failure.retryable)
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
            assertEquals(0, count("schedule_ir_envelopes"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["GEMINI", "LUNA"])
    fun `C3TP04 duplicate physical request cannot consume the same admitted slot twice`(provider: String) {
        val request = request(provider)
        Loopback(provider, 200, response3(), mapper).use { http ->
            val adapter = adapter(provider, http)
            assertNotNull(adapter.generate(request))
            assertFailsWith<RuntimeException> { adapter.generate(request) }
            assertEquals(1, http.requests.size)
            assertEquals(1, count("ai_call_reservations"))
        }
    }

    private fun freezeApprovedForChain() {
        val input = SelectedDslPhysicalInputFixture.approvedInput()
        val capsule = ScheduleDslOfflinePipeline.snapshotApprovedTerms(input)
        routes.freeze(
            F.ir.run,
            1,
            FinalAnalysisRouteChoice.DslApprovedTermsV3(
                IrExecutionIdentity("schedule-dsl-response-3", "schedule-ir-product-4", input.policy.version),
                input.policy,
                capsule.snapshot.context,
                input.meetingMode,
                "ko-time-terms-1",
            ),
        )
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
    }

    private fun runRealChain(
        gemini: Loopback,
        luna: Loopback,
    ) {
        // Production wall clock remains the existing fixed fixture; this is not a real-time60s duration proof.
        SelectedScheduleDslBatchProcessor(
            publication,
            adapter("GEMINI", gemini),
            adapter("LUNA", luna),
            RetryDelayPort { _ -> },
            JitterPort { _ -> 0L },
            MonotonicTimePort { System.nanoTime() },
        ).process(SubmissionBatchId(F.ir.batch))
    }

    private fun assertOneBoundedChain(
        gemini: Loopback,
        luna: Loopback,
    ) {
        assertEquals(4, gemini.requests.size)
        assertEquals(1, luna.requests.size)
        assertEquals(1, count("analysis_invocations"))
        assertEquals(5, count("coordination_attempts"))
        assertEquals(4, jdbc.queryForObject("SELECT gemini_attempts FROM analysis_invocations", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT luna_attempts FROM analysis_invocations", Int::class.java))
        assertEquals(5, count("ai_call_reservations"))
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT execution_id) FROM ai_call_reservations", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT invocation_id) FROM coordination_attempts", Int::class.java))
        assertEquals(
            listOf(1, 2, 3, 4),
            jdbc
                .queryForList("SELECT ordinal FROM ai_call_reservations WHERE operation='FINAL_PRIMARY' ORDER BY ordinal", Int::class.java)
                .map { requireNotNull(it) },
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT ordinal FROM ai_call_reservations WHERE operation='FINAL_FALLBACK'",
                Int::class.java,
            ),
        )
        assertEquals(0, count("structured_submission_results"))
        assertEquals(0, count("schedule_ir_execution_bindings"))
    }

    @ParameterizedTest
    @ValueSource(ints = [503, 529])
    fun `C3TP05 actual coordinator retries primary and publishes the single Luna typed winner`(status: Int) {
        freezeApprovedForChain()
        Loopback("GEMINI", status, "", mapper).use { gemini ->
            Loopback("LUNA", 200, response3(), mapper).use { luna ->
                runRealChain(gemini, luna)
                assertOneBoundedChain(gemini, luna)
                assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
                assertEquals(
                    "OPENAI",
                    jdbc.queryForObject(
                        """
                        SELECT a.provider FROM analysis_invocations i JOIN coordination_attempts a ON a.id=i.winner_attempt_id
                        """.trimIndent(),
                        String::class.java,
                    ),
                )
                assertEquals(5, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
                assertEquals(2, count("schedule_ir_inputs"))
                val version = assertNotNull(jdbc.queryForObject("SELECT version FROM coordination_runs", Long::class.java))
                val route = assertNotNull(routes.find(F.ir.run))
                val restored = storedRead.readStored(CoordinationRunId(F.ir.run), version, route.fingerprint())
                assertEquals(
                    listOf(InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))),
                    restored.calculation.candidates.candidates
                        .single()
                        .timeRanges,
                )
                assertEquals(4, gemini.requests.size)
                assertEquals(1, luna.requests.size)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 504])
    fun `ITFHTTP01 selected HTTP timeout immediately publishes the single Luna typed winner`(status: Int) {
        freezeApprovedForChain()
        val frozenRoute = assertNotNull(routes.find(F.ir.run))
        val frozenVersions = jdbc.queryForList("SELECT id,revision,raw_text,input_evidence::text FROM submission_versions ORDER BY id")
        val frozenItems =
            jdbc.queryForList(
                "SELECT batch_id,submission_version_id FROM submission_batch_items ORDER BY submission_version_id",
            )
        Loopback("GEMINI", status, "", mapper).use { gemini ->
            Loopback("LUNA", 200, response3(), mapper).use { luna ->
                runRealChain(gemini, luna)
                assertEquals(1, gemini.requests.size)
                assertEquals(1, luna.requests.size)
                assertEquals(1, count("analysis_invocations"))
                assertEquals(2, count("coordination_attempts"))
                assertEquals(1, jdbc.queryForObject("SELECT gemini_attempts FROM analysis_invocations", Int::class.java))
                assertEquals(1, jdbc.queryForObject("SELECT luna_attempts FROM analysis_invocations", Int::class.java))
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM coordination_attempts WHERE provider='GEMINI' AND failure_kind='TIMEOUT' AND finished_at IS NOT NULL",
                        Int::class.java,
                    ),
                )
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM coordination_attempts WHERE provider='OPENAI' AND failure_kind IS NULL AND finished_at IS NOT NULL",
                        Int::class.java,
                    ),
                )
                assertEquals(
                    "OPENAI",
                    jdbc.queryForObject(
                        "SELECT a.provider FROM analysis_invocations i JOIN coordination_attempts a ON a.id=i.winner_attempt_id WHERE i.finished_at IS NOT NULL",
                        String::class.java,
                    ),
                )
                val invocationId = assertNotNull(jdbc.queryForObject("SELECT id FROM analysis_invocations", UUID::class.java))
                assertEquals(
                    invocationId,
                    jdbc.queryForObject("SELECT DISTINCT invocation_id FROM coordination_attempts", UUID::class.java),
                )
                assertEquals(2, count("ai_call_reservations"))
                assertEquals(invocationId, jdbc.queryForObject("SELECT DISTINCT execution_id FROM ai_call_reservations", UUID::class.java))
                assertEquals(
                    listOf(1),
                    jdbc.queryForList("SELECT ordinal FROM ai_call_reservations WHERE operation='FINAL_PRIMARY'", Int::class.java),
                )
                assertEquals(
                    listOf(1),
                    jdbc.queryForList("SELECT ordinal FROM ai_call_reservations WHERE operation='FINAL_FALLBACK'", Int::class.java),
                )
                assertEquals(
                    2,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM ai_call_reservations r JOIN coordination_attempts a ON a.id=r.attempt_id WHERE a.invocation_id=r.execution_id",
                        Int::class.java,
                    ),
                )
                assertEquals("MATCHING", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
                assertEquals(1, count("schedule_ir_envelopes"))
                assertEquals(5, jdbc.queryForObject("SELECT format_version FROM schedule_ir_envelopes", Int::class.java))
                assertEquals(2, count("schedule_ir_inputs"))
                assertEquals(0, count("structured_submission_results"))
                assertEquals(0, count("schedule_ir_execution_bindings"))
                assertEquals(frozenRoute, routes.find(F.ir.run))
                val selected = frozenRoute.choice as FinalAnalysisRouteChoice.DslApprovedTermsV3
                val fresh = assertNotNull(JdbcScheduleDslApprovedTermsRepository(jdbc, mapper).find(selected.context))
                val snapshot = ScheduleDslOfflinePipeline.snapshotApprovedTerms(SelectedDslPhysicalInputFixture.approvedInput()).snapshot
                val fromStoredIr = ScheduleDslCalculator.calculateApprovedTerms(fresh, snapshot, selected.policy, selected.meetingMode)
                val version = assertNotNull(jdbc.queryForObject("SELECT version FROM coordination_runs", Long::class.java))
                val restored = storedRead.readStored(CoordinationRunId(F.ir.run), version, frozenRoute.fingerprint())
                assertEquals(frozenRoute, restored.route)
                assertEquals(fromStoredIr, restored.calculation)
                // Independent existing synthetic AND19-21 oracle; these instants are not computed by a production helper.
                assertEquals(
                    listOf(InstantTimeRange(Instant.parse("2027-01-08T10:00:00Z"), Instant.parse("2027-01-08T12:00:00Z"))),
                    restored.calculation.candidates.candidates
                        .single()
                        .timeRanges,
                )
                assertEquals(
                    frozenVersions,
                    jdbc.queryForList("SELECT id,revision,raw_text,input_evidence::text FROM submission_versions ORDER BY id"),
                )
                assertEquals(
                    frozenItems,
                    jdbc.queryForList("SELECT batch_id,submission_version_id FROM submission_batch_items ORDER BY submission_version_id"),
                )
                assertEquals(1, gemini.requests.size)
                assertEquals(1, luna.requests.size)
                assertEquals(2, count("ai_call_reservations"))
            }
        }
    }

    @Test
    fun `C3TP06 both providers fail within one bounded chain without winner IR or budget refund`() {
        freezeApprovedForChain()
        Loopback("GEMINI", 503, "", mapper).use { gemini ->
            Loopback("LUNA", 503, "", mapper).use { luna ->
                runRealChain(gemini, luna)
                assertOneBoundedChain(gemini, luna)
                assertEquals("ANALYSIS_DELAYED", jdbc.queryForObject("SELECT status FROM coordination_runs", String::class.java))
                assertEquals(
                    0,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM analysis_invocations WHERE winner_attempt_id IS NOT NULL",
                        Int::class.java,
                    ),
                )
                assertEquals(
                    5,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM coordination_attempts WHERE finished_at IS NOT NULL AND failure_kind='SERVER'",
                        Int::class.java,
                    ),
                )
                assertEquals(0, count("schedule_ir_envelopes"))
                assertEquals(0, count("schedule_ir_inputs"))
                assertEquals(0, count("candidates"))
                assertEquals(0, count("recommendation_analyses"))
                assertEquals(
                    BigInteger.valueOf(50),
                    assertNotNull(
                        jdbc.queryForObject(
                            "SELECT held FROM ai_room_budgets",
                            java.math.BigDecimal::class.java,
                        ),
                    ).toBigIntegerExact(),
                )
                assertEquals(
                    0,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM ai_call_reservations WHERE state='SETTLED'",
                        Int::class.java,
                    ),
                )
            }
        }
    }

    class TestAuthority : AiBudgetAuthorityRegistryPort {
        override fun quote(request: AiBudgetQuoteRequest) =
            AiVerifiedBudgetQuote(
                "a".repeat(64),
                request.call.profileFingerprint,
                request.call.wholePayloadFingerprint,
                request.policyFingerprint,
                "b".repeat(64),
                BigInteger.TEN,
                AiBudgetUnit("TEST_SUBUNIT", 0),
                F.selectedAt.plusSeconds(60),
            )

        override fun protectedFinalReserve(request: AiBudgetQuoteRequest) =
            AiVerifiedFinalReserve(
                request.policyFingerprint,
                BigInteger.ZERO,
                AiBudgetUnit("TEST_SUBUNIT", 0),
                F.selectedAt.plusSeconds(60),
            )

        override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null
    }

    @TestConfiguration(proxyBeanMethods = false)
    class AuthorityConfig {
        @Bean @Primary
        fun c3TransportTestAuthority() = TestAuthority()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ApprovedPublicationConfig {
        @Bean fun c3LegacyEnvelope(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleDslRepository = JdbcScheduleDslRepository(jdbc, mapper)

        @Bean fun c3ApprovedEnvelope(
            jdbc: JdbcTemplate,
            mapper: ObjectMapper,
        ): ScheduleDslApprovedTermsRepository = JdbcScheduleDslApprovedTermsRepository(jdbc, mapper)

        @Bean fun c3Publication(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            routes: FinalAnalysisRouteRegistry,
            envelopes: ScheduleDslRepository,
            approved: ScheduleDslApprovedTermsRepository,
            invocations: AnalysisInvocationRepository,
            attempts: CoordinationAttemptRepository,
            clock: Clock,
            mapper: ObjectMapper,
        ) = SelectedScheduleDslProcessingPersistenceService(
            runs,
            rooms,
            submissions,
            routes,
            envelopes,
            StrictScheduleDslContextResponseParser(mapper),
            invocations,
            attempts,
            clock,
            approved,
            StrictScheduleDslApprovedTermsResponseParser(mapper),
        )

        @Bean fun c3StoredRead(
            runs: CoordinationRunRepository,
            rooms: MeetingRoomRepository,
            submissions: SubmissionRepository,
            routes: FinalAnalysisRouteRegistry,
            envelopes: ScheduleDslRepository,
            approved: ScheduleDslApprovedTermsRepository,
        ) = ScheduleDslStoredReadService(runs, rooms, submissions, routes, envelopes, approved)
    }

    private class Loopback(
        provider: String,
        status: Int,
        json: String,
        mapper: ObjectMapper,
    ) : AutoCloseable {
        val requests: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint =
            URI(
                "http://127.0.0.1:${server.address.port}" +
                    if (provider == "LUNA") "/v1/responses" else "/v1beta/models/gemini-3.8-flash:generateContent",
            )

        init {
            val payload =
                if (status != 200) {
                    mapOf("error" to mapOf("code" to "OVERLOADED", "type" to "server_error"))
                } else if (provider == "LUNA") {
                    mapOf(
                        "status" to "completed",
                        "output" to
                            listOf(
                                mapOf(
                                    "type" to "message",
                                    "role" to "assistant",
                                    "content" to listOf(mapOf("type" to "output_text", "text" to json)),
                                ),
                            ),
                        "usage" to mapOf("input_tokens" to 17, "output_tokens" to 23),
                    )
                } else {
                    mapOf(
                        "candidates" to
                            listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to json))), "finishReason" to "STOP")),
                        "usageMetadata" to mapOf("promptTokenCount" to 17, "candidatesTokenCount" to 23, "totalTokenCount" to 40),
                    )
                }
            val bytes = mapper.writeValueAsBytes(payload)
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
        private val POLICY = "c".repeat(64)
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
