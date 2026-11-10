package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

internal enum class ProviderRejectionStatus {
    INVALID_ARGUMENT,
    INVALID_REQUEST_ERROR,
    PERMISSION_DENIED,
    UNAUTHENTICATED,
    NOT_FOUND,
    RESOURCE_EXHAUSTED,
    UNAVAILABLE,
    INTERNAL,
    DEADLINE_EXCEEDED,
    UNKNOWN,
}

internal enum class ProviderRejectionReason {
    UNSUPPORTED_PARAMETER,
    SCHEMA_COMPLEXITY,
    UNKNOWN,
}

internal enum class ProviderErrorInfoReason {
    UNKNOWN,
    API_KEY_INVALID,
    SERVICE_DISABLED,
    BILLING_DISABLED,
    API_KEY_SERVICE_BLOCKED,
    API_KEY_IP_ADDRESS_BLOCKED,
    API_KEY_HTTP_REFERRER_BLOCKED,
    RATE_LIMIT_EXCEEDED,
    RESOURCE_QUOTA_EXCEEDED,
}

internal enum class ProviderRejectedField {
    CANDIDATE_COUNT,
    RESPONSE_JSON_SCHEMA,
    RESPONSE_MIME_TYPE,
    RESPONSE_FORMAT,
    MAX_OUTPUT_TOKENS,
    THINKING_LEVEL,
    TEMPERATURE,
    TOP_P,
    TOP_K,
    MODEL,
    CONTENTS,
}

internal data class ProviderRejectionDiagnostic(
    val provider: AnalysisProvider,
    val httpStatus: Int,
    val providerStatus: ProviderRejectionStatus,
    val reason: ProviderRejectionReason,
    val fields: Set<ProviderRejectedField>,
    val bodyBytes: Int,
    val bodyLimitReached: Boolean,
    val malformed: Boolean,
    val errorInfoReason: ProviderErrorInfoReason = ProviderErrorInfoReason.UNKNOWN,
)

/** Contains only bounded numbers and closed enums; never retains a provider body or free text. */
internal class ProviderRejectionDiagnosticException(
    val diagnostic: ProviderRejectionDiagnostic,
) : RuntimeException(diagnostic.toString(), null, false, true)

internal object ProviderRejectionDiagnostics {
    private val messageInspection = ThreadLocal<java.util.function.Consumer<String>>()
    private val errorInspection = ThreadLocal<java.util.function.Consumer<Map<String, Any?>>>()

    /** Explicit test-owned lexical inspection only; never registered as an application bean or persisted here. */
    fun observeMessageForCurrentThread(observer: java.util.function.Consumer<String>): AutoCloseable {
        val owner = Thread.currentThread()
        check(messageInspection.get() == null) { "One explicit inspection lease per thread" }
        messageInspection.set(observer)
        var closed = false
        return AutoCloseable {
            check(Thread.currentThread() === owner) { "Inspection lease is thread-owned" }
            if (!closed) {
                check(messageInspection.get() === observer) { "Inspection owner changed" }
                messageInspection.remove()
                closed = true
            }
        }
    }

    /** Explicit transient test observation; no default listener, storage or provider failure enrichment. */
    fun observeErrorForCurrentThread(observer: java.util.function.Consumer<Map<String, Any?>>): AutoCloseable {
        val owner = Thread.currentThread()
        check(errorInspection.get() == null) { "One explicit error inspection lease per thread" }
        errorInspection.set(observer)
        var closed = false
        return AutoCloseable {
            check(Thread.currentThread() === owner) { "Error inspection lease is thread-owned" }
            if (!closed) {
                check(errorInspection.get() === observer) { "Error inspection owner changed" }
                errorInspection.remove()
                closed = true
            }
        }
    }

    private fun immutableInspectionValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> ->
                java.util.Collections.unmodifiableMap(
                    value.entries.associateTo(linkedMapOf<String, Any?>()) { (key, child) ->
                        require(key is String)
                        key to immutableInspectionValue(child)
                    },
                )
            is List<*> -> java.util.Collections.unmodifiableList(value.map(::immutableInspectionValue))
            else -> value
        }

    private const val LIMIT = 16_384
    private val reader =
        JsonMapper
            .builder(
                JsonFactory
                    .builder()
                    .streamReadConstraints(
                        StreamReadConstraints
                            .builder()
                            .maxNestingDepth(32)
                            .maxStringLength(LIMIT)
                            .maxNumberLength(64)
                            .build(),
                    ).build(),
            ).build()
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    private val parameterNames =
        linkedMapOf(
            "candidateCount" to ProviderRejectedField.CANDIDATE_COUNT,
            "responseJsonSchema" to ProviderRejectedField.RESPONSE_JSON_SCHEMA,
            "responseMimeType" to ProviderRejectedField.RESPONSE_MIME_TYPE,
            "responseFormat" to ProviderRejectedField.RESPONSE_FORMAT,
            "maxOutputTokens" to ProviderRejectedField.MAX_OUTPUT_TOKENS,
            "thinkingLevel" to ProviderRejectedField.THINKING_LEVEL,
            "temperature" to ProviderRejectedField.TEMPERATURE,
            "topP" to ProviderRejectedField.TOP_P,
            "topK" to ProviderRejectedField.TOP_K,
            "model" to ProviderRejectedField.MODEL,
            "contents" to ProviderRejectedField.CONTENTS,
        )
    private val aliases =
        buildMap {
            parameterNames.forEach { (name, field) ->
                for (spelling in listOf(name, snake(name)).distinct()) {
                    put(spelling, field)
                    if (field !in setOf(ProviderRejectedField.MODEL, ProviderRejectedField.CONTENTS)) {
                        put("generationConfig.$spelling", field)
                        put("generation_config.$spelling", field)
                    }
                }
            }
            listOf(
                "thinkingConfig.thinkingLevel",
                "thinking_config.thinking_level",
                "generationConfig.thinkingConfig.thinkingLevel",
                "generation_config.thinking_config.thinking_level",
            ).forEach { put(it, ProviderRejectedField.THINKING_LEVEL) }
        }
    private val literalParameters =
        aliases.map { (name, field) ->
            Regex("(?<![A-Za-z0-9_.\\[\\]/:-])${Regex.escape(name)}(?![A-Za-z0-9_.\\[\\]/:-])") to field
        }

    /** Decode transient bytes to closed facts only; diagnostic failure never replaces the HTTP failure. */
    fun decode(
        provider: AnalysisProvider,
        httpStatus: Int,
        responseBytes: ByteArray,
    ): ProviderRejectionDiagnosticException {
        val empty =
            ProviderRejectionDiagnostic(
                provider,
                httpStatus.takeIf { it in 100..599 } ?: 0,
                ProviderRejectionStatus.UNKNOWN,
                ProviderRejectionReason.UNKNOWN,
                emptySet(),
                responseBytes.size.coerceAtMost(LIMIT + 1),
                responseBytes.size >= LIMIT,
                false,
            )
        if (responseBytes.size > LIMIT) return ProviderRejectionDiagnosticException(empty)
        var limitReached = empty.bodyLimitReached
        return try {
            // The dispatch gate reads status errors before OkHttp's transparent decompression.
            val decoded =
                if (responseBytes.size >= 2 && responseBytes[0] == 0x1f.toByte() && responseBytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(ByteArrayInputStream(responseBytes)).use { it.readNBytes(LIMIT + 1) }
                } else {
                    responseBytes
                }
            limitReached = limitReached || decoded.size >= LIMIT
            if (decoded.size > LIMIT) return ProviderRejectionDiagnosticException(empty.copy(bodyLimitReached = limitReached))
            val root = reader.readValue<Map<*, *>>(decoded)
            val error =
                root["error"] as? Map<*, *>
                    ?: return ProviderRejectionDiagnosticException(empty.copy(bodyLimitReached = limitReached, malformed = true))
            if (httpStatus in 400..599 && !limitReached) {
                val inspection = errorInspection.get()
                if (inspection != null) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val copy = immutableInspectionValue(error) as Map<String, Any?>
                        inspection.accept(copy)
                    } catch (_: Exception) {
                        // Optional observation cannot alter the provider response or its classification.
                    }
                }
            }
            if (provider == AnalysisProvider.GEMINI && httpStatus == 400 && !limitReached) {
                val message = error["message"] as? String
                val inspection = messageInspection.get()
                if (message != null && inspection != null) {
                    try {
                        inspection.accept(message.take(2048))
                    } catch (_: Exception) {
                        // A test inspection failure must never replace or enrich the provider failure.
                    }
                }
            }
            val providerStatus =
                if (provider == AnalysisProvider.OPENAI && error["type"] == "invalid_request_error") {
                    ProviderRejectionStatus.INVALID_REQUEST_ERROR
                } else {
                    ProviderRejectionStatus.entries.firstOrNull { it.name == error["status"] } ?: ProviderRejectionStatus.UNKNOWN
                }
            val descriptions = mutableListOf<String>()
            (error["message"] as? String)?.let(descriptions::add)
            val fields = linkedSetOf<ProviderRejectedField>()
            (error["param"] as? String)?.let { aliases[it]?.let(fields::add) }
            (error["details"] as? List<*>)?.take(16)?.forEach { detail ->
                ((detail as? Map<*, *>)?.get("fieldViolations") as? List<*>)?.take(16)?.forEach violation@{ item ->
                    val violation = item as? Map<*, *> ?: return@violation
                    (violation["field"] as? String)?.let { aliases[it]?.let(fields::add) }
                    (violation["description"] as? String)?.let(descriptions::add)
                }
            }
            val unsupported =
                error["code"] == "unsupported_parameter" ||
                    descriptions.any { text ->
                        val lower = text.lowercase(Locale.ROOT)
                        listOf("unsupported", "not supported", "unknown name", "cannot find field", "unrecognized parameter")
                            .any(lower::contains)
                    }
            val complexSchema =
                descriptions.any { text ->
                    val lower = text.lowercase(Locale.ROOT)
                    lower.contains("schema") &&
                        listOf("too many states", "too complex", "deeply nested", "nesting depth", "maximum schema depth")
                            .any(lower::contains)
                }
            if (unsupported) {
                descriptions.forEach { text ->
                    literalParameters.filter { (pattern, _) -> pattern.containsMatchIn(text) }.forEach { (_, field) -> fields.add(field) }
                }
            }
            if (complexSchema) fields.add(ProviderRejectedField.RESPONSE_JSON_SCHEMA)
            val reason =
                when {
                    complexSchema -> ProviderRejectionReason.SCHEMA_COMPLEXITY
                    unsupported && fields.isNotEmpty() -> ProviderRejectionReason.UNSUPPORTED_PARAMETER
                    else -> ProviderRejectionReason.UNKNOWN
                }
            ProviderRejectionDiagnosticException(
                empty.copy(
                    providerStatus = providerStatus,
                    reason = reason,
                    fields = fields.toSet(),
                    bodyLimitReached = limitReached,
                    errorInfoReason = googleReason(provider, error),
                ),
            )
        } catch (_: Exception) {
            ProviderRejectionDiagnosticException(empty.copy(bodyLimitReached = limitReached, malformed = true))
        }
    }

    private fun googleReason(
        provider: AnalysisProvider,
        error: Map<*, *>,
    ): ProviderErrorInfoReason {
        if (provider != AnalysisProvider.GEMINI) return ProviderErrorInfoReason.UNKNOWN
        val reasons =
            (error["details"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? Map<*, *> }
                .filter { it["@type"] == "type.googleapis.com/google.rpc.ErrorInfo" && it["domain"] == "googleapis.com" }
                .mapNotNull { detail ->
                    ProviderErrorInfoReason.entries.firstOrNull { it != ProviderErrorInfoReason.UNKNOWN && it.name == detail["reason"] }
                }.toSet()
        return reasons.singleOrNull() ?: ProviderErrorInfoReason.UNKNOWN
    }

    private fun snake(value: String) = value.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.ROOT)
}
