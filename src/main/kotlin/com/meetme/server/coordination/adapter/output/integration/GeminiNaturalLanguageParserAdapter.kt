package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.ClientOptions
import com.google.genai.types.FinishReason
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.submission.domain.StructuredSubmissionResult
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration

@Component
@Primary
class GeminiNaturalLanguageParserAdapter
    @Autowired
    constructor(
        private val properties: GeminiProperties,
        objectMapper: ObjectMapper,
    ) : NaturalLanguageParserPort {
        private val contract = NaturalLanguageProviderContract(objectMapper)
        private var endpoint: URI? = null

        internal constructor(
            properties: GeminiProperties,
            objectMapper: ObjectMapper,
            endpoint: URI,
        ) : this(properties, objectMapper) {
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
            val budget = minOf(request.callTimeout, Duration.ofMillis(CALL_TIMEOUT_MILLIS.toLong()))
            if (budget.toMillis() <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
            val deadline = System.nanoTime() + budget.toNanos()
            try {
                val retryOptions =
                    HttpRetryOptions
                        .builder()
                        .attempts(1)
                        .initialDelay(0.0)
                        .maxDelay(0.0)
                        .jitter(0.0)
                        .build()
                val httpOptions = HttpOptions.builder().timeout(budget.toMillis().toInt()).retryOptions(retryOptions)
                endpoint?.let { httpOptions.baseUrl(it.toString()) }
                val transport =
                    OkHttpClient
                        .Builder()
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(Duration.ZERO)
                        .readTimeout(Duration.ZERO)
                        .writeTimeout(Duration.ZERO)
                        .callTimeout(budget)
                        .build()
                // Client creation stays on the caller thread, including synthetic SDK factory tests.
                val response =
                    Client
                        .builder()
                        .apiKey(properties.apiKey)
                        .vertexAI(false)
                        .clientOptions(ClientOptions.builder().customHttpClient(transport).build())
                        .httpOptions(httpOptions.build())
                        .build()
                        .use { client ->
                            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
                            if (remainingMillis <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                            val config =
                                GenerateContentConfig
                                    .builder()
                                    .httpOptions(
                                        HttpOptions
                                            .builder()
                                            .timeout(remainingMillis.toInt())
                                            .retryOptions(retryOptions)
                                            .build(),
                                    ).responseMimeType("application/json")
                                    .responseJsonSchema(RESPONSE_SCHEMA)
                                    .candidateCount(1)
                                    .maxOutputTokens(MAX_OUTPUT_TOKENS)
                                    .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.LOW))
                                    .build()
                            client.models.generateContent(properties.model, prompt(request), config)
                        }
                if (System.nanoTime() >= deadline) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                val candidates = response.candidates().orElse(null)
                if (candidates?.size != 1 ||
                    candidates
                        .single()
                        .finishReason()
                        .orElse(null)
                        ?.knownEnum() != FinishReason.Known.STOP
                ) {
                    throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                }
                val text = response.text() ?: throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                val responseBytes = text.toByteArray(Charsets.UTF_8).size
                if (responseBytes > properties.maxResponseBytes) throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                val parsed = contract.parseProviderResponse(text, request, strict = true)
                if (System.nanoTime() >= deadline) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                val usage = response.usageMetadata().orElse(null)
                return NaturalLanguageBatchResult(
                    parsed,
                    ParserUsage(
                        usage?.promptTokenCount()?.orElse(null)?.toLong(),
                        usage?.candidatesTokenCount()?.orElse(null)?.toLong(),
                        responseBytes,
                    ),
                )
            } catch (exception: NaturalLanguageParserException) {
                throw exception
            } catch (exception: RuntimeException) {
                val kind = ProviderFailureClassifier.classify(exception)
                val retryAfter = if (kind in TRANSIENT_KINDS) extractRetryAfterMillis(exception) else null
                throw NaturalLanguageParserException(kind, retryAfter, exception)
            }
        }

        internal fun parseProviderResponse(
            json: String,
            request: NaturalLanguageBatchRequest,
        ): List<StructuredSubmissionResult> = contract.parseProviderResponse(json, request)

        internal fun prompt(request: NaturalLanguageBatchRequest): String = contract.prompt(request)

        private fun extractRetryAfterMillis(exception: RuntimeException): Long? {
            val apiFailure =
                generateSequence<Throwable>(exception) { it.cause }.take(16).filterIsInstance<ApiException>().firstOrNull()
                    ?: return null
            val details = apiFailure.message()
            val seconds =
                Regex("(?i)retry[-_ ]?after[^0-9]*(\\d+)")
                    .find(details)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
            if (seconds != null && seconds <= Long.MAX_VALUE / 1_000) return seconds * 1_000
            return Regex("(?i)retryDelay[^0-9]*(\\d+(?:\\.\\d+)?)s")
                .find(details)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?.times(1_000)
                ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE }
                ?.toLong()
        }

        companion object {
            internal const val CALL_TIMEOUT_MILLIS = 15_000
            internal const val MAX_OUTPUT_TOKENS = 32_768
            internal val RESPONSE_SCHEMA: Map<String, Any> = NaturalLanguageProviderContract.RESPONSE_SCHEMA
            private val TRANSIENT_KINDS =
                setOf(ParserFailureKind.NETWORK, ParserFailureKind.TIMEOUT, ParserFailureKind.RATE_LIMIT, ParserFailureKind.SERVER)
        }
    }
