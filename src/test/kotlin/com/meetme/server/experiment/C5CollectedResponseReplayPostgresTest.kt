package com.meetme.server.experiment

import com.meetme.server.coordination.adapter.output.integration.C5EvaluationSelectedHttpTransport
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic collected bytes only. The poisoned transport cannot send, admit, reserve or contact a provider. */
@SpringBootTest(properties = ["meetme.reliability.enabled=false", "meetme.data-retention.enabled=false"])
class C5CollectedResponseReplayPostgresTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource
    private lateinit var request: SelectedScheduleDslProviderRequest
    private lateinit var transport: C5EvaluationSelectedHttpTransport
    private lateinit var replay: C5CollectedResponseReplay
    private lateinit var budget: DurableAiBudgetPort
    private val sendCalls = AtomicInteger()
    private val providers = listOf(AnalysisProvider.GEMINI, AnalysisProvider.OPENAI)

    @BeforeEach
    fun seedOnlySyntheticNativeRows() {
        jdbc.execute("TRUNCATE meeting_rooms,guest_browser_sessions,account_users CASCADE")
        N.seed(jdbc, mapper)
        sendCalls.set(0)
        val capsule = N.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", N.policy.version),
                N.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val invocation = FinalAnalysisRouteTestFixture.invocation()
        request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(
                FinalAnalysisRouteTestFixture.route(choice),
                invocation.copy(deadlineAt = invocation.startedAt.plusSeconds(60)),
            ),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        Mockito.`when`(request.timeout).thenReturn(Duration.ofSeconds(40))
        budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val original =
            C5EvaluationSelectedHttpTransport(
                mapper,
                AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC()),
                schemaCoherenceCandidate = true,
                providerParityCandidate = true,
                commonFiniteExpressionSchemaCandidate = true,
            )
        transport =
            Mockito.mock(
                C5EvaluationSelectedHttpTransport::class.java,
                Mockito.withSettings().spiedInstance(original).defaultAnswer { call ->
                    if (call.method.name == "generate") {
                        sendCalls.incrementAndGet()
                        throw AssertionError("FORBIDDEN_COLLECTED_RESPONSE_SEND")
                    }
                    call.callRealMethod()
                },
            )
        replay = C5CollectedResponseReplay(mapper, transport)
    }

    @AfterEach
    fun noPhysicalOrInvocationSideEffects() {
        assertEquals(0, sendCalls.get())
        Mockito.verifyNoInteractions(budget)
        assertEquals(0, count("analysis_invocations"))
        assertEquals(0, count("coordination_attempts"))
    }

    @Test
    fun `CR01 both collected provider envelopes decode the identical synthetic C5 declaration and common request`() {
        val parsed = providers.map { parse(it) }
        assertEquals(parsed[0].envelope, parsed[1].envelope)
        for ((index, item) in parsed.withIndex()) {
            assertEquals(providers[index], item.record.provider)
            assertEquals(item.record.provider, item.response.provider)
            assertEquals(model(item.record.provider), item.response.model)
            assertEquals(document(), item.response.responseJson)
            assertEquals(101L, item.response.usage.inputTokens)
            assertEquals(23L, item.response.usage.outputTokens)
            assertEquals(response(item.record.provider).size, item.response.usage.responseBytes)
            assertEquals(N.snapshot().context, item.snapshot.context)
        }
        val gemini = mapper.readTree(body(AnalysisProvider.GEMINI))
        val openAi = mapper.readTree(body(AnalysisProvider.OPENAI))
        assertEquals(
            openAi.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertEquals(
            openAi.path("input").asText(),
            gemini
                .path("contents")
                .path(0)
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertEquals(openAi.path("text").path("format").path("schema"), gemini.path("generationConfig").path("responseJsonSchema"))
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `CR02 publication fresh JDBC restoration and manual mode windows retain the declared synthetic meaning`() {
        for (provider in providers) {
            val parsed = parse(provider)
            val restored =
                replay.persistAndCalculate(
                    parsed,
                    dataSource,
                    1,
                    FinalAnalysisRouteTestFixture.selectedAt,
                    N.policy,
                    MeetingMode.EITHER,
                )
            assertEquals(parsed.record, restored.record)
            assertEquals(parsed.envelope, restored.envelope)
            assertFalse(parsed.envelope === restored.envelope)
            val first = ParticipantId(FinalAnalysisRouteTestFixture.ir.firstParticipant)
            val result = restored.calculation
            assertEquals(
                listOf(N.thursday, N.friday),
                result.preparedByMode
                    .getValue(MeetingMode.REMOTE)
                    .participants
                    .single {
                        it.participantId == first
                    }.availableTimes,
            )
            assertEquals(
                listOf(N.friday),
                result.preparedByMode
                    .getValue(MeetingMode.IN_PERSON)
                    .participants
                    .single {
                        it.participantId == first
                    }.availableTimes,
            )
            assertEquals(IrCalculationStatus.PARTIAL, result.status)
            assertEquals(
                setOf(N.thursday, N.friday),
                result.recommendations.options
                    .map { it.window }
                    .toSet(),
            )
            assertEquals(
                setOf(MeetingMode.REMOTE),
                result.recommendations.options
                    .single {
                        it.window == N.thursday
                    }.variants
                    .map { it.meetingMode }
                    .toSet(),
            )
            assertEquals(
                setOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON),
                result.recommendations.options
                    .single {
                        it.window == N.friday
                    }.variants
                    .map { it.meetingMode }
                    .toSet(),
            )
            assertTrue(
                result.recommendations.options
                    .flatMap { it.variants }
                    .all { it.participantIds.size == 2 },
            )
        }
        assertEquals(1, count("schedule_ir_envelopes"))
        assertEquals(2, count("schedule_ir_inputs"))
        val stored = assertNotNull(jdbc.queryForObject("SELECT envelope::text FROM schedule_ir_envelopes", String::class.java))
        for (key in listOf("\"quote\"", "\"rawText\"", "\"answerText\"", "\"prompt\"")) assertFalse(stored.contains(key))
    }

    @Test
    fun `CR03 hashes request binding correlation and fixed metadata settings reject drift before publication`() {
        for (provider in providers) {
            val bytes = body(provider)
            val result = response(provider)
            val valid = record(provider, bytes, result)
            assertEquals(valid, mapper.readValue(mapper.writeValueAsBytes(valid), C5CollectedResponseReplay.Record::class.java))
            assertEquals(listOf(40_000L, 60_000L), listOf(valid.callLimitMs, valid.totalLimitMs))
            assertEquals(0, valid.retries)
            assertEquals(0, valid.fallbacks)
            for (invalid in listOf(
                valid.copy(correlationId = UUID(0, 0)),
                valid.copy(requestSha256 = "0".repeat(64)),
                valid.copy(responseSha256 = "0".repeat(64)),
                valid.copy(model = "unapproved-model"),
                valid.copy(callLimitMs = 15_000),
                valid.copy(totalLimitMs = 60_001),
                valid.copy(retries = 1),
                valid.copy(fallbacks = 1),
            )) {
                assertFailsWith<RuntimeException> { replay.parse(invalid, bytes, result, request) }
            }
            val changed = mapper.readTree(bytes) as ObjectNode
            changed.put("unexpected_request_change", true)
            val changedBytes = mapper.writeValueAsBytes(changed)
            assertEquals(
                "OFFLINE_REQUEST_BINDING_MISMATCH",
                assertFailsWith<IllegalArgumentException> {
                    replay.parse(valid.copy(requestSha256 = sha(changedBytes)), changedBytes, result, request)
                }.message,
            )
            assertEquals(
                "OFFLINE_COLLECTED_HASH_MISMATCH",
                assertFailsWith<IllegalArgumentException> {
                    replay.parse(valid, bytes, "invalid JSON".toByteArray(), request)
                }.message,
            )
        }
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `CR04 malformed duplicate trailing and incomplete outer responses never reach publication`() {
        for (provider in providers) {
            val valid = response(provider)
            val incomplete = mapper.readTree(valid) as ObjectNode
            if (provider == AnalysisProvider.GEMINI) {
                (incomplete.path("candidates").path(0) as ObjectNode).put("finishReason", "MAX_TOKENS")
            } else {
                incomplete.put("status", "incomplete")
            }
            val duplicate =
                if (provider ==
                    AnalysisProvider.GEMINI
                ) {
                    "{\"candidates\":[],\"candidates\":[]}"
                } else {
                    "{\"output\":[],\"output\":[]}"
                }
            val invalid =
                listOf("not JSON".toByteArray(), duplicate.toByteArray(), valid + " {}".toByteArray(), mapper.writeValueAsBytes(incomplete))
            for (bytes in invalid) {
                val requestBytes = body(provider)
                assertFailsWith<RuntimeException> { replay.parse(record(provider, requestBytes, bytes), requestBytes, bytes, request) }
            }
        }
        val wrongModel = mapper.readTree(response(AnalysisProvider.OPENAI)) as ObjectNode
        wrongModel.put("model", "different-model")
        val bytes = mapper.writeValueAsBytes(wrongModel)
        val requestBytes = body(AnalysisProvider.OPENAI)
        assertFailsWith<RuntimeException> {
            replay.parse(
                record(AnalysisProvider.OPENAI, requestBytes, bytes),
                requestBytes,
                bytes,
                request,
            )
        }
        // Gemini modelVersion is not enforced by the unchanged decoder; CR03 pins the collected model tuple instead.
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `CR05 strict inner C5 duplicate trailing and unknown fields remain rejected without repair`() {
        val unknown = mapper.readTree(document()) as ObjectNode
        unknown.put("unexpected_declaration", true)
        val invalid = listOf("{\"results\":[],\"results\":[]}", document() + " {}", mapper.writeValueAsString(unknown))
        for (provider in providers) {
            for (text in invalid) {
                val requestBytes = body(provider)
                val responseBytes = response(provider, text)
                val failure =
                    assertFailsWith<NaturalLanguageParserException> {
                        replay.parse(record(provider, requestBytes, responseBytes), requestBytes, responseBytes, request)
                    }
                assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            }
        }
        assertEquals(0, count("schedule_ir_envelopes"))
    }

    @Test
    fun `CR06 existing native source fences still reject stale publication and the send poison is never invoked`() {
        val parsed = parse(AnalysisProvider.OPENAI)
        jdbc.update(
            "UPDATE submission_versions SET revision=revision+1 WHERE id=?",
            N
                .input()
                .inputs
                .first()
                .submissionVersionId,
        )
        assertFailsWith<RuntimeException> {
            replay.persistAndCalculate(parsed, dataSource, 1, FinalAnalysisRouteTestFixture.selectedAt, N.policy, MeetingMode.EITHER)
        }
        assertEquals(0, count("schedule_ir_envelopes"))
        assertEquals(0, count("schedule_ir_inputs"))
        assertEquals(0, sendCalls.get())
        assertTrue(Mockito.mockingDetails(transport).invocations.none { it.method.name == "generate" })
        Mockito.verifyNoInteractions(budget)
    }

    private fun count(table: String): Int = assertNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java))

    private fun model(provider: AnalysisProvider) = if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna"

    private fun body(provider: AnalysisProvider) = transport.body(request, provider, model(provider))

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun record(
        provider: AnalysisProvider,
        input: ByteArray,
        output: ByteArray,
    ) = C5CollectedResponseReplay.Record(
        UUID(0, 71),
        C5CollectedResponseReplay.Origin.SYNTHETIC,
        provider,
        model(provider),
        sha(input),
        sha(output),
    )

    private fun parse(provider: AnalysisProvider): C5CollectedResponseReplay.Parsed {
        val input = body(provider)
        val output = response(provider)
        return replay.parse(record(provider, input, output), input, output, request)
    }

    private fun response(
        provider: AnalysisProvider,
        text: String = document(),
    ): ByteArray =
        mapper.writeValueAsBytes(
            if (provider == AnalysisProvider.GEMINI) {
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "finishReason" to "STOP",
                                "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to text))),
                            ),
                        ),
                    "usageMetadata" to mapOf("promptTokenCount" to 101, "candidatesTokenCount" to 23),
                )
            } else {
                mapOf(
                    "status" to "completed",
                    "error" to null,
                    "incomplete_details" to null,
                    "model" to model(provider),
                    "output" to
                        listOf(
                            mapOf(
                                "type" to "message",
                                "role" to "assistant",
                                "status" to "completed",
                                "content" to listOf(mapOf("type" to "output_text", "text" to text, "refusal" to null)),
                            ),
                        ),
                    "usage" to mapOf("input_tokens" to 101, "output_tokens" to 23),
                )
            },
        )

    /** Only the existing nonpersonal N fixture is translated; no model or historical response is changed. */
    private fun document(): String {
        fun literal(value: Any?): Any? =
            when (value) {
                is Map<*, *> ->
                    if (value.keys == setOf("source_id", "revision", "start_code_point", "end_code_point", "quote")) {
                        linkedMapOf("source_id" to value["source_id"], "revision" to value["revision"], "quote" to value["quote"])
                    } else {
                        value.entries.associate { (key, child) -> key.toString() to literal(child) }
                    }
                is List<*> -> value.map(::literal)
                else -> value
            }

        @Suppress("UNCHECKED_CAST")
        val root = literal(N.document()) as Map<String, Any?>
        val results =
            (root.getValue("results") as List<*>).map { raw ->
                val result = (raw as Map<*, *>).entries.associate { it.key.toString() to it.value }.toMutableMap()
                result["conditions"] =
                    (result.getValue("conditions") as List<*>).map { item ->
                        val condition = (item as Map<*, *>).entries.associate { it.key.toString() to it.value }
                        condition.filterKeys { it !in setOf("scope", "approved_term", "alternative_group", "alternative") } +
                            ("temporal" to mapOf("kind" to "RAW_SCOPE", "scope" to condition["scope"]))
                    }
                val program = result.getValue("program") as Map<*, *>
                val nodes =
                    (program["nodes"] as List<*>).associate { item ->
                        val node = item as Map<*, *>
                        node["id"].toString() to node
                    }

                fun inline(id: Any?): Any? {
                    if (id == null) return null
                    val node = nodes.getValue(id.toString())
                    require(node["operator"] != "CHOICE") { "Synthetic N fixture unexpectedly changed its AST" }
                    return node.entries
                        .filter { it.key !in setOf("id", "choice_group", "branch_labels", "children") }
                        .associate { it.key.toString() to it.value } + ("children" to (node["children"] as List<*>).map(::inline))
                }
                result["program"] =
                    program.entries.associate { (key, value) ->
                        key.toString() to
                            when (key) {
                                "nodes" -> emptyList<Any>()
                                "availability_root", "exclusion_root", "preference_root" -> inline(value)
                                else -> value
                            }
                    }
                result["directional_times"] = emptyList<Any>()
                result
            }
        return mapper.writeValueAsString(root + mapOf("semantic_version" to "schedule-ir-product-6", "results" to results))
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:18-alpine").withImagePullPolicy { false }.apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { true }
        }

        @JvmStatic @AfterAll
        fun stop() = postgres.stop()
    }
}
