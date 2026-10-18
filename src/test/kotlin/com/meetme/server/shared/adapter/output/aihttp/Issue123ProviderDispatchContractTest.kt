package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierOptions
import com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier
import com.meetme.server.precheck.adapter.output.provider.GeminiPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import okhttp3.OkHttpClient
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** All five real transport operations use loopback only. Synthetic port is not billing/PG proof. */
class Issue123ProviderDispatchContractTest {
    enum class Route { DECISIONS, PERSONAL_COUNT_AND_GENERATION, FINAL_PRIMARY, FINAL_FALLBACK }

    private val mapper = JsonMapper.builder().build()
    private val raw = "  synthetic original input  "
    private val date = LocalDate.of(2026, 10, 18)
    private val key = "synthetic-issue123-not-a-real-key"

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `AD04 actual adapters reserve claim and observe every physical leg against exact wire`(route: Route) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
            invoke(route, server, gate(budget), context(route))
            val expected = operations(route)
            assertEquals(expected.size, server.requests.size)
            assertEquals(expected, budget.reserves.flatMap { it.calls }.map { it.operation })
            assertEquals(expected.size, budget.claims.size)
            assertEquals(expected.size, budget.observations.size)
            assertEquals(
                expected.size,
                budget.claims
                    .map { it.reservationId }
                    .toSet()
                    .size,
            )
            for ((index, request) in server.requests.withIndex()) {
                val spec = budget.reserves.flatMap { it.calls }[index]
                assertEquals(dispatchHash(request.body), spec.wholePayloadFingerprint)
                val body = request.body.toString(Charsets.UTF_8)
                assertFalse(body.contains(context(route).execution.roomId.toString()))
                assertFalse(body.contains(context(route).execution.ownerToken.toString()))
                assertFalse(body.contains("dispatchContext"))
                assertFalse(body.contains("reservationId"))
            }
            assertTrue(budget.observations.all { it.outcome == AiBudgetOutcome.SUCCEEDED })
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `AD02 missing trusted reservation denies actual adapter request without paid fallback`(route: Route) {
        val budget = DispatchBudget().apply { denyReserve = true }
        DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), context(route)) }.exceptionOrNull()
            assertTrue(failure != null)
            assertConfigurationFailure(failure)
            assertEquals(1, budget.reserves.size)
            assertTrue(budget.claims.isEmpty())
            assertTrue(server.requests.isEmpty())
            assertTrue(budget.observations.isEmpty())
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `AD02 actual adapter cannot bypass ON gate with missing lineage`(route: Route) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), null) }.exceptionOrNull()
            assertTrue(failure != null)
            assertConfigurationFailure(failure)
            assertTrue(server.requests.isEmpty())
            assertTrue(budget.reserves.isEmpty())
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `AD01 OFF retains legacy payload trace and adds no new authority calls`(route: Route) {
        val budget = DispatchBudget().apply { reserveFailure = true }
        val traces = mutableListOf<List<Pair<String, String>>>()
        repeat(2) { mode ->
            DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
                invoke(route, server, if (mode == 0) null else gate(budget, false), null)
                traces += server.requests.map { it.path to it.body.toString(Charsets.UTF_8) }
            }
        }
        assertEquals(operations(route).size, traces[0].size)
        assertEquals(traces[0], traces[1])
        assertTrue(budget.reserves.isEmpty())
        assertTrue(budget.claims.isEmpty())
        assertTrue(budget.observations.isEmpty())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `US04 rejected observation returns safe failure and never a successful parse or CLEAR`(route: Route) {
        val budget = DispatchBudget().apply { observationAccepted = false }
        DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), context(route)) }.exceptionOrNull()
            assertTrue(failure != null)
            assertConfigurationFailure(failure)
            assertEquals(1, server.requests.size, "Personal count record failure must stop generation")
            assertEquals(1, budget.observations.size)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `US04 thrown observation failure cannot trigger another provider request`(route: Route) {
        val budget = DispatchBudget().apply { observationFailure = true }
        DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), context(route)) }.exceptionOrNull()
            assertTrue(failure != null)
            assertConfigurationFailure(failure)
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.observations.size)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `ID07 actual adapters cannot hide a second wire request behind one 503 Call`(route: Route) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 503 to "{}" }, retryAfter = true).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), context(route)) }.exceptionOrNull()
            assertTrue(failure != null)
            assertProviderStatus(failure, "SERVER", ParserFailureKind.SERVER)
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.claims.size)
            assertEquals(1, budget.reserves.size)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `ID07 actual 429 stays retryable provider rate limit and does not hide another send`(route: Route) {
        val budget = DispatchBudget()
        DispatchEndpoint(response = {
            _,
            _,
            ->
            429 to """{"error":{"code":429,"status":"RATE_LIMIT_EXCEEDED","type":"rate_limit_error"}}"""
        }, retryAfter = true).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), context(route)) }.exceptionOrNull()
            assertTrue(failure != null)
            assertProviderStatus(failure, "RATE_LIMITED", ParserFailureKind.RATE_LIMIT)
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.claims.size)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `ID02 actual adapter rejects another source before budget and network`(route: Route) {
        assertContextMismatch(route, true)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `ID02 actual adapter rejects another reference context before budget and network`(route: Route) {
        assertContextMismatch(route, false)
    }

    private fun assertContextMismatch(
        route: Route,
        source: Boolean,
    ) {
        val budget = DispatchBudget()
        val valid = context(route)
        val invalid =
            valid.copy(
                execution =
                    if (source) {
                        valid.execution.copy(
                            sourceFingerprint = "different",
                        )
                    } else {
                        valid.execution.copy(contextFingerprint = "different")
                    },
            )
        DispatchEndpoint(response = { path, _ -> 200 to response(route, path) }).use { server ->
            val failure = runCatching { invoke(route, server, gate(budget), invalid) }.exceptionOrNull()
            assertTrue(failure != null)
            assertConfigurationFailure(failure)
            assertTrue(budget.reserves.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    private fun assertProviderStatus(
        failure: Throwable,
        code: String,
        kind: ParserFailureKind,
    ) {
        when (failure) {
            is AmbiguityClassificationException -> assertEquals(code, failure.failureCode)
            is PrecheckProviderException -> assertEquals(code, failure.failureCode)
            is NaturalLanguageParserException -> {
                assertEquals(kind, failure.kind)
                assertTrue(failure.retryable, "An actual provider transient remains distinct from accounting CONFIGURATION")
            }
            else -> fail("Expected bounded provider status, not ${failure.javaClass.simpleName}")
        }
    }

    internal fun context(route: Route): AiProviderDispatchContext {
        val final = route in setOf(Route.FINAL_PRIMARY, Route.FINAL_FALLBACK)
        val source = if (final) dispatchFramedHash("final-source-v1", UUID(0, 888).toString(), raw) else dispatchFramedHash(raw)
        val contextual =
            if (final) {
                dispatchFramedHash(
                    "final-context-v1",
                    "Asia/Seoul",
                    date.toString(),
                    date.plusDays(5).toString(),
                    UUID(0, 888).toString(),
                    "ko-KR",
                    date.toString(),
                )
            } else {
                dispatchFramedHash(dispatchNow.toString(), "Asia/Seoul", "ko-KR", date.toString(), date.plusDays(5).toString())
            }
        val original = dispatchContext(final)
        return original.copy(execution = original.execution.copy(sourceFingerprint = source, contextFingerprint = contextual))
    }

    private fun operations(route: Route): List<AiPhysicalOperation> =
        when (route) {
            Route.DECISIONS -> listOf(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
            Route.PERSONAL_COUNT_AND_GENERATION -> listOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)
            Route.FINAL_PRIMARY -> listOf(AiPhysicalOperation.FINAL_PRIMARY)
            Route.FINAL_FALLBACK -> listOf(AiPhysicalOperation.FINAL_FALLBACK)
        }

    private fun invoke(
        route: Route,
        server: DispatchEndpoint,
        dispatch: AiHttpDispatchGate?,
        context: AiProviderDispatchContext?,
    ) {
        when (route) {
            Route.DECISIONS ->
                OpenAiAmbiguityClassifier(
                    key,
                    mapper,
                    AmbiguityClassifierOptions(server.uri("/v1/decisions"), true, 0.8),
                    dispatchGate = dispatch,
                ).classify(
                    AmbiguityClassificationRequest(
                        raw,
                        dispatchNow,
                        ZoneId.of("Asia/Seoul"),
                        "ko-KR",
                        date,
                        date.plusDays(5),
                        Duration.ofSeconds(3),
                        dispatchContext = context,
                    ),
                )
            Route.PERSONAL_COUNT_AND_GENERATION ->
                GeminiPreSubmissionCheckProvider
                    .runtime(
                        GeminiProperties(apiKey = key),
                        mapper,
                        PrecheckProviderRuntimeProperties(true),
                        endpoint = server.origin,
                        dispatchGate = dispatch,
                    ).check(
                        PrecheckProviderRequest(
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
                        ),
                    )
            Route.FINAL_PRIMARY ->
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = key),
                    mapper,
                    server.origin,
                    dispatchGate = dispatch,
                ).parse(batch(context))
            Route.FINAL_FALLBACK ->
                OpenAiLunaNaturalLanguageParserAdapter(
                    OpenAiProperties(apiKey = key),
                    mapper,
                    OkHttpClient(),
                    server.uri("/v1/responses"),
                    dispatchGate = dispatch,
                ).parse(batch(context))
        }
    }

    internal fun batch(context: AiProviderDispatchContext?) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            date,
            date.plusDays(5),
            listOf(NaturalLanguageInput(UUID(0, 888).toString(), raw, Locale.forLanguageTag("ko-KR"), date)),
            callTimeout = Duration.ofSeconds(3),
            dispatchContext = context,
        )

    private fun response(
        route: Route,
        path: String,
    ): String {
        if (path.endsWith(":countTokens")) return "{\"totalTokens\":256}"
        if (route ==
            Route.DECISIONS
        ) {
            return """{"model":"gpt-6-luna","answers":[{"type":"choice","name":"material_ambiguity","choice":"CLEAR","confidence":0.9,""" +
                """"probabilities":[{"value":"CLEAR","probability":0.9},{"value":"NEEDS_REVIEW","probability":0.05},""" +
                """{"value":"INDETERMINATE","probability":0.05}]}],"usage":{"input_tokens":137,"output_tokens":7,"total_tokens":144}}"""
        }
        val payload =
            if (route == Route.PERSONAL_COUNT_AND_GENERATION) {
                "{\"verdict\":\"CLEAR\"}"
            } else {
                """{"schema_version":"3","results":[{"input_ref":"${UUID(
                    0,
                    888,
                )}","rejection_code":null,"conditions":[{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-18","day_of_week":null,"start_time":"19:00","end_time":"21:00"}]}]}"""
            }
        return if (route == Route.FINAL_FALLBACK) {
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

    private fun assertConfigurationFailure(failure: Throwable) {
        when (failure) {
            is AmbiguityClassificationException -> assertEquals("CONFIGURATION", failure.failureCode)
            is PrecheckProviderException -> assertEquals("CONFIGURATION", failure.failureCode)
            is NaturalLanguageParserException -> {
                assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
                assertFalse(failure.retryable)
            }
            else -> fail("Expected a safe CONFIGURATION failure, not ${failure.javaClass.simpleName}")
        }
    }
}
