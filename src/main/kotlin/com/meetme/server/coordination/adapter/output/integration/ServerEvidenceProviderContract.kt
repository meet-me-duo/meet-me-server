package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** The model chooses immutable server references; native text is never reconstructed from model prose. */
class ServerEvidenceProviderContract(
    private val mapper: ObjectMapper,
    private val allowPlaceConstraints: Boolean = false,
) {
    private val previous = ScheduleDslStructuralProviderContract(mapper, allowPlaceConstraints = allowPlaceConstraints)
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun requestBytes(
        capsule: ScheduleDslProviderInputCapsule,
        meetingMode: MeetingMode,
    ): ByteArray {
        val root = map(reader.readValue<Map<String, Any?>>(previous.requestBytes(capsule, meetingMode))).toMutableMap()
        val catalog = ServerEvidenceCatalog.create(capsule.snapshot)
        root["request_contract_version"] = SERVER_EVIDENCE_REQUEST_CONTRACT
        root["response_contract_version"] = SERVER_EVIDENCE_RESPONSE_CONTRACT
        root["producer_schema_revision"] = "server-evidence-refs-1"
        root["evidence_catalog_version"] = ServerEvidenceCatalog.VERSION
        root["evidence_catalog"] =
            mapOf(
                "version" to ServerEvidenceCatalog.VERSION,
                "entries" to
                    catalog.entries.map {
                        mapOf(
                            "id" to it.id,
                            "input_ref" to it.inputId.toString(),
                            "source_id" to it.span.sourceId,
                            "revision" to it.span.revision,
                            "kind" to it.kind.name,
                            "text" to it.text,
                        )
                    },
            )
        root["schema"] = responseSchema()
        val prompt = root.getValue("prompt") as String
        val start = prompt.indexOf("Meaning and evidence")
        val end = prompt.indexOf("Temporal representation")
        require(start >= 0 && end > start)
        val instructions = prompt.substring(0, start) + INSTRUCTIONS + "\n\n" + prompt.substring(end)
        val place =
            if (allowPlaceConstraints) {
                ScheduleDslPlaceProviderContract.INSTRUCTIONS
                    .lines()
                    .filterNot { "locator" in it.lowercase() || "native quote" in it.lowercase() }
                    .joinToString("\n")
            } else {
                ""
            }
        root.putAll(ScheduleDslRelativeDateContext.enrich(capsule, root))
        root["prompt"] =
            (instructions + "\n\n" + place + "\n\n" + INSTRUCTIONS + "\n" + ScheduleDslRelativeDateContext.INSTRUCTIONS)
                .replace("while keeping evidence quotes exactly as written", "while selecting issued references to unchanged native text")
                .replace(
                    "The server retains the supplied meaning and temporal proof bundle, never an invented enclosing quote.",
                    "The server retains the declared meaning and the selected native evidence references.",
                )
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "Server evidence request byte limit" } }
    }

    fun responseSchema(): Map<String, Any?> {
        fun reference(value: Map<String, Any?>): Map<String, Any?> =
            when {
                value["type"] == "array" -> value + ("items" to REF)
                value["anyOf"] is List<*> -> mapOf("anyOf" to listOf(REF, mapOf("type" to "null")))
                else -> REF
            }

        fun convert(value: Any?): Any? =
            when (value) {
                is List<*> -> value.map { convert(it) }
                is Map<*, *> -> {
                    val next = map(value).mapValues { convert(it.value) }.toMutableMap()
                    val properties = value["properties"] as? Map<*, *>
                    if (properties != null) {
                        next["properties"] =
                            map(properties).filterKeys { it != "proofs" }.mapValues { (name, field) ->
                                if (name in EVIDENCE_FIELDS) reference(map(field)) else convert(field)
                            }
                        (value["required"] as? List<*>)?.let { next["required"] = it.filter { name -> name != "proofs" } }
                    }
                    next
                }
                else -> value
            }
        val root = map(convert(previous.responseSchema()))
        return root +
            mapOf(
                "properties" to (
                    map(root["properties"]) + (
                        "evidence_catalog_version" to
                            mapOf("type" to "string", "enum" to listOf(ServerEvidenceCatalog.VERSION))
                    )
                ),
                "required" to ((root["required"] as List<*>) + "evidence_catalog_version"),
            )
    }

    fun parseProviderResponse(
        json: String,
        snapshot: ScheduleEvidenceSnapshot,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslStructuralEnvelope =
        try {
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144) { "SERVER_EVIDENCE_RAW_BYTE_LIMIT" }
            val root = reader.readValue<Map<String, Any?>>(json)
            require(root["evidence_catalog_version"] == ServerEvidenceCatalog.VERSION) { "Missing evidence catalog version" }
            val expected =
                setOf("semantic_version", "ast_version", "vocabulary_version", "results", "evidence_catalog_version") +
                    if (allowPlaceConstraints) setOf("place_constraints_version") else emptySet()
            require(root.keys == expected) { "Unexpected server evidence response fields" }
            val catalog = ServerEvidenceCatalog.create(snapshot)

            fun expand(
                value: Any?,
                input: UUID,
                depth: Int = 0,
            ): Any? {
                require(depth <= 32)

                fun reference(raw: Any?): Map<String, Any?> {
                    val id = raw as? String ?: throw IllegalArgumentException("Expected server evidence ID")
                    val proof = catalog.resolve(input, id)
                    val source = requireNotNull(snapshot.sourceText(input, proof.span.sourceId))
                    val text =
                        source.substring(
                            source.offsetByCodePoints(0, proof.span.startCodePoint),
                            source.offsetByCodePoints(0, proof.span.endCodePoint),
                        )
                    return mapOf("source_id" to proof.span.sourceId, "revision" to proof.span.revision, "quote" to text, "catalog_id" to id)
                }
                return when (value) {
                    is List<*> -> value.map { expand(it, input, depth + 1) }
                    is Map<*, *> ->
                        map(value).mapValues { (name, field) ->
                            require(
                                name !in setOf("quote", "proofs", "start_code_point", "end_code_point", "catalog_id"),
                            ) { "Mixed model evidence syntax" }
                            if (name in EVIDENCE_FIELDS) {
                                when (field) {
                                    null -> {
                                        require(name == "place_evidence")
                                        null
                                    }
                                    is List<*> -> {
                                        require(field.size in 1..32)
                                        field.map { reference(it) }
                                    }
                                    else -> reference(field)
                                }
                            } else {
                                expand(field, input, depth + 1)
                            }
                        }
                    is String -> value.also { require('\u0000' !in it && it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it) }
                    else -> value
                }
            }
            val results = root["results"] as? List<*> ?: throw IllegalArgumentException("Missing results")
            require(results.size in 2..50)
            val expanded =
                results.map { raw ->
                    val result = map(raw)
                    val input = UUID.fromString(result["input_ref"] as String)
                    require(input.toString() == result["input_ref"] && snapshot.context.inputs.any { it.submissionVersionId == input })
                    val conditions = result["conditions"] as? List<*> ?: throw IllegalArgumentException("Missing conditions")
                    require(conditions.all { map(it).keys == CONDITION_FIELDS }) { "Unexpected referenced condition fields" }
                    expand(result, input)
                }
            val expandedJson = mapper.writeValueAsString((root - "evidence_catalog_version") + ("results" to expanded))
            require(expandedJson.toByteArray(Charsets.UTF_8).size <= 2_097_152) { "SERVER_EVIDENCE_EXPANSION_BYTE_LIMIT" }
            ScheduleDslStructuralProviderContract(mapper, allowPlaceConstraints = allowPlaceConstraints, serverCatalog = catalog)
                .parseProviderResponse(
                    expandedJson,
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
        } catch (failure: NaturalLanguageParserException) {
            throw failure
        } catch (failure: RuntimeException) {
            val fixedCode =
                failure.message?.takeIf {
                    it in setOf("SERVER_EVIDENCE_RAW_BYTE_LIMIT", "SERVER_EVIDENCE_EXPANSION_BYTE_LIMIT")
                } ?: "SERVER_EVIDENCE_INVALID_REFERENCE_OR_SHAPE"
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE, cause = IllegalArgumentException(fixedCode))
        }

    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate {
            (it.key as? String ?: throw IllegalArgumentException("Expected key")) to it.value
        } ?: throw IllegalArgumentException("Expected object")

    companion object {
        private val EVIDENCE_FIELDS = setOf("evidence", "date_evidence", "clock_evidence", "term_evidence", "place_evidence")
        private val CONDITION_FIELDS =
            setOf("id", "effect", "interpretation", "temporal_resolution", "evidence", "assertion", "supersedes", "temporal")
        private val REF = mapOf("type" to "string", "pattern" to "^ev:[a-f0-9]{32}$")
        private const val INSTRUCTIONS = """
            Server-issued evidence references
            Return evidence_catalog_version exactly as supplied. Every evidence field selects IDs from evidence_catalog,
            owned by the same input_ref and actual original or linked supplemental source. Return reference IDs only;
            never create a proof bank, quote, source, revision, evidence offset, or replacement evidence text.
            evidence/date_evidence/clock_evidence are nonempty arrays of IDs (place declarations use one evidence ID);
            term_evidence and place_evidence select one ID. Context entries preserve actual wording including negation,
            conjunctions and restrictions. TERM entries identify exact approved lexemes; CLOCK and PLACE entries are
            literal subspans, not assertions of meaning. For a relation node select the context that supports that relation;
            retain the appropriate original and supplemental contexts instead of joining or paraphrasing them.
            Choose relevant context plus exact temporal/place subspans without changing condition ownership or branch scope.
            Missing evidence remains unresolved, never an invented reference or participant confirmation.
            Questions are context, not participant assertions. The server resolves IDs to native positions and hashes.
            These references establish provenance only; interpret meaning and all logical relations from the supplied text.
        """
    }
}
