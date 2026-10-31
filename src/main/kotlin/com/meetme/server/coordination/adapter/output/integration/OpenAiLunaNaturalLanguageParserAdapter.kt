package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
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
import com.meetme.server.shared.adapter.output.aihttp.PreparedAiHttpRequest
import com.meetme.server.shared.adapter.output.aihttp.physicalProfile
import com.meetme.server.shared.adapter.output.aihttp.validateFinalDispatch
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
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
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component("lunaParser")
class OpenAiLunaNaturalLanguageParserAdapter
    constructor(
        private val properties: OpenAiProperties,
        private val objectMapper: ObjectMapper,
        private val transportObservations: ParserTransportObservationPort? = null,
        private val dispatchGate: AiHttpDispatchGate? = null,
    ) : NaturalLanguageParserPort {
        private val contract = NaturalLanguageProviderContract(objectMapper)
        private var httpClient: OkHttpClient = OkHttpClient.Builder().build()
        private var endpoint: URI = URI.create("https://api.openai.com/v1/responses")

        @Autowired
        constructor(
            properties: OpenAiProperties,
            objectMapper: ObjectMapper,
            managedDispatchGate: AiHttpDispatchGate,
            transportObservations: ParserTransportObservationPort? = null,
        ) : this(properties, objectMapper, transportObservations, managedDispatchGate)

        internal constructor(
            properties: OpenAiProperties,
            objectMapper: ObjectMapper,
            httpClient: OkHttpClient,
            endpoint: URI,
            transportObservations: ParserTransportObservationPort? = null,
            dispatchGate: AiHttpDispatchGate? = null,
        ) : this(properties, objectMapper, transportObservations, dispatchGate) {
            this.httpClient = httpClient
            this.endpoint = endpoint
        }

        override fun parse(request: NaturalLanguageBatchRequest): NaturalLanguageBatchResult {
            val observation =
                ParserTransportObservationSession(
                    request.observationContext,
                    com.meetme.server.coordination.application.port.output.AnalysisProvider.OPENAI,
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
            val budget = minOf(request.callTimeout, AnalysisInvocationBudget.LUNA_CALL_TIMEOUT)
            if (budget.toMillis() <= 0) throw NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
            val deadline = System.nanoTime() + budget.toNanos()
            var pending: Call? = null
            var physical: GatedAiHttpExchange? = null
            var physicalUsage = AiBudgetUsage()
            var completed = false
            var primaryFailure: NaturalLanguageParserException? = null

            fun propagate(failure: NaturalLanguageParserException): Nothing {
                primaryFailure = failure
                throw failure
            }
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
                        .eventListenerFactory(observation.eventListenerFactory)
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(Duration.ZERO)
                        .readTimeout(Duration.ZERO)
                        .writeTimeout(Duration.ZERO)
                        .callTimeout(Duration.ofNanos(remaining))
                        .build()
                if (dispatchGate?.properties?.enabled == true) {
                    validateFinalDispatch(request)
                    val context = request.dispatchContext!!
                    val prepared =
                        PreparedAiHttpRequest.freeze(
                            AiPhysicalOperation.FINAL_FALLBACK,
                            context.providerOrdinal,
                            context.finalAttemptId,
                            physicalProfile(
                                AiPhysicalOperation.FINAL_FALLBACK,
                                properties.model,
                                endpoint,
                                32 * 1024 * 1024,
                            ),
                            body.toByteArray(Charsets.UTF_8),
                        )
                    val group = dispatchGate.prepareGroup(context, listOf(prepared))
                    physical = dispatchGate.forPreparedCall(transport, group, AiPhysicalOperation.FINAL_FALLBACK)
                }
                val httpRequest =
                    Request
                        .Builder()
                        .url(endpoint.toString())
                        .header("Authorization", "Bearer ${properties.apiKey}")
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                pending = (physical?.client ?: transport).newCall(httpRequest)
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
                val observedUsage = root["usage"]
                physicalUsage =
                    AiBudgetUsage(
                        inputTokens = tokenCount(observedUsage, "input_tokens"),
                        outputTokens = tokenCount(observedUsage, "output_tokens"),
                    )
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
                completed = true
                return NaturalLanguageBatchResult(
                    parsed,
                    ParserUsage(
                        tokenCount(usage, "input_tokens"),
                        tokenCount(usage, "output_tokens"),
                        text.toByteArray(Charsets.UTF_8).size,
                    ),
                )
            } catch (_: AiHttpDispatchException) {
                pending?.cancel()
                propagate(NaturalLanguageParserException(ParserFailureKind.CONFIGURATION))
            } catch (exception: AiHttpProviderStatusException) {
                pending?.cancel()
                propagate(httpFailure(exception.status, exception.retryAfterMillis?.div(1000)?.toString(), exception.responseBytes))
            } catch (exception: NaturalLanguageParserException) {
                pending?.cancel()
                propagate(exception)
            } catch (exception: IOException) {
                pending?.cancel()
                propagate(NaturalLanguageParserException(ProviderFailureClassifier.classify(exception), cause = exception))
            } catch (exception: RuntimeException) {
                pending?.cancel()
                propagate(NaturalLanguageParserException(ProviderFailureClassifier.classify(exception), cause = exception))
            } finally {
                try {
                    physical?.observe(
                        UUID.randomUUID(),
                        if (completed) AiBudgetOutcome.SUCCEEDED else AiBudgetOutcome.FAILED,
                        physicalUsage,
                    )
                } catch (observationFailure: AiHttpDispatchException) {
                    val failure = primaryFailure
                    if (failure == null) throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
                    failure.addSuppressed(observationFailure)
                }
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
