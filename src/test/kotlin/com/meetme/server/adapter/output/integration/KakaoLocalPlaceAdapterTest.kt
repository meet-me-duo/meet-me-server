package com.meetme.server.adapter.output.integration

import com.meetme.server.application.port.output.NormalizedPlaceSnapshot
import com.meetme.server.application.port.output.PlaceNormalizationResult
import com.meetme.server.application.port.output.PlaceSearchException
import com.meetme.server.application.port.output.PlaceSearchFailureKind
import com.meetme.server.application.port.output.PlaceSearchPort
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KakaoLocalPlaceAdapterTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `resolves only exact normalized name found across all pageable results`() {
        val requests = CopyOnWriteArrayList<String>()
        val adapter =
            adapter { exchange ->
                requests += exchange.requestURI.rawQuery.orEmpty()
                val page = queryParameter(exchange, "page")?.toInt() ?: 1
                when (page) {
                    1 -> respond(exchange, 200, response(documents("other-1", "다른 장소"), isEnd = false))
                    2 -> respond(exchange, 200, response(documents("target", "  봉천역  "), isEnd = false))
                    else -> respond(exchange, 200, response(documents("other-2", "봉천역입구"), isEnd = true))
                }
            }

        val result = adapter.normalize("봉천역")

        val resolved = assertIs<PlaceNormalizationResult.Resolved>(result)
        assertEquals("target", resolved.place.providerPlaceId)
        assertEquals("봉천역", resolved.place.displayName.trim())
        assertEquals(3, requests.size)
        assertTrue(requests.all { "size=15" in it })
    }

    @Test
    fun `returns no exact match when provider has only partial names`() {
        val adapter =
            adapter { exchange ->
                respond(exchange, 200, response(documents("partial", "봉천역입구"), isEnd = true))
            }

        assertEquals(PlaceNormalizationResult.NoExactMatch, adapter.normalize("봉천역"))
    }

    @Test
    fun `returns ambiguous when two exact normalized names exist on different pages`() {
        val adapter =
            adapter { exchange ->
                val page = queryParameter(exchange, "page")?.toInt() ?: 1
                if (page == 1) {
                    respond(exchange, 200, response(documents("first", "봉천 역"), isEnd = false))
                } else {
                    respond(exchange, 200, response(documents("second", "봉천역"), isEnd = true))
                }
            }

        assertEquals(PlaceNormalizationResult.AmbiguousExactMatch, adapter.normalize("  봉천역 "))
    }

    @Test
    fun `retries retryable provider response only through initial plus two attempts`() {
        val calls = AtomicInteger()
        val adapter =
            adapter { exchange ->
                calls.incrementAndGet()
                respond(exchange, 500, "{}")
            }

        val failure = assertThrows<PlaceSearchException> { adapter.normalize("봉천역") }

        assertEquals(PlaceSearchFailureKind.SERVER, failure.kind)
        assertEquals(3, calls.get())
    }

    @Test
    fun `does not retry non retryable provider response`() {
        val calls = AtomicInteger()
        val adapter =
            adapter { exchange ->
                calls.incrementAndGet()
                respond(exchange, 400, "{}")
            }

        val failure = assertThrows<PlaceSearchException> { adapter.normalize("봉천역") }

        assertEquals(PlaceSearchFailureKind.CLIENT, failure.kind)
        assertEquals(1, calls.get())
    }

    @Test
    fun `keeps Kakao response types outside the outbound port contract`() {
        val returnTypes =
            PlaceSearchPort::class.java.methods
                .filter { it.declaringClass == PlaceSearchPort::class.java }
                .map { it.genericReturnType.typeName }

        assertTrue(returnTypes.none { "Kakao" in it || "adapter.output.integration" in it })
        assertTrue(NormalizedPlaceSnapshot::class.java.packageName.startsWith("com.meetme.server.application.port.output"))
    }

    private fun adapter(handler: (HttpExchange) -> Unit): KakaoLocalPlaceAdapter {
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        local.createContext("/v2/local/search/keyword.json", handler)
        local.start()
        server = local
        return KakaoLocalPlaceAdapter(
            KakaoLocalProperties(
                apiKey = "test-key-not-a-secret",
                baseUrl = "http://127.0.0.1:${local.address.port}",
            ),
        )
    }

    private fun queryParameter(
        exchange: HttpExchange,
        name: String,
    ): String? =
        exchange.requestURI.rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.first() == name }
            ?.getOrNull(1)

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun documents(
        id: String,
        name: String,
    ): String = """{"id":"$id","place_name":"$name","x":"126.946","y":"37.482"}"""

    private fun response(
        document: String,
        isEnd: Boolean,
    ): String = """{"meta":{"is_end":$isEnd,"pageable_count":45,"total_count":45},"documents":[$document]}"""
}
