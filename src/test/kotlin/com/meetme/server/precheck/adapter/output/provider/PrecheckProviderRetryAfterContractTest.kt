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

/** Retry-After is advisory data; it must never authorize another paid exchange. */
class PrecheckProviderRetryAfterContractTest {
    @Test
    fun `P16 server Retry After zero cannot authorize a second physical exchange`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Retry-After", "0")
            val body = """{"error":{"code":503}}""".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val adapter =
                GeminiPreSubmissionCheckProvider(
                    GeminiProperties(apiKey = "synthetic-only-key"),
                    JsonMapper.builder().build(),
                    PrecheckProviderOptions(URI("http://127.0.0.1:${server.address.port}"), true),
                )
            val request =
                PrecheckProviderRequest(
                    UUID.fromString("00000000-0000-0000-0000-000000000116"),
                    "주말 아무 때나",
                    Instant.parse("2026-10-17T10:00:00Z"),
                    ZoneId.of("Asia/Seoul"),
                    "ko-KR",
                    LocalDate.of(2026, 10, 17),
                    LocalDate.of(2026, 10, 31),
                    Duration.ofSeconds(2),
                )
            assertEquals("SERVER", assertFailsWith<PrecheckProviderException> { adapter.check(request) }.failureCode)
            assertEquals(1, calls.get(), "Retry-After does not grant an extra transport or provider budget")
        } finally {
            server.stop(0)
        }
    }
}
