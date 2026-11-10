package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.output.persistence.FinalAnalysisRoutePostgresTest
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.application.service.ScheduleEvidenceSnapshotFactory
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.support.SelectedDslPhysicalInputFixture
import com.meetme.server.support.mappedPortReadyPostgres
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.FinalAnalysisRouteTestFixture as F

/** Actual public adapters, PostgreSQL admission and loopback only; no provider or semantic-quality claim. */
@SpringBootTest
@Import(FinalAnalysisRoutePostgresTest.RouteTestClock::class, SelectedApprovedTermsProviderPostgresTest.AuthorityConfig::class)
class C5LunaReasoningWirePostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var routes: FinalAnalysisRouteRegistry

    @Autowired private lateinit var runs: CoordinationRunRepository

    @Autowired private lateinit var invocations: AnalysisInvocationRepository

    @Autowired private lateinit var attempts: CoordinationAttemptRepository

    @Autowired private lateinit var budget: DurableAiBudgetPort

    @Test
    fun `C5LOW01 actual C5 Luna separates trusted instructions with low reasoning and one native wire reservation`() {
        val fixture = request(5, luna = true)
        val original = F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items")
        val baseline = lunaWithoutReasoning(fixture)
        val body = exchange(fixture, luna = true)
        val actual = mapper.readValue(body, Map::class.java).toMutableMap()
        assertEquals<Set<*>>(baseline.keys + "reasoning", actual.keys)
        assertEquals(mapOf("effort" to "low"), actual.remove("reasoning"))
        assertContentEquals(mapper.writeValueAsBytes(baseline), mapper.writeValueAsBytes(actual))
        assertNativePayload(fixture, body)
        assertEquals(original, F.rows(jdbc, "submission_versions") + F.rows(jdbc, "submission_batch_items"))
    }

    @Test
    fun `C5LOW02 C5 Gemini keeps its complete established request and LOW thinking without Luna reasoning`() {
        val fixture = request(5, luna = false)
        val body = exchange(fixture, luna = false)
        val expected =
            linkedMapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to fixture.neutral.toString(Charsets.UTF_8))))),
                "generationConfig" to
                    linkedMapOf(
                        "responseMimeType" to "application/json",
                        "responseJsonSchema" to GeminiResponseSchemaProjection.project(fixture.schema),
                        "maxOutputTokens" to 32768,
                        "thinkingConfig" to mapOf("thinkingLevel" to "LOW"),
                    ),
            )
        assertContentEquals(mapper.writeValueAsBytes(expected), body)
        assertTrue(mapper.readTree(body).path("reasoning").isMissingNode)
    }

    @Test
    fun `C5LOW03 C2 C3 and C4 Luna retain full established wire without a new reasoning field`() {
        for (version in listOf(2, 3, 4)) {
            val fixture = request(version, luna = true)
            val body = exchange(fixture, luna = true)
            assertContentEquals(mapper.writeValueAsBytes(lunaWithoutReasoning(fixture)), body, "C$version whole wire")
            assertTrue(mapper.readTree(body).path("reasoning").isMissingNode, "C$version")
            assertNativePayload(fixture, body)
        }
    }

    private fun lunaWithoutReasoning(fixture: WireFixture): Map<String, Any?> {
        val neutral = mapper.readValue(fixture.neutral, Map::class.java)
        val input =
            if (fixture.version == 5) mapper.writeValueAsString(neutral - "prompt") else fixture.neutral.toString(Charsets.UTF_8)
        return linkedMapOf<String, Any?>(
            "model" to "gpt-6-luna",
            "store" to false,
            "input" to input,
            "max_output_tokens" to 32768,
            "text" to
                mapOf(
                    "format" to
                        mapOf(
                            "type" to "json_schema",
                            "name" to if (fixture.version == 5) "meet_me_schedule_dsl_v5" else "meet_me_schedule_dsl",
                            "strict" to true,
                            "schema" to fixture.schema,
                        ),
                ),
        ).apply { if (fixture.version == 5) put("instructions", neutral["prompt"]) }
    }

    private fun assertNativePayload(
        fixture: WireFixture,
        body: ByteArray,
    ) {
        val text = requireNotNull(mapper.readTree(body).path("input").asString())
        if (fixture.version == 5) {
            val neutral = mapper.readValue(fixture.neutral, Map::class.java)
            assertEquals(neutral["prompt"], mapper.readTree(body).path("instructions").asString())
            assertContentEquals(mapper.writeValueAsBytes(neutral - "prompt"), text.toByteArray(Charsets.UTF_8))
        } else {
            assertContentEquals(fixture.neutral, text.toByteArray(Charsets.UTF_8))
        }
        assertEquals(1, fixture.request.inputCapsule.questions.size)
        assertTrue(text.contains(F.q.PROMPT))
        assertTrue(text.contains(F.q.answer.answerText))
        fixture.input.inputs.forEach { assertTrue(text.contains(it.rawText)) }
        val embeddedSchema = mapper.readTree(text).path("schema")
        val responseSchema =
            mapper
                .readTree(body)
                .path("text")
                .path("format")
                .path("schema")
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(fixture.schema)), embeddedSchema)
        assertEquals(embeddedSchema, responseSchema, "Schema duplication remains part of this unchanged contract")
    }

    private fun exchange(
        fixture: WireFixture,
        luna: Boolean,
    ): ByteArray {
        Loopback(luna, mapper).use { http ->
            val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, clock)
            val adapter: SelectedScheduleDslProviderPort =
                if (luna) {
                    OpenAiLunaSelectedScheduleDslProviderAdapter(OpenAiProperties(apiKey = "synthetic-only"), mapper, gate, http.endpoint)
                } else {
                    GeminiSelectedScheduleDslProviderAdapter(GeminiProperties(apiKey = "synthetic-only"), mapper, gate, http.endpoint)
                }
            val response = adapter.generate(fixture.request)
            assertEquals("{}", response.responseJson, "Transport returns original fixture bytes without semantic rewriting")
            assertEquals(if (luna) AnalysisProvider.OPENAI else AnalysisProvider.GEMINI, response.provider)
            assertEquals(if (luna) "gpt-6-luna" else "gemini-3.8-flash", response.model)
            assertEquals(1, http.requests.size)
            assertEquals(if (luna) "/v1/responses" else "/v1beta/models/gemini-3.8-flash:generateContent", http.paths.single())
            val body = http.requests.single()
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations", Int::class.java))
            assertEquals(fixture.request.attempt.id, jdbc.queryForObject("SELECT attempt_id FROM ai_call_reservations", UUID::class.java))
            assertEquals(
                if (luna) "FINAL_FALLBACK" else "FINAL_PRIMARY",
                jdbc.queryForObject("SELECT operation FROM ai_call_reservations", String::class.java),
            )
            assertEquals(1, jdbc.queryForObject("SELECT ordinal FROM ai_call_reservations", Int::class.java))
            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
                jdbc.queryForObject("SELECT whole_payload_fingerprint FROM ai_call_reservations", String::class.java),
            )
            return body
        }
    }

    private fun request(
        version: Int,
        luna: Boolean,
    ): WireFixture {
        val input =
            when (version) {
                2 -> SelectedDslPhysicalInputFixture.contextInput()
                3 -> SelectedDslPhysicalInputFixture.approvedInput()
                else ->
                    SelectedDslPhysicalInputFixture.contextInput().copy(
                        responseContractVersion = "schedule-dsl-response-$version",
                        semanticVersion = "schedule-ir-product-${version + 1}",
                        policy = ScheduleDslContextModePolicy.contextual(),
                    )
            }
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        SelectedDslPhysicalInputFixture.seed(jdbc, mapper, input)
        jdbc.update(
            """
            UPDATE ai_room_budgets SET state='READY',epoch_id=?,unit_code='TEST_SUBUNIT',unit_scale=0,
                cap=100,settled=0,held=0,policy_fingerprint=?,coverage_started_at=? WHERE room_id=?
            """.trimIndent(),
            UUID(0, 9701),
            "c".repeat(64),
            Timestamp.from(F.ir.reference.minusSeconds(1)),
            F.ir.room,
        )
        val zone = ZoneId.of(input.timeZone)
        val native = F.q.native(input)
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                assertNotNull(runs.findById(CoordinationRunId(input.runId))),
                native,
                zone,
                input.searchStart,
                input.searchEnd,
                input.policy.version,
            )
        val capsule = ScheduleDslProviderInputCapsule.create(snapshot, native)
        val identity = IrExecutionIdentity(input.responseContractVersion, input.semanticVersion, input.policy.version)
        val choice =
            when (version) {
                2 -> FinalAnalysisRouteChoice.DslContextV2(identity, input.policy, snapshot.context, input.meetingMode)
                3 ->
                    FinalAnalysisRouteChoice.DslApprovedTermsV3(
                        identity,
                        input.policy,
                        snapshot.context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
                4 ->
                    FinalAnalysisRouteChoice.DslContextModeV4(
                        identity,
                        input.policy,
                        snapshot.context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
                else ->
                    FinalAnalysisRouteChoice.DslStructuralV5(
                        identity,
                        input.policy,
                        snapshot.context,
                        input.meetingMode,
                        "ko-time-terms-1",
                    )
            }
        val route = routes.freeze(F.ir.run, 1, choice)
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
        val inputs =
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
                AiDispatchFingerprints.batchSource(inputs),
                AiDispatchFingerprints.batchContext(zone, input.searchStart, input.searchEnd, inputs),
            )
        val request =
            SelectedScheduleDslProviderRequest(
                binding,
                attempt,
                capsule,
                inputs,
                AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(5).toNanos(), attempt.id, 1),
                Duration.ofSeconds(5),
            )
        val (neutral, schema) =
            when (version) {
                2 ->
                    ScheduleDslContextProviderContract(mapper).requestBytes(capsule) to
                        ScheduleDslProviderContract(mapper).responseSchema()
                3 ->
                    ScheduleDslApprovedTermsProviderContract(mapper).let {
                        it.requestBytes(capsule, input.meetingMode) to it.responseSchema()
                    }
                4 ->
                    ScheduleDslContextModeProviderContract(mapper).let {
                        it.requestBytes(capsule, input.meetingMode) to it.responseSchema()
                    }
                else ->
                    ScheduleDslStructuralProviderContract(mapper).let {
                        it.requestBytes(capsule, input.meetingMode) to it.responseSchema()
                    }
            }
        return WireFixture(version, input, request, neutral, schema)
    }

    private data class WireFixture(
        val version: Int,
        val input: ScheduleDslOfflineInput,
        val request: SelectedScheduleDslProviderRequest,
        val neutral: ByteArray,
        val schema: Map<String, Any?>,
    )

    private class Loopback(
        luna: Boolean,
        mapper: ObjectMapper,
    ) : AutoCloseable {
        val requests: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: URI = URI("http://127.0.0.1:${server.address.port}")

        init {
            val payload =
                if (luna) {
                    mapOf(
                        "status" to "completed",
                        "output" to
                            listOf(
                                mapOf(
                                    "type" to "message",
                                    "role" to "assistant",
                                    "content" to listOf(mapOf("type" to "output_text", "text" to "{}")),
                                ),
                            ),
                        "usage" to mapOf("input_tokens" to 17, "output_tokens" to 23),
                    )
                } else {
                    mapOf(
                        "candidates" to
                            listOf(
                                mapOf("content" to mapOf("parts" to listOf(mapOf("text" to "{}"))), "finishReason" to "STOP"),
                            ),
                        "usageMetadata" to mapOf("promptTokenCount" to 17, "candidatesTokenCount" to 23, "totalTokenCount" to 40),
                    )
                }
            val bytes = mapper.writeValueAsBytes(payload)
            server.createContext("/") { exchange ->
                paths += exchange.requestURI.path
                requests += exchange.requestBody.use { it.readAllBytes() }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
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
