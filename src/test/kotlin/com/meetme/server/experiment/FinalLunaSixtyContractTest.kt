package com.meetme.server.experiment

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transport-only synthetic fixtures; no credentials, provider calls, or semantic success claims. */
class FinalLunaSixtyContractTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `200 preserves exact request and response with sixty second cap and no second transmission`() {
        Fixture(200).use { fixture ->
            val output = temporary.resolve(UUID.randomUUID().toString())
            val probe = FinalLunaSixtyProbe(output, sha(PAYLOAD))
            val report = probe.run("synthetic-placeholder", PAYLOAD, fixture.endpoint)

            assertEquals(true, report["transportSucceeded"])
            assertNull(report["failure"])
            assertEquals(200, report["httpStatus"])
            assertEquals(1L, number(report, "physicalAdmissions"))
            assertEquals(Duration.ofSeconds(60).toNanos(), number(report, "configuredTimeoutNanos"))
            assertTrue(number(report, "elapsedNanos") > 0)
            assertContentEquals(PAYLOAD, fixture.requests.single())
            assertEquals(sha(PAYLOAD), sha(fixture.requests.single()))
            assertContentEquals("{}".toByteArray(), Files.readAllBytes(output.resolve("response.json")))
            val events = report["events"] as List<*>
            val start = events.filterIsInstance<Map<*, *>>().single { it["event"] == "CALL_START" }
            assertTrue((start["configuredTimeoutNanos"] as Number).toLong() in 1..Duration.ofSeconds(60).toNanos())

            runCatching { probe.run("synthetic-placeholder", PAYLOAD, fixture.endpoint) }
            assertEquals(1, fixture.requests.size)
            assertEquals(1, probe.ledger.admissions)
        }
    }

    @Test
    fun `503 retry after zero preserves status and cannot trigger a follow up request`() {
        Fixture(503).use { fixture ->
            val probe = probe()
            val report = probe.run("synthetic-placeholder", PAYLOAD, fixture.endpoint)

            assertEquals(false, report["transportSucceeded"])
            assertEquals("HTTP_503", report["failure"])
            assertEquals("SERVER", report["failureKind"])
            assertEquals(503, report["httpStatus"])
            assertEquals(1L, number(report, "physicalAdmissions"))
            assertEquals(1, fixture.requests.size)
            assertEquals(1, probe.ledger.admissions)
        }
    }

    @Test
    fun `short caller deadline stops delayed response as timeout after one transmission`() {
        Fixture(200, delay = true).use { fixture ->
            val report = probe().run("synthetic-placeholder", PAYLOAD, fixture.endpoint, Duration.ofMillis(250))

            assertEquals(false, report["transportSucceeded"])
            assertEquals("TIMEOUT", report["failure"])
            assertNull(report["httpStatus"])
            assertEquals(Duration.ofMillis(250).toNanos(), number(report, "configuredTimeoutNanos"))
            assertTrue(number(report, "elapsedNanos") in Duration.ofMillis(150).toNanos()..Duration.ofSeconds(3).toNanos())
            assertEquals(1L, number(report, "physicalAdmissions"))
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `payload mismatch foreign endpoint and expanded timeout fail before any admission`() {
        Fixture(200).use { fixture ->
            val wrongPayload = probe()
            assertFailsWith<IllegalArgumentException> {
                wrongPayload.run("synthetic-placeholder", "{}".toByteArray(), fixture.endpoint)
            }
            assertEquals(0, wrongPayload.ledger.admissions)

            val foreign = probe()
            assertFailsWith<IllegalArgumentException> {
                foreign.run("synthetic-placeholder", PAYLOAD, URI("https://example.invalid/v1/responses"))
            }
            assertEquals(0, foreign.ledger.admissions)

            val expanded = probe()
            assertFailsWith<IllegalArgumentException> {
                expanded.run("synthetic-placeholder", PAYLOAD, fixture.endpoint, Duration.ofSeconds(61))
            }
            assertEquals(0, expanded.ledger.admissions)
            assertEquals(0, fixture.requests.size)
        }
    }

    private fun probe(): FinalLunaSixtyProbe = FinalLunaSixtyProbe(temporary.resolve(UUID.randomUUID().toString()), sha(PAYLOAD))

    private fun number(
        report: Map<String, Any?>,
        key: String,
    ): Long = (report.getValue(key) as Number).toLong()

    private class Fixture(
        status: Int,
        delay: Boolean = false,
    ) : AutoCloseable {
        val requests = ConcurrentLinkedQueue<ByteArray>()
        private val release = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: URI

        init {
            server.executor = executor
            server.createContext("/v1/responses") { exchange ->
                exchange.use {
                    requests += it.requestBody.readAllBytes()
                    if (delay) release.await(5, TimeUnit.SECONDS)
                    if (status == 503) it.responseHeaders.add("Retry-After", "0")
                    val body = "{}".toByteArray()
                    runCatching {
                        it.sendResponseHeaders(status, body.size.toLong())
                        it.responseBody.write(body)
                    }
                }
            }
            server.start()
            endpoint = URI("http://127.0.0.1:${server.address.port}/v1/responses")
        }

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private val PAYLOAD =
            """
            {"model":"gpt-6-luna","store":false,"input":"Synthetic transport fixture only.","max_output_tokens":32768,
            "text":{"format":{"type":"json_schema","name":"synthetic","strict":true,
            "schema":{"type":"object","properties":{},"required":[],"additionalProperties":false}}}}
            """.trimIndent().toByteArray()

        private fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
