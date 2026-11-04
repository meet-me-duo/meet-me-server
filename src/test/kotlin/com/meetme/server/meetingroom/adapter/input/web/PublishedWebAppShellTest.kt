package com.meetme.server.meetingroom.adapter.input.web

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class PublishedWebAppShellTest {
    @Test
    fun `loads current HTML only from fixed HTTPS index with no credentials or conditional cache`() {
        var calls = 0
        val client = client()
        `when`(client.sendAsync(any(HttpRequest::class.java), anyHandler())).thenAnswer { invocation ->
            val request = invocation.getArgument<HttpRequest>(0)
            assertEquals("https://app.meet-me.co.kr/index.html", request.uri().toString())
            assertEquals("GET", request.method())
            assertEquals(
                setOf("accept", "cache-control"),
                request
                    .headers()
                    .map()
                    .keys
                    .map { it.lowercase() }
                    .toSet(),
            )
            assertEquals("no-cache", request.headers().firstValue("Cache-Control").orElseThrow())
            assertEquals(3L, request.timeout().orElseThrow().seconds)
            calls++
            CompletableFuture.completedFuture(response(200, "text/html; charset=utf-8", "shell$calls"))
        }
        val shell = PublishedWebAppShell(client)
        assertEquals("shell1", shell.load())
        assertEquals("shell2", shell.load())
    }

    @Test
    fun `rejects redirects errors non HTML and oversized published shell`() {
        for (response in listOf(
            response(302, "text/html", "redirect"),
            response(500, "text/html", "failure"),
            response(200, "application/json", "{}"),
            response(200, "text/html", "a".repeat(PublishedWebAppShell.MAX_BYTES + 1)),
        )) {
            val client = client()
            `when`(client.sendAsync(any(HttpRequest::class.java), anyHandler()))
                .thenReturn(CompletableFuture.completedFuture(response))
            assertFails { PublishedWebAppShell(client).load() }
        }
        PublishedWebAppShell()
        val unsafe = client()
        `when`(unsafe.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS)
        assertFails { PublishedWebAppShell(unsafe) }
    }

    @Test
    fun `whole exchange deadline cancels a response that never finishes its body`() {
        val client = client()
        val stalled = CompletableFuture<HttpResponse<ByteArray>>()
        `when`(client.sendAsync(any(HttpRequest::class.java), anyHandler())).thenReturn(stalled)
        val before = System.nanoTime()
        assertFails { PublishedWebAppShell(client).load() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 5000)
        assertTrue(stalled.isCancelled)
    }

    @Test
    fun `streaming body accepts the exact limit and cancels before retaining excess bytes`() {
        val accepted = LimitedHtmlSubscriber()
        accepted.onSubscribe(Subscription())
        accepted.onNext(listOf(ByteBuffer.wrap(ByteArray(PublishedWebAppShell.MAX_BYTES))))
        accepted.onComplete()
        assertContentEquals(ByteArray(PublishedWebAppShell.MAX_BYTES), accepted.body.toCompletableFuture().join())

        val subscription = Subscription()
        val excess = LimitedHtmlSubscriber()
        excess.onSubscribe(subscription)
        excess.onNext(listOf(ByteBuffer.wrap(ByteArray(PublishedWebAppShell.MAX_BYTES)), ByteBuffer.wrap(byteArrayOf(1))))
        assertTrue(subscription.cancelled)
        assertTrue(excess.body.toCompletableFuture().isCompletedExceptionally)
    }

    @Test
    fun `actual installed HTTP body handler cancels oversized chunked streams before buffering`() {
        val client = client()
        val subscription = Subscription()
        `when`(client.sendAsync(any(HttpRequest::class.java), anyHandler())).thenAnswer { invocation ->
            val handler = invocation.getArgument<HttpResponse.BodyHandler<ByteArray>>(1)
            val info = mock(HttpResponse.ResponseInfo::class.java)
            val subscriber = handler.apply(info)
            subscriber.onSubscribe(subscription)
            subscriber.onNext(listOf(ByteBuffer.wrap(ByteArray(PublishedWebAppShell.MAX_BYTES + 1))))
            subscriber.onComplete()
            subscriber.body.toCompletableFuture().thenApply { response(200, "text/html", "unused") }
        }
        assertFails { PublishedWebAppShell(client).load() }
        assertTrue(subscription.cancelled)
    }

    private fun client(): HttpClient =
        mock(HttpClient::class.java).also {
            `when`(it.followRedirects()).thenReturn(HttpClient.Redirect.NEVER)
        }

    private class Subscription : Flow.Subscription {
        var cancelled = false

        override fun request(n: Long) = Unit

        override fun cancel() {
            cancelled = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun response(
        status: Int,
        type: String,
        body: String,
    ): HttpResponse<ByteArray> {
        val response = mock(HttpResponse::class.java) as HttpResponse<ByteArray>
        `when`(response.statusCode()).thenReturn(status)
        `when`(response.headers()).thenReturn(HttpHeaders.of(mapOf("Content-Type" to listOf(type))) { _, _ -> true })
        `when`(response.body()).thenReturn(body.toByteArray(Charsets.UTF_8))
        return response
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyHandler(): HttpResponse.BodyHandler<ByteArray>? =
        any(HttpResponse.BodyHandler::class.java) as HttpResponse.BodyHandler<ByteArray>?
}
