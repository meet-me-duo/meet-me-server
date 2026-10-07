package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Issue #96 physical transport boundaries. Raw loopback TCP only; no credentials or external calls. */
class LunaTransportNoReplayContractTest {
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "수요일 19–21", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )

    @Test
    fun `connection reset before response never replays one Luna physical attempt`() {
        RawEndpoint { socket ->
            socket.setSoLinger(true, 0)
            socket.close()
        }.use { endpoint ->
            val failure = assertFailsWith<NaturalLanguageParserException> { endpoint.adapter().parse(request) }

            assertTrue(failure.kind in setOf(ParserFailureKind.NETWORK, ParserFailureKind.TIMEOUT))
            assertEquals(1, endpoint.accepted.get(), "A transport connection reset must not create another provider exchange")
        }
    }

    @Test
    fun `timeout includes unfinished chunked body and cancels the server observed connection`() {
        val bodyStarted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val requestCount = AtomicInteger()
        RawEndpoint { socket ->
            readRequest(socket)
            requestCount.incrementAndGet()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                write("1\r\n{\r\n".toByteArray())
                flush()
            }
            bodyStarted.countDown()
            socket.soTimeout = 2_000
            // The chunked body deliberately has no terminating chunk. Cancellation must close the exchange.
            val connectionClosed =
                try {
                    socket.getInputStream().read() == -1
                } catch (_: java.net.SocketTimeoutException) {
                    false
                } catch (_: java.io.IOException) {
                    true
                }
            if (connectionClosed) disconnected.countDown()
        }.use { endpoint ->
            val started = System.nanoTime()
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    endpoint.adapter().parse(request.copy(callTimeout = Duration.ofMillis(350)))
                }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)

            assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS), "The timeout must happen after response headers and a body chunk")
            assertTrue(elapsed < Duration.ofSeconds(2), "The unfinished body must remain inside the caller's budget")
            assertTrue(disconnected.await(2, TimeUnit.SECONDS), "Cancellation must reach the actual server socket")
            assertEquals(1, endpoint.accepted.get())
            assertEquals(1, requestCount.get())
        }
    }

    private fun readRequest(socket: Socket) {
        socket.soTimeout = 2_000
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0) { "Connection closed before request headers" }
            header.append(next.toChar())
        }
        val length =
            Regex("(?im)^Content-Length: (\\d+)")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        repeat(length) { check(input.read() >= 0) }
    }

    private class RawEndpoint(
        action: (Socket) -> Unit,
    ) : AutoCloseable {
        val accepted = AtomicInteger()
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()

        init {
            executor.submit {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    executor.submit { socket.use(action) }
                }
            }
        }

        fun adapter() =
            OpenAiLunaNaturalLanguageParserAdapter(
                OpenAiProperties(apiKey = "synthetic-loopback-placeholder"),
                JsonMapper.builder().build(),
                OkHttpClient.Builder().build(),
                URI.create("http://127.0.0.1:${server.localPort}/responses"),
            )

        override fun close() {
            server.close()
            executor.shutdownNow()
            check(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
