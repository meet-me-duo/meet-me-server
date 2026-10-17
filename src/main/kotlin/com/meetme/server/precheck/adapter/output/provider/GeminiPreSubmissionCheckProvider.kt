package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.Duration

/** Actual provider capability must be verified separately; synthetic endpoints do not prove it. */
data class PrecheckProviderOptions(
    val endpoint: URI = URI("https://generativelanguage.googleapis.com"),
    val totalOutputTokenCapVerified: Boolean = false,
)

/** One attempt only. The application owns admission, durable retries and late-result fencing. */
class GeminiPreSubmissionCheckProvider(
    private val properties: GeminiProperties,
    objectMapper: ObjectMapper,
    private val options: PrecheckProviderOptions = PrecheckProviderOptions(),
) : PreSubmissionCheckProvider {
    private val contract = PrecheckProviderContract(objectMapper)
    private val client =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ZERO)
            .readTimeout(Duration.ZERO)
            .writeTimeout(Duration.ZERO)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code != 200) {
                    val status = response.code
                    response.close()
                    // Reject before OkHttp's HTTP follow-up policy (including 503 Retry-After: 0).
                    failStatus(status)
                }
                response
            }.build()

    override fun check(request: PrecheckProviderRequest): PrecheckProviderResult {
        if (properties.apiKey.isBlank() || !options.totalOutputTokenCapVerified || request.maximumOutputTokens != OUTPUT_TOKEN_LIMIT) {
            fail("CONFIGURATION")
        }
        validateEndpoint()
        val budget = minOf(request.timeout, Duration.ofSeconds(15))
        if (budget.isZero || budget.isNegative) fail("TIMEOUT")
        val deadline = System.nanoTime() + budget.toNanos()
        if (request.rawText.isEmpty() || request.rawText.codePointCount(0, request.rawText.length) > 500) fail("INVALID_REQUEST")
        val payload = contract.requestBytes(request)
        if (payload.size > BODY_BYTE_LIMIT) fail("INVALID_REQUEST")
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) fail("TIMEOUT")
        try {
            val transport = client.newBuilder().callTimeout(Duration.ofNanos(remaining)).build()
            val httpRequest =
                Request
                    .Builder()
                    .url(options.endpoint.resolve("/v1beta/models/${properties.model}:generateContent").toString())
                    .header("x-goog-api-key", properties.apiKey)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            val call = transport.newCall(httpRequest)
            if (System.nanoTime() >= deadline) fail("TIMEOUT")
            call.timeout().deadlineNanoTime(deadline)
            call.execute().use { response ->
                val body = response.body ?: fail("INVALID_RESPONSE")
                if (body.contentLength() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
                val bytes =
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(2048)
                        while (true) {
                            if (System.nanoTime() >= deadline) fail("TIMEOUT")
                            val count = input.read(buffer, 0, minOf(buffer.size, BODY_BYTE_LIMIT + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            if (output.size() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
                        }
                        output.toByteArray()
                    }
                if (System.nanoTime() >= deadline) fail("TIMEOUT")
                val result = contract.parse(bytes, request).copy(provider = "GEMINI", model = properties.model)
                if (System.nanoTime() >= deadline) fail("TIMEOUT")
                return result
            }
        } catch (exception: PrecheckProviderException) {
            throw exception
        } catch (_: InterruptedIOException) {
            fail("TIMEOUT")
        } catch (_: IOException) {
            if (System.nanoTime() >= deadline) fail("TIMEOUT")
            fail("CONNECTION")
        } catch (_: IllegalArgumentException) {
            fail("CONFIGURATION")
        }
    }

    private fun validateEndpoint() {
        val endpoint = options.endpoint
        val loopback = endpoint.host in setOf("127.0.0.1", "localhost", "::1", "[::1]")
        if ((endpoint.scheme != "https" && !(endpoint.scheme == "http" && loopback)) ||
            endpoint.host.isNullOrBlank() ||
            endpoint.rawUserInfo != null ||
            endpoint.rawQuery != null ||
            endpoint.rawFragment != null ||
            endpoint.rawPath !in setOf("", "/")
        ) {
            fail("CONFIGURATION")
        }
    }

    private fun failStatus(status: Int): Nothing =
        fail(
            when (status) {
                429 -> "RATE_LIMITED"
                in 500..599 -> "SERVER"
                400, 401, 403 -> "CONFIGURATION"
                else -> "INVALID_RESPONSE"
            },
        )

    private fun fail(code: String): Nothing = throw PrecheckProviderException(code)

    companion object {
        private const val BODY_BYTE_LIMIT = 16 * 1024
        private const val OUTPUT_TOKEN_LIMIT = 2048
    }
}
