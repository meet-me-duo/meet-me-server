package com.meetme.server.adapter.output.integration

import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.meetme.server.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.application.port.output.NaturalLanguageBatchResult
import com.meetme.server.application.port.output.NaturalLanguageParserException
import com.meetme.server.application.port.output.NaturalLanguageParserPort
import com.meetme.server.application.port.output.ParserFailureKind
import com.meetme.server.application.port.output.ParserUsage
import com.meetme.server.config.GeminiProperties
import com.meetme.server.domain.common.SubmissionVersionId
import com.meetme.server.domain.submission.StructuredCondition
import com.meetme.server.domain.submission.StructuredSubmissionResult
import com.meetme.server.domain.submission.TimePolarity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Component
class GeminiNaturalLanguageParserAdapter(
    private val properties: GeminiProperties,
    private val objectMapper: ObjectMapper,
) : NaturalLanguageParserPort {
    override fun parse(request: NaturalLanguageBatchRequest): NaturalLanguageBatchResult {
        if (properties.apiKey.isBlank()) {
            throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
        }
        require(request.inputs.isNotEmpty() && request.inputs.size <= 50)
        val expectedRefs = request.inputs.map { it.inputRef }.toSet()
        require(expectedRefs.size == request.inputs.size)
        try {
            val config =
                GenerateContentConfig
                    .builder()
                    .responseMimeType("application/json")
                    .responseJsonSchema(RESPONSE_SCHEMA)
                    .candidateCount(1)
                    .maxOutputTokens(MAX_OUTPUT_TOKENS)
                    .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.LOW))
                    .build()
            val response =
                Client
                    .builder()
                    .apiKey(properties.apiKey)
                    .httpOptions(
                        HttpOptions
                            .builder()
                            .timeout(CALL_TIMEOUT_MILLIS)
                            .retryOptions(HttpRetryOptions.builder().attempts(1))
                            .build(),
                    ).build()
                    .use { client -> client.models.generateContent(properties.model, prompt(request), config) }
            val text = response.text() ?: throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
            val responseBytes = text.toByteArray(Charsets.UTF_8).size
            if (responseBytes > properties.maxResponseBytes) {
                throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
            }
            val parsed = parseProviderResponse(text, request)
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
            throw NaturalLanguageParserException(classify(exception), extractRetryAfterMillis(exception), exception)
        }
    }

    internal fun parseProviderResponse(
        json: String,
        request: NaturalLanguageBatchRequest,
    ): List<StructuredSubmissionResult> {
        @Suppress("UNCHECKED_CAST")
        val root = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        if (root.keys != setOf("schema_version", "results") || root["schema_version"] != "1") {
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }
        @Suppress("UNCHECKED_CAST")
        val results =
            root["results"] as? List<Map<String, Any?>>
                ?: throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        val requestByRef = request.inputs.associateBy { it.inputRef }
        val parsed =
            results.map { result ->
                if (!result.keys.all { it in setOf("input_ref", "conditions", "rejection_code") }) {
                    throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                }
                val ref =
                    result["input_ref"]?.toString()
                        ?: throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                if (ref !in requestByRef) throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                @Suppress("UNCHECKED_CAST")
                val rawConditions =
                    result["conditions"] as? List<Map<String, Any?>>
                        ?: throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                if (rawConditions.size > 32) throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                var conditionRejected = false
                val conditions =
                    rawConditions.mapNotNull {
                        try {
                            parseCondition(it, request)
                        } catch (_: IllegalArgumentException) {
                            conditionRejected = true
                            null
                        } catch (_: NaturalLanguageParserException) {
                            conditionRejected = true
                            null
                        }
                    }
                val rejectionCode = result["rejection_code"]?.toString() ?: if (conditionRejected) "CONDITION_VALIDATION_FAILED" else null
                if (conditions.isEmpty() && rejectionCode.isNullOrBlank()) {
                    throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                }
                StructuredSubmissionResult(SubmissionVersionId(UUID.fromString(ref)), conditions, rejectionCode)
            }
        val expectedRefs = request.inputs.map { it.inputRef }.toSet()
        if (parsed.map { it.submissionVersionId.value.toString() }.toSet() != expectedRefs || parsed.size != request.inputs.size) {
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }
        return parsed
    }

    private fun parseCondition(
        map: Map<String, Any?>,
        request: NaturalLanguageBatchRequest,
    ): StructuredCondition =
        when (map["type"]?.toString()) {
            "TIME_WINDOW" -> {
                requireOnly(map, TIME_FIELDS)
                val date = map["date"]?.toString()?.let(LocalDate::parse)
                val day = map["day_of_week"]?.toString()?.let(DayOfWeek::valueOf)
                if (date != null && (date < request.searchStartDate || date >= request.searchEndDate)) {
                    throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                }
                StructuredCondition.TimeWindow(
                    TimePolarity.valueOf(map.getValue("polarity").toString()),
                    date,
                    day,
                    LocalTime.parse(map.getValue("start_time").toString()),
                    LocalTime.parse(map.getValue("end_time").toString()),
                )
            }
            "SPECIFIC_PLACE" -> {
                requireOnly(map, setOf("type", "query", "radius_meters"))
                if (map.keys.any { it in setOf("latitude", "longitude", "coordinates") }) invalid()
                StructuredCondition.SpecificPlace(
                    map.getValue("query").toString(),
                    (map["radius_meters"] as? Number)?.toInt() ?: 1_000,
                )
            }
            "TRAVEL_CONSTRAINT" -> {
                requireOnly(map, setOf("type", "expression"))
                StructuredCondition.TravelConstraint(map.getValue("expression").toString())
            }
            "UNRESOLVED_PLACE" -> {
                requireOnly(map, setOf("type", "query"))
                StructuredCondition.UnresolvedPlace(map.getValue("query").toString())
            }
            else -> invalid()
        }

    private fun requireOnly(
        map: Map<String, Any?>,
        fields: Set<String>,
    ) {
        if (!map.keys.all { it in fields }) invalid()
    }

    private fun invalid(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)

    private fun classify(exception: RuntimeException): ParserFailureKind {
        val text = exception.message.orEmpty().lowercase()
        return when {
            "429" in text || "resource_exhausted" in text -> ParserFailureKind.RATE_LIMIT
            "timeout" in text || "timed out" in text -> ParserFailureKind.TIMEOUT
            Regex("\\b5\\d\\d\\b").containsMatchIn(text) -> ParserFailureKind.SERVER
            "connect" in text || "network" in text || "socket" in text -> ParserFailureKind.NETWORK
            else -> ParserFailureKind.INVALID_RESPONSE
        }
    }

    private fun extractRetryAfterMillis(exception: RuntimeException): Long? {
        val text = exception.message.orEmpty()
        val seconds =
            Regex("(?i)retry[-_ ]?after[^0-9]*(\\d+)")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        if (seconds != null) return seconds * 1_000
        return Regex("(?i)retryDelay[^0-9]*(\\d+(?:\\.\\d+)?)s")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?.times(1_000)
            ?.toLong()
    }

    private fun prompt(request: NaturalLanguageBatchRequest): String =
        buildString {
            appendLine("Convert each Korean meeting constraint into the supplied language-neutral JSON schema.")
            appendLine("Never invent coordinates. Classify home/work/school-near expressions as TRAVEL_CONSTRAINT.")
            appendLine("Use UNRESOLVED_PLACE when a location cannot identify one place. Preserve every input_ref exactly once.")
            appendLine("Room time zone: ${request.timeZone.id}")
            appendLine("Search range: ${request.searchStartDate} until ${request.searchEndDate} (exclusive)")
            appendLine("Inputs:")
            request.inputs.forEach { appendLine("${it.inputRef}\t${it.locale.toLanguageTag()}\t${it.rawText}") }
        }

    companion object {
        internal const val CALL_TIMEOUT_MILLIS = 15_000
        internal const val MAX_OUTPUT_TOKENS = 32_768
        private val TIME_FIELDS = setOf("type", "polarity", "date", "day_of_week", "start_time", "end_time")
        private val RESPONSE_SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("schema_version", "results"),
                "properties" to
                    mapOf(
                        "schema_version" to mapOf("type" to "string", "enum" to listOf("1")),
                        "results" to
                            mapOf(
                                "type" to "array",
                                "maxItems" to 50,
                                "items" to
                                    mapOf(
                                        "type" to "object",
                                        "additionalProperties" to false,
                                        "required" to listOf("input_ref", "conditions"),
                                        "properties" to
                                            mapOf(
                                                "input_ref" to mapOf("type" to "string"),
                                                "rejection_code" to mapOf("type" to listOf("string", "null")),
                                                "conditions" to
                                                    mapOf(
                                                        "type" to "array",
                                                        "maxItems" to 32,
                                                        "items" to
                                                            mapOf(
                                                                "type" to "object",
                                                                "additionalProperties" to false,
                                                                "required" to listOf("type"),
                                                                "properties" to
                                                                    mapOf(
                                                                        "type" to
                                                                            mapOf(
                                                                                "type" to "string",
                                                                                "enum" to
                                                                                    listOf(
                                                                                        "TIME_WINDOW",
                                                                                        "SPECIFIC_PLACE",
                                                                                        "TRAVEL_CONSTRAINT",
                                                                                        "UNRESOLVED_PLACE",
                                                                                    ),
                                                                            ),
                                                                        "polarity" to
                                                                            mapOf(
                                                                                "type" to "string",
                                                                                "enum" to listOf("AVAILABLE", "UNAVAILABLE"),
                                                                            ),
                                                                        "date" to
                                                                            mapOf("type" to listOf("string", "null"), "format" to "date"),
                                                                        "day_of_week" to
                                                                            mapOf(
                                                                                "type" to listOf("string", "null"),
                                                                                "enum" to DayOfWeek.entries.map { it.name } + null,
                                                                            ),
                                                                        "start_time" to
                                                                            mapOf("type" to listOf("string", "null"), "format" to "time"),
                                                                        "end_time" to
                                                                            mapOf("type" to listOf("string", "null"), "format" to "time"),
                                                                        "query" to mapOf("type" to listOf("string", "null")),
                                                                        "radius_meters" to
                                                                            mapOf(
                                                                                "type" to listOf("integer", "null"),
                                                                                "minimum" to 100,
                                                                                "maximum" to 50_000,
                                                                            ),
                                                                        "expression" to mapOf("type" to listOf("string", "null")),
                                                                    ),
                                                            ),
                                                    ),
                                            ),
                                    ),
                            ),
                    ),
            )
    }
}
