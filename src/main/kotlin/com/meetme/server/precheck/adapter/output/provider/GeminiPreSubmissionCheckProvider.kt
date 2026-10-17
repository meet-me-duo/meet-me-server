package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
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

/** Legacy loopback-only synthetic seam; this flag cannot activate the runtime provider. */
data class PrecheckProviderOptions(
    val endpoint: URI = URI("https://generativelanguage.googleapis.com"),
    val totalOutputTokenCapVerified: Boolean = false,
)

/** At most one count and one generation. The application owns admission and late-result fencing. */
class GeminiPreSubmissionCheckProvider private constructor(
    private val properties: GeminiProperties,
    objectMapper: ObjectMapper,
    private val options: PrecheckProviderOptions,
    private val runtimeProfile: RuntimeProfile?,
) : PreSubmissionCheckProvider {
    constructor(
        properties: GeminiProperties,
        objectMapper: ObjectMapper,
        options: PrecheckProviderOptions = PrecheckProviderOptions(),
    ) : this(properties, objectMapper, options, null)

    private val contract = PrecheckProviderContract(objectMapper)
    private val client =
        (runtimeProfile?.transport?.client ?: OkHttpClient())
            .newBuilder()
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
        val enabled = runtimeProfile?.activation?.enabled ?: options.totalOutputTokenCapVerified
        if (properties.apiKey.isBlank() || !enabled || request.maximumOutputTokens != OUTPUT_TOKEN_LIMIT) fail("CONFIGURATION")
        validateEndpoint()
        val budget = minOf(request.timeout, Duration.ofSeconds(15))
        if (budget.isZero || budget.isNegative) fail("TIMEOUT")
        val deadline = System.nanoTime() + budget.toNanos()
        if (request.rawText.isEmpty() || request.rawText.codePointCount(0, request.rawText.length) > 500) fail("INVALID_REQUEST")
        // Serialize the complete generation input once; count uses the same frozen prompt and schema.
        val payload = contract.requestBytes(request, runtimeProfile != null)
        if (payload.size > BODY_BYTE_LIMIT) fail("INVALID_REQUEST")
        val countPayload = runtimeProfile?.let { contract.countRequestBytes(payload, properties.model) }
        if (countPayload != null && countPayload.size > BODY_BYTE_LIMIT) fail("INVALID_REQUEST")
        try {
            if (countPayload != null) {
                val countBytes = exchange("COUNT_TOKENS", "countTokens", countPayload, deadline)
                val count = contract.parseCount(countBytes)
                if (count > INPUT_TOKEN_LIMIT) fail("INVALID_REQUEST")
                remaining(deadline)
            }
            var generationAttempted = false
            var usageObserved = false
            try {
                val bytes = exchange("GENERATE_CONTENT", "generateContent", payload, deadline) { generationAttempted = true }
                if (runtimeProfile != null) {
                    val usage = contract.observeUsage(bytes)
                    recordUsage(usage.tokens)
                    usageObserved = true
                    if (usage.invalid) fail("INVALID_RESPONSE")
                }
                val result = contract.parse(bytes, request).copy(provider = "GEMINI", model = properties.model)
                if (runtimeProfile != null && result.inputTokens != null && result.inputTokens > INPUT_TOKEN_LIMIT) fail("INVALID_RESPONSE")
                remaining(deadline)
                return result
            } finally {
                if (runtimeProfile != null && generationAttempted && !usageObserved) recordUsage(emptyMap())
            }
        } catch (exception: PrecheckProviderException) {
            throw exception
        } catch (_: InterruptedIOException) {
            fail("TIMEOUT")
        } catch (_: IOException) {
            if (deadline - System.nanoTime() <= 0) fail("TIMEOUT")
            fail("CONNECTION")
        } catch (_: IllegalArgumentException) {
            fail("CONFIGURATION")
        }
    }

    private fun exchange(
        operation: String,
        method: String,
        payload: ByteArray,
        deadline: Long,
        onAttempt: () -> Unit = {},
    ): ByteArray {
        val transport = client.newBuilder().callTimeout(Duration.ofNanos(remaining(deadline))).build()
        val httpRequest =
            Request
                .Builder()
                .url(options.endpoint.resolve("/v1beta/models/${properties.model}:$method").toString())
                .header("x-goog-api-key", properties.apiKey)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        val call = transport.newCall(httpRequest)
        remaining(deadline)
        call.timeout().deadlineNanoTime(deadline)
        runtimeProfile?.meterRegistry?.counter("meetme.precheck.provider.http.attempts", "operation", operation)?.increment()
        onAttempt()
        return call.execute().use { response ->
            if (response.code != 200) failStatus(response.code)
            val body = response.body ?: fail("INVALID_RESPONSE")
            if (body.contentLength() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
            val bytes =
                body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(2048)
                    while (true) {
                        remaining(deadline)
                        val count = input.read(buffer, 0, minOf(buffer.size, BODY_BYTE_LIMIT + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
                    }
                    output.toByteArray()
                }
            remaining(deadline)
            bytes
        }
    }

    private fun recordUsage(tokens: Map<String, Long?>) {
        val registry = runtimeProfile?.meterRegistry ?: return
        for (kind in listOf("INPUT", "CANDIDATE", "THOUGHT")) {
            val value = tokens[kind]
            if (value == null) {
                registry.counter("meetme.precheck.provider.usage.unknown", "operation", "GENERATE_CONTENT", "kind", kind).increment()
            } else {
                DistributionSummary
                    .builder("meetme.precheck.provider.usage.tokens")
                    .tag("operation", "GENERATE_CONTENT")
                    .tag("kind", kind)
                    .register(registry)
                    .record(value.toDouble())
            }
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).also { if (it <= 0) fail("TIMEOUT") }

    private fun validateEndpoint() {
        val endpoint = options.endpoint
        val synthetic = endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "::1", "[::1]")
        val supportedRuntime = runtimeProfile != null && endpoint == URI("https://generativelanguage.googleapis.com")
        if ((!synthetic && !supportedRuntime) ||
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
        private const val INPUT_TOKEN_LIMIT = 4096

        /** Fixed documented GenerateContent profile, not evidence of live API support or billing. */
        fun runtime(
            properties: GeminiProperties,
            objectMapper: ObjectMapper,
            activation: PrecheckProviderRuntimeProperties,
            transport: PrecheckProviderHttpTransport = PrecheckProviderHttpTransport(),
            meterRegistry: MeterRegistry? = null,
            endpoint: URI = URI("https://generativelanguage.googleapis.com"),
        ): GeminiPreSubmissionCheckProvider =
            GeminiPreSubmissionCheckProvider(
                properties,
                objectMapper,
                PrecheckProviderOptions(endpoint),
                RuntimeProfile(activation, transport, meterRegistry),
            )
    }

    private data class RuntimeProfile(
        val activation: PrecheckProviderRuntimeProperties,
        val transport: PrecheckProviderHttpTransport,
        val meterRegistry: MeterRegistry?,
    )
}
