package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public v2 edge contract: transport completion is independent of parser success. Loopback only. */
class ParserTransportFailureContractTest {
    private val context = ParserObservationContext(UUID(0, 210), UUID(0, 211))
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "synthetic-private-input", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
            callTimeout = Duration.ofSeconds(2),
            observationContext = context,
        )

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `HTTP 200 with invalid parser payload remains one completed local call`(provider: AnalysisProvider) {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.requestBody.use { it.readAllBytes() }
            val bytes = """{"synthetic-private":"invalid parser envelope"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            assertFailsWith<NaturalLanguageParserException> {
                adapter(provider, URI.create("http://127.0.0.1:${server.address.port}"), snapshots).parse(request)
            }
            assertEquals(1, requests)
            val observation = snapshots.single()
            assertEquals(context, observation.context)
            assertTrue(observation.parserDispatched)
            val call = observation.calls.single()
            assertEquals(200, call.httpStatus)
            assertEquals(ParserClientCallOutcome.COMPLETED, call.outcome)
            assertTrue(call.elapsedNanos >= 0)
            assertFalse(observation.toString().contains("synthetic-private"))
        } finally {
            server.stop(0)
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `stalled local endpoint records one failed call without status or hidden replay`(provider: AnalysisProvider) {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        // Hold the ephemeral port while never accepting/responding to the connection.
        ServerSocket().use { closedEndpoint ->
            closedEndpoint.bind(InetSocketAddress("127.0.0.1", 0))
            val endpoint = URI.create("http://127.0.0.1:${closedEndpoint.localPort}")
            // The unaccepted connection remains bounded by the adapter's explicit call timeout.
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    adapter(provider, endpoint, snapshots).parse(request.copy(callTimeout = Duration.ofMillis(150)))
                }
            assertTrue(failure.kind in setOf(ParserFailureKind.NETWORK, ParserFailureKind.TIMEOUT))
            val observation = snapshots.single()
            assertTrue(observation.parserDispatched)
            assertEquals(1, observation.calls.size)
            assertEquals(ParserClientCallOutcome.FAILED, observation.calls.single().outcome)
            assertNull(observation.calls.single().httpStatus)
            assertTrue(observation.calls.single().elapsedNanos >= 0)
        }
    }

    private fun adapter(
        provider: AnalysisProvider,
        endpoint: URI,
        snapshots: MutableList<ParserTransportSnapshot>,
    ): NaturalLanguageParserPort {
        val recorder = ParserTransportObservationPort { snapshots += it }
        val mapper = JsonMapper.builder().build()
        return when (provider) {
            AnalysisProvider.GEMINI ->
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = "synthetic-loopback-placeholder"),
                    mapper,
                    endpoint,
                    recorder,
                )
            AnalysisProvider.OPENAI ->
                OpenAiLunaNaturalLanguageParserAdapter(
                    OpenAiProperties(apiKey = "synthetic-loopback-placeholder"),
                    mapper,
                    OkHttpClient(),
                    endpoint,
                    recorder,
                )
        }
    }
}
