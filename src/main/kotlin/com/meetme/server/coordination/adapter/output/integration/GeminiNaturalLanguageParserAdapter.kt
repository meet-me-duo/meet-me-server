package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
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
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.TimePolarity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.DateTimeException
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
        val schemaVersion = root["schema_version"]?.toString()
        if (root.keys != setOf("schema_version", "results") || schemaVersion !in setOf("1", "2")) {
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
                            parseCondition(it, request, requireNotNull(schemaVersion))
                        } catch (_: DateTimeException) {
                            conditionRejected = true
                            null
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
        val groupedAreaNames =
            parsed
                .flatMap { it.conditions }
                .filterIsInstance<StructuredCondition.SpecificPlace>()
                .filter { it.areaKey != null }
                .groupBy({ requireNotNull(it.areaKey) }, { requireNotNull(it.areaName) })
        if (groupedAreaNames.values.any { it.distinct().size != 1 }) {
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
        }
        return parsed
    }

    private fun parseCondition(
        map: Map<String, Any?>,
        request: NaturalLanguageBatchRequest,
        schemaVersion: String,
    ): StructuredCondition {
        val meaningful = map.filterValues { it != null }
        return when (meaningful["type"]?.toString()) {
            "TIME_WINDOW" -> {
                requireOnly(meaningful, TIME_FIELDS)
                val date = meaningful["date"]?.toString()?.let(LocalDate::parse)
                val day = meaningful["day_of_week"]?.toString()?.let(DayOfWeek::valueOf)
                if (date != null && (date < request.searchStartDate || date >= request.searchEndDate)) {
                    throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE)
                }
                val normalizedDay =
                    if (date != null && day != null) {
                        require(date.dayOfWeek == day) { "Date and day_of_week must agree" }
                        null
                    } else {
                        day
                    }
                val startTime = LocalTime.parse(meaningful.getValue("start_time").toString())
                val rawEndTime = meaningful.getValue("end_time").toString()
                val endsAtNextDayStart = rawEndTime == END_OF_DAY
                StructuredCondition.TimeWindow(
                    TimePolarity.valueOf(meaningful.getValue("polarity").toString()),
                    date,
                    normalizedDay,
                    startTime,
                    if (endsAtNextDayStart) LocalTime.MIDNIGHT else LocalTime.parse(rawEndTime),
                    endsAtNextDayStart,
                )
            }
            "SPECIFIC_PLACE" -> {
                if (meaningful.keys.any { it in setOf("latitude", "longitude", "coordinates") }) invalid()
                if (schemaVersion == "2") {
                    requireOnly(meaningful, setOf("type", "query", "area_key", "area_name"))
                    StructuredCondition.SpecificPlace(
                        query = meaningful.getValue("query").toString(),
                        areaKey = meaningful.getValue("area_key").toString(),
                        areaName = meaningful.getValue("area_name").toString(),
                    )
                } else {
                    requireOnly(meaningful, setOf("type", "query", "radius_meters"))
                    StructuredCondition.SpecificPlace(
                        meaningful.getValue("query").toString(),
                        (meaningful["radius_meters"] as? Number)?.toInt() ?: 1_000,
                    )
                }
            }
            "TRAVEL_CONSTRAINT" -> {
                requireOnly(meaningful, setOf("type", "expression"))
                StructuredCondition.TravelConstraint(meaningful.getValue("expression").toString())
            }
            "UNRESOLVED_PLACE" -> {
                requireOnly(meaningful, setOf("type", "query"))
                StructuredCondition.UnresolvedPlace(meaningful.getValue("query").toString())
            }
            else -> invalid()
        }
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

    internal fun prompt(request: NaturalLanguageBatchRequest): String =
        buildString {
            appendLine("Convert each Korean meeting constraint into the supplied language-neutral JSON schema.")
            appendLine("Never invent coordinates. Classify home/work/school-near expressions as TRAVEL_CONSTRAINT.")
            appendLine("Use UNRESOLVED_PLACE when a location cannot identify one place. Preserve every input_ref exactly once.")
            appendLine(
                "Compare every explicit place across the whole batch and assign the same area_key to places in one practical meeting area.",
            )
            appendLine("Use different area_key values when places are too far apart for one local meeting area.")
            appendLine(
                "Adjacent stations or explicit places roughly within 2 km may share the same area_key; " +
                    "for example 봉천역 and 서울대입구역.",
            )
            appendLine("Use AREA_1, AREA_2, ... keys and give every shared key one identical Korean area_name suitable for display.")
            appendLine("Use HH:mm room-local wall-clock time without a UTC offset for start_time and end_time.")
            appendLine("Only end_time may use 24:00 to mean the exclusive start of the next local day.")
            appendLine("Room time zone: ${request.timeZone.id}")
            appendLine("Search range: ${request.searchStartDate} until ${request.searchEndDate} (exclusive)")
            appendLine("Inputs:")
            request.inputs.forEach { appendLine("${it.inputRef}\t${it.locale.toLanguageTag()}\t${it.rawText}") }
        }

    companion object {
        internal const val CALL_TIMEOUT_MILLIS = 15_000
        internal const val MAX_OUTPUT_TOKENS = 32_768
        private const val END_OF_DAY = "24:00"
        private val TIME_FIELDS = setOf("type", "polarity", "date", "day_of_week", "start_time", "end_time")
        private val START_TIME_SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "string",
                "description" to "Room-local wall-clock time in HH:mm format without a UTC offset",
            )
        private val END_TIME_SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "string",
                "description" to
                    "Exclusive room-local end in HH:mm format without a UTC offset; 24:00 means the next local day boundary",
            )
        private val POLARITY_SCHEMA: Map<String, Any> =
            mapOf("type" to "string", "enum" to listOf("AVAILABLE", "UNAVAILABLE"))
        private val CONDITION_SCHEMAS: List<Map<String, Any>> =
            listOf(
                timeWindowSchema(
                    date = mapOf("type" to "string", "format" to "date"),
                    dayOfWeek = mapOf("type" to "null"),
                ),
                timeWindowSchema(
                    date = mapOf("type" to "null"),
                    dayOfWeek = mapOf("type" to "string", "enum" to DayOfWeek.entries.map { it.name }),
                ),
                conditionSchema(
                    type = "SPECIFIC_PLACE",
                    required = listOf("type", "query", "area_key", "area_name"),
                    properties =
                        mapOf(
                            "query" to mapOf("type" to "string"),
                            "area_key" to
                                mapOf(
                                    "type" to "string",
                                ),
                            "area_name" to mapOf("type" to "string"),
                        ),
                ),
                conditionSchema(
                    type = "TRAVEL_CONSTRAINT",
                    required = listOf("type", "expression"),
                    properties = mapOf("expression" to mapOf("type" to "string")),
                ),
                conditionSchema(
                    type = "UNRESOLVED_PLACE",
                    required = listOf("type", "query"),
                    properties = mapOf("query" to mapOf("type" to "string")),
                ),
            )
        internal val RESPONSE_SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("schema_version", "results"),
                "properties" to
                    mapOf(
                        "schema_version" to mapOf("type" to "string", "enum" to listOf("2")),
                        "results" to
                            mapOf(
                                "type" to "array",
                                // Gemini rejects the nested 50 x 32 maxItems product as too complex.
                                // parseProviderResponse still enforces both application limits.
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
                                                        "items" to
                                                            mapOf(
                                                                "anyOf" to CONDITION_SCHEMAS,
                                                            ),
                                                    ),
                                            ),
                                    ),
                            ),
                    ),
            )

        private fun timeWindowSchema(
            date: Map<String, Any>,
            dayOfWeek: Map<String, Any>,
        ): Map<String, Any> =
            conditionSchema(
                type = "TIME_WINDOW",
                required = listOf("type", "polarity", "date", "day_of_week", "start_time", "end_time"),
                properties =
                    mapOf(
                        "polarity" to POLARITY_SCHEMA,
                        "date" to date,
                        "day_of_week" to dayOfWeek,
                        "start_time" to START_TIME_SCHEMA,
                        "end_time" to END_TIME_SCHEMA,
                    ),
            )

        private fun conditionSchema(
            type: String,
            required: List<String>,
            properties: Map<String, Map<String, Any>>,
        ): Map<String, Any> =
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to required,
                "properties" to
                    buildMap {
                        put("type", mapOf("type" to "string", "enum" to listOf(type)))
                        putAll(properties)
                    },
            )
    }
}
