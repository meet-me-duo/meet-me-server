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
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.aihttp.GatedAiHttpExchange
import com.meetme.server.shared.adapter.output.aihttp.physicalProfile
import com.meetme.server.shared.adapter.output.aihttp.validateFinalDispatch
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.submission.domain.StructuredSubmissionResult
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Duration
import java.util.UUID

@Component
@Primary
class GeminiNaturalLanguageParserAdapter
    constructor(
        private val properties: GeminiProperties,
        objectMapper: ObjectMapper,
        private val transportObservations: ParserTransportObservationPort? = null,
        private val dispatchGate: AiHttpDispatchGate? = null,
    ) : NaturalLanguageParserPort {
        private val contract = NaturalLanguageProviderContract(objectMapper)
        private val mapper = objectMapper
        private var endpoint: URI? = null

        @Autowired
        constructor(
            properties: GeminiProperties,
            objectMapper: ObjectMapper,
            managedDispatchGate: AiHttpDispatchGate,
            transportObservations: ParserTransportObservationPort? = null,
        ) : this(properties, objectMapper, transportObservations, managedDispatchGate)

        internal constructor(
            properties: GeminiProperties,
            objectMapper: ObjectMapper,
            endpoint: URI,
            transportObservations: ParserTransportObservationPort? = null,
            dispatchGate: AiHttpDispatchGate? = null,
        ) : this(properties, objectMapper, transportObservations, dispatchGate) {
            this.endpoint = endpoint
        }

        override fun parse(request: NaturalLanguageBatchRequest): NaturalLanguageBatchResult {
            val observation =
                ParserTransportObservationSession(
                    request.observationContext,
                    com.meetme.server.coordination.application.port.output.AnalysisProvider.GEMINI,
                    transportObservations,
                )
            try {
                return parseObserved(request, observation)
            } finally {
                observation.finish()
            }
        }

        private fun parseObserved(
            request: NaturalLanguageBatchRequest,
            observation: ParserTransportObservationSession,
        ): NaturalLanguageBatchResult {
            if (properties.apiKey.isBlank()) throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
            validateFinalNaturalLanguageRequest(request)
            val budget = minOf(request.callTimeout, Duration.ofMillis(CALL_TIMEOUT_MILLIS.toLong()))
            if (budget.toMillis() <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
            val deadline = System.nanoTime() + budget.toNanos()
            var physical: GatedAiHttpExchange? = null
            var physicalUsage = AiBudgetUsage()
            var completed = false
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
                        .eventListenerFactory(observation.eventListenerFactory)
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(Duration.ZERO)
                        .readTimeout(Duration.ZERO)
                        .writeTimeout(Duration.ZERO)
                        .callTimeout(budget)
                        .build()
                if (dispatchGate?.properties?.enabled == true) {
                    validateFinalDispatch(request)
                    val wireEndpoint =
                        (
                            endpoint ?: URI(
                                "https://generativelanguage.googleapis.com",
                            )
                        ).resolve("/v1beta/models/${properties.model}:generateContent")
                    physical =
                        dispatchGate.forLazySingleCall(
                            transport,
                            request.dispatchContext,
                            AiPhysicalOperation.FINAL_PRIMARY,
                            physicalProfile(
                                AiPhysicalOperation.FINAL_PRIMARY,
                                properties.model,
                                wireEndpoint,
                                32 * 1024 * 1024,
                            ),
                        )
                }
                // Client creation stays on the caller thread, including synthetic SDK factory tests.
                val response =
                    Client
                        .builder()
                        .apiKey(properties.apiKey)
                        .vertexAI(false)
                        .clientOptions(ClientOptions.builder().customHttpClient(physical?.client ?: transport).build())
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
                val observedUsage = response.usageMetadata().orElse(null)
                val input =
                    observedUsage
                        ?.promptTokenCount()
                        ?.orElse(null)
                        ?.toLong()
                        ?.takeIf { it >= 0 }
                val candidate =
                    observedUsage
                        ?.candidatesTokenCount()
                        ?.orElse(null)
                        ?.toLong()
                        ?.takeIf { it >= 0 }
                val thought =
                    observedUsage
                        ?.thoughtsTokenCount()
                        ?.orElse(null)
                        ?.toLong()
                        ?.takeIf { it >= 0 }
                val output =
                    if (candidate != null &&
                        thought != null &&
                        candidate <= Long.MAX_VALUE - thought
                    ) {
                        candidate + thought
                    } else {
                        null
                    }
                physicalUsage = AiBudgetUsage(inputTokens = input, outputTokens = output, reasoningTokens = thought)
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
                completed = true
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
            } catch (exception: Exception) {
                val causes = generateSequence<Throwable>(exception) { it.cause }.take(16).toList()
                if (causes.any { it is AiHttpDispatchException }) throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
                val httpStatus = causes.filterIsInstance<AiHttpProviderStatusException>().firstOrNull()
                if (httpStatus != null) {
                    val error = runCatching { mapper.readTree(httpStatus.responseBytes)["error"] }.getOrNull()
                    val code =
                        error?.get("status")?.takeIf { it.isString }?.asString() ?: error?.get("code")?.takeIf { it.isString }?.asString()
                    val type = error?.get("type")?.takeIf { it.isString }?.asString()
                    val statusKind = ProviderFailureClassifier.classifyStatus(httpStatus.status, code, type)
                    throw NaturalLanguageParserException(
                        statusKind,
                        if (statusKind in
                            TRANSIENT_KINDS
                        ) {
                            httpStatus.retryAfterMillis
                        } else {
                            null
                        },
                    )
                }
                val kind = ProviderFailureClassifier.classify(exception)
                val retryAfter = if (kind in TRANSIENT_KINDS && exception is RuntimeException) extractRetryAfterMillis(exception) else null
                throw NaturalLanguageParserException(kind, retryAfter, exception)
            } finally {
                try {
                    physical?.observe(
                        UUID.randomUUID(),
                        if (completed) AiBudgetOutcome.SUCCEEDED else AiBudgetOutcome.FAILED,
                        physicalUsage,
                    )
                } catch (
                    _: AiHttpDispatchException,
                ) {
                    throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
                }
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
            internal val CALL_TIMEOUT_MILLIS = AnalysisInvocationBudget.GEMINI_CALL_TIMEOUT.toMillis().toInt()
            internal const val MAX_OUTPUT_TOKENS = 32_768
            internal val RESPONSE_SCHEMA: Map<String, Any> = NaturalLanguageProviderContract.RESPONSE_SCHEMA
            private val TRANSIENT_KINDS =
                setOf(ParserFailureKind.NETWORK, ParserFailureKind.TIMEOUT, ParserFailureKind.RATE_LIMIT, ParserFailureKind.SERVER)
        }
    }
