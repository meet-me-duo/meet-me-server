package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.Endpoint
import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.envelope
import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.mapper
import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.request
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DC01,05,08-11: local validation, bounded transport and privacy; all credentials are synthetic. */
@ExtendWith(OutputCaptureExtension::class)
class OpenAiAmbiguityClassificationSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = ["OFF", "KEY", "THRESHOLD_MISSING", "NAN", "INFINITY", "NEGATIVE", "ABOVE_ONE"])
    fun `DCS01 unavailable configuration sends nothing and records one safe failure`(invalid: String) {
        Endpoint().use { e ->
            val threshold =
                when (invalid) {
                    "THRESHOLD_MISSING" -> null
                    "NAN" -> Double.NaN
                    "INFINITY" -> Double.POSITIVE_INFINITY
                    "NEGATIVE" -> -0.1
                    "ABOVE_ONE" -> 1.1
                    else -> 0.8
                }
            val classifier =
                e.classifier(
                    enabled = invalid != "OFF",
                    threshold = threshold,
                    key =
                        if (invalid ==
                            "KEY"
                        ) {
                            " "
                        } else {
                            DecisionClassificationFixtures.KEY
                        },
                )
            assertEquals("CONFIGURATION", assertFailsWith<AmbiguityClassificationException> { classifier.classify(request) }.failureCode)
            assertTrue(e.sent.isEmpty())
            assertEquals(
                0.0,
                e.registry
                    .find("meetme.precheck.classification.http.attempts")
                    .counter()
                    ?.count() ?: 0.0,
            )
            assertEquals(
                1.0,
                e.registry
                    .find(
                        "meetme.precheck.classification.results",
                    ).tags("outcome", "FAILED", "failure", "CONFIGURATION")
                    .counter()
                    ?.count(),
            )
            assertEquals(
                1L,
                e.registry
                    .find("meetme.precheck.classification.duration")
                    .timer()
                    ?.count(),
            )
            listOf("input", "output").forEach {
                assertEquals(
                    0L,
                    e.registry
                        .find("meetme.precheck.classification.usage.$it.tokens")
                        .summary()
                        ?.count() ?: 0L,
                )
                assertEquals(
                    0.0,
                    e.registry
                        .find("meetme.precheck.classification.usage.$it.unknown")
                        .counter()
                        ?.count() ?: 0.0,
                )
            }
            assertEquals(
                0.0,
                e.registry
                    .find("meetme.precheck.classification.usage.incomplete")
                    .counter()
                    ?.count() ?: 0.0,
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["EMPTY", "501", "RAW_NUL", "LOCALE_EMPTY", "LOCALE_NUL", "LOCALE_INVALID", "RANGE", "REQUEST_BYTES", "ZERO", "NEGATIVE"],
    )
    fun `DCS02 invalid original context size or timeout is rejected before HTTP`(invalid: String) {
        val input =
            when (invalid) {
                "EMPTY" -> request.copy(rawText = "")
                "501" -> request.copy(rawText = "😀".repeat(501))
                "RAW_NUL" -> request.copy(rawText = "원문\u0000보존")
                "LOCALE_EMPTY" -> request.copy(locale = "")
                "LOCALE_NUL" -> request.copy(locale = "ko-\u0000KR")
                "LOCALE_INVALID" -> request.copy(locale = "ko_KR")
                "RANGE" -> request.copy(searchEndDate = request.searchStartDate.minusDays(1))
                "REQUEST_BYTES" -> request.copy(locale = "en-x-" + List(5000) { "aaaa" }.joinToString("-"))
                "ZERO" -> request.copy(timeout = Duration.ZERO)
                else -> request.copy(timeout = Duration.ofMillis(-1))
            }
        Endpoint().use { e ->
            assertEquals(
                if (invalid in
                    setOf("ZERO", "NEGATIVE")
                ) {
                    "TIMEOUT"
                } else {
                    "INVALID_REQUEST"
                },
                assertFailsWith<AmbiguityClassificationException> { e.classifier().classify(input) }.failureCode,
            )
            assertTrue(e.sent.isEmpty())
        }
    }

    @Test
    fun `DCS03 exact five hundred codepoints preserve emoji and whitespace`() {
        val raw = " " + "😀".repeat(498) + " "
        Endpoint().use { e ->
            assertEquals(
                AmbiguityAssessment.CLEAR,
                assertDoesNotThrow<AmbiguityClassificationResult> {
                    e.classifier().classify(request.copy(rawText = raw))
                }.assessment,
            )
            assertEquals(raw, mapper.readTree(mapper.readTree(e.sent.single().body).path("input").asText()).path("raw_text").asText())
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [302, 307, 308, 400, 401, 403, 404, 429, 500, 503])
    fun `DCS04 HTTP errors never redirect retry or call a fallback`(status: Int) {
        Endpoint(status = status, body = "PRIVATE_RAW_RESPONSE").use { e ->
            val error = assertFailsWith<AmbiguityClassificationException> { e.classifier(permissiveClient = true).classify(request) }
            assertEquals(
                when (status) {
                    400, 401, 403 -> "CONFIGURATION"
                    429 -> "RATE_LIMITED"
                    in 500..599 -> "SERVER"
                    else -> "INVALID_RESPONSE"
                },
                error.failureCode,
            )
            assertEquals(error.failureCode, error.message)
            assertEquals(1, e.sent.size)
            assertEquals(
                1.0,
                e.registry
                    .find("meetme.precheck.classification.http.attempts")
                    .counter()
                    ?.count(),
            )
            listOf("input", "output").forEach {
                assertEquals(
                    1.0,
                    e.registry
                        .find("meetme.precheck.classification.usage.$it.unknown")
                        .counter()
                        ?.count(),
                )
            }
            assertEquals(
                1.0,
                e.registry
                    .find("meetme.precheck.classification.usage.incomplete")
                    .counter()
                    ?.count(),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["STALL", "TRICKLE"])
    fun `DCS05 one absolute deadline includes the entire response body`(mode: String) {
        Endpoint(stall = mode == "STALL", slowChunks = mode == "TRICKLE").use { e ->
            val started = System.nanoTime()
            assertEquals(
                "TIMEOUT",
                assertFailsWith<AmbiguityClassificationException> {
                    e.classifier().classify(request.copy(timeout = Duration.ofMillis(250)))
                }.failureCode,
            )
            assertTrue(e.bodyStarted.count == 0L)
            assertEquals(1, e.sent.size)
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
            listOf("input", "output").forEach {
                assertEquals(
                    1.0,
                    e.registry
                        .find("meetme.precheck.classification.usage.$it.unknown")
                        .counter()
                        ?.count(),
                )
            }
        }
    }

    @Test
    fun `DCS06 oversized actual response fails without retry and never trusts a truncated prefix`() {
        Endpoint(body = envelope() + " ".repeat(16 * 1024)).use { e ->
            assertEquals(
                "INVALID_RESPONSE",
                assertFailsWith<AmbiguityClassificationException> { e.classifier().classify(request) }.failureCode,
            )
            assertEquals(1, e.sent.size)
        }
    }

    @Test
    fun `DCS07 interrupted connection is one attempt and does not invent usage`() {
        Endpoint(disconnect = true).use { e ->
            assertEquals(
                "CONNECTION",
                assertFailsWith<AmbiguityClassificationException> {
                    e.classifier(permissiveClient = true).classify(request)
                }.failureCode,
            )
            assertEquals(1, e.sent.size)
            listOf("input", "output").forEach {
                assertEquals(
                    1.0,
                    e.registry
                        .find("meetme.precheck.classification.usage.$it.unknown")
                        .counter()
                        ?.count(),
                )
            }
        }
    }

    @Test
    fun `DCS08 default off and fixed canonical profile are inspected without opening a socket`() {
        val captured = CopyOnWriteArrayList<okhttp3.Request>()
        val timeouts = CopyOnWriteArrayList<Long>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    captured += chain.request()
                    val timeout = chain.call().timeout()
                    val callBudget = timeout.timeoutNanos().takeIf { it > 0 }
                    val absoluteBudget = if (timeout.hasDeadline()) timeout.deadlineNanoTime() - System.nanoTime() else null
                    timeouts += listOfNotNull(callBudget, absoluteBudget).minOrNull() ?: 0L
                    Response
                        .Builder()
                        .request(
                            chain.request(),
                        ).protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("synthetic no network")
                        .body(envelope().toResponseBody())
                        .build()
                }.build()
        val disabled = OpenAiAmbiguityClassifier(DecisionClassificationFixtures.KEY, mapper, client = client)
        assertEquals("CONFIGURATION", assertFailsWith<AmbiguityClassificationException> { disabled.classify(request) }.failureCode)
        assertTrue(captured.isEmpty())
        val enabled =
            OpenAiAmbiguityClassifier(
                DecisionClassificationFixtures.KEY,
                mapper,
                AmbiguityClassifierOptions(enabled = true, minimumConfidence = 0.8),
                client,
            )
        assertDoesNotThrow<AmbiguityClassificationResult> { enabled.classify(request.copy(timeout = Duration.ofSeconds(30))) }
        assertEquals("https://api.openai.com/v1/decisions", captured.single().url.toString())
        assertNull(captured.single().url.query)
        assertTrue(timeouts.single() in 1..Duration.ofSeconds(15).toNanos())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.invalid/v1/decisions",
            "http://localhost:1/v1/decisions",
            "https://api.openai.com/v1/responses",
            "https://api.openai.com/v1/decisions?key=synthetic",
            "http://127.0.0.1:1/v1/decisions#private",
        ],
    )
    fun `DCS09 unsupported endpoint cannot route a credential`(endpoint: String) {
        val captured = CopyOnWriteArrayList<okhttp3.Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    captured += chain.request()
                    Response
                        .Builder()
                        .request(
                            chain.request(),
                        ).protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("synthetic no network")
                        .body(envelope().toResponseBody())
                        .build()
                }.build()
        val classifier =
            OpenAiAmbiguityClassifier(
                DecisionClassificationFixtures.KEY,
                mapper,
                AmbiguityClassifierOptions(URI(endpoint), true, 0.8),
                client,
            )
        assertEquals("CONFIGURATION", assertFailsWith<AmbiguityClassificationException> { classifier.classify(request) }.failureCode)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `DCS10 logs exceptions and all metric labels exclude own raw context credentials and response`(output: CapturedOutput) {
        val raw = "PRIVATE_ORIGINAL_107 태그와 로그에 노출 금지"
        Endpoint(status = 503, body = "PRIVATE_RAW_RESPONSE_107").use { e ->
            val error = assertFailsWith<AmbiguityClassificationException> { e.classifier().classify(request.copy(rawText = raw)) }
            assertEquals("SERVER", error.message)
            assertEquals(
                1.0,
                e.registry
                    .find("meetme.precheck.classification.results")
                    .tags("outcome", "FAILED", "failure", "SERVER")
                    .counter()
                    ?.count(),
            )
            e.registry.meters.forEach { meter ->
                assertTrue(
                    meter.id.tags
                        .map { it.key }
                        .toSet()
                        .all { it in setOf("provider", "model", "operation", "outcome", "failure") },
                )
                assertEquals("OPENAI_DECISIONS", meter.id.getTag("provider"))
                assertEquals("gpt-6-luna", meter.id.getTag("model"))
                assertEquals("CLASSIFY", meter.id.getTag("operation"))
                assertTrue(
                    meter.id.getTag(
                        "outcome",
                    ) == null ||
                        meter.id.getTag("outcome") in setOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE", "FAILED"),
                )
                assertTrue(
                    meter.id.getTag("failure") == null ||
                        meter.id.getTag("failure") in
                        setOf(
                            "NONE",
                            "LOW_CONFIDENCE",
                            "REFUSED",
                            "MODEL_UNCERTAIN",
                            "CONFIGURATION",
                            "INVALID_REQUEST",
                            "INVALID_RESPONSE",
                            "TIMEOUT",
                            "CONNECTION",
                            "RATE_LIMITED",
                            "SERVER",
                        ),
                )
            }
            val observed = output.all + error.toString() + e.registry.meters.joinToString { it.id.toString() }
            for (secret in listOf(
                raw,
                DecisionClassificationFixtures.KEY,
                "PRIVATE_RAW_RESPONSE_107",
                request.referenceAt.toString(),
                "Asia/Seoul",
                "Authorization",
            )) {
                assertFalse(observed.contains(secret), "private data must not be observed")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 1.0])
    fun `DCS11 inclusive synthetic confidence endpoints are explicit options only`(threshold: Double) {
        Endpoint(body = envelope(DecisionClassificationFixtures.answer(confidence = threshold.toString()))).use { e ->
            assertEquals(
                AmbiguityAssessment.CLEAR,
                assertDoesNotThrow<AmbiguityClassificationResult> {
                    e.classifier(threshold = threshold).classify(request)
                }.assessment,
            )
        }
    }
}
