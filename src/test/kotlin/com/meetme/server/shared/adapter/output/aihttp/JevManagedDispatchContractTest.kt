package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.adapter.output.classification.JevAmbiguityClassifier
import com.meetme.server.precheck.adapter.output.classification.JevClassifierOptions
import com.meetme.server.precheck.adapter.output.provider.GeminiPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-054/055: loopback transport and the durable-budget port; no live inference or real-price authority. */
class JevManagedDispatchContractTest {
    private val mapper = JsonMapper.builder().build()
    private val raw = "  수요일 오후 7~9시는 가능하고 다른 조건도 그대로 보존해요.  "
    private val date = LocalDate.of(2026, 10, 18)

    @Test
    fun `managed Jev binds one classification slot to the exact sent bytes model endpoint and unchanged source`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope() }).use { endpoint ->
            val result = classifier(endpoint, budget).classify(request())

            assertEquals(AmbiguityAssessment.CLEAR, result.assessment)
            assertEquals(AmbiguityAssessment.CLEAR, result.semanticAssessment)
            assertEquals("TYPESAFE", result.provider)
            assertEquals("jev-1.14.0", result.model)
            val sent = endpoint.requests.single()
            assertEquals("/v1/systemone", sent.path)
            val intent = budget.reserves.single()
            val call = intent.calls.single()
            assertEquals(request().dispatchContext?.execution, intent.execution)
            assertEquals(AiPhysicalOperation.PRECHECK_CLASSIFICATION, call.operation)
            assertEquals(1, call.ordinal)
            assertNull(call.attemptId)
            assertEquals(dispatchHash(sent.body), call.wholePayloadFingerprint)
            val profile = "PRECHECK_CLASSIFICATION\njev-1.14.0\nPOST\n${endpoint.uri("/v1/systemone")}\n16384\nv1"
            assertEquals(dispatchHash(profile.toByteArray(Charsets.UTF_8)), call.profileFingerprint)
            assertEquals(call, budget.claims.single().call)
            val json = mapper.readTree(sent.body)
            assertEquals("jev-1.14.0", json["model"].asText())
            assertEquals(setOf("model", "state", "questions"), json.propertyNames().toSet())
            assertEquals(
                setOf("raw_text", "reference_at", "time_zone", "locale", "search_start_date", "search_end_date"),
                json["state"].propertyNames().toSet(),
            )
            assertEquals(raw, json["state"]["raw_text"].asText())
            assertEquals(dispatchNow.toString(), json["state"]["reference_at"].asText())
            assertEquals(setOf("material_ambiguity"), json["questions"].propertyNames().toSet())
            assertFalse(sent.body.toString(Charsets.UTF_8).contains("ownerToken"))
            val observation = budget.observations.single()
            assertEquals(AiBudgetOutcome.SUCCEEDED, observation.outcome)
            assertEquals(137L, observation.usage.inputTokens)
            assertEquals(7L, observation.usage.outputTokens)
            assertTrue(observation.usage.usageComplete)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["LOW_CONFIDENCE", "TIED_MAXIMUM"])
    fun `managed statistical uncertainty defers without changing the raw semantic evidence`(kind: String) {
        val tied = kind == "TIED_MAXIMUM"
        val probabilities = if (tied) mapOf("CLEAR" to 0.5, "NEEDS_REVIEW" to 0.5, "INDETERMINATE" to 0.0) else distribution(0.6)
        val confidence = if (tied) 0.25 else 0.4
        val threshold = if (tied) 0.2 else 0.8
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope(confidence = confidence, probabilities = probabilities) }).use { endpoint ->
            val result = classifier(endpoint, budget, threshold = threshold).classify(request())

            assertEquals(AmbiguityAssessment.INDETERMINATE, result.assessment)
            assertEquals(AmbiguityClassificationReason.LOW_CONFIDENCE, result.reason)
            assertEquals(AmbiguityAssessment.CLEAR, result.semanticAssessment)
            assertEquals(confidence, result.confidence)
            assertEquals(probabilities.mapKeys { AmbiguityAssessment.valueOf(it.key) }, result.probabilities)
            assertEquals(1, endpoint.requests.size)
            assertEquals(AiBudgetOutcome.SUCCEEDED, budget.observations.single().outcome)
        }
    }

    @Test
    fun `confidence equal to the explicitly supplied synthetic threshold is eligible when the maximum is unique`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope(confidence = 0.4, probabilities = distribution(0.6)) }).use { endpoint ->
            val result = classifier(endpoint, budget, threshold = 0.4).classify(request())

            assertEquals(AmbiguityAssessment.CLEAR, result.assessment)
            assertNull(result.reason)
            assertEquals(0.4, result.confidence)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["NULL_THRESHOLD", "NAN_THRESHOLD", "OUTSIDE_THRESHOLD", "DISABLED", "MISSING_GATE", "OFF_GATE", "MISSING_CONTEXT"],
    )
    fun `incomplete managed configuration never reserves or connects`(configuration: String) {
        val budget = DispatchBudget()
        val connections = AtomicInteger()
        DispatchEndpoint(response = { _, _ -> 200 to envelope() }).use { endpoint ->
            val threshold =
                when (configuration) {
                    "NULL_THRESHOLD" -> null
                    "NAN_THRESHOLD" -> Double.NaN
                    "OUTSIDE_THRESHOLD" -> 1.01
                    else -> 0.8
                }
            val dispatchGate = if (configuration == "MISSING_GATE") null else gate(budget, enabled = configuration != "OFF_GATE")
            val classifier =
                JevAmbiguityClassifier(
                    "synthetic-jev-key",
                    mapper,
                    JevClassifierOptions(endpoint.uri("/v1/systemone"), configuration != "DISABLED", minimumConfidence = threshold),
                    client = trackedClient(connections),
                    dispatchGate = dispatchGate,
                )
            val input = if (configuration == "MISSING_CONTEXT") request().copy(dispatchContext = null) else request()

            val failure = assertFailsWith<AmbiguityClassificationException> { classifier.classify(input) }

            assertEquals("CONFIGURATION", failure.failureCode)
            assertTrue(budget.reserves.isEmpty())
            assertTrue(budget.claims.isEmpty())
            assertEquals(0, connections.get())
            assertTrue(endpoint.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["RESERVATION", "CLAIM"])
    fun `denied reservation or physical claim prevents every provider connection`(denial: String) {
        val budget =
            DispatchBudget().apply {
                denyReserve = denial == "RESERVATION"
                denyClaim = denial == "CLAIM"
            }
        val connections = AtomicInteger()
        DispatchEndpoint(response = { _, _ -> 200 to envelope() }).use { endpoint ->
            val failure =
                assertFailsWith<AmbiguityClassificationException> {
                    classifier(endpoint, budget, client = trackedClient(connections)).classify(request())
                }

            assertEquals("CONFIGURATION", failure.failureCode)
            assertEquals(1, budget.reserves.size)
            assertEquals(if (denial == "CLAIM") 1 else 0, budget.claims.size)
            assertEquals(0, connections.get())
            assertTrue(endpoint.requests.isEmpty())
        }
    }

    @Test
    fun `reusing an already claimed classification identity cannot issue another request`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope() }).use { endpoint ->
            val classifier = classifier(endpoint, budget)
            val input = request()
            classifier.classify(input)

            val failure = assertFailsWith<AmbiguityClassificationException> { classifier.classify(input) }

            assertEquals("CONFIGURATION", failure.failureCode)
            assertEquals(1, endpoint.requests.size)
            assertEquals(2, budget.claims.size)
        }
    }

    @Test
    fun `changed prepared payload cannot be sent under a reservation for the original bytes`() {
        val budget = DispatchBudget()
        val mutatingClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val outbound = chain.request()
                    val body = requireNotNull(outbound.body)
                    val bytes = Buffer().also { body.writeTo(it) }.readByteArray()
                    val altered = bytes.toString(Charsets.UTF_8).replace("ko-KR", "en-US").toByteArray(Charsets.UTF_8)
                    chain.proceed(outbound.newBuilder().post(altered.toRequestBody(body.contentType())).build())
                }.build()
        DispatchEndpoint(response = { _, _ -> 200 to envelope() }).use { endpoint ->
            assertFailsWith<AmbiguityClassificationException> { classifier(endpoint, budget, client = mutatingClient).classify(request()) }

            assertEquals(1, budget.reserves.size)
            assertTrue(endpoint.requests.isEmpty(), "A late body mutation must not reuse permission for a different source/context")
        }
    }

    @Test
    fun `valid usage survives an invalid answer and is observed as a failed physical request`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 200 to envelope(choice = "UNRECOGNIZED") }).use { endpoint ->
            val failure = assertFailsWith<AmbiguityClassificationException> { classifier(endpoint, budget).classify(request()) }

            assertEquals("INVALID_RESPONSE", failure.failureCode)
            val observed = budget.observations.single()
            assertEquals(AiBudgetOutcome.FAILED, observed.outcome)
            assertEquals(AiBudgetFailure.INVALID_RESPONSE, observed.failure)
            assertEquals(137L, observed.usage.inputTokens)
            assertEquals(7L, observed.usage.outputTokens)
            assertTrue(observed.usage.usageComplete)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `unknown input usage remains incomplete evidence while a valid semantic answer is preserved`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = {
            _,
            _,
            ->
            200 to envelope(usage = mapOf("input_tokens" to "unknown", "output_tokens" to 7))
        }).use { endpoint ->
            val result = classifier(endpoint, budget).classify(request())

            assertEquals(AmbiguityAssessment.CLEAR, result.assessment)
            assertNull(result.inputTokens)
            assertFalse(result.usageComplete)
            val observed = budget.observations.single()
            assertNull(observed.usage.inputTokens)
            assertEquals(7L, observed.usage.outputTokens)
            assertFalse(observed.usage.usageComplete, "Usage observation cannot authorize a complete financial settlement")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CLEAR", "NEEDS_REVIEW", "LOW_CONFIDENCE"])
    fun `real two stage pipeline preserves the original through Jev and only required Gemini children`(route: String) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { path, _ ->
            200 to
                when {
                    path.endsWith(":countTokens") -> "{\"totalTokens\":256}"
                    path.endsWith(":generateContent") -> generationResponse()
                    route == "LOW_CONFIDENCE" -> envelope(confidence = 0.4, probabilities = distribution(0.6))
                    route == "NEEDS_REVIEW" -> envelope(choice = "NEEDS_REVIEW", probabilities = distribution(0.9, "NEEDS_REVIEW"))
                    else -> envelope()
                }
        }).use { endpoint ->
            val dispatchGate = gate(budget)
            val classifier = classifier(endpoint, budget)
            val generator =
                GeminiPreSubmissionCheckProvider.runtime(
                    GeminiProperties(apiKey = "synthetic-gemini-key"),
                    mapper,
                    PrecheckProviderRuntimeProperties(true),
                    endpoint = endpoint.origin,
                    dispatchGate = dispatchGate,
                )
            val provider = TwoStagePreSubmissionCheckProvider(classifier, generator, physicalDispatch = dispatchGate)
            val input = request()
            val result =
                provider.check(
                    PrecheckProviderRequest(
                        UUID(0, 124),
                        raw,
                        dispatchNow,
                        input.timeZone,
                        input.locale,
                        date,
                        date.plusDays(5),
                        Duration.ofSeconds(3),
                        roomId = UUID(0, 123),
                        executionGeneration = 7,
                        dispatchContext = input.dispatchContext,
                    ),
                )

            assertEquals(PrecheckVerdict.CLEAR, result.verdict)
            val expected =
                if (route == "CLEAR") {
                    listOf(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                } else {
                    listOf(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        AiPhysicalOperation.PRECHECK_COUNT,
                        AiPhysicalOperation.PRECHECK_GENERATION,
                    )
                }
            assertEquals(expected, budget.reserves.flatMap { it.calls }.map { it.operation })
            assertEquals(expected.size, endpoint.requests.size)
            assertEquals(expected.size, budget.claims.size)
            assertEquals(expected.size, budget.observations.size)
            endpoint.requests.forEach { sent ->
                assertTrue(sent.body.toString(Charsets.UTF_8).contains(raw), "Every stage must retain the unchanged original input")
            }
        }
    }

    private fun request(): AmbiguityClassificationRequest {
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
        return AmbiguityClassificationRequest(
            raw,
            dispatchNow,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(5),
            Duration.ofSeconds(3),
            context,
        )
    }

    private fun classifier(
        endpoint: DispatchEndpoint,
        budget: DispatchBudget,
        threshold: Double? = 0.8,
        client: OkHttpClient = OkHttpClient(),
    ) = JevAmbiguityClassifier(
        "synthetic-jev-key",
        mapper,
        JevClassifierOptions(endpoint.uri("/v1/systemone"), true, minimumConfidence = threshold),
        client = client,
        dispatchGate = gate(budget),
    )

    private fun trackedClient(connections: AtomicInteger): OkHttpClient =
        OkHttpClient
            .Builder()
            .eventListener(
                object : EventListener() {
                    override fun connectStart(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                    ) {
                        connections.incrementAndGet()
                    }
                },
            ).build()

    private fun distribution(
        maximum: Double,
        selected: String = "CLEAR",
    ): Map<String, Double> =
        listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").associateWith { if (it == selected) maximum else (1.0 - maximum) / 2.0 }

    private fun envelope(
        choice: String = "CLEAR",
        confidence: Double = 0.85,
        probabilities: Map<String, Double> = distribution(0.9),
        usage: Map<String, Any> = mapOf("input_tokens" to 137, "output_tokens" to 7),
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "model" to "jev-1.14.0",
                "answers" to
                    mapOf(
                        "material_ambiguity" to
                            mapOf("type" to "choice", "choice" to choice, "probabilities" to probabilities, "confidence" to confidence),
                    ),
                "usage" to usage,
            ),
        )

    private fun generationResponse(): String =
        mapper.writeValueAsString(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("text" to "{\"verdict\":\"CLEAR\"}")))),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 256, "candidatesTokenCount" to 7, "thoughtsTokenCount" to 0),
            ),
        )
}
