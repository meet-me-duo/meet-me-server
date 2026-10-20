package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.adapter.output.provider.LunaPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Responses transport over loopback, shared dispatch port and existing personal verdict contract only. */
@ExtendWith(OutputCaptureExtension::class)
class LunaPrecheckManagedDispatchContractTest {
    private val mapper = JsonMapper.builder().build()
    private val raw = "내일 8시 온라인 가능하고 강남역은 제외해요."
    private val date = LocalDate.of(2026, 10, 18)

    @Test
    fun `one Standard low reasoning Responses POST preserves input and reserves only its generation operation`() {
        val budget = DispatchBudget()
        LunaPrecheckLoopback { _, _ -> 200 to lunaPersonalEnvelope(lunaPersonalQuestion(raw)) }.use { endpoint ->
            val result = provider(endpoint, budget).check(request())

            assertEquals(PrecheckVerdict.NEEDS_CHOICE, result.verdict)
            assertEquals("OPENAI", result.provider)
            assertEquals("gpt-6-luna", result.model)
            assertEquals("8시", result.sourceExcerpt)
            assertEquals(listOf("A", "B"), result.choices.map { it.id })
            assertEquals(100L, result.inputTokens)
            assertEquals(30L, result.outputTokens)
            val sent = endpoint.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/v1/responses", sent.path)
            assertEquals("Bearer synthetic-luna-personal-key", sent.authorization)
            val body = mapper.readTree(sent.body)
            assertEquals("gpt-6-luna", body["model"].asText())
            assertFalse(body["store"].asBoolean())
            assertEquals("default", body["service_tier"].asText())
            assertEquals("low", body["reasoning"]["effort"].asText())
            assertEquals(2048, body["max_output_tokens"].asInt())
            val input = mapper.readTree(body["input"].asText())
            assertEquals(
                setOf("input_ref", "raw_text", "reference_at", "time_zone", "locale", "search_start_date", "search_end_date"),
                input.propertyNames().toSet(),
            )
            assertEquals(raw, input["raw_text"].asText())
            assertEquals(dispatchNow.toString(), input["reference_at"].asText())
            assertTrue(body["instructions"].asText().contains("untrusted"))
            assertEquals("json_schema", body["text"]["format"]["type"].asText())
            assertTrue(body["text"]["format"]["strict"].asBoolean())
            assertStrictObjects(body["text"]["format"]["schema"])
            val call =
                budget.reserves
                    .single()
                    .calls
                    .single()
            assertEquals(AiPhysicalOperation.PRECHECK_GENERATION, call.operation)
            assertEquals(1, call.ordinal)
            assertNull(call.attemptId)
            assertEquals(dispatchHash(sent.body), call.wholePayloadFingerprint)
            val profile = "PRECHECK_GENERATION\ngpt-6-luna\nPOST\n${endpoint.uri}\n16384\nv1"
            assertEquals(dispatchHash(profile.toByteArray(Charsets.UTF_8)), call.profileFingerprint)
            assertEquals(call, budget.claims.single().call)
            assertTrue(budget.reserves.flatMap { it.calls }.none { it.operation == AiPhysicalOperation.PRECHECK_COUNT })
            val observed = budget.observations.single()
            assertEquals(AiBudgetOutcome.SUCCEEDED, observed.outcome)
            assertEquals(100L, observed.usage.inputTokens)
            assertEquals(30L, observed.usage.outputTokens)
            assertEquals(10L, observed.usage.reasoningTokens)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["RESERVE", "CLAIM", "OFF", "MISSING_CONTEXT", "WRONG_OUTPUT_CAP"])
    fun `disabled or unauthorized Luna generation cannot issue a provider request`(reason: String) {
        val budget =
            DispatchBudget().apply {
                denyReserve = reason == "RESERVE"
                denyClaim = reason == "CLAIM"
            }
        LunaPrecheckLoopback { _, _ -> 200 to lunaPersonalEnvelope() }.use { endpoint ->
            val request =
                when (reason) {
                    "MISSING_CONTEXT" -> request().copy(dispatchContext = null)
                    "WRONG_OUTPUT_CAP" -> request().copy(maximumOutputTokens = 2049)
                    else -> request()
                }
            val failure =
                assertFailsWith<PrecheckProviderException> { provider(endpoint, budget, enabled = reason != "OFF").check(request) }

            assertEquals("CONFIGURATION", failure.failureCode)
            assertTrue(endpoint.requests.isEmpty())
            if (reason in setOf("OFF", "MISSING_CONTEXT", "WRONG_OUTPUT_CAP")) assertTrue(budget.reserves.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["INVALID_PAYLOAD", "INCOMPLETE", "REFUSAL", "EXCESS_OUTPUT"])
    fun `valid usage is observed even when Luna cannot publish a valid personal verdict`(kind: String) {
        val budget = DispatchBudget()
        val response =
            when (kind) {
                "INVALID_PAYLOAD" -> lunaPersonalEnvelope("{\"verdict\":\"UNRECOGNIZED\"}")
                "INCOMPLETE" -> lunaPersonalEnvelope().replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")
                "REFUSAL" -> lunaPersonalEnvelope().replace("\"type\":\"output_text\"", "\"type\":\"refusal\"")
                else -> lunaPersonalEnvelope(outputTokens = 2049)
            }
        LunaPrecheckLoopback { _, _ -> 200 to response }.use { endpoint ->
            assertEquals(
                "INVALID_RESPONSE",
                assertFailsWith<PrecheckProviderException> { provider(endpoint, budget).check(request()) }.failureCode,
            )

            assertEquals(1, endpoint.requests.size)
            val observed = budget.observations.single()
            assertEquals(AiBudgetOutcome.FAILED, observed.outcome)
            assertEquals(AiBudgetFailure.INVALID_RESPONSE, observed.failure)
            assertEquals(100L, observed.usage.inputTokens)
            assertEquals(if (kind == "EXCESS_OUTPUT") 2049L else 30L, observed.usage.outputTokens)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [429, 503])
    fun `Retry After zero cannot create a hidden second Luna request`(status: Int) {
        val budget = DispatchBudget()
        LunaPrecheckLoopback(retryAfterZero = true) { _, _ ->
            status to "{\"error\":{\"message\":\"private-provider-error\"}}"
        }.use { endpoint ->
            assertFailsWith<PrecheckProviderException> { provider(endpoint, budget).check(request()) }
            assertEquals(1, endpoint.requests.size)
            assertEquals(1, budget.claims.size)
            assertEquals(1, budget.observations.size)
        }
    }

    @Test
    fun `Jev and Luna generation consume a shared deadline rather than receiving fresh stage time`() {
        val budget = DispatchBudget()
        LunaPrecheckLoopback(delayMillis = 350) { _, _ -> 200 to lunaPersonalEnvelope() }.use { endpoint ->
            val classifier =
                AmbiguityClassificationPort {
                    Thread.sleep(180)
                    AmbiguityClassificationResult(AmbiguityAssessment.NEEDS_REVIEW, confidence = 0.9)
                }
            val gate = gate(budget)
            val pipeline = TwoStagePreSubmissionCheckProvider(classifier, provider(endpoint, budget), physicalDispatch = gate)
            val started = System.nanoTime()

            assertEquals(
                "TIMEOUT",
                assertFailsWith<PrecheckProviderException> { pipeline.check(request().copy(timeout = Duration.ofMillis(400))) }.failureCode,
            )

            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
            assertEquals(1, endpoint.requests.size)
            assertEquals(listOf(AiPhysicalOperation.PRECHECK_GENERATION), budget.reserves.flatMap { it.calls }.map { it.operation })
        }
    }

    @Test
    fun `provider diagnostics and original private text never enter logs or exception causes`(captured: CapturedOutput) {
        val privateError = "LUNA_PRIVATE_RESPONSE_SENTINEL synthetic-luna-personal-key"
        LunaPrecheckLoopback { _, _ -> 401 to "{\"error\":{\"message\":\"$privateError\"}}" }.use { endpoint ->
            val failure = assertFailsWith<PrecheckProviderException> { provider(endpoint, DispatchBudget()).check(request()) }

            assertNull(failure.cause)
            for (privateValue in listOf(raw, privateError, "synthetic-luna-personal-key")) {
                assertFalse((captured.all + failure.toString()).contains(privateValue))
            }
        }
    }

    private fun provider(
        endpoint: LunaPrecheckLoopback,
        budget: DispatchBudget,
        enabled: Boolean = true,
    ) = LunaPreSubmissionCheckProvider.runtime(
        OpenAiProperties(apiKey = "synthetic-luna-personal-key"),
        mapper,
        PrecheckProviderRuntimeProperties(enabled),
        transport = PrecheckProviderHttpTransport(OkHttpClient()),
        endpoint = endpoint.uri,
        dispatchGate = gate(budget),
    )

    private fun request(): PrecheckProviderRequest {
        val original = dispatchContext()
        val context =
            original.copy(
                execution =
                    original.execution.copy(
                        sourceFingerprint = dispatchFramedHash(raw),
                        contextFingerprint =
                            dispatchFramedHash(
                                dispatchNow.toString(),
                                "Asia/Seoul",
                                "ko-KR",
                                date.toString(),
                                date.plusDays(5).toString(),
                            ),
                    ),
            )
        return PrecheckProviderRequest(
            UUID(0, 124),
            raw,
            dispatchNow,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(5),
            Duration.ofSeconds(3),
            roomId = UUID(0, 123),
            executionGeneration = 7,
            dispatchContext = context,
        )
    }

    private fun assertStrictObjects(node: JsonNode) {
        if (node["type"]?.let { it.isString && it.asText() == "object" } == true) {
            assertEquals(node["properties"].propertyNames().toSet(), node["required"].asSequence().map { it.asText() }.toSet())
            assertFalse(node["additionalProperties"].asBoolean())
        }
        if (node.isObject || node.isArray) node.forEach { assertStrictObjects(it) }
    }
}

internal fun lunaPersonalEnvelope(
    payload: String = "{\"verdict\":\"CLEAR\",\"prompt\":null,\"sourceExcerpt\":null,\"choices\":null}",
    outputTokens: Int = 30,
): String =
    JsonMapper.builder().build().writeValueAsString(
        mapOf(
            "status" to "completed",
            "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload)))),
            "usage" to
                mapOf(
                    "input_tokens" to 100,
                    "output_tokens" to outputTokens,
                    "output_tokens_details" to mapOf("reasoning_tokens" to 10),
                ),
        ),
    )

internal fun lunaPersonalQuestion(
    raw: String,
    resolvedDate: String = "2026년 10월 19일",
): String =
    JsonMapper.builder().build().writeValueAsString(
        mapOf(
            "verdict" to "NEEDS_CHOICE",
            "prompt" to "가능한 8시는 오전인가요, 오후인가요?",
            "sourceExcerpt" to "8시",
            "choices" to
                listOf(
                    mapOf("id" to "A", "label" to "오전 8시", "proposedText" to raw.replace("내일", resolvedDate).replace("8시", "오전 8시")),
                    mapOf("id" to "B", "label" to "오후 8시", "proposedText" to raw.replace("내일", resolvedDate).replace("8시", "오후 8시")),
                ),
        ),
    )

internal class LunaPrecheckLoopback(
    private val delayMillis: Long = 0,
    private val retryAfterZero: Boolean = false,
    private val response: (String, Int) -> Pair<Int, String>,
) : AutoCloseable {
    data class Received(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: ByteArray,
    )

    val requests: MutableList<Received> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val uri: URI get() = URI("http://127.0.0.1:${server.address.port}/v1/responses")

    init {
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.use { it.readAllBytes() }
                requests +=
                    Received(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
                val (status, text) = response(exchange.requestURI.path, requests.size)
                if (delayMillis > 0) Thread.sleep(delayMillis)
                if (retryAfterZero) exchange.responseHeaders.add("Retry-After", "0")
                exchange.responseHeaders.add("Content-Type", "application/json")
                val bytes = text.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: IOException) {
                // The caller may cancel a bounded request; fixture diagnostics must contain no private body.
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
