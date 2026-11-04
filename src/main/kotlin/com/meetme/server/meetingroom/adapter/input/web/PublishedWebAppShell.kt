package com.meetme.server.meetingroom.adapter.input.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

@Configuration
class RoomPreviewConfiguration {
    @Bean
    fun webAppShell(): WebAppShell = PublishedWebAppShell()
}

/** Fetch only the current public shell, never a request-derived host/path or a room URL. */
class PublishedWebAppShell(
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
) : WebAppShell {
    init {
        require(client.followRedirects() == HttpClient.Redirect.NEVER) { "Web shell redirects must be disabled" }
    }

    override fun load(): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("$ORIGIN/index.html"))
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "text/html")
                .header("Cache-Control", "no-cache")
                .GET()
                .build()
        val pending = client.sendAsync(request) { LimitedHtmlSubscriber() }
        val response =
            try {
                // Includes streaming body completion, unlike a timeout on headers/InputStream alone.
                pending.get(3, TimeUnit.SECONDS)
            } catch (failure: Exception) {
                pending.cancel(true)
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw IllegalStateException("Published Web shell unavailable")
            }
        check(response.statusCode() == 200) { "Published Web shell unavailable" }
        val contentType =
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .substringBefore(';')
                .trim()
        check(contentType.equals("text/html", ignoreCase = true)) { "Published Web shell is not HTML" }
        val bytes = response.body()
        check(bytes.size <= MAX_BYTES) { "Published Web shell too large" }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val ORIGIN = "https://app.meet-me.co.kr"
        const val MAX_BYTES = 262144
    }
}

/** Limit streaming/chunked bodies before allocation; the caller also bounds the whole exchange. */
internal class LimitedHtmlSubscriber : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val output = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (result.isDone) return
        for (buffer in item) {
            if (buffer.remaining() > PublishedWebAppShell.MAX_BYTES - output.size()) {
                subscription.cancel()
                result.completeExceptionally(IllegalStateException("Published Web shell too large"))
                return
            }
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            output.write(chunk)
        }
        subscription.request(1)
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(output.toByteArray())
    }
}
