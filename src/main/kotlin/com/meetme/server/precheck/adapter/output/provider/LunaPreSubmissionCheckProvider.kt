package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
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
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.Duration
import java.util.UUID

/** One bounded Responses generation, sharing its caller's Jev deadline. No count or provider fallback. */
class LunaPreSubmissionCheckProvider private constructor(
    private val properties: OpenAiProperties,
    objectMapper: ObjectMapper,
    private val activation: PrecheckProviderRuntimeProperties,
    transport: PrecheckProviderHttpTransport,
    private val endpoint: URI,
    private val dispatchGate: AiHttpDispatchGate?,
    private val meterRegistry: MeterRegistry?,
) : PreSubmissionCheckProvider {
    private val contract = PrecheckProviderContract(objectMapper)
    private val client =
        transport.client
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
                    fail(statusCode(status))
                }
                response
            }.build()

    override fun check(request: PrecheckProviderRequest): PrecheckProviderResult {
        var physical: GatedAiHttpExchange? = null
        var usage = AiBudgetUsage()
        var failure: AiBudgetFailure? = null
        var succeeded = false
        var deadline: Long? = null
        try {
            if (!activation.enabled ||
                properties.apiKey.isBlank() ||
                properties.apiKey.any { it !in '!'..'~' } ||
                request.maximumOutputTokens != 2048
            ) {
                fail("CONFIGURATION")
            }
            val canonical = endpoint == URI("https://api.openai.com/v1/responses")
            val loopback =
                endpoint.scheme == "http" &&
                    endpoint.host in setOf("127.0.0.1", "::1", "[::1]") &&
                    endpoint.port in 1..65535 &&
                    endpoint.rawPath == "/v1/responses"
            if ((!canonical && !loopback) || endpoint.rawUserInfo != null || endpoint.rawQuery != null || endpoint.rawFragment != null) {
                fail("CONFIGURATION")
            }
            val budget = minOf(request.timeout, Duration.ofSeconds(15))
            if (budget.isZero || budget.isNegative) fail("TIMEOUT")
            deadline = System.nanoTime() + budget.toNanos()
            if (request.rawText.isBlank() ||
                '\u0000' in request.rawText ||
                request.rawText.codePointCount(0, request.rawText.length) > 500
            ) {
                fail("INVALID_REQUEST")
            }
            val payload = contract.lunaRequestBytes(request)
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
                    request.evidence,
                )
                val execution = request.dispatchContext!!.execution
                if (execution.executionId != request.checkId ||
                    execution.roomId != request.roomId ||
                    execution.generation != request.executionGeneration?.toLong()
                ) {
                    fail("CONFIGURATION")
                }
                val prepared =
                    PreparedAiHttpRequest.freeze(
                        AiPhysicalOperation.PRECHECK_GENERATION,
                        1,
                        null,
                        physicalProfile(AiPhysicalOperation.PRECHECK_GENERATION, properties.model, endpoint, BODY_BYTE_LIMIT),
                        payload,
                    )
                val group = dispatchGate.prepareGroup(request.dispatchContext, listOf(prepared))
                physical = dispatchGate.forPreparedCall(client, group, AiPhysicalOperation.PRECHECK_GENERATION)
            } else if (request.dispatchContext != null) {
                fail("CONFIGURATION")
            }
            val transport = (physical?.client ?: client).newBuilder().callTimeout(Duration.ofNanos(remaining(deadline))).build()
            val httpRequest =
                Request
                    .Builder()
                    .url(endpoint.toString())
                    .header("Authorization", "Bearer " + properties.apiKey)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            val call = transport.newCall(httpRequest)
            call.timeout().deadlineNanoTime(deadline)
            remaining(deadline)
            meterRegistry?.counter("meetme.precheck.provider.http.attempts", "operation", "GENERATE_CONTENT")?.increment()
            val bytes =
                call.execute().use { response ->
                    if (response.code != 200) fail(statusCode(response.code))
                    val body = response.body ?: fail("INVALID_RESPONSE")
                    if (body.contentLength() > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE")
                    body.byteStream().readNBytes(BODY_BYTE_LIMIT + 1).also { if (it.size > BODY_BYTE_LIMIT) fail("INVALID_RESPONSE") }
                }
            val observed = contract.observeLunaUsage(bytes)
            usage =
                AiBudgetUsage(
                    inputTokens = observed.tokens["INPUT"],
                    outputTokens = observed.tokens["OUTPUT"],
                    reasoningTokens = observed.tokens["THOUGHT"],
                )
            for (kind in listOf("INPUT", "OUTPUT", "THOUGHT")) {
                val value = observed.tokens[kind]
                if (value ==
                    null
                ) {
                    meterRegistry
                        ?.counter(
                            "meetme.precheck.provider.usage.unknown",
                            "operation",
                            "GENERATE_CONTENT",
                            "kind",
                            kind,
                        )?.increment()
                } else {
                    meterRegistry?.let {
                        DistributionSummary
                            .builder("meetme.precheck.provider.usage.tokens")
                            .tag("operation", "GENERATE_CONTENT")
                            .tag("kind", kind)
                            .register(it)
                            .record(value.toDouble())
                    }
                }
            }
            if (observed.invalid) fail("INVALID_RESPONSE")
            val result = contract.parseLuna(bytes, request)
            remaining(deadline)
            succeeded = true
            return result
        } catch (exception: Exception) {
            val code =
                when (exception) {
                    is AiHttpDispatchException -> "CONFIGURATION"
                    is AiHttpProviderStatusException -> statusCode(exception.status)
                    is PrecheckProviderException -> exception.failureCode
                    is InterruptedIOException -> "TIMEOUT"
                    is IOException -> if (deadline?.let { it - System.nanoTime() <= 0 } == true) "TIMEOUT" else "CONNECTION"
                    is IllegalArgumentException -> "CONFIGURATION"
                    else -> "INVALID_RESPONSE"
                }
            failure =
                when (code) {
                    "TIMEOUT" -> AiBudgetFailure.TIMEOUT
                    "CONNECTION" -> AiBudgetFailure.NETWORK
                    "RATE_LIMITED" -> AiBudgetFailure.RATE_LIMIT
                    "SERVER" -> AiBudgetFailure.PROVIDER
                    "INVALID_RESPONSE" -> AiBudgetFailure.INVALID_RESPONSE
                    else -> AiBudgetFailure.CONFIGURATION
                }
            fail(code)
        } finally {
            try {
                physical?.observe(UUID.randomUUID(), if (succeeded) AiBudgetOutcome.SUCCEEDED else AiBudgetOutcome.FAILED, usage, failure)
            } catch (
                _: AiHttpDispatchException,
            ) {
                fail(if (deadline?.let { it - System.nanoTime() <= 0 } == true) "TIMEOUT" else "CONFIGURATION")
            }
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - System.nanoTime()).also { if (it <= 0) fail("TIMEOUT") }

    private fun fail(code: String): Nothing = throw PrecheckProviderException(code)

    private fun statusCode(status: Int): String =
        when (status) {
            429 -> "RATE_LIMITED"
            in 500..599 -> "SERVER"
            400, 401, 403 -> "CONFIGURATION"
            else -> "INVALID_RESPONSE"
        }

    companion object {
        private const val BODY_BYTE_LIMIT = 16384

        fun runtime(
            properties: OpenAiProperties,
            objectMapper: ObjectMapper,
            activation: PrecheckProviderRuntimeProperties,
            transport: PrecheckProviderHttpTransport = PrecheckProviderHttpTransport(),
            endpoint: URI = URI("https://api.openai.com/v1/responses"),
            dispatchGate: AiHttpDispatchGate? = null,
            meterRegistry: MeterRegistry? = null,
        ): LunaPreSubmissionCheckProvider =
            LunaPreSubmissionCheckProvider(properties, objectMapper, activation, transport, endpoint, dispatchGate, meterRegistry)
    }
}
