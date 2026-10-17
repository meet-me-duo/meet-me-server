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
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #110 actual SDK/OkHttp over loopback with synthetic inputs and placeholder credentials only. */
class ParserTransportAdapterContractTest {
    private val mapper = JsonMapper.builder().build()
    private val context =
        ParserObservationContext(
            UUID.fromString("00000000-0000-0000-0000-000000000110"),
            UUID.fromString("00000000-0000-0000-0000-000000000111"),
        )
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "synthetic-private-input", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
            callTimeout = Duration.ofSeconds(3),
            observationContext = context,
        )

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `successful parse records one settled local client call and excludes context from provider payload`(provider: AnalysisProvider) {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        Endpoint(200, envelope(provider)).use { endpoint ->
            val result = adapter(provider, endpoint.uri, ParserTransportObservationPort { snapshots += it }).parse(request)
            assertEquals(1, result.results.size)
            assertEquals(1, endpoint.bodies.size)
            val observed = snapshots.single()
            assertEquals(context, observed.context)
            assertEquals(provider, observed.provider)
            assertTrue(observed.parserDispatched)
            assertEquals(1, observed.calls.single().callNumber)
            assertEquals(200, observed.calls.single().httpStatus)
            assertEquals(ParserClientCallOutcome.COMPLETED, observed.calls.single().outcome)
            assertTrue(observed.calls.single().elapsedNanos >= 0)
            assertFalse(observed.toString().contains("synthetic-private"))
            endpoint.bodies.forEach {
                assertFalse(it.contains(context.invocationId.toString()))
                assertFalse(it.contains(context.attemptId.toString()))
                assertFalse(it.contains("observationContext"))
                assertFalse(it.contains("observation_context"))
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `HTTP 503 is a completed client lifecycle with status rather than a successful provider analysis`(provider: AnalysisProvider) {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        Endpoint(503, """{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic-private-error"}}""").use { endpoint ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    adapter(
                        provider,
                        endpoint.uri,
                        ParserTransportObservationPort {
                            snapshots +=
                                it
                        },
                    ).parse(request)
                }
            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertEquals(1, endpoint.bodies.size, "Observation must not introduce another transport retry")
            val call = snapshots.single().calls.single()
            assertEquals(503, call.httpStatus)
            assertEquals(ParserClientCallOutcome.COMPLETED, call.outcome)
            assertTrue(call.elapsedNanos >= 0)
            assertFalse(snapshots.single().toString().contains("synthetic-private"))
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `configuration failure records parser entered with known zero client calls`(provider: AnalysisProvider) {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        Endpoint(200, envelope(provider)).use { endpoint ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    adapter(
                        provider,
                        endpoint.uri,
                        ParserTransportObservationPort {
                            snapshots +=
                                it
                        },
                        apiKey = "",
                    ).parse(request)
                }
            assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
            assertTrue(endpoint.bodies.isEmpty())
            assertEquals(context, snapshots.single().context)
            assertTrue(snapshots.single().parserDispatched)
            assertTrue(snapshots.single().calls.isEmpty())
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `recorder failure preserves successful parse and the original provider failure without another request`(
        provider: AnalysisProvider,
    ) {
        var writes = 0
        val recorder =
            ParserTransportObservationPort {
                writes++
                error("synthetic-private-store-failure")
            }
        Endpoint(200, envelope(provider)).use { endpoint ->
            assertEquals(1, adapter(provider, endpoint.uri, recorder).parse(request).results.size)
            assertEquals(1, endpoint.bodies.size)
        }
        Endpoint(503, """{"error":{"code":503,"status":"UNAVAILABLE","message":"synthetic-private-error"}}""").use { endpoint ->
            val failure = assertFailsWith<NaturalLanguageParserException> { adapter(provider, endpoint.uri, recorder).parse(request) }
            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertEquals(1, endpoint.bodies.size)
        }
        assertEquals(2, writes)
    }

    @ParameterizedTest
    @EnumSource(AnalysisProvider::class)
    fun `legacy request without explicit context preserves parsing without fabricated linkage`(provider: AnalysisProvider) {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        Endpoint(200, envelope(provider)).use { endpoint ->
            assertEquals(
                1,
                adapter(
                    provider,
                    endpoint.uri,
                    ParserTransportObservationPort {
                        snapshots += it
                    },
                ).parse(request.copy(observationContext = null)).results.size,
            )
            assertEquals(1, endpoint.bodies.size)
            assertTrue(snapshots.isEmpty())
        }
    }

    private fun adapter(
        provider: AnalysisProvider,
        endpoint: URI,
        recorder: ParserTransportObservationPort,
        apiKey: String = "synthetic-loopback-placeholder",
    ): NaturalLanguageParserPort =
        when (provider) {
            AnalysisProvider.GEMINI -> GeminiNaturalLanguageParserAdapter(GeminiProperties(apiKey = apiKey), mapper, endpoint, recorder)
            AnalysisProvider.OPENAI ->
                OpenAiLunaNaturalLanguageParserAdapter(
                    OpenAiProperties(apiKey = apiKey),
                    mapper,
                    OkHttpClient(),
                    endpoint,
                    recorder,
                )
        }

    private fun envelope(provider: AnalysisProvider): String {
        val wire =
            """{"schema_version":"3","results":[{""" +
                """"input_ref":"${request.inputs.single().inputRef}","rejection_code":null,"conditions":[""" +
                """{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-07","day_of_week":null,""" +
                """"start_time":"19:00","end_time":"21:00"}]}]}"""
        return if (provider ==
            AnalysisProvider.GEMINI
        ) {
            mapper.writeValueAsString(
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to wire))),
                                "finishReason" to "STOP",
                            ),
                        ),
                ),
            )
        } else {
            mapper.writeValueAsString(
                mapOf(
                    "status" to "completed",
                    "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to wire)))),
                ),
            )
        }
    }

    private class Endpoint(
        private val status: Int,
        private val body: String,
    ) : AutoCloseable {
        val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val uri: URI get() = URI.create("http://127.0.0.1:${server.address.port}")

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
                val bytes = body.toByteArray(Charsets.UTF_8)
                try {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
