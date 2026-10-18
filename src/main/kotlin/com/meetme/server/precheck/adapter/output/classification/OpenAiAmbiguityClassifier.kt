package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.aihttp.GatedAiHttpExchange
import com.meetme.server.shared.adapter.output.aihttp.PreparedAiHttpRequest
import com.meetme.server.shared.adapter.output.aihttp.physicalProfile
import com.meetme.server.shared.adapter.output.aihttp.validatePersonalDispatch
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.DateTimeException
import java.time.Duration
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.UUID

/** Standalone candidate only: not registered in Spring or connected to submission processing. */
class OpenAiAmbiguityClassifier(
    private val apiKey: String,
    objectMapper: ObjectMapper,
    private val options: AmbiguityClassifierOptions = AmbiguityClassifierOptions(),
    client: OkHttpClient = OkHttpClient(),
    private val meterRegistry: MeterRegistry? = null,
    private val dispatchGate: AiHttpDispatchGate? = null,
) : AmbiguityClassificationPort {
    private val contract = AmbiguityClassificationContract(objectMapper)
    private val transport =
        client
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
                    // Reject before OkHttp can repeat a 503 with Retry-After: 0.
                    failStatus(status)
                }
                response
            }.build()

    override fun classify(request: AmbiguityClassificationRequest): AmbiguityClassificationResult {
        val started = System.nanoTime()
        var deadline: Long? = null
        var attempted = false
        var usageObserved = false
        var outcome = "FAILED"
        var failure = "NONE"
        var physical: GatedAiHttpExchange? = null
        var physicalUsage = AiBudgetUsage()
        try {
            validateConfiguration()
            val budget = minOf(request.timeout, Duration.ofSeconds(15))
            if (budget.isZero || budget.isNegative) fail("TIMEOUT")
            val expires = System.nanoTime() + budget.toNanos()
            deadline = expires
            validateInput(request)
            val payload = contract.requestBytes(request)
            if (payload.size > BODY_BYTE_LIMIT) fail("INVALID_REQUEST")
            if (dispatchGate?.properties?.enabled == true) {
                validatePersonalDispatch(
                    request.dispatchContext,
                    request.rawText,
                    request.referenceAt,
                    request.timeZone,
                    request.locale,
                    request.searchStartDate,
                    request.searchEndDate,
                )
                val prepared =
                    PreparedAiHttpRequest.freeze(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        1,
                        null,
                        physicalProfile(AiPhysicalOperation.PRECHECK_CLASSIFICATION, "gpt-6-luna", options.endpoint, BODY_BYTE_LIMIT),
                        payload,
                    )
                val group = dispatchGate.prepareGroup(request.dispatchContext, listOf(prepared))
                physical = dispatchGate.forPreparedCall(transport, group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
            }
            val httpRequest =
                Request
                    .Builder()
                    .url(options.endpoint.toString())
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            val call =
                (physical?.client ?: transport)
                    .newBuilder()
                    .callTimeout(Duration.ofNanos(remaining(expires)))
                    .build()
                    .newCall(httpRequest)
            remaining(expires)
            call.timeout().deadlineNanoTime(expires)
            meterRegistry?.counter("$METRIC.http.attempts", TAGS)?.increment()
            attempted = true
            val bytes =
                call.execute().use { response ->
                    if (response.code != 200) failStatus(response.code)
                    val body = response.body ?: fail("INVALID_RESPONSE")
                    if (body.contentLength() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
                    val bytes =
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(2048)
                            while (true) {
                                remaining(expires)
                                val count = input.read(buffer, 0, minOf(buffer.size, BODY_BYTE_LIMIT + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                if (output.size() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
                            }
                            output.toByteArray()
                        }
                    remaining(expires)
                    bytes
                }
            val envelope = contract.read(bytes)
            val usage = contract.usage(envelope)
            physicalUsage = AiBudgetUsage(inputTokens = usage.input, outputTokens = usage.output, usageComplete = usage.complete)
            recordUsage(usage)
            usageObserved = true
            val result = contract.parse(envelope, usage, options.minimumConfidence ?: fail("CONFIGURATION"))
            remaining(expires)
            outcome = result.assessment.name
            failure = result.reason?.name ?: "NONE"
            return result
        } catch (exception: Exception) {
            val code =
                when (exception) {
                    is AiHttpDispatchException -> "CONFIGURATION"
                    is AiHttpProviderStatusException -> statusCode(exception.status)
                    is AmbiguityClassificationException -> exception.failureCode.takeIf { it in FAILURE_CODES } ?: "INVALID_RESPONSE"
                    is InterruptedIOException -> "TIMEOUT"
                    is IOException -> if (deadline?.let { it - System.nanoTime() <= 0 } == true) "TIMEOUT" else "CONNECTION"
                    is IllegalArgumentException -> "CONFIGURATION"
                    else -> "INVALID_RESPONSE"
                }
            failure = code
            // Keep provider bodies, headers, keys and parser messages out of the exception chain.
            throw AmbiguityClassificationException(code)
        } finally {
            if (attempted && !usageObserved) recordUsage(AmbiguityClassificationContract.UsageSnapshot())
            recordResult(outcome, failure, System.nanoTime() - started)
            try {
                physical?.observe(
                    UUID.randomUUID(),
                    if (outcome ==
                        "FAILED"
                    ) {
                        AiBudgetOutcome.FAILED
                    } else {
                        AiBudgetOutcome.SUCCEEDED
                    },
                    physicalUsage,
                )
            } catch (_: AiHttpDispatchException) {
                fail("CONFIGURATION")
            }
        }
    }

    private fun validateConfiguration() {
        val threshold = options.minimumConfidence
        if (!options.enabled ||
            apiKey.isBlank() ||
            threshold == null ||
            !threshold.isFinite() ||
            threshold !in 0.0..1.0
        ) {
            fail("CONFIGURATION")
        }
        val endpoint = options.endpoint
        val canonical = endpoint == URI("https://api.openai.com/v1/decisions")
        val synthetic = endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "::1", "[::1]")
        if ((!canonical && !synthetic) ||
            endpoint.rawPath != "/v1/decisions" ||
            endpoint.rawUserInfo != null ||
            endpoint.rawQuery != null ||
            endpoint.rawFragment != null
        ) {
            fail("CONFIGURATION")
        }
    }

    private fun validateInput(request: AmbiguityClassificationRequest) {
        if (request.rawText.isEmpty() ||
            '\u0000' in request.rawText ||
            request.rawText.codePointCount(0, request.rawText.length) > 500 ||
            request.locale.isBlank() ||
            '\u0000' in request.locale ||
            request.searchStartDate > request.searchEndDate
        ) {
            fail("INVALID_REQUEST")
        }
        try {
            request.referenceAt.atZone(request.timeZone)
            if (Locale
                    .Builder()
                    .setLanguageTag(request.locale)
                    .build()
                    .language
                    .isBlank()
            ) {
                fail("INVALID_REQUEST")
            }
        } catch (_: DateTimeException) {
            fail("INVALID_REQUEST")
        } catch (_: IllformedLocaleException) {
            fail("INVALID_REQUEST")
        }
    }

    private fun recordUsage(usage: AmbiguityClassificationContract.UsageSnapshot) {
        val registry = meterRegistry ?: return
        for ((direction, value) in mapOf("input" to usage.input, "output" to usage.output)) {
            if (value == null) {
                registry.counter("$METRIC.usage.$direction.unknown", TAGS).increment()
            } else {
                DistributionSummary
                    .builder("$METRIC.usage.$direction.tokens")
                    .tags(TAGS)
                    .register(registry)
                    .record(value.toDouble())
            }
        }
        if (!usage.complete) registry.counter("$METRIC.usage.incomplete", TAGS).increment()
    }

    private fun recordResult(
        outcome: String,
        failure: String,
        elapsed: Long,
    ) {
        val registry = meterRegistry ?: return
        val tags = TAGS + listOf(Tag.of("outcome", outcome), Tag.of("failure", failure))
        registry.counter("$METRIC.results", tags).increment()
        registry.timer("$METRIC.duration", tags).record(Duration.ofNanos(elapsed.coerceAtLeast(0)))
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).also { if (it <= 0) fail("TIMEOUT") }

    private fun failStatus(status: Int): Nothing = fail(statusCode(status))

    private fun statusCode(status: Int): String =
        when (status) {
            429 -> "RATE_LIMITED"
            in 500..599 -> "SERVER"
            400, 401, 403 -> "CONFIGURATION"
            else -> "INVALID_RESPONSE"
        }

    private fun fail(code: String): Nothing = throw AmbiguityClassificationException(code)

    companion object {
        private const val BODY_BYTE_LIMIT = 16 * 1024
        private const val METRIC = "meetme.precheck.classification"
        private val FAILURE_CODES =
            setOf("CONFIGURATION", "INVALID_REQUEST", "INVALID_RESPONSE", "TIMEOUT", "CONNECTION", "RATE_LIMITED", "SERVER")
        private val TAGS =
            listOf(
                Tag.of("provider", "OPENAI_DECISIONS"),
                Tag.of("model", "gpt-6-luna"),
                Tag.of("operation", "CLASSIFY"),
            )
    }
}
