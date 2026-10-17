package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public transport oracle from the accepted pre-submit specification; loopback only. */
class PrecheckProviderTransportContractTest {
    private val mapper = JsonMapper.builder().build()
    private val input =
        PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000107"),
            "내일 7~8시 가능",
            Instant.parse("2026-10-17T10:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 17),
            LocalDate.of(2026, 10, 31),
            Duration.ofSeconds(2),
        )

    @Test
    fun `P01 one bounded request carries own context and clear does not invent unknown usage`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""")).use { endpoint ->
            val result = endpoint.adapter().check(input)
            assertEquals(PrecheckVerdict.CLEAR, result.verdict)
            assertNull(result.inputTokens)
            assertNull(result.outputTokens)
            assertTrue(result.choices.isEmpty())
            val sent = endpoint.requests.single()
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", sent.path)
            assertNull(sent.query, "Credentials must not be placed in the URL")
            assertEquals("synthetic-precheck-key", sent.apiKey)
            val body = mapper.readTree(sent.body)
            val configuration = body.path("generationConfig")
            assertEquals(2048, configuration.path("maxOutputTokens").asInt())
            assertEquals(1, configuration.path("candidateCount").asInt())
            assertEquals(0, configuration.path("thinkingConfig").path("thinkingBudget").asInt(-1))
            assertEquals("application/json", configuration.path("responseMimeType").asText())
            assertEquals(false, configuration.path("responseJsonSchema").path("additionalProperties").asBoolean(true))
            val text =
                body
                    .path("contents")
                    .get(0)
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText()
            val data = mapper.readTree(text)
            assertEquals(input.rawText, data.path("raw_text").asText())
            assertEquals(input.checkId.toString(), data.path("input_ref").asText())
            assertEquals(input.referenceAt.toString(), data.path("reference_at").asText())
            assertEquals("Asia/Seoul", data.path("time_zone").asText())
            assertEquals(input.locale, data.path("locale").asText())
            assertEquals(input.searchStartDate.toString(), data.path("search_start_date").asText())
            assertEquals(input.searchEndDate.toString(), data.path("search_end_date").asText())
            assertEquals(
                setOf("input_ref", "raw_text", "reference_at", "time_zone", "locale", "search_start_date", "search_end_date"),
                data.propertyNames().toSet(),
            )
            assertTrue(body.path("systemInstruction").path("parts").get(0).path("text").asText().isNotBlank())
            assertTrue(sent.body.toByteArray().size <= 16 * 1024)
        }
    }

    @Test
    fun `P02 validated proposal preserves complete alternatives and known usage`() {
        Endpoint(
            200,
            envelope(proposal(), """, "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":25,"thoughtsTokenCount":0}"""),
        ).use { endpoint ->
            val result = endpoint.adapter().check(input)
            assertEquals(PrecheckVerdict.NEEDS_CHOICE, result.verdict)
            assertEquals("7~8시", result.sourceExcerpt)
            assertEquals(listOf("A", "B"), result.choices.map { it.id })
            assertEquals(listOf("2026-10-18 오전 7~8시 가능", "2026-10-18 오후 7~8시 가능"), result.choices.map { it.proposedText })
            assertEquals(100L, result.inputTokens)
            assertEquals(25L, result.outputTokens)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `P03 partial usage retains unknown thinking cost rather than reporting zero`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""", """, "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":25}""")).use { endpoint ->
            val result = endpoint.adapter().check(input)
            assertEquals(100L, result.inputTokens)
            assertNull(result.outputTokens)
        }
    }

    @Test
    fun `P04 missing verdict unknown key invalid enum and malformed JSON are typed failures`() {
        for (payload in listOf("{}", """{"verdict":"CLEAR","hidden":"x"}""", """{"verdict":"YES"}""", "{")) {
            Endpoint(200, envelope(payload)).use { endpoint ->
                assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
                assertEquals(1, endpoint.requests.size)
            }
        }
    }

    @Test
    fun `P05 incomplete duplicate unrelated or overlong proposals never become clear`() {
        val payloads =
            listOf(
                proposal().replace("7~8시", "없는구절"),
                proposal().replace("\"id\":\"B\"", "\"id\":\"A\""),
                proposal().replace("2026-10-18 오후 7~8시 가능", "2026-10-18 오전 7~8시 가능"),
                proposal().replace("2026-10-18 오후 7~8시 가능", "가".repeat(501)),
                """{"verdict":"NEEDS_CHOICE","prompt":"확인","sourceExcerpt":"7~8시","choices":[]}""",
            )
        for (payload in payloads) {
            Endpoint(200, envelope(payload)).use { endpoint ->
                assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
                assertEquals(1, endpoint.requests.size)
            }
        }
    }

    @Test
    fun `P06 clear or needs edit cannot carry an unanswered proposal`() {
        for (verdict in listOf("CLEAR", "NEEDS_EDIT")) {
            Endpoint(200, envelope(proposal().replace("NEEDS_CHOICE", verdict))).use { endpoint ->
                assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
            }
        }
    }

    @Test
    fun `P07 known response text with truncation finish reason is not publishable`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""").replace("\"STOP\"", "\"MAX_TOKENS\"")).use { endpoint ->
            assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `P08 oversized actual HTTP body is rejected without retry`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""") + " ".repeat(16 * 1024)).use { endpoint ->
            assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `P09 server error sends one physical request without hidden retry`() {
        Endpoint(503, """{"error":{"code":503}}""").use { endpoint ->
            assertEquals("SERVER", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `P10 redirect cannot forward credentials or create a second exchange`() {
        Endpoint(302, "", redirect = true).use { endpoint ->
            assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(input) }.failureCode)
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `P11 timeout includes stalled response body and terminates the exchange`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}"""), stall = true).use { endpoint ->
            val began = System.nanoTime()
            assertEquals(
                "TIMEOUT",
                assertFailsWith<PrecheckProviderException> {
                    endpoint.adapter().check(input.copy(timeout = Duration.ofMillis(300)))
                }.failureCode,
            )
            assertTrue(endpoint.bodyStarted.await(1, TimeUnit.SECONDS))
            assertTrue(Duration.ofNanos(System.nanoTime() - began) < Duration.ofSeconds(2))
            assertEquals(1, endpoint.requests.size)
        }
    }

    @Test
    fun `P12 unavailable bounded configuration fails before network`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""")).use { endpoint ->
            for (adapter in listOf(endpoint.adapter(verified = false), endpoint.adapter(key = ""))) {
                assertEquals("CONFIGURATION", assertFailsWith<PrecheckProviderException> { adapter.check(input) }.failureCode)
            }
            assertEquals(
                "CONFIGURATION",
                assertFailsWith<PrecheckProviderException> {
                    endpoint.adapter().check(input.copy(maximumOutputTokens = 2049))
                }.failureCode,
            )
            assertTrue(endpoint.requests.isEmpty())
        }
    }

    @Test
    fun `P13 invalid raw input and oversized request are not sent or truncated`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""")).use { endpoint ->
            val requests =
                listOf(
                    input.copy(rawText = ""),
                    input.copy(rawText = "😀".repeat(501)),
                    input.copy(locale = "x".repeat(16 * 1024)),
                )
            for (request in requests) {
                assertEquals(
                    "INVALID_REQUEST",
                    assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(request) }.failureCode,
                )
            }
            assertTrue(endpoint.requests.isEmpty())
        }
    }

    @Test
    fun `P14 exactly five hundred Unicode codepoints are sent without rewriting`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""")).use { endpoint ->
            val raw = "😀".repeat(500)
            assertEquals(PrecheckVerdict.CLEAR, endpoint.adapter().check(input.copy(rawText = raw)).verdict)
            val text =
                mapper
                    .readTree(endpoint.requests.single().body)
                    .path("contents")
                    .get(0)
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText()
            assertEquals(raw, mapper.readTree(text).path("raw_text").asText())
        }
    }

    @Test
    fun `P15 zero budget makes no network request`() {
        Endpoint(200, envelope("""{"verdict":"CLEAR"}""")).use { endpoint ->
            assertEquals(
                "TIMEOUT",
                assertFailsWith<PrecheckProviderException> {
                    endpoint.adapter().check(input.copy(timeout = Duration.ZERO))
                }.failureCode,
            )
            assertTrue(endpoint.requests.isEmpty())
        }
    }

    private fun proposal() =
        """{"verdict":"NEEDS_CHOICE","prompt":"어느 시간인가요?","sourceExcerpt":"7~8시","choices":[{"id":"A","label":"오전","proposedText":"2026-10-18 오전 7~8시 가능"},{"id":"B","label":"오후","proposedText":"2026-10-18 오후 7~8시 가능"}]}"""

    private fun envelope(
        payload: String,
        usage: String = "",
    ) = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":${mapper.writeValueAsString(payload)}}]}}]$usage}"""

    private data class Sent(
        val path: String,
        val query: String?,
        val apiKey: String?,
        val body: String,
    )

    private inner class Endpoint(
        status: Int,
        body: String,
        redirect: Boolean = false,
        stall: Boolean = false,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Sent>()
        val bodyStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    requests.add(
                        Sent(
                            exchange.requestURI.path,
                            exchange.requestURI.rawQuery,
                            exchange.requestHeaders.getFirst("x-goog-api-key"),
                            exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                        ),
                    )
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    if (redirect) exchange.responseHeaders.add("Location", "/must-not-follow")
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    if (stall) {
                        exchange.responseBody.write(bytes, 0, 1)
                        exchange.responseBody.flush()
                        bodyStarted.countDown()
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

        fun adapter(
            verified: Boolean = true,
            key: String = "synthetic-precheck-key",
        ) = GeminiPreSubmissionCheckProvider(
            GeminiProperties(apiKey = key),
            mapper,
            PrecheckProviderOptions(URI("http://127.0.0.1:${server.address.port}"), verified),
        )

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
