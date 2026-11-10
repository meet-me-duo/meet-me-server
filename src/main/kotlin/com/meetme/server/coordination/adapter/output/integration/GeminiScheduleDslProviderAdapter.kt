package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ScheduleDslCallBudget
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderRequest
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.time.Duration

/** One REST attempt. No SDK retries, upper Gemini retry loop, or Luna fallback is entered. */
class GeminiScheduleDslProviderAdapter(
    private val properties: GeminiProperties,
    private val mapper: ObjectMapper,
    private val budget: ScheduleDslCallBudget,
    private val endpoint: URI = URI("https://generativelanguage.googleapis.com"),
) : ScheduleDslProviderPort {
    override fun generate(request: ScheduleDslProviderRequest): ScheduleDslProviderResponse {
        require(request.ticket.identity.irSemanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION)
        val responseVersion = request.ticket.identity.responseContractVersion
        when (responseVersion) {
            SCHEDULE_DSL_RESPONSE_CONTRACT -> require(request.inputCapsule == null)
            SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT -> {
                val capsule = requireNotNull(request.inputCapsule)
                require(capsule.snapshot == request.snapshot)
            }
            else -> throw IllegalArgumentException("Unsupported DSL response contract")
        }
        require(request.snapshot.context == request.ticket.context)
        require(properties.apiKey.isNotBlank() && properties.apiKey.all { it in '!'..'~' }) { "Missing DSL provider configuration" }
        val canonical = endpoint == URI("https://generativelanguage.googleapis.com")
        val loopback =
            endpoint.scheme == "http" &&
                endpoint.host in setOf("127.0.0.1", "::1", "[::1]") &&
                endpoint.port in 1..65535 &&
                endpoint.path in setOf("", "/")
        require((canonical || loopback) && endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
        val timeout = request.timeout.coerceAtMost(AnalysisInvocationBudget.GEMINI_CALL_TIMEOUT)
        if (timeout.isNegative || timeout.isZero) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        val cutoff = System.nanoTime() + timeout.toNanos()
        val body =
            if (responseVersion == SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT) {
                ScheduleDslGeminiWire.requestContextBytes(mapper, requireNotNull(request.inputCapsule), properties.model)
            } else {
                ScheduleDslGeminiWire.requestBytes(mapper, request.snapshot, properties.model)
            }
        require(body.size <= 1_048_576)
        try {
            budget.claim(request.ticket.fingerprint() + ":FINAL:" + responseVersion, "GEMINI", properties.model)
        } catch (
            _: RuntimeException,
        ) {
            throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
        }
        val remaining = cutoff - System.nanoTime()
        if (remaining <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        val client =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ZERO)
                .callTimeout(Duration.ofNanos(remaining))
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code != 200) {
                        val code = response.code
                        response.close()
                        throw HttpFailure(code)
                    }
                    response
                }.build()
        val http =
            Request
                .Builder()
                .url(endpoint.resolve("/v1beta/models/${properties.model}:generateContent").toString())
                .header("x-goog-api-key", properties.apiKey)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        return try {
            client.newCall(http).execute().use { response ->
                if (response.code != 200) throw HttpFailure(response.code)
                val responseBody = response.body ?: throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                val bytes = responseBody.byteStream().use { it.readNBytes(1_048_577) }
                ScheduleDslGeminiWire.response(mapper, bytes)
            }
        } catch (failure: HttpFailure) {
            val kind =
                when (failure.status) {
                    401 -> ParserFailureKind.AUTHENTICATION
                    403 -> ParserFailureKind.PERMISSION
                    429 -> ParserFailureKind.RATE_LIMIT
                    in 500..599 -> ParserFailureKind.SERVER
                    400 -> ParserFailureKind.INVALID_REQUEST
                    else -> ParserFailureKind.INVALID_RESPONSE
                }
            throw NaturalLanguageParserException(kind)
        } catch (
            _: InterruptedIOException,
        ) {
            throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
        } catch (
            _: IOException,
        ) {
            throw NaturalLanguageParserException(ParserFailureKind.NETWORK)
        }
    }

    private class HttpFailure(
        val status: Int,
    ) : RuntimeException("DSL provider HTTP status")
}
