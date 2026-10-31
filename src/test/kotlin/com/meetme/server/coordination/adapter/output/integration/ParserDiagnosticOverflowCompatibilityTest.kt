package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Diagnostic arithmetic must not alter the existing bounded provider call. Loopback only, no paid API. */
class ParserDiagnosticOverflowCompatibilityTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `huge requested duration retains the bounded server failure with diagnostics optional`(enabled: Boolean) {
        val calls = AtomicInteger()
        val snapshots = mutableListOf<ParserTransportDiagnosticSnapshot>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.transferTo(OutputStream.nullOutputStream()) }
            val body = """{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val adapter =
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = "synthetic-placeholder"),
                    JsonMapper.builder().build(),
                    URI("http://127.0.0.1:${server.address.port}"),
                    transportDiagnostics = if (enabled) ParserTransportDiagnosticPort { snapshots += it } else null,
                )
            val request =
                NaturalLanguageBatchRequest(
                    ZoneId.of("Asia/Seoul"),
                    LocalDate.of(2026, 11, 2),
                    LocalDate.of(2026, 11, 3),
                    listOf(NaturalLanguageInput(UUID(0, 1).toString(), "synthetic input", Locale.KOREAN, LocalDate.of(2026, 11, 2))),
                    callTimeout = Duration.ofSeconds(Long.MAX_VALUE),
                    observationContext = ParserObservationContext(UUID(0, 11), UUID(0, 12)),
                )

            val failure = assertFailsWith<NaturalLanguageParserException> { adapter.parse(request) }
            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertEquals(1, calls.get())
            if (enabled) {
                val observed = snapshots.single().calls.single()
                assertNull(observed.requestedTimeoutNanos)
                assertTrue(observed.metadataUnavailable)
                assertEquals(Duration.ofSeconds(15).toNanos(), observed.adapterBudgetNanos)
            } else {
                assertTrue(snapshots.isEmpty())
            }
        } finally {
            server.stop(0)
        }
    }
}
