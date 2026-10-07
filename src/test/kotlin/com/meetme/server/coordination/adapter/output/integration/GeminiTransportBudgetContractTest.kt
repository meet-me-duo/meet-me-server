package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Issue #96: real SDK over loopback only; synthetic response and placeholder API key. */
class GeminiTransportBudgetContractTest {
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "수요일 19–21시", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )

    @Test
    fun `503 sends one SDK request and has no hidden final retry sleep inside call budget`() {
        val error = """{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic unavailable"}}"""
        Endpoint(503, error).use { fixture ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    fixture.adapter().parse(request.copy(callTimeout = Duration.ofMillis(750)))
                }
            assertEquals(ParserFailureKind.SERVER, failure.kind, "A final SDK backoff would consume the call budget and become TIMEOUT")
            assertEquals(1, fixture.calls.size, "The processor owns retries; SDK and transport must each send one request")
        }
    }

    @Test
    fun `request timeout also bounds stalled Gemini response body`() {
        Endpoint(200, envelope(), bodyDelay = 1_000).use { fixture ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    fixture.adapter().parse(request.copy(callTimeout = Duration.ofMillis(250)))
                }
            assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test
    fun `SDK success parses common response under requested timeout without nested retry`() {
        Endpoint(200, envelope()).use { fixture ->
            val result = fixture.adapter().parse(request.copy(callTimeout = Duration.ofSeconds(2)))
            assertEquals(1, fixture.calls.size)
            assertEquals(
                1,
                result.results
                    .single()
                    .conditions.size,
            )
            assertEquals(100L, result.usage.inputTokens)
            assertEquals(30L, result.usage.outputTokens)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["MAX_TOKENS", "SAFETY"])
    fun `truncated or blocked Gemini response cannot publish valid-looking JSON`(reason: String) {
        Endpoint(200, envelope().replace("\"finishReason\":\"STOP\"", "\"finishReason\":\"$reason\"")).use { fixture ->
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.adapter().parse(request) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(1, fixture.calls.size)
        }
    }

    private fun envelope(): String {
        val providerResponse =
            """{"schema_version":"2","results":[{"input_ref":"${request.inputs.single().inputRef}","rejection_code":null,"conditions":[{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-07","day_of_week":null,"start_time":"19:00","end_time":"21:00"}]}]}"""
        return JsonMapper.builder().build().writeValueAsString(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf(
                            "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to providerResponse))),
                            "finishReason" to "STOP",
                        ),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 100, "candidatesTokenCount" to 30),
            ),
        )
    }

    private class Endpoint(
        private val status: Int,
        private val response: String,
        private val bodyDelay: Long = 0,
    ) : AutoCloseable {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange ->
                calls += exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
                val bytes = response.toByteArray(Charsets.UTF_8)
                try {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    if (bodyDelay > 0) Thread.sleep(bodyDelay)
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun adapter() =
            GeminiNaturalLanguageParserAdapter(
                GeminiProperties(apiKey = "synthetic-loopback-placeholder"),
                JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:${server.address.port}"),
            )

        override fun close() = server.stop(0)
    }
}
