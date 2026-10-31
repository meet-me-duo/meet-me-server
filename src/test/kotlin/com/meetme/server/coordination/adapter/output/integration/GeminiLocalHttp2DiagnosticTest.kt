package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
import com.google.genai.types.ClientOptions
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserDiagnosticEventKind
import com.meetme.server.coordination.application.port.output.ParserDiagnosticResetCode
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.http2.ErrorCode
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real pinned SDK and TLS/ALPN HTTP/2 on loopback; no real credentials or provider endpoints. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(30)
class GeminiLocalHttp2DiagnosticTest {
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 11, 13),
            LocalDate.of(2026, 11, 14),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "11월 13일 오후 7시부터 9시 가능", Locale.KOREAN, LocalDate.of(2026, 11, 13))),
            observationContext = ParserObservationContext(UUID(0, 2), UUID(0, 3)),
        )

    @Test
    @Order(1)
    fun `fast HTTP2 synthetic SDK reply is a valid success control`() {
        Endpoint(MockResponse().setBody(envelope())).use { fixture ->
            val result = fixture.parse(request.copy(callTimeout = Duration.ofSeconds(5)), "fast-success")
            assertEquals(
                1,
                result.results
                    .single()
                    .conditions.size,
            )
            fixture.assertOneHttp2Request()
            assertTrue(
                fixture
                    .snapshot()
                    .calls
                    .single()
                    .events
                    .any { it.kind == ParserDiagnosticEventKind.CALL_END },
            )
        }
    }

    @Test
    @Order(2)
    fun `caller thirty seconds is capped by adapter fifteen seconds over real HTTP2`() {
        Endpoint(MockResponse().setBody(envelope()).setHeadersDelay(18, TimeUnit.SECONDS)).use { fixture ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    fixture.parse(request.copy(callTimeout = Duration.ofSeconds(30)), "adapter-cap-15s")
                }
            assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
            fixture.assertOneHttp2Request()
            val call = fixture.snapshot().calls.single()
            assertEquals(Duration.ofSeconds(30).toNanos(), call.requestedTimeoutNanos)
            assertEquals(Duration.ofSeconds(15).toNanos(), call.adapterBudgetNanos)
            assertTrue(assertNotNull(call.actualCallTimeoutNanos) in Duration.ofSeconds(14).toNanos()..Duration.ofSeconds(15).toNanos())
            assertTrue(fixture.elapsedNanos in Duration.ofSeconds(14).toNanos()..Duration.ofSeconds(18).toNanos())
            assertTrue(call.events.any { it.kind == ParserDiagnosticEventKind.CANCELED })
            assertTrue(call.events.any { it.kind == ParserDiagnosticEventKind.CALL_FAILED })
            assertTrue(call.events.none { it.kind == ParserDiagnosticEventKind.RESPONSE_HEADERS_END })
        }
    }

    @Test
    @Order(3)
    fun `shorter caller timeout bounds an HTTP2 response before adapter cap`() {
        Endpoint(MockResponse().setBody(envelope()).setHeadersDelay(5, TimeUnit.SECONDS)).use { fixture ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    fixture.parse(request.copy(callTimeout = Duration.ofMillis(500)), "caller-cap-500ms")
                }
            assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
            fixture.assertOneHttp2Request()
            assertEquals(
                Duration.ofMillis(500).toNanos(),
                fixture
                    .snapshot()
                    .calls
                    .single()
                    .adapterBudgetNanos,
            )
            assertTrue(fixture.elapsedNanos in Duration.ofMillis(350).toNanos()..Duration.ofSeconds(3).toNanos())
        }
    }

    @Test
    @Order(4)
    fun `injected remote HTTP2 CANCEL is an early reset rather than proof of local timeout`() {
        val reset = MockResponse().setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START).setHttp2ErrorCode(ErrorCode.CANCEL.httpCode)
        Endpoint(reset).use { fixture ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    fixture.parse(request.copy(callTimeout = Duration.ofSeconds(5)), "remote-cancel-control")
                }
            assertEquals(ParserFailureKind.NETWORK, failure.kind)
            fixture.assertOneHttp2Request()
            assertEquals(
                ParserDiagnosticResetCode.CANCEL,
                fixture
                    .snapshot()
                    .calls
                    .single()
                    .streamResetCode,
            )
            assertTrue(fixture.elapsedNanos < Duration.ofSeconds(3).toNanos())
        }
    }

    @Test
    @Order(5)
    fun `deadline before execute arms watchdog while later setter alone does not reschedule it`() {
        rawDeadlineCall(late = false)
        rawDeadlineCall(late = true)
    }

    @Test
    @Order(6)
    fun `real gate late deadline rejects late successful reply without rearming SDK watchdog`() {
        Endpoint(MockResponse().setBody(envelope()).setHeadersDelay(1_200, TimeUnit.MILLISECONDS)).use { fixture ->
            val snapshots =
                request.inputs.map {
                    AiDispatchInputSnapshot(
                        it.inputRef,
                        it.rawText,
                        it.locale.toLanguageTag(),
                        it.referenceDate,
                    )
                }
            val execution =
                AiBudgetExecution(
                    UUID(0, 10),
                    AiBudgetExecutionKind.FINAL_ANALYSIS,
                    UUID(0, 11),
                    0,
                    UUID(0, 12),
                    0,
                    AiDispatchFingerprints.batchSource(snapshots),
                    AiDispatchFingerprints.batchContext(request.timeZone, request.searchStartDate, request.searchEndDate, snapshots),
                )
            val gate =
                AiHttpDispatchGate(
                    AiDispatchGateProperties(enabled = true),
                    mock(DurableAiBudgetPort::class.java),
                    Clock.systemUTC(),
                    runtimeGuard =
                        AiRuntimeDispatchGuardPort { _, _ ->
                            java.time.Instant
                                .now()
                                .plusSeconds(60)
                        },
                    enforceBudget = false,
                )
            val cutoff = System.nanoTime() + Duration.ofMillis(700).toNanos()
            val input =
                request.copy(
                    callTimeout = Duration.ofSeconds(2),
                    dispatchContext = AiProviderDispatchContext(execution, cutoff, UUID(0, 13)),
                )
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.parse(input, "real-gate-late-deadline", gate) }
            assertEquals(ParserFailureKind.CONFIGURATION, failure.kind, "Existing successful-reply observation denial remains fail-closed")
            fixture.assertOneHttp2Request()
            assertTrue(System.nanoTime() > cutoff)
            val call = fixture.snapshot().calls.single()
            assertTrue(call.events.any { it.kind == ParserDiagnosticEventKind.CALL_END })
            assertTrue(call.events.any { it.kind == ParserDiagnosticEventKind.RESPONSE_HEADERS_END })
            assertTrue(call.events.none { it.kind == ParserDiagnosticEventKind.CANCELED })
            assertTrue(fixture.elapsedNanos > Duration.ofMillis(1_100).toNanos())
            assertTrue(fixture.elapsedNanos < Duration.ofSeconds(2).toNanos())
            assertTrue(assertNotNull(call.requestHeadersDeadlineRemainingNanos) < Duration.ofMillis(700).toNanos())
        }
    }

    private fun rawDeadlineCall(late: Boolean) {
        Endpoint(MockResponse().setBody("synthetic").setHeadersDelay(1_200, TimeUnit.MILLISECONDS)).use { fixture ->
            var configuredAt = 0L
            var cutoff = 0L
            val diagnostics = mutableListOf<ParserTransportDiagnosticSnapshot>()
            val observation =
                ParserTransportObservationSession(
                    request.observationContext,
                    com.meetme.server.coordination.application.port.output.AnalysisProvider.GEMINI,
                    null,
                    ParserTransportDiagnosticPort { diagnostics += it },
                )
            observation.configureTimeouts(Duration.ofSeconds(3).toNanos(), Duration.ofSeconds(3).toNanos(), null)
            val client =
                fixture
                    .trustedClient(
                        OkHttpClient
                            .Builder()
                            .callTimeout(Duration.ofSeconds(3))
                            .retryOnConnectionFailure(false)
                            .build(),
                    ).newBuilder()
                    .eventListenerFactory(observation.eventListenerFactory)
                    .addInterceptor { chain ->
                        if (late) {
                            configuredAt = System.nanoTime()
                            cutoff = configuredAt + Duration.ofMillis(500).toNanos()
                            chain.call().timeout().deadlineNanoTime(cutoff)
                        }
                        chain.proceed(chain.request())
                    }.build()
            val call = client.newCall(Request.Builder().url(fixture.server.url("/deadline-control")).build())
            if (!late) {
                configuredAt = System.nanoTime()
                cutoff = configuredAt + Duration.ofMillis(500).toNanos()
                call.timeout().deadlineNanoTime(cutoff)
            }
            val started = System.nanoTime()
            try {
                if (late) {
                    call.execute().use { assertEquals("synthetic", it.body?.string()) }
                    assertTrue(System.nanoTime() > cutoff, "Getter deadline is insufficient evidence of watchdog rescheduling")
                } else {
                    assertFailsWith<java.io.InterruptedIOException> { call.execute().use { it.body?.string() } }
                    assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos())
                }
                fixture.assertOneHttp2Request()
            } finally {
                observation.finish()
                records +=
                    mapOf(
                        "case" to if (late) "raw-late-deadline" else "raw-prestart-deadline",
                        "elapsedNanos" to System.nanoTime() - started,
                        "configuredAfterStartNanos" to configuredAt - started,
                        "finishedAfterDeadlineNanos" to System.nanoTime() - cutoff,
                        "isCanceled" to call.isCanceled(),
                        "protocols" to fixture.protocols.toList(),
                        "serverRequests" to fixture.server.requestCount,
                        "transport" to diagnostics.toList(),
                        "scope" to "raw OkHttp watchdog diagnostic; not an adapter or final-result acceptance test",
                    )
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun envelope(): String {
        val structured =
            """{"schema_version":"3","results":[{"input_ref":"${request.inputs.single().inputRef}","rejection_code":null,"conditions":[{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-11-13","day_of_week":null,"start_time":"19:00","end_time":"21:00"}]}]}"""
        return json.writeValueAsString(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf(
                            "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to structured))),
                            "finishReason" to "STOP",
                        ),
                    ),
                "usageMetadata" to mapOf("promptTokenCount" to 100, "candidatesTokenCount" to 30),
            ),
        )
    }

    private class Endpoint(
        response: MockResponse,
    ) : AutoCloseable {
        val server = MockWebServer()
        val protocols = mutableListOf<String>()
        private val snapshots = mutableListOf<ParserTransportDiagnosticSnapshot>()
        var elapsedNanos = 0L
            private set

        init {
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.enqueue(response)
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        }

        // SDK and adapter options are unchanged; only this test's ephemeral loopback certificate is trusted.
        fun trustedClient(client: OkHttpClient): OkHttpClient =
            client
                .newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .addNetworkInterceptor { chain ->
                    protocols += assertNotNull(chain.connection()).protocol().toString()
                    chain.proceed(chain.request())
                }.build()

        fun parse(
            request: NaturalLanguageBatchRequest,
            label: String,
            gate: AiHttpDispatchGate? = null,
        ): com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult {
            val builder =
                object : Client.Builder() {
                    override fun clientOptions(options: ClientOptions): Client.Builder =
                        super.clientOptions(options.toBuilder().customHttpClient(trustedClient(options.customHttpClient().get())).build())
                }
            val adapter =
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = "synthetic-loopback-placeholder"),
                    json,
                    server.url("/").toUri(),
                    dispatchGate = gate,
                    transportDiagnostics = ParserTransportDiagnosticPort { snapshots += it },
                )
            val started = System.nanoTime()
            var failure: ParserFailureKind? = null
            try {
                mockStatic(Client::class.java).use { factory ->
                    factory.`when`<Client.Builder> { Client.builder() }.thenReturn(builder)
                    return adapter.parse(request)
                }
            } catch (exception: NaturalLanguageParserException) {
                failure = exception.kind
                throw exception
            } finally {
                elapsedNanos = System.nanoTime() - started
                records +=
                    mapOf(
                        "case" to label,
                        "elapsedNanos" to elapsedNanos,
                        "failureKind" to failure,
                        "serverRequests" to server.requestCount,
                        "protocols" to protocols.toList(),
                        "transport" to snapshots.toList(),
                        "evidenceCapturedBeforeFixtureShutdown" to true,
                        "threadInterruptedAtCapture" to Thread.currentThread().isInterrupted,
                        "nativeTimeoutConclusionRequiresJUnitPass" to true,
                    )
            }
        }

        fun snapshot() = snapshots.single()

        fun assertOneHttp2Request() {
            assertEquals(1, server.requestCount, "No hidden retry or second stream is allowed")
            assertEquals(listOf("h2"), protocols)
        }

        override fun close() = server.shutdown()
    }

    companion object {
        private val json = JsonMapper.builder().build()
        private val records = mutableListOf<Map<String, Any?>>()
        private val certificate =
            HeldCertificate
                .Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

        @JvmStatic
        @AfterAll
        fun exportSafeEvidence() {
            val destination = Path.of("build/reports/local-http2-diagnostic.json")
            Files.createDirectories(destination.parent)
            Files.writeString(destination, json.writeValueAsString(mapOf("providerRequests" to 0, "cases" to records)) + "\n")
        }
    }
}
