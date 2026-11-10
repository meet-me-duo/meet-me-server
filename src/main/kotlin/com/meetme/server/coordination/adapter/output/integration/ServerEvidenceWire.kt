package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.submission.domain.dsl.IndexedEvidenceSelection
import com.meetme.server.submission.domain.dsl.MeetingDateScopePolicy
import com.meetme.server.submission.domain.dsl.MeetingMonthCalendarFacts
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.dsl.WeeklyScheduleRules
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** Shared native V5 wire. Runtime and evaluation use the same model-visible data, rules and schema. */
object ServerEvidenceWire {
    fun bytes(
        mapper: ObjectMapper,
        neutral: ByteArray,
        provider: AnalysisProvider,
        model: String,
    ): ByteArray {
        val data =
            mapper
                .readerFor(Map::class.java)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue<Map<String, Any?>>(neutral)
                .toMutableMap()
        val scoped = data["response_contract_version"] == SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
        require(
            if (data["response_contract_version"] == SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT) {
                data["request_contract_version"] == SERVER_EVIDENCE_WEEKLY_REQUEST_CONTRACT &&
                    data["date_scope_version"] == MeetingDateScopePolicy.VERSION &&
                    data["calendar_facts_version"] == MeetingMonthCalendarFacts.VERSION &&
                    data["evidence_selection_version"] == IndexedEvidenceSelection.VERSION &&
                    data["weekly_rules_version"] == WeeklyScheduleRules.VERSION
            } else if (data["response_contract_version"] == SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT) {
                data["request_contract_version"] == SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT &&
                    data["date_scope_version"] == MeetingDateScopePolicy.VERSION &&
                    data["calendar_facts_version"] == MeetingMonthCalendarFacts.VERSION &&
                    data["evidence_selection_version"] == IndexedEvidenceSelection.VERSION
            } else if (data["response_contract_version"] == SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT) {
                data["request_contract_version"] == SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT &&
                    data["date_scope_version"] == MeetingDateScopePolicy.VERSION &&
                    data["calendar_facts_version"] == MeetingMonthCalendarFacts.VERSION
            } else if (scoped) {
                data["request_contract_version"] == SERVER_EVIDENCE_SCOPED_REQUEST_CONTRACT &&
                    data["date_scope_version"] == MeetingDateScopePolicy.VERSION
            } else {
                data["request_contract_version"] == SERVER_EVIDENCE_REQUEST_CONTRACT &&
                    data["response_contract_version"] == SERVER_EVIDENCE_RESPONSE_CONTRACT
            },
        )
        require(data["evidence_catalog_version"] == ServerEvidenceCatalog.VERSION)
        val instructions = data.remove("prompt") as? String ?: throw IllegalArgumentException("Missing evidence instructions")
        require(instructions.isNotBlank())
        @Suppress("UNCHECKED_CAST")
        val schema =
            ServerEvidenceGenerationSchema.create(
                data.remove("schema") as? Map<String, Any?> ?: throw IllegalArgumentException("Missing evidence schema"),
            )
        val input = mapper.writeValueAsString(data)
        val root =
            if (provider == AnalysisProvider.GEMINI) {
                mapOf(
                    "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to instructions))),
                    "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to input)))),
                    "generationConfig" to
                        mapOf(
                            "responseMimeType" to "application/json",
                            "responseJsonSchema" to GeminiResponseSchemaProjection.project(schema),
                            "maxOutputTokens" to 32768,
                            "thinkingConfig" to mapOf("thinkingLevel" to "LOW"),
                        ),
                )
            } else {
                mapOf(
                    "model" to model,
                    "store" to false,
                    "instructions" to instructions,
                    "input" to input,
                    "max_output_tokens" to 32768,
                    "reasoning" to mapOf("effort" to "low"),
                    "text" to
                        mapOf(
                            "format" to
                                mapOf(
                                    "type" to "json_schema",
                                    "name" to "meet_me_server_evidence_v1",
                                    "strict" to true,
                                    "schema" to schema,
                                ),
                        ),
                )
            }
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "Evidence wire request limit" } }
    }
}

/** Existing temporal precision and eight-level expression bounds, applied identically for both providers. */
internal object ServerEvidenceGenerationSchema {
    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (it.key as String) to it.value }
            ?: throw IllegalArgumentException("Expected evidence schema object")

    private fun list(value: Any?) = value as? List<*> ?: throw IllegalArgumentException("Expected evidence schema list")

    fun create(schema: Map<String, Any?>): Map<String, Any?> {
        val rootProperties = map(schema["properties"])
        val results = map(rootProperties["results"])
        val result = map(results["items"])
        val fields = map(result["properties"])
        val conditions = map(fields["conditions"])
        val item = map(conditions["items"])
        val original = list(item["anyOf"])
        require(original.size in 2..3)
        val weeklyBranches = original.drop(2)
        weeklyBranches.forEach { branch ->
            val props = map(map(branch)["properties"])
            val temporal = list(map(props["temporal"])["anyOf"]).single()
            require(map(map(temporal)["properties"])["kind"] == mapOf("type" to "string", "enum" to listOf("WEEKLY")))
        }
        val other = map(original[1])
        val atoms = list(map(map(other["properties"])["temporal"])["anyOf"]).map(::map)
        require(
            atoms.map { map(map(it["properties"])["kind"])["enum"] } ==
                listOf(listOf("APPROVED_TERM"), listOf("BOUNDS"), listOf("UNKNOWN"), listOf("RAW_SCOPE")),
        )

        fun arm(
            value: Any?,
            type: String,
        ) = list(map(value)["anyOf"]).map(::map).single { it["type"] == type }

        fun atom(
            original: Map<String, Any?>,
            changes: Map<String, Any?>,
        ) = original + ("properties" to (map(original["properties"]) + changes))

        fun branch(
            temporal: Map<String, Any?>,
            effects: List<String>? = null,
            interpretations: List<String>? = null,
            precision: List<String>? = null,
        ): Map<String, Any?> {
            val props = map(other["properties"]).toMutableMap()
            props["temporal"] = mapOf("anyOf" to listOf(temporal))
            if (effects != null) props["effect"] = map(props["effect"]) + ("enum" to effects)
            if (interpretations != null) props["interpretation"] = map(props["interpretation"]) + ("enum" to interpretations)
            if (precision != null) props["temporal_resolution"] = map(props["temporal_resolution"]) + ("enum" to precision)
            return other + ("properties" to props)
        }
        val effects = listOf("AVAILABLE", "UNAVAILABLE", "PREFERRED", "UNRESOLVED")
        val known = effects - "UNRESOLVED"
        val interpretations = listOf("EXPLICIT", "INFERRED", "UNRESOLVED")
        val imprecise = listOf("NON_PRECISE")
        val term = atoms[0]
        val scope = map(term["properties"])["model_scope"]
        val bounds = atoms[1]
        val lower = arm(map(bounds["properties"])["lower"], "object")
        val upper = arm(map(bounds["properties"])["upper"], "object")
        val absent = arm(map(bounds["properties"])["lower"], "null")

        fun bound(
            lo: Any?,
            hi: Any?,
        ) = atom(bounds, mapOf("lower" to lo, "upper" to hi))
        val variants =
            listOf(
                original[0],
                branch(atom(term, mapOf("model_scope" to arm(scope, "null"))), effects, interpretations, imprecise),
                branch(atom(term, mapOf("model_scope" to arm(scope, "object"))), effects, listOf("INFERRED"), imprecise),
                branch(bound(lower, absent), known, listOf("EXPLICIT", "INFERRED"), imprecise),
                branch(bound(absent, upper), known, listOf("EXPLICIT", "INFERRED"), imprecise),
                branch(bound(lower, upper), effects, listOf("EXPLICIT"), listOf("PRECISE_INTERVAL")),
                branch(bound(lower, upper), effects, listOf("INFERRED"), imprecise),
                branch(atoms[2], effects, interpretations, imprecise),
                branch(atoms[3]),
            )
        val nextConditions = conditions + ("items" to (item + ("anyOf" to (variants + weeklyBranches))))
        val nextResult = result + ("properties" to (fields + ("conditions" to nextConditions)))
        val nextResults = results + ("items" to nextResult)
        val coherent = schema + ("properties" to (rootProperties + ("results" to nextResults)))
        val name = "c5_inline_expression"
        val definitions = map(coherent["\$defs"])
        require(definitions.keys == setOf(name) || definitions.keys == setOf(name, "meeting_date"))
        val expressions = list(map(definitions[name])["anyOf"])
        require(expressions.size == 6)

        fun refs(
            value: Any?,
            next: String,
        ): Any? =
            when (value) {
                is List<*> -> value.map { refs(it, next) }
                is Map<*, *> -> {
                    val node = map(value)
                    if (node["type"] == "array" && node["maxItems"] == 0 && node["items"] == mapOf("\$ref" to "#/\$defs/$name")) {
                        node + ("items" to mapOf("type" to "null"))
                    } else {
                        node.mapValues { (key, child) ->
                            if (key == "\$ref") {
                                when (child) {
                                    "#/\$defs/$name" -> next
                                    "#/\$defs/meeting_date" -> child.also { require("meeting_date" in definitions) }
                                    else -> throw IllegalArgumentException("Unsupported evidence schema reference")
                                }
                            } else {
                                refs(child, next)
                            }
                        }
                    }
                }
                else -> value
            }
        val finite =
            (1..8).associate { depth ->
                (if (depth == 1) name else "${name}_depth_$depth") to
                    mapOf("anyOf" to refs(if (depth == 8) expressions.take(1) else expressions, "#/\$defs/${name}_depth_${depth + 1}"))
            }
        return coherent + ("\$defs" to (definitions.filterKeys { it != name } + finite))
    }
}
