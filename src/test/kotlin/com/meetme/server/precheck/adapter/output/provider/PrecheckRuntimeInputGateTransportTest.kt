package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RI: fixed runtime profile; local synthetic HTTP is not live support or billing verification. */
class PrecheckRuntimeInputGateTransportTest {
    private val mapper = JsonMapper.builder().build()
    private val input =
        PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000202"),
            "내일 저녁 7~8시 가능",
            Instant.parse("2026-10-17T10:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 17),
            LocalDate.of(2026, 10, 31),
            Duration.ofSeconds(2),
        )

    @Test
    fun `RI01 full frozen request is counted once at 4096 before one generation`() {
        Endpoint(
            countBody = """{"totalTokens":4096,"cachedContentTokenCount":0}""",
            generationBody = envelope(usage = usage(4096)),
        ).use { e ->
            val raw = "😀".repeat(500)
            val result = assertDoesNotThrow<PrecheckProviderResult> { e.adapter().check(input.copy(rawText = raw)) }
            assertEquals(PrecheckVerdict.CLEAR, result.verdict)
            assertEquals(4096L, result.inputTokens)
            assertEquals(25L, result.outputTokens)
            e.assertAttempts(1, 1)
            val counted = mapper.readTree(e.sent[0].body)
            val generated = mapper.readTree(e.sent[1].body)
            assertEquals(setOf("generateContentRequest"), counted.propertyNames().toSet())
            val full = counted.path("generateContentRequest")
            assertEquals("models/gemini-3.8-flash", full.path("model").asText())
            assertEquals(generated.propertyNames().toSet() + "model", full.propertyNames().toSet())
            generated.propertyNames().forEach { assertEquals(generated.path(it), full.path(it), it) }
            val config = generated.path("generationConfig")
            assertEquals(2048, config.path("maxOutputTokens").asInt())
            assertEquals(1, config.path("candidateCount").asInt())
            assertEquals("LOW", config.path("thinkingConfig").path("thinkingLevel").asText())
            assertFalse(config.path("thinkingConfig").has("thinkingBudget"))
            assertTrue(config.path("responseJsonSchema").isObject)
            assertTrue(generated.path("systemInstruction").isObject)
            val data =
                mapper.readTree(
                    generated
                        .path("contents")
                        .get(0)
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText(),
                )
            assertEquals(raw, data.path("raw_text").asText())
            assertEquals(input.referenceAt.toString(), data.path("reference_at").asText())
            assertEquals(input.timeZone.id, data.path("time_zone").asText())
            assertEquals(input.locale, data.path("locale").asText())
            assertEquals(input.searchStartDate.toString(), data.path("search_start_date").asText())
            assertEquals(input.searchEndDate.toString(), data.path("search_end_date").asText())
            e.sent.forEach { assertTrue(it.body.toByteArray().size <= 16 * 1024) }
        }
    }

    @Test
    fun `RI02 count above 4096 rejects before generation and is never substituted for usage`() {
        Endpoint(countBody = """{"totalTokens":4097}""").use { e ->
            assertEquals("INVALID_REQUEST", failure { e.adapter().check(input) })
            e.assertAttempts(1, 0)
            e.assertNoGenerationUsage()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}", "null", "[]", "{", "{\"totalTokens\":null}", "{\"totalTokens\":0}", "{\"totalTokens\":-1}",
            "{\"totalTokens\":1.5}", "{\"totalTokens\":\"100\"}", "{\"totalTokens\":true}",
            "{\"totalTokens\":9223372036854775808}", "{\"totalTokens\":100,\"totalTokens\":101}", "{\"totalTokens\":100} {}",
        ],
    )
    fun `RI03 malformed missing or non positive integral count never admits generation`(body: String) {
        Endpoint(countBody = body).use { e ->
            assertEquals("INVALID_RESPONSE", failure { e.adapter().check(input) })
            e.assertAttempts(1, 0)
            e.assertNoGenerationUsage()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [302, 307, 308, 400, 401, 403, 429, 500, 503])
    fun `RI04 count HTTP failures do not redirect retry or generate`(status: Int) {
        Endpoint(countStatus = status).use { e ->
            val expected =
                when (status) {
                    400, 401, 403 -> "CONFIGURATION"
                    429 -> "RATE_LIMITED"
                    500, 503 -> "SERVER"
                    else -> "INVALID_RESPONSE"
                }
            assertEquals(expected, failure { e.adapter(permissiveClient = true).check(input) })
            e.assertAttempts(1, 0)
            e.assertNoGenerationUsage()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 429, 503])
    fun `RI05 generation HTTP failure consumes one attempt and records unknown usage once`(status: Int) {
        Endpoint(generationStatus = status).use { e ->
            val expected =
                when (status) {
                    401 -> "CONFIGURATION"
                    429 -> "RATE_LIMITED"
                    else -> "SERVER"
                }
            assertEquals(expected, failure { e.adapter(permissiveClient = true).check(input) })
            e.assertAttempts(1, 1)
            listOf("INPUT", "CANDIDATE", "THOUGHT").forEach { e.assertUsage(it, null) }
        }
    }

    @Test
    fun `RI06 byte bound rejects oversized count response before generation`() {
        Endpoint(countBody = """{"totalTokens":100,"padding":"${"x".repeat(16 * 1024)}"}""").use { e ->
            assertEquals("INVALID_RESPONSE", failure { e.adapter().check(input) })
            e.assertAttempts(1, 0)
        }
    }

    @Test
    fun `RI07 count cannot fill missing returned provider usage`() {
        Endpoint(generationBody = envelope(usage = null)).use { e ->
            val result = assertDoesNotThrow<PrecheckProviderResult> { e.adapter().check(input) }
            assertNull(result.inputTokens)
            assertNull(result.outputTokens)
            e.assertAttempts(1, 1)
            listOf("INPUT", "CANDIDATE", "THOUGHT").forEach { e.assertUsage(it, null) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CAP", "MAX_TOKENS", "VERDICT"])
    fun `RI08 valid returned usage survives a rejected cap finish reason or semantic verdict`(invalid: String) {
        val prompt = if (invalid == "CAP") 4097 else 256
        val body =
            envelope(
                payload =
                    if (invalid ==
                        "VERDICT"
                    ) {
                        "{}"
                    } else {
                        """{"verdict":"CLEAR"}"""
                    },
                usage = usage(prompt),
                finish =
                    if (invalid ==
                        "MAX_TOKENS"
                    ) {
                        "MAX_TOKENS"
                    } else {
                        "STOP"
                    },
            )
        Endpoint(generationBody = body).use { e ->
            assertEquals("INVALID_RESPONSE", failure { e.adapter().check(input) })
            e.assertAttempts(1, 1)
            e.assertUsage("INPUT", prompt.toLong())
            e.assertUsage("CANDIDATE", 25)
            e.assertUsage("THOUGHT", 0)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "1.5", "\"7\"", "true", "null", "9223372036854775808"])
    fun `RI09 invalid usage kind remains unknown while valid other kinds are observed`(invalid: String) {
        Endpoint(
            generationBody = envelope(usage = """{"promptTokenCount":256,"candidatesTokenCount":$invalid,"thoughtsTokenCount":0}"""),
        ).use { e ->
            assertEquals("INVALID_RESPONSE", failure { e.adapter().check(input) })
            e.assertAttempts(1, 1)
            e.assertUsage("INPUT", 256)
            e.assertUsage("CANDIDATE", null)
            e.assertUsage("THOUGHT", 0)
        }
    }

    @Test
    fun `RI10 partial valid usage observes only returned known values`() {
        Endpoint(generationBody = envelope(usage = """{"promptTokenCount":256,"candidatesTokenCount":25}""")).use { e ->
            val result = assertDoesNotThrow<PrecheckProviderResult> { e.adapter().check(input) }
            assertEquals(256L, result.inputTokens)
            assertNull(result.outputTokens)
            e.assertUsage("INPUT", 256)
            e.assertUsage("CANDIDATE", 25)
            e.assertUsage("THOUGHT", null)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["MALFORMED", "DUPLICATE", "TRAILING", "OVERSIZE"])
    fun `RI11 untrustworthy complete envelope records three unknown kinds and no known usage`(invalid: String) {
        val valid = envelope()
        val body =
            when (invalid) {
                "MALFORMED" -> valid.dropLast(1)
                "DUPLICATE" -> valid.replace("\"promptTokenCount\":256", "\"promptTokenCount\":256,\"promptTokenCount\":257")
                "TRAILING" -> "$valid {}"
                else ->
                    valid +
                        " ".repeat(16 * 1024)
            }
        Endpoint(generationBody = body).use { e ->
            assertEquals("INVALID_RESPONSE", failure { e.adapter().check(input) })
            e.assertAttempts(1, 1)
            listOf("INPUT", "CANDIDATE", "THOUGHT").forEach { e.assertUsage(it, null) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMPTY", "501", "BODY", "CAP", "TIMEOUT"])
    fun `RI12 invalid local input never sends even count`(invalid: String) {
        val request =
            when (invalid) {
                "EMPTY" -> input.copy(rawText = "")
                "501" -> input.copy(rawText = "😀".repeat(501))
                "BODY" ->
                    input.copy(
                        locale =
                            "가".repeat(
                                16 * 1024,
                            ),
                    )
                ; "CAP" -> input.copy(maximumOutputTokens = 2049)
                else -> input.copy(timeout = Duration.ZERO)
            }
        Endpoint().use { e ->
            val expected =
                when (invalid) {
                    "CAP" -> "CONFIGURATION"
                    "TIMEOUT" -> "TIMEOUT"
                    else -> "INVALID_REQUEST"
                }
            assertEquals(expected, failure { e.adapter().check(request) })
            e.assertAttempts(0, 0)
            e.assertNoGenerationUsage()
        }
    }

    @Test
    fun `RI13 count and generation share one deadline rather than receiving fresh stage timeouts`() {
        Endpoint(countDelayMillis = 600, generationDelayMillis = 600).use { e ->
            val started = System.nanoTime()
            assertEquals("TIMEOUT", failure { e.adapter().check(input.copy(timeout = Duration.ofMillis(1000))) })
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
            e.assertAttempts(1, 1)
            listOf("INPUT", "CANDIDATE", "THOUGHT").forEach { e.assertUsage(it, null) }
        }
    }

    @Test
    fun `RI14 trickling count body cannot restart timeout or reach generation`() {
        Endpoint(stallCount = true).use { e ->
            assertEquals("TIMEOUT", failure { e.adapter().check(input.copy(timeout = Duration.ofMillis(250))) })
            assertTrue(e.countBodyStarted.await(1, TimeUnit.SECONDS))
            e.assertAttempts(1, 0)
            e.assertNoGenerationUsage()
        }
    }

    @Test
    fun `RI15 generation connection failure records attempted HTTP and unknown usage without retry`() {
        Endpoint(disconnectGeneration = true).use { e ->
            assertEquals("CONNECTION", failure { e.adapter(permissiveClient = true).check(input) })
            e.assertAttempts(1, 1)
            listOf("INPUT", "CANDIDATE", "THOUGHT").forEach { e.assertUsage(it, null) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://generativelanguage.googleapis.com", "https://example.invalid", "http://192.0.2.1:1"])
    fun `RI16 legacy verification boolean cannot bypass runtime through an external endpoint`(endpoint: String) {
        val original = ProxySelector.getDefault()
        val selected = CopyOnWriteArrayList<URI>()
        val deny =
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    selected += uri
                    throw AssertionError("External transport must be rejected before routing; no socket was opened")
                }

                override fun connectFailed(
                    uri: URI?,
                    sa: SocketAddress?,
                    ioe: IOException?,
                ) = Unit
            }
        try {
            ProxySelector.setDefault(deny)
            val provider =
                GeminiPreSubmissionCheckProvider(
                    GeminiProperties(apiKey = "synthetic-runtime-key"),
                    mapper,
                    PrecheckProviderOptions(URI(endpoint), true),
                )
            assertEquals("CONFIGURATION", failure { provider.check(input) })
            assertTrue(selected.isEmpty())
        } finally {
            ProxySelector.setDefault(original)
        }
    }

    private fun failure(block: () -> Unit): String = assertFailsWith<PrecheckProviderException> { block() }.failureCode

    private fun usage(prompt: Int = 256) = """{"promptTokenCount":$prompt,"candidatesTokenCount":25,"thoughtsTokenCount":0}"""

    private fun envelope(
        payload: String = """{"verdict":"CLEAR"}""",
        usage: String? = usage(),
        finish: String = "STOP",
    ): String =
        """{"candidates":[{"finishReason":"$finish","content":{"parts":[{"text":${mapper.writeValueAsString(
            payload,
        )}}]}}]${if (usage == null) "" else ",\"usageMetadata\":$usage"}}"""

    private data class Sent(
        val path: String,
        val body: String,
    )

    private inner class Endpoint(
        val countStatus: Int = 200,
        val countBody: String = """{"totalTokens":256}""",
        val generationStatus: Int = 200,
        val generationBody: String = envelope(),
        val countDelayMillis: Long = 0,
        val generationDelayMillis: Long = 0,
        val stallCount: Boolean = false,
        val disconnectGeneration: Boolean = false,
    ) : AutoCloseable {
        val registry = SimpleMeterRegistry()
        val sent = CopyOnWriteArrayList<Sent>()
        val countBodyStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val path = exchange.requestURI.path
                    sent += Sent(path, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                    assertEquals("POST", exchange.requestMethod)
                    assertNull(exchange.requestURI.rawQuery)
                    assertEquals("synthetic-runtime-key", exchange.requestHeaders.getFirst("x-goog-api-key"))
                    val count = path.endsWith(":countTokens")
                    if (!count && disconnectGeneration) {
                        exchange.close()
                        return@createContext
                    }
                    val delay = if (count) countDelayMillis else generationDelayMillis
                    if (delay > 0) Thread.sleep(delay)
                    val body = if (count) countBody else generationBody
                    val status = if (count) countStatus else generationStatus
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    if (status in 300..399) exchange.responseHeaders.add("Location", "/must-not-follow")
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    if (count && stallCount) {
                        exchange.responseBody.write(bytes, 0, 1)
                        exchange.responseBody.flush()
                        countBodyStarted.countDown()
                        release.await(3, TimeUnit.SECONDS)
                    } else {
                        exchange.responseBody.write(bytes)
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun adapter(permissiveClient: Boolean = false) =
            GeminiPreSubmissionCheckProvider.runtime(
                GeminiProperties(apiKey = "synthetic-runtime-key"),
                mapper,
                PrecheckProviderRuntimeProperties(enabled = true),
                PrecheckProviderHttpTransport(
                    OkHttpClient
                        .Builder()
                        .retryOnConnectionFailure(
                            permissiveClient,
                        ).followRedirects(permissiveClient)
                        .followSslRedirects(permissiveClient)
                        .build(),
                ),
                registry,
                URI("http://127.0.0.1:${server.address.port}"),
            )

        fun assertAttempts(
            count: Int,
            generation: Int,
        ) {
            assertEquals(count, sent.count { it.path.endsWith(":countTokens") })
            assertEquals(generation, sent.count { it.path.endsWith(":generateContent") })
            assertEquals(count + generation, sent.size)
            assertEquals(
                count.toDouble(),
                registry
                    .find("meetme.precheck.provider.http.attempts")
                    .tag("operation", "COUNT_TOKENS")
                    .counter()
                    ?.count() ?: 0.0,
            )
            assertEquals(
                generation.toDouble(),
                registry
                    .find("meetme.precheck.provider.http.attempts")
                    .tag("operation", "GENERATE_CONTENT")
                    .counter()
                    ?.count() ?: 0.0,
            )
            registry.meters.forEach { meter ->
                val allowed = if (meter.id.name.contains("usage")) setOf("operation", "kind") else setOf("operation")
                assertEquals(
                    allowed,
                    meter.id.tags
                        .map { it.key }
                        .toSet(),
                )
                assertTrue(meter.id.getTag("operation") in setOf("COUNT_TOKENS", "GENERATE_CONTENT"))
                if ("kind" in allowed) {
                    assertTrue(meter.id.getTag("kind") in setOf("INPUT", "CANDIDATE", "THOUGHT"))
                }
            }
        }

        fun assertUsage(
            kind: String,
            known: Long?,
        ) {
            val summary =
                registry
                    .find(
                        "meetme.precheck.provider.usage.tokens",
                    ).tags("operation", "GENERATE_CONTENT", "kind", kind)
                    .summary()
            assertEquals(if (known == null) 0 else 1, summary?.count()?.toInt() ?: 0)
            assertEquals(known?.toDouble() ?: 0.0, summary?.totalAmount() ?: 0.0)
            assertEquals(
                if (known ==
                    null
                ) {
                    1.0
                } else {
                    0.0
                },
                registry
                    .find(
                        "meetme.precheck.provider.usage.unknown",
                    ).tags("operation", "GENERATE_CONTENT", "kind", kind)
                    .counter()
                    ?.count()
                    ?: 0.0,
            )
        }

        fun assertNoGenerationUsage() {
            listOf("INPUT", "CANDIDATE", "THOUGHT").forEach { kind ->
                assertEquals(
                    0L,
                    registry
                        .find(
                            "meetme.precheck.provider.usage.tokens",
                        ).tags("operation", "GENERATE_CONTENT", "kind", kind)
                        .summary()
                        ?.count()
                        ?: 0L,
                )
                assertEquals(
                    0.0,
                    registry
                        .find(
                            "meetme.precheck.provider.usage.unknown",
                        ).tags("operation", "GENERATE_CONTENT", "kind", kind)
                        .counter()
                        ?.count()
                        ?: 0.0,
                )
            }
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
