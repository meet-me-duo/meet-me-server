package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Valid JSON is not sufficient for a question that must be stored safely in PostgreSQL. */
class PrecheckProviderUnicodeContractTest {
    @Test
    fun `P17 escaped NUL in question prompt is an invalid response`() {
        rejectedQuestion("질문\u0000", "오전")
    }

    @Test
    fun `P18 escaped NUL in choice label is an invalid response`() {
        rejectedQuestion("오전과 오후 중 어느 뜻인가요?", "오전\u0000")
    }

    private fun rejectedQuestion(
        prompt: String,
        label: String,
    ) {
        val mapper = JsonMapper.builder().build()
        val proposal =
            mapOf(
                "verdict" to "NEEDS_CHOICE",
                "prompt" to prompt,
                "sourceExcerpt" to "7~8시",
                "choices" to
                    listOf(
                        mapOf("id" to "A", "label" to label, "proposedText" to "오전 7~8시"),
                        mapOf("id" to "B", "label" to "오후", "proposedText" to "오후 7~8시"),
                    ),
            )
        val body =
            mapper.writeValueAsBytes(
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "finishReason" to "STOP",
                                "content" to mapOf("parts" to listOf(mapOf("text" to mapper.writeValueAsString(proposal)))),
                            ),
                        ),
                ),
            )
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val provider =
                GeminiPreSubmissionCheckProvider(
                    GeminiProperties(apiKey = "synthetic-only-key"),
                    mapper,
                    PrecheckProviderOptions(URI("http://127.0.0.1:${server.address.port}"), true),
                )
            val request =
                PrecheckProviderRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000118"),
                    "7~8시 가능",
                    Instant.parse("2026-10-17T10:00:00Z"),
                    ZoneId.of("Asia/Seoul"),
                    "ko-KR",
                    LocalDate.of(2026, 10, 17),
                    LocalDate.of(2026, 10, 31),
                    Duration.ofSeconds(2),
                )
            assertEquals("INVALID_RESPONSE", assertFailsWith<PrecheckProviderException> { provider.check(request) }.failureCode)
            assertEquals(1, calls.get())
        } finally {
            server.stop(0)
        }
    }
}
