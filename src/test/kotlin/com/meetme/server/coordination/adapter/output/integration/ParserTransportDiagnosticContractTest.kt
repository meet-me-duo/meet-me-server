package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserClientCallDiagnostic
import com.meetme.server.coordination.application.port.output.ParserDiagnosticCause
import com.meetme.server.coordination.application.port.output.ParserDiagnosticEvent
import com.meetme.server.coordination.application.port.output.ParserDiagnosticEventKind
import com.meetme.server.coordination.application.port.output.ParserDiagnosticResetCode
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import com.sun.net.httpserver.HttpServer
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import okio.Timeout
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Optional facts only; no paid provider, input fixture, credential or exception text is recorded. */
class ParserTransportDiagnosticContractTest {
    private val context = ParserObservationContext(UUID(0, 501), UUID(0, 502))
    private val json = JsonMapper.builder().build()
    private val diagnostics = mutableListOf<ParserTransportDiagnosticSnapshot>()
    private val normal = mutableListOf<ParserTransportSnapshot>()
    private var clock = 1_000L

    @Test
    fun `timeouts and cancel header failure order are facts without a causal origin claim`() {
        val call = call(Timeout().timeout(75, TimeUnit.NANOSECONDS).deadlineNanoTime(1_300), true)
        val session = session()
        session.configureTimeouts(900, 800, 1_500)
        val events = session.eventListenerFactory.create(call)
        events.callStart(call)
        clock = 1_050
        events.requestHeadersStart(call)
        clock = 1_075
        events.requestHeadersEnd(call, call.request())
        clock = 1_100
        events.responseHeadersStart(call)
        clock = 1_120
        events.responseHeadersEnd(call, response(call))
        clock = 1_125
        events.canceled(call)
        clock = 1_150
        events.callFailed(call, SocketTimeoutException("PRIVATE_TIMEOUT_BODY"))
        session.finish()

        val observed = diagnostics.single().calls.single()
        assertEquals(context, diagnostics.single().context)
        assertEquals(AnalysisProvider.GEMINI, diagnostics.single().provider)
        assertEquals(900L, observed.requestedTimeoutNanos)
        assertEquals(800L, observed.adapterBudgetNanos)
        assertEquals(75L, observed.actualCallTimeoutNanos)
        assertEquals(300L, observed.callStartDeadlineRemainingNanos)
        assertEquals(250L, observed.requestHeadersDeadlineRemainingNanos)
        assertEquals(500L, observed.dispatchRemainingAtCallStartNanos)
        assertEquals(true, observed.canceledAtFailure)
        assertEquals(listOf(ParserDiagnosticCause.SOCKET_TIMEOUT), observed.causes)
        assertNull(observed.streamResetCode)
        assertEquals(
            listOf(
                ParserDiagnosticEventKind.CALL_START,
                ParserDiagnosticEventKind.REQUEST_HEADERS_START,
                ParserDiagnosticEventKind.REQUEST_HEADERS_END,
                ParserDiagnosticEventKind.RESPONSE_HEADERS_START,
                ParserDiagnosticEventKind.RESPONSE_HEADERS_END,
                ParserDiagnosticEventKind.CANCELED,
                ParserDiagnosticEventKind.CALL_FAILED,
            ),
            observed.events.map { it.kind },
        )
        assertEquals(listOf(0L, 50L, 75L, 100L, 120L, 125L, 150L), observed.events.map { it.elapsedNanos })
        assertTrue(observed.settled)
        assertFalse(observed.metadataUnavailable)
        assertFalse(diagnostics.toString().contains("PRIVATE_"))
    }

    @Test
    fun `zero timeout unset deadline and expired deadline stay distinct`() {
        val session = session()
        session.configureTimeouts(0, 0, 900)
        val first = call(Timeout())
        session.eventListenerFactory.create(first).also {
            it.callStart(first)
            it.callEnd(first)
        }
        val second = call(Timeout().deadlineNanoTime(900))
        session.eventListenerFactory.create(second).also {
            it.callStart(second)
            it.requestHeadersStart(second)
            it.callEnd(second)
        }
        session.finish()

        val (unset, expired) = diagnostics.single().calls
        assertEquals(0L, unset.actualCallTimeoutNanos)
        assertNull(unset.callStartDeadlineRemainingNanos)
        assertNull(unset.requestHeadersDeadlineRemainingNanos)
        assertEquals(0L, unset.dispatchRemainingAtCallStartNanos)
        assertEquals(0L, expired.callStartDeadlineRemainingNanos)
        assertEquals(0L, expired.requestHeadersDeadlineRemainingNanos)
    }

    @Test
    fun `unknown exception names and lexical reset hints never become observed reset facts`() {
        val session = session()
        val call = call()
        session.eventListenerFactory.create(call).also {
            it.callStart(call)
            it.callFailed(call, PrivateUnlistedIOException("PRIVATE_RST_STREAM_CANCEL_REMOTE_KEY"))
        }
        session.finish()
        val observed = diagnostics.single().calls.single()

        assertEquals(listOf(ParserDiagnosticCause.OTHER), observed.causes)
        assertNull(observed.streamResetCode)
        assertEquals(false, observed.canceledAtFailure)
        val encoded = json.writeValueAsString(diagnostics.single())
        assertFalse(encoded.contains("PRIVATE_"))
        assertFalse(encoded.contains(PrivateUnlistedIOException::class.java.name))
        assertFalse(encoded.contains("remote", ignoreCase = true))
    }

    @Test
    fun `typed HTTP2 CANCEL is only a reset code and not evidence of remote cancellation`() {
        val session = session()
        val call = call()
        session.eventListenerFactory.create(call).also {
            it.callStart(call)
            it.callFailed(call, StreamResetException(ErrorCode.CANCEL))
        }
        session.finish()
        val observed = diagnostics.single().calls.single()

        assertEquals(listOf(ParserDiagnosticCause.STREAM_RESET), observed.causes)
        assertEquals(ParserDiagnosticResetCode.CANCEL, observed.streamResetCode)
        assertEquals(false, observed.canceledAtFailure)
        assertFalse(observed.toString().contains("REMOTE"))
        assertFalse(observed.toString().contains("LOCAL_CAUSE"))
    }

    @Test
    fun `an unsettled call remains explicit and never fabricates a complete normal snapshot`() {
        val session = session()
        val call = call()
        session.eventListenerFactory.create(call).callStart(call)
        session.finish()

        assertTrue(normal.isEmpty())
        assertFalse(
            diagnostics
                .single()
                .calls
                .single()
                .settled,
        )
        assertEquals(
            listOf(ParserDiagnosticEventKind.CALL_START),
            diagnostics
                .single()
                .calls
                .single()
                .events
                .map { it.kind },
        )
    }

    @Test
    fun `event and cause overflow is bounded explicitly without losing settled state`() {
        val session = session()
        val call = call()
        val events = session.eventListenerFactory.create(call)
        events.callStart(call)
        repeat(30) {
            events.requestHeadersStart(call)
            events.requestHeadersEnd(call, call.request())
        }
        var cause: IOException = IOException("PRIVATE_ROOT")
        repeat(12) { cause = IOException("PRIVATE_CAUSE_$it", cause) }
        events.callFailed(call, cause)
        session.finish()
        val observed = diagnostics.single().calls.single()

        assertEquals(ParserClientCallDiagnostic.MAX_EVENTS, observed.events.size)
        assertEquals(ParserClientCallDiagnostic.MAX_CAUSES, observed.causes.size)
        assertTrue(observed.eventsTruncated)
        assertTrue(observed.causesTruncated)
        assertTrue(observed.settled)
        assertEquals(1, normal.single().calls.size)
        assertFalse(observed.toString().contains("PRIVATE_"))
    }

    @Test
    fun `client call overflow leaves legacy unknown and marks the sidecar truncated`() {
        val session = session()
        repeat(ParserTransportSnapshot.MAX_CLIENT_CALLS + 1) {
            val call = call()
            session.eventListenerFactory.create(call).also {
                it.callStart(call)
                it.callEnd(call)
            }
        }
        session.finish()

        assertTrue(normal.isEmpty())
        assertEquals(ParserTransportSnapshot.MAX_CLIENT_CALLS, diagnostics.single().calls.size)
        assertTrue(diagnostics.single().clientCallsTruncated)
    }

    @Test
    fun `unavailable timeout or cancellation metadata cannot escape or disclose its exception`() {
        val call = mock(Call::class.java)
        `when`(call.timeout()).thenThrow(IllegalStateException("PRIVATE_TIMEOUT_GETTER"))
        `when`(call.isCanceled()).thenThrow(IllegalStateException("PRIVATE_CANCEL_GETTER"))
        val session = session()
        session.eventListenerFactory.create(call).also {
            it.callStart(call)
            it.callFailed(call, IOException("PRIVATE_IO"))
        }
        session.finish()

        val observed = diagnostics.single().calls.single()
        assertTrue(observed.metadataUnavailable)
        assertNull(observed.actualCallTimeoutNanos)
        assertNull(observed.canceledAtFailure)
        assertTrue(observed.settled)
        assertFalse(observed.toString().contains("PRIVATE_"))
        assertEquals(1, normal.single().calls.size)
    }

    @Test
    fun `diagnostic recorder failure preserves normal observation and finish remains once only`() {
        var recordings = 0
        val session =
            ParserTransportObservationSession(
                context,
                AnalysisProvider.GEMINI,
                ParserTransportObservationPort { normal += it },
                ParserTransportDiagnosticPort {
                    recordings++
                    error("PRIVATE_DIAGNOSTIC_STORE")
                },
                { clock },
            )
        session.finish()
        session.finish()

        assertEquals(1, recordings)
        assertEquals(1, normal.size)
    }

    @Test
    fun `normal recorder failure does not erase independent diagnostic evidence`() {
        val session =
            ParserTransportObservationSession(
                context,
                AnalysisProvider.GEMINI,
                ParserTransportObservationPort { error("PRIVATE_NORMAL_STORE") },
                ParserTransportDiagnosticPort { diagnostics += it },
                { clock },
            )
        session.finish()
        session.finish()
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `optional diagnostics work without a normal recorder but never invent a missing context`() {
        val session =
            ParserTransportObservationSession(
                context,
                AnalysisProvider.GEMINI,
                null,
                ParserTransportDiagnosticPort { diagnostics += it },
                { clock },
            )
        val call = call()
        session.eventListenerFactory.create(call).also {
            it.callStart(call)
            it.callEnd(call)
        }
        session.finish()
        assertEquals(1, diagnostics.single().calls.size)
        val missing =
            ParserTransportObservationSession(
                null,
                AnalysisProvider.GEMINI,
                null,
                ParserTransportDiagnosticPort {
                    diagnostics +=
                        it
                },
            )
        missing.finish()
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `diagnostic DTOs contain only the approved bounded metadata fields`() {
        fun fields(type: Class<*>) =
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .toSet()
        assertEquals(setOf("context", "provider", "calls", "clientCallsTruncated"), fields(ParserTransportDiagnosticSnapshot::class.java))
        assertEquals(setOf("kind", "elapsedNanos"), fields(ParserDiagnosticEvent::class.java))
        assertEquals(
            setOf(
                "callNumber",
                "requestedTimeoutNanos",
                "adapterBudgetNanos",
                "actualCallTimeoutNanos",
                "callStartDeadlineRemainingNanos",
                "requestHeadersDeadlineRemainingNanos",
                "dispatchRemainingAtCallStartNanos",
                "canceledAtFailure",
                "causes",
                "causesTruncated",
                "streamResetCode",
                "events",
                "eventsTruncated",
                "metadataUnavailable",
                "settled",
            ),
            fields(ParserClientCallDiagnostic::class.java),
        )
    }

    @Test
    fun `actual Gemini adapter opt in records a known zero without changing missing key classification`() {
        val request = request()
        val adapter =
            GeminiNaturalLanguageParserAdapter(
                GeminiProperties(apiKey = ""),
                json,
                transportDiagnostics = ParserTransportDiagnosticPort { diagnostics += it },
            )
        assertEquals(ParserFailureKind.CONFIGURATION, assertFailsWith<NaturalLanguageParserException> { adapter.parse(request) }.kind)
        assertEquals(context, diagnostics.single().context)
        assertTrue(diagnostics.single().calls.isEmpty())
    }

    @Test
    fun `real SDK loopback failure keeps one call and records requested budget without provider content`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.use { it.transferTo(OutputStream.nullOutputStream()) }
            val response = """{"error":{"code":503,"status":"UNAVAILABLE","message":"PRIVATE_PROVIDER_BODY"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(503, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val adapter =
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = "synthetic-placeholder"),
                    json,
                    URI("http://127.0.0.1:${server.address.port}"),
                    transportDiagnostics = ParserTransportDiagnosticPort { diagnostics += it },
                )
            val failure =
                assertFailsWith<NaturalLanguageParserException> { adapter.parse(request().copy(callTimeout = Duration.ofMillis(750))) }
            assertEquals(ParserFailureKind.SERVER, failure.kind)
            assertEquals(1, requests.get())
            val observed = diagnostics.single().calls.single()
            assertEquals(Duration.ofMillis(750).toNanos(), observed.requestedTimeoutNanos)
            assertEquals(Duration.ofMillis(750).toNanos(), observed.adapterBudgetNanos)
            val actualTimeout = assertNotNull(observed.actualCallTimeoutNanos)
            assertTrue(actualTimeout >= 0)
            assertTrue(observed.settled)
            assertTrue(observed.events.any { it.kind == ParserDiagnosticEventKind.REQUEST_HEADERS_END })
            assertFalse(json.writeValueAsString(diagnostics).contains("PRIVATE_"))
        } finally {
            server.stop(0)
        }
    }

    private fun session() =
        ParserTransportObservationSession(
            context,
            AnalysisProvider.GEMINI,
            ParserTransportObservationPort { normal += it },
            ParserTransportDiagnosticPort { diagnostics += it },
            { clock },
        )

    private fun call(
        timeout: Timeout = Timeout(),
        canceled: Boolean = false,
    ): Call =
        mock(Call::class.java).also {
            `when`(it.timeout()).thenReturn(timeout)
            `when`(it.isCanceled()).thenReturn(canceled)
            `when`(
                it.request(),
            ).thenReturn(
                Request
                    .Builder()
                    .url("http://127.0.0.1:9/PRIVATE_URL?key=PRIVATE_QUERY")
                    .header("Authorization", "PRIVATE_KEY")
                    .build(),
            )
        }

    private fun response(call: Call): Response =
        Response
            .Builder()
            .request(call.request())
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("PRIVATE_MESSAGE")
            .build()

    private fun request() =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 3),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "PRIVATE_INPUT", Locale.KOREAN, LocalDate.of(2026, 11, 2))),
            observationContext = context,
        )

    private class PrivateUnlistedIOException(
        message: String,
    ) : IOException(message)
}
