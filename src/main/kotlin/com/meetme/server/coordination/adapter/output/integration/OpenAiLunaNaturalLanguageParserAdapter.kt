package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.submission.domain.StructuredSubmissionResult
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component("lunaParser")
class OpenAiLunaNaturalLanguageParserAdapter
    @Autowired
    constructor(
        private val properties: OpenAiProperties,
        private val objectMapper: ObjectMapper,
    ) : NaturalLanguageParserPort {
        private val contract = NaturalLanguageProviderContract(objectMapper)
        private var httpClient: OkHttpClient = OkHttpClient.Builder().build()
        private var endpoint: URI = URI.create("https://api.openai.com/v1/responses")

        internal constructor(
            properties: OpenAiProperties,
            objectMapper: ObjectMapper,
            httpClient: OkHttpClient,
            endpoint: URI,
        ) : this(properties, objectMapper) {
            this.httpClient = httpClient
            this.endpoint = endpoint
        }

        override fun parse(request: NaturalLanguageBatchRequest): NaturalLanguageBatchResult {
            if (properties.apiKey.isBlank()) throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
            require(request.inputs.isNotEmpty() && request.inputs.size <= 50)
            require(
                request.inputs
                    .map { it.inputRef }
                    .toSet()
                    .size == request.inputs.size,
            )
            val budget = minOf(request.callTimeout, Duration.ofSeconds(15))
            if (budget.toMillis() <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
            val deadline = System.nanoTime() + budget.toNanos()
            var pending: Call? = null
            try {
                val body =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "model" to properties.model,
                            "store" to false,
                            "input" to prompt(request),
                            "max_output_tokens" to NaturalLanguageProviderContract.MAX_OUTPUT_TOKENS,
                            "text" to
                                mapOf(
                                    "format" to
                                        mapOf(
                                            "type" to "json_schema",
                                            "name" to "meet_me_conditions_v3",
                                            "strict" to true,
                                            "schema" to RESPONSE_SCHEMA,
                                        ),
                                ),
                        ),
                    )
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                // Override even an injected client's defaults: no hidden retries, redirects, or phase timeouts.
                val transport =
                    httpClient
                        .newBuilder()
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(Duration.ZERO)
                        .readTimeout(Duration.ZERO)
                        .writeTimeout(Duration.ZERO)
                        .callTimeout(Duration.ofNanos(remaining))
                        .build()
                val httpRequest =
                    Request
                        .Builder()
                        .url(endpoint.toString())
                        .header("Authorization", "Bearer ${properties.apiKey}")
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                pending = transport.newCall(httpRequest)
                val callRemaining = deadline - System.nanoTime()
                if (callRemaining <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                pending.timeout().timeout(callRemaining, TimeUnit.NANOSECONDS)
                val root =
                    pending.execute().use { response ->
                        val responseBody = response.body ?: invalid()
                        if (responseBody.contentLength() > properties.maxResponseBytes) invalid()
                        val bytes = responseBody.byteStream().readNBytes(properties.maxResponseBytes + 1)
                        if (bytes.size > properties.maxResponseBytes) invalid()
                        if (!response.isSuccessful) throw httpFailure(response.code, response.header("Retry-After"), bytes)
                        if (System.nanoTime() >= deadline) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                        objectMapper.readTree(bytes)
                    }
                if (root["status"]?.asString() != "completed") invalid()
                val output = root["output"]?.takeIf { it.isArray } ?: invalid()
                val texts = mutableListOf<String>()
                output.asSequence().forEach { item ->
                    if (item["type"]?.asString() == "message") {
                        val content = item["content"]?.takeIf { it.isArray } ?: invalid()
                        content.asSequence().forEach { part ->
                            when (part["type"]?.asString()) {
                                "refusal" -> invalid()
                                "output_text" -> texts += part["text"]?.takeIf { it.isString }?.asString() ?: invalid()
                                else -> invalid()
                            }
                        }
                    }
                }
                if (texts.size != 1) invalid()
                val text = texts.single()
                val parsed = parseProviderResponse(text, request)
                if (System.nanoTime() >= deadline) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                val usage = root["usage"]
                return NaturalLanguageBatchResult(
                    parsed,
                    ParserUsage(
                        tokenCount(usage, "input_tokens"),
                        tokenCount(usage, "output_tokens"),
                        text.toByteArray(Charsets.UTF_8).size,
                    ),
                )
            } catch (exception: NaturalLanguageParserException) {
                pending?.cancel()
                throw exception
            } catch (exception: IOException) {
                pending?.cancel()
                throw NaturalLanguageParserException(ProviderFailureClassifier.classify(exception), cause = exception)
            } catch (exception: RuntimeException) {
                pending?.cancel()
                throw NaturalLanguageParserException(ProviderFailureClassifier.classify(exception), cause = exception)
            }
        }

        internal fun prompt(request: NaturalLanguageBatchRequest): String = contract.prompt(request)

        internal fun parseProviderResponse(
            json: String,
            request: NaturalLanguageBatchRequest,
        ): List<StructuredSubmissionResult> = contract.parseProviderResponse(json, request, strict = true)

        private fun httpFailure(
            status: Int,
            retryAfterHeader: String?,
            bytes: ByteArray,
        ): NaturalLanguageParserException {
            val error = runCatching { objectMapper.readTree(bytes)["error"] }.getOrNull()
            val code = error?.get("code")?.takeIf { it.isString }?.asString()
            val type = error?.get("type")?.takeIf { it.isString }?.asString()
            val kind = ProviderFailureClassifier.classifyStatus(status, code, type)
            val retryAfter =
                retryAfterHeader
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1_000 }
                    ?.times(1_000)
            return NaturalLanguageParserException(
                kind,
                retryAfterMillis = if (kind == ParserFailureKind.RATE_LIMIT || kind == ParserFailureKind.SERVER) retryAfter else null,
            )
        }

        private fun tokenCount(
            usage: JsonNode?,
            field: String,
        ): Long? =
            usage
                ?.get(field)
                ?.takeIf { it.isIntegralNumber }
                ?.asLong()
                ?.takeIf { it >= 0 }

        private fun invalid(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)

        companion object {
            internal val RESPONSE_SCHEMA: Map<String, Any> = NaturalLanguageProviderContract.RESPONSE_SCHEMA
        }
    }
