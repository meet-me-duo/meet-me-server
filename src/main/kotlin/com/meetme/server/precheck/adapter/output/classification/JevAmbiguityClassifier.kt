package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.aihttp.GatedAiHttpExchange
import com.meetme.server.shared.adapter.output.aihttp.PreparedAiHttpRequest
import com.meetme.server.shared.adapter.output.aihttp.physicalProfile
import com.meetme.server.shared.adapter.output.aihttp.validatePersonalDispatch
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
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
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.UUID

/** Exact-wire managed classification; standalone diagnostic results do not grant production dispatch. */
class JevAmbiguityClassifier(
    private val apiKey: String,
    objectMapper: ObjectMapper,
    private val options: JevClassifierOptions = JevClassifierOptions(),
    client: OkHttpClient = OkHttpClient(),
    private val dispatchGate: AiHttpDispatchGate? = null,
) : AmbiguityClassificationPort {
    private val contract = JevAmbiguityClassificationContract(objectMapper)
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
                    // Stop before OkHttp's implicit same-call status follow-up (including Retry-After:0).
                    fail(statusCode(status))
                }
                response
            }.build()

    override fun classify(request: AmbiguityClassificationRequest): AmbiguityClassificationResult {
        var deadline: Long? = null
        var physical: GatedAiHttpExchange? = null
        var observedUsage = AiBudgetUsage()
        var succeeded = false
        var observedFailure: AiBudgetFailure? = null
        try {
            validateConfiguration()
            val managed = request.dispatchContext != null || dispatchGate?.properties?.enabled == true
            if (managed &&
                (
                    dispatchGate?.properties?.enabled != true ||
                        options.minimumConfidence?.let { !it.isFinite() || it !in 0.0..1.0 } == true ||
                        options.requireMinimumConfidence &&
                        options.minimumConfidence == null
                )
            ) {
                fail("CONFIGURATION")
            }
            val budget = minOf(request.timeout, Duration.ofSeconds(15))
            if (budget.isNegative || budget.isZero) fail("TIMEOUT")
            val expires = System.nanoTime() + budget.toNanos()
            deadline = expires
            validateInput(request)
            val bytes = contract.requestBytes(request)
            if (bytes.size > BODY_LIMIT) fail("INVALID_REQUEST")
            if (managed) {
                validatePersonalDispatch(
                    request.dispatchContext,
                    request.rawText,
                    request.referenceAt,
                    request.timeZone,
                    request.locale,
                    request.searchStartDate,
                    request.searchEndDate,
                )
                val gate = requireNotNull(dispatchGate)
                val prepared =
                    PreparedAiHttpRequest.freeze(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        1,
                        null,
                        physicalProfile(AiPhysicalOperation.PRECHECK_CLASSIFICATION, options.model, options.endpoint, BODY_LIMIT),
                        bytes,
                    )
                val group = gate.prepareGroup(request.dispatchContext, listOf(prepared))
                physical = gate.forPreparedCall(transport, group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
            }
            val http =
                Request
                    .Builder()
                    .url(options.endpoint.toString())
                    .header("Authorization", "Bearer $apiKey")
                    .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            val call =
                (physical?.client ?: transport)
                    .newBuilder()
                    .callTimeout(Duration.ofNanos(remaining(expires)))
                    .build()
                    .newCall(http)
            call.timeout().deadlineNanoTime(expires)
            remaining(expires)
            val responseBytes =
                call.execute().use { response ->
                    if (response.code != 200) fail(statusCode(response.code))
                    val body = response.body ?: fail("INVALID_RESPONSE")
                    if (body.contentLength() > BODY_LIMIT) fail("INVALID_RESPONSE")
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(2048)
                        while (true) {
                            remaining(expires)
                            val n = input.read(buffer, 0, minOf(buffer.size, BODY_LIMIT + 1 - output.size()))
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            if (output.size() > BODY_LIMIT) fail("INVALID_RESPONSE")
                        }
                        remaining(expires)
                        output.toByteArray()
                    }
                }
            observedUsage = contract.usageSnapshot(responseBytes)
            val semantic = contract.parse(responseBytes)
            val result =
                if (managed &&
                    (
                        options.minimumConfidence == null ||
                            requireNotNull(semantic.confidence) < options.minimumConfidence ||
                            semantic.probabilities.values.count { it == semantic.probabilities.values.maxOrNull() } != 1
                    )
                ) {
                    semantic.copy(assessment = AmbiguityAssessment.INDETERMINATE, reason = AmbiguityClassificationReason.LOW_CONFIDENCE)
                } else {
                    semantic
                }
            remaining(expires)
            succeeded = true
            return result
        } catch (e: Exception) {
            val code =
                when (e) {
                    is AiHttpDispatchException -> "CONFIGURATION"
                    is AiHttpProviderStatusException -> statusCode(e.status)
                    is AmbiguityClassificationException -> e.failureCode.takeIf { it in FAILURE_CODES } ?: "INVALID_RESPONSE"
                    is InterruptedIOException -> "TIMEOUT"
                    is IOException -> if (deadline?.let { it - System.nanoTime() <= 0 } == true) "TIMEOUT" else "CONNECTION"
                    is IllegalArgumentException -> "CONFIGURATION"
                    else -> "INVALID_RESPONSE"
                }
            observedFailure =
                when (code) {
                    "TIMEOUT" -> AiBudgetFailure.TIMEOUT
                    "CONNECTION" -> AiBudgetFailure.NETWORK
                    "RATE_LIMITED" -> AiBudgetFailure.RATE_LIMIT
                    "INVALID_RESPONSE" -> AiBudgetFailure.INVALID_RESPONSE
                    "SERVER" -> AiBudgetFailure.PROVIDER
                    else -> AiBudgetFailure.CONFIGURATION
                }
            // Never expose key/body/headers/parser diagnostics or an exception cause containing them.
            throw AmbiguityClassificationException(code)
        } finally {
            try {
                physical?.observe(
                    UUID.randomUUID(),
                    if (succeeded) AiBudgetOutcome.SUCCEEDED else AiBudgetOutcome.FAILED,
                    observedUsage,
                    observedFailure,
                )
            } catch (_: AiHttpDispatchException) {
                fail("CONFIGURATION")
            }
        }
    }

    private fun validateConfiguration() {
        if (!options.enabled ||
            apiKey.isBlank() ||
            apiKey.any { it !in '!'..'~' } ||
            options.model != JevAmbiguityClassificationContract.MODEL
        ) {
            fail("CONFIGURATION")
        }
        val endpoint = options.endpoint
        val canonical = endpoint == URI("https://api.typesafe.ai/v1/systemone")
        val loopback =
            endpoint.scheme == "http" &&
                endpoint.host in setOf("127.0.0.1", "::1", "[::1]") &&
                endpoint.port in 1..65535
        if ((!canonical && !loopback) ||
            endpoint.rawPath != "/v1/systemone" ||
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
            Locale.Builder().setLanguageTag(request.locale).build()
        } catch (_: IllformedLocaleException) {
            fail("INVALID_REQUEST")
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).also { if (it <= 0) fail("TIMEOUT") }

    private fun fail(code: String): Nothing = throw AmbiguityClassificationException(code)

    private fun statusCode(status: Int): String =
        when (status) {
            400, 401, 402, 403, 415, 422 -> "CONFIGURATION"
            413 -> "INVALID_REQUEST"
            429 -> "RATE_LIMITED"
            in 500..599 -> "SERVER"
            else -> "INVALID_RESPONSE"
        }

    companion object {
        private const val BODY_LIMIT = 16 * 1024
        private val FAILURE_CODES =
            setOf("CONFIGURATION", "INVALID_REQUEST", "INVALID_RESPONSE", "TIMEOUT", "CONNECTION", "RATE_LIMITED", "SERVER")
    }
}
