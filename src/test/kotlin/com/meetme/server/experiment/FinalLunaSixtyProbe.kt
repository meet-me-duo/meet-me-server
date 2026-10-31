package com.meetme.server.experiment

import com.meetme.server.coordination.adapter.output.integration.ProviderFailureClassifier
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Standalone approved transport diagnostic; never changes the application adapter or its 27s authority. */
internal class FinalLunaSixtyProbe(
    val output: Path,
    private val expectedPayloadSha: String,
) {
    val ledger = StandaloneGeminiOnceDiagnostic.OnceLedger(output.resolve("wire-ledger.json"))
    private val events = CopyOnWriteArrayList<Map<String, Any?>>()

    fun run(
        key: String,
        payload: ByteArray,
        endpoint: URI = ENDPOINT,
        timeout: Duration = Duration.ofSeconds(60),
    ): Map<String, Any?> {
        require(endpoint == ENDPOINT || endpoint.scheme == "http" && endpoint.host in setOf("localhost", "127.0.0.1"))
        require(timeout > Duration.ZERO && timeout <= Duration.ofSeconds(60))
        require(payload.isNotEmpty() && payload.size <= 1_048_576 && sha(payload) == expectedPayloadSha)
        require(key.isNotBlank())
        check(ledger.admissions == 0 && !Files.exists(output.resolve("results.json")))
        var httpStatus: Int? = null
        var responseBytes: ByteArray? = null
        var failure: String? = null
        var causeClasses: List<String> = emptyList()
        val started = System.nanoTime()

        fun event(
            name: String,
            vararg values: Pair<String, Any?>,
        ) {
            events += mapOf("event" to name, "elapsedNanos" to System.nanoTime() - started) + values
        }
        val client =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ZERO)
                .callTimeout(timeout)
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) = event("CALL_START", "configuredTimeoutNanos" to call.timeout().timeoutNanos())

                        override fun dnsStart(
                            call: Call,
                            domainName: String,
                        ) = event("DNS_START")

                        override fun connectStart(
                            call: Call,
                            inetSocketAddress: InetSocketAddress,
                            proxy: Proxy,
                        ) = event("CONNECT_START")

                        override fun secureConnectStart(call: Call) = event("TLS_START")

                        override fun requestHeadersStart(call: Call) {
                            ledger.admit()
                            event("HEADERS_START")
                        }

                        override fun requestHeadersEnd(
                            call: Call,
                            request: Request,
                        ) = event("HEADERS_END")

                        override fun requestBodyEnd(
                            call: Call,
                            byteCount: Long,
                        ) = event("REQUEST_BODY_END", "bodyBytes" to byteCount)

                        override fun responseHeadersStart(call: Call) = event("RESPONSE_HEADERS_START")

                        override fun responseHeadersEnd(
                            call: Call,
                            response: Response,
                        ) {
                            httpStatus = response.code
                            event("RESPONSE_HEADERS", "httpStatus" to response.code)
                        }

                        override fun canceled(call: Call) = event("CANCELED")

                        override fun callEnd(call: Call) = event("CALL_END")

                        override fun callFailed(
                            call: Call,
                            ioe: IOException,
                        ) = event(
                            "CALL_FAILED",
                            "causeClass" to ioe.javaClass.simpleName,
                        )
                    },
                ).addNetworkInterceptor { chain ->
                    val outgoing = chain.request()
                    check(outgoing.method == "POST" && outgoing.url.toUri() == endpoint)
                    val sent = Buffer().also { checkNotNull(outgoing.body).writeTo(it) }.readByteArray()
                    check(sha(sent) == expectedPayloadSha)
                    val response = chain.proceed(outgoing)
                    if (response.code != 200) {
                        httpStatus = response.code
                        response.use { responseBytes = it.body?.byteStream()?.readNBytes(MAX_RESPONSE_BYTES + 1) }
                        // Stop before OkHttp's implicit Retry-After follow-up, even within the same Call.
                        throw ResponseStatus(response.code)
                    }
                    response
                }.build()
        try {
            val request =
                Request
                    .Builder()
                    .url(endpoint.toString())
                    .header("Authorization", "Bearer $key")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            val call = client.newCall(request)
            call.timeout().deadlineNanoTime(System.nanoTime() + timeout.toNanos())
            call.execute().use { response ->
                httpStatus = response.code
                responseBytes = response.body?.byteStream()?.readNBytes(MAX_RESPONSE_BYTES + 1)
            }
            if (responseBytes == null || responseBytes!!.size > MAX_RESPONSE_BYTES) failure = "RESPONSE_LIMIT"
        } catch (status: ResponseStatus) {
            failure = "HTTP_${status.status}"
        } catch (exception: Exception) {
            causeClasses = generateSequence<Throwable>(exception) { it.cause }.take(16).map { it.javaClass.simpleName }.toList()
            failure =
                if (generateSequence<Throwable>(exception) { it.cause }.take(16).any { it is InterruptedIOException }) {
                    "TIMEOUT"
                } else {
                    "NETWORK"
                }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        val elapsed = System.nanoTime() - started
        val bytes = responseBytes
        val safe = bytes != null && bytes.size <= MAX_RESPONSE_BYTES && (key.length < 5 || !bytes.toString(Charsets.UTF_8).contains(key))
        if (safe) Files.write(output.resolve("response.json"), bytes, StandardOpenOption.CREATE_NEW)
        val decoded = bytes?.takeIf { safe }?.let { runCatching { JSON.readTree(it) }.getOrNull() }
        val report =
            mapOf(
                "scope" to "STANDALONE_FINAL_LUNA_DIAGNOSTIC_NOT_APPLICATION_AUTHORITY_OR_E2E",
                "success" to (failure == null && httpStatus == 200),
                "transportSucceeded" to (failure == null && httpStatus == 200),
                "httpStatus" to httpStatus,
                "failure" to failure,
                "failureKind" to httpStatus?.takeIf { it != 200 }?.let { ProviderFailureClassifier.classifyStatus(it, null).name },
                "causeClasses" to causeClasses,
                "configuredTimeoutNanos" to timeout.toNanos(),
                "elapsedNanos" to elapsed,
                "requestUtf8Bytes" to payload.size,
                "payloadSha256" to sha(payload),
                "responseBytes" to bytes?.size,
                "responseExported" to safe,
                "physicalAdmissions" to ledger.admissions,
                "maximumPhysicalRequests" to 1,
                "automaticRetries" to 0,
                "actualJevRequests" to 0,
                "actualGeminiRequests" to 0,
                "events" to events.toList(),
                "usage" to decoded?.path("usage"),
                "responseStatus" to decoded?.path("status")?.asText(),
                "billedCostObserved" to false,
                "billedCost" to null,
            )
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(report), StandardOpenOption.CREATE_NEW)
        return report
    }

    private class ResponseStatus(
        val status: Int,
    ) : IOException("HTTP_STATUS")

    companion object {
        val JSON = StandaloneGeminiOnceDiagnostic.JSON
        val ENDPOINT: URI = URI("https://api.openai.com/v1/responses")
        const val MAX_RESPONSE_BYTES = 262_144

        fun sha(bytes: ByteArray): String = StandaloneGeminiOnceDiagnostic.sha(bytes)
    }
}
