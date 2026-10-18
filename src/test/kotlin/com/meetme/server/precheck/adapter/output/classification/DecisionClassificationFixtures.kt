package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.OkHttpClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent official Decisions wire fixtures. Every number below is synthetic, not a measurement. */
internal object DecisionClassificationFixtures {
    val mapper = JsonMapper.builder().build()
    val request =
        AmbiguityClassificationRequest(
            "  주말 아무 때나, 민수 이후만 가능. 단 토요일 저녁 제외  ",
            Instant.parse("2026-10-18T03:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 18),
            LocalDate.of(2026, 10, 31),
            Duration.ofSeconds(2),
        )
    const val KEY = "synthetic-decision-key-private"
    const val USAGE =
        """{"input_tokens":137,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":0},""" +
            """"output_tokens":7,"output_tokens_details":{"reasoning_tokens":2},"total_tokens":144}"""

    fun probabilities(choice: String = "CLEAR") =
        listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").joinToString(",", "[", "]") {
            """{"value":"$it","probability":${if (it == choice) "0.8" else "0.1"}}"""
        }

    fun answer(
        choice: String = "CLEAR",
        confidence: String = "0.9",
        probabilities: String = probabilities(choice),
    ) = """{"type":"choice","name":"material_ambiguity","choice":"$choice","confidence":$confidence,"probabilities":$probabilities}"""

    fun envelope(
        answer: String = answer(),
        usage: String? = USAGE,
    ) = """{"model":"gpt-6-luna","answers":[$answer]${if (usage == null) "" else ",\"usage\":$usage"}}"""

    data class Sent(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    class Endpoint(
        val status: Int = 200,
        val body: String = envelope(),
        val stall: Boolean = false,
        val slowChunks: Boolean = false,
        val disconnect: Boolean = false,
    ) : AutoCloseable {
        val registry = SimpleMeterRegistry()
        val sent = CopyOnWriteArrayList<Sent>()
        val bodyStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}/v1/decisions")

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    sent +=
                        Sent(
                            exchange.requestMethod,
                            exchange.requestURI.path,
                            exchange.requestURI.rawQuery,
                            exchange.requestHeaders.getFirst("Authorization"),
                            exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                        )
                    if (disconnect) return@createContext
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    if (status in 300..399) exchange.responseHeaders.add("Location", "/must-not-follow")
                    if (status == 503) exchange.responseHeaders.add("Retry-After", "0")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    if (stall) {
                        exchange.responseBody.write(bytes, 0, 1)
                        exchange.responseBody.flush()
                        bodyStarted.countDown()
                        release.await(3, TimeUnit.SECONDS)
                    } else if (slowChunks) {
                        for (index in bytes.indices step 20) {
                            exchange.responseBody.write(bytes, index, minOf(20, bytes.size - index))
                            exchange.responseBody.flush()
                            bodyStarted.countDown()
                            Thread.sleep(40)
                        }
                    } else {
                        exchange.responseBody.write(bytes)
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun classifier(
            enabled: Boolean = true,
            threshold: Double? = 0.8,
            key: String = KEY,
            permissiveClient: Boolean = false,
        ) = OpenAiAmbiguityClassifier(
            key,
            mapper,
            AmbiguityClassifierOptions(endpoint, enabled, threshold),
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(
                    permissiveClient,
                ).followRedirects(permissiveClient)
                .followSslRedirects(permissiveClient)
                .build(),
            registry,
        )

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
