package com.meetme.server.experiment

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Free pinned-SDK tests; no production host, real credential or live-data fixture. */
class StandaloneGeminiOnceContractTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `real TLS HTTP2 SDK preserves tiny configuration and strict response`() {
        fixture(MockResponse().setBody(envelope())).use { f ->
            val report = f.run()
            assertEquals(true, report["success"])
            assertEquals(200, report["httpStatus"])
            assertEquals(23, report["inputTokens"])
            assertEquals(30, report["outputTokens"])
            assertEquals(0, report["thoughtTokens"])
            assertEquals(53, report["totalTokens"])
            assertNull(report["billedCost"])
            assertEquals(false, report["billedCostObserved"])
            assertEquals(1, report["admissions"])
            assertEquals(1, report["headersStarted"])
            assertEquals(1, report["headersEmitted"])
            val wire = f.server.takeRequest(1, TimeUnit.SECONDS)!!
            assertTrue(wire.path!!.contains("gemini-3.8-flash:generateContent"))
            val body = StandaloneGeminiOnceDiagnostic.JSON.readTree(wire.body.readUtf8())
            assertEquals(128, body["generationConfig"]["maxOutputTokens"].asInt())
            assertEquals(1, body["generationConfig"]["candidateCount"].asInt())
            assertEquals("LOW", body["generationConfig"]["thinkingConfig"]["thinkingLevel"].asString())
            assertTrue(StandaloneGeminiOnceDiagnostic.PROMPT.toByteArray().size < 512)
            assertTrue(StandaloneGeminiOnceDiagnostic.JSON.writeValueAsBytes(body).size <= 4_096)
            f.assertOneH2()
        }
    }

    @Test
    fun `503 terminates without SDK retry sleep or fallback and keeps unknown usage null`() {
        val error = """{"error":{"code":503,"status":"UNAVAILABLE","message":"PRIVATE_ERROR_BODY_HEADER_KEY"}}"""
        fixture(MockResponse().setResponseCode(503).setBody(error)).use { f ->
            val report = f.run()
            assertEquals(false, report["success"])
            assertEquals("SERVER", report["failureKind"])
            assertEquals(503, report["httpStatus"])
            assertNull(report["inputTokens"])
            assertNull(report["outputTokens"])
            assertNull(report["totalTokens"])
            assertEquals(0, report["automaticRetries"])
            assertEquals(0, report["fallbackCalls"])
            assertFalse(StandaloneGeminiOnceDiagnostic.JSON.writeValueAsString(report).contains("PRIVATE_"))
            assertTrue((report["elapsedNanos"] as Long) < Duration.ofSeconds(3).toNanos())
            f.assertOneH2()
        }
    }

    @Test
    fun `redirect response never sends a second request`() {
        fixture(
            MockResponse().setResponseCode(307).addHeader("Location", "https://generativelanguage.googleapis.com/forbidden-follow"),
        ).use { f ->
            val report = f.run()
            assertEquals(false, report["success"])
            assertEquals(307, report["httpStatus"])
            assertEquals(1, report["headersEmitted"])
            f.assertOneH2()
        }
    }

    @Test
    @Timeout(45)
    fun `standalone thirty second timeout is effective on delayed real HTTP2`() {
        fixture(MockResponse().setBody(envelope()).setHeadersDelay(32, TimeUnit.SECONDS)).use { f ->
            val report = f.run(StandaloneGeminiOnceDiagnostic.TIMEOUT)
            assertEquals(false, report["success"])
            assertEquals("TIMEOUT", report["failureKind"])
            assertNull(report["httpStatus"])
            assertNull(report["totalTokens"])
            assertTrue((report["elapsedNanos"] as Long) in Duration.ofSeconds(29).toNanos()..Duration.ofSeconds(33).toNanos())
            assertTrue((report["sdkRequestTimeoutMillis"] as Long) in 29_000..30_000)
            f.assertOneH2()
        }
    }

    @Test
    fun `stalled response body also obeys caller budget with no resend`() {
        fixture(MockResponse().setBody(envelope()).setBodyDelay(2, TimeUnit.SECONDS)).use { f ->
            val report = f.run(Duration.ofMillis(500))
            assertEquals(false, report["success"])
            assertEquals("TIMEOUT", report["failureKind"])
            assertEquals(200, report["httpStatus"])
            assertTrue(Files.exists(f.output.resolve("results.json")))
            f.assertOneH2()
        }
    }

    @Test
    fun `same diagnostic cannot dispatch twice and original report is not overwritten`() {
        fixture(MockResponse().setBody(envelope())).use { f ->
            val probe = StandaloneGeminiOnceDiagnostic(f.output, UUID.randomUUID())
            assertEquals(true, probe.run("synthetic-placeholder", f.server.url("/").toUri(), f.client)["success"])
            val original = Files.readAllBytes(f.output.resolve("results.json"))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                probe.run("synthetic-placeholder", f.server.url("/").toUri(), f.client)
            }
            assertTrue(original.contentEquals(Files.readAllBytes(f.output.resolve("results.json"))))
            f.assertOneH2()
        }
    }

    @Test
    fun `wire ledger concurrent admission has one winner and refuses reopening`() {
        val file = temporary.resolve("counter.json")
        val ledger = StandaloneGeminiOnceDiagnostic.OnceLedger(file)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..8).map { Callable { runCatching { ledger.admit() }.isSuccess } }).map { it.get() }
            assertEquals(1, results.count { it })
            assertEquals(1, StandaloneGeminiOnceDiagnostic.JSON.readTree(Files.readAllBytes(file))["admissions"].asInt())
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { StandaloneGeminiOnceDiagnostic.OnceLedger(file) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `one approval cannot be reused with a new manifest or plan id`() {
        val approval = "Sentinel_fd2804bb90af7a8473a4a2567ec4fec4"
        val claim = StandaloneGeminiOnceDiagnostic.claimApproval(temporary, approval, "a".repeat(64))
        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            StandaloneGeminiOnceDiagnostic.claimApproval(temporary, approval, "b".repeat(64))
        }
        assertEquals("manifestSha256=${"a".repeat(64)}\n", Files.readString(claim))
    }

    @Test
    fun `malformed tiny replies never report successful structured interpretation`() {
        for (response in listOf("{}", """{"date":"2026-11-13","start":"19:00","end":"20:00","extra":1}""")) {
            fixture(MockResponse().setBody(envelope(response))).use { f ->
                val report = f.run()
                assertEquals(false, report["success"])
                assertEquals("INVALID_RESPONSE", report["failureKind"])
                assertEquals(200, report["httpStatus"])
                f.assertOneH2()
            }
        }
        assertFailsWith<tools.jackson.core.JacksonException> {
            StandaloneGeminiOnceDiagnostic.strictResponse("""{"date":"2026-11-13","start":"19:00","end":"20:00"} {}""")
        }
        assertFailsWith<tools.jackson.core.JacksonException> {
            StandaloneGeminiOnceDiagnostic.strictResponse("""{"date":"2026-11-13","start":"19:00","start":"19:00","end":"20:00"}""")
        }
    }

    private fun fixture(response: MockResponse) = Fixture(response, temporary.resolve(UUID.randomUUID().toString()))

    private class Fixture(
        response: MockResponse,
        val output: Path,
    ) : AutoCloseable {
        val server = MockWebServer()
        private val certificate =
            HeldCertificate
                .Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val protocols = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .addNetworkInterceptor { chain ->
                    protocols += assertNotNull(chain.connection()).protocol().toString()
                    chain.proceed(chain.request())
                }.build()

        init {
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.enqueue(response)
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        }

        fun run(timeout: Duration = Duration.ofSeconds(5)) =
            StandaloneGeminiOnceDiagnostic(output, UUID.randomUUID()).run("synthetic-placeholder", server.url("/").toUri(), client, timeout)

        fun assertOneH2() {
            assertEquals(1, server.requestCount)
            // The pre-guard network interceptor also observes a refused second call, so it is not a sent-header counter.
            assertTrue(protocols.isNotEmpty() && protocols.all { it == "h2" })
        }

        override fun close() = server.shutdown()
    }

    private fun envelope(text: String = """{"date":"2026-11-13","start":"19:00","end":"20:00"}""") =
        StandaloneGeminiOnceDiagnostic.JSON.writeValueAsString(
            mapOf(
                "candidates" to
                    listOf(
                        mapOf("content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to text))), "finishReason" to "STOP"),
                    ),
                "usageMetadata" to
                    mapOf("promptTokenCount" to 23, "candidatesTokenCount" to 30, "thoughtsTokenCount" to 0, "totalTokenCount" to 53),
            ),
        )
}
