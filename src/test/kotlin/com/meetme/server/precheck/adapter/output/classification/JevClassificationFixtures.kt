package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic official System One wire fixtures, never an observed model response or quality oracle. */
internal object JevClassificationFixtures {
    val mapper = JsonMapper.builder().build()
    const val KEY = "synthetic-jev-contract-key"
    const val MODEL = "jev-1.14.0"
    const val USAGE = """{"input_tokens":137,"output_tokens":7}"""
    val request =
        AmbiguityClassificationRequest(
            "  주말 아무 때나, 다른 참여자 이후도 가능. 단 토요일 저녁 제외  ",
            Instant.parse("2026-10-18T03:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 18),
            LocalDate.of(2026, 10, 31),
            Duration.ofSeconds(2),
        )

    fun probabilities(choice: String = "CLEAR"): String =
        listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").joinToString(",", "{", "}") {
            """"$it":${if (it == choice) "0.6" else "0.2"}"""
        }

    fun answer(
        choice: String = "CLEAR",
        confidence: String = "0.4",
        probabilities: String = probabilities(choice),
    ): String = """{"type":"choice","choice":"$choice","probabilities":$probabilities,"confidence":$confidence}"""

    fun envelope(
        answer: String = answer(),
        usage: String? = USAGE,
        model: String = MODEL,
    ): String = """{"model":"$model","answers":{"material_ambiguity":$answer}${if (usage == null) "" else ",\"usage\":$usage"}}"""

    data class Sent(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val contentType: String?,
        val longContext: String?,
        val body: ByteArray,
    )

    class Endpoint(
        val status: Int = 200,
        body: String = envelope(),
        val bytes: ByteArray = body.toByteArray(Charsets.UTF_8),
        val chunked: Boolean = false,
        val stall: Boolean = false,
        val disconnect: Boolean = false,
    ) : AutoCloseable {
        val sent = CopyOnWriteArrayList<Sent>()
        val callTimeouts = CopyOnWriteArrayList<Long>()
        val bodyStarted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint: URI get() = URI("http://127.0.0.1:${server.address.port}/v1/systemone")

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
                            exchange.requestHeaders.getFirst("Content-Type"),
                            exchange.requestHeaders.getFirst("TypeSafe-Danger-Zone-Long-Context"),
                            exchange.requestBody.readAllBytes(),
                        )
                    if (disconnect) return@createContext
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    if (status in 300..399) exchange.responseHeaders.add("Location", "/must-not-follow")
                    if (status == 503 || status == 529) exchange.responseHeaders.add("Retry-After", "0")
                    exchange.sendResponseHeaders(status, if (chunked) 0 else bytes.size.toLong())
                    if (stall) {
                        exchange.responseBody.write(bytes, 0, 1)
                        exchange.responseBody.flush()
                        bodyStarted.countDown()
                        release.await(3, TimeUnit.SECONDS)
                    } else {
                        exchange.responseBody.write(bytes)
                    }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun client(): OkHttpClient =
            OkHttpClient
                .Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            if (hostname != "127.0.0.1") throw UnknownHostException("SYNTHETIC_LOOPBACK_ONLY")
                            return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                        }
                    },
                ).retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    if (chain.request().url.host != "127.0.0.1") throw UnknownHostException("SYNTHETIC_LOOPBACK_ONLY")
                    callTimeouts += chain.call().timeout().timeoutNanos()
                    chain.proceed(chain.request())
                }.build()

        fun classifier(
            enabled: Boolean = true,
            key: String = KEY,
            endpoint: URI = this.endpoint,
            model: String = MODEL,
        ): JevAmbiguityClassifier = JevAmbiguityClassifier(key, mapper, JevClassifierOptions(endpoint, enabled, model), client())

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
