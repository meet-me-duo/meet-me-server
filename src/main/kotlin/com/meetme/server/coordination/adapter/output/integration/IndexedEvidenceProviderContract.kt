package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.IndexedEvidenceSelection
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** Model-visible small selections are bound to a frozen native catalog; ownership stays server-validated. */
class IndexedEvidenceProviderContract(
    private val mapper: ObjectMapper,
    private val allowPlaceConstraints: Boolean = false,
) {
    private val previous = CalendarFactsEvidenceProviderContract(mapper, allowPlaceConstraints)
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun requestBytes(
        capsule: ScheduleDslProviderInputCapsule,
        meetingMode: MeetingMode,
    ): ByteArray {
        val root = reader.readValue<Map<String, Any?>>(previous.requestBytes(capsule, meetingMode)).toMutableMap()
        val catalog = map(root["evidence_catalog"])
        val entries = catalog["entries"] as List<*>
        root["request_contract_version"] = SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT
        root["response_contract_version"] = SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
        root["producer_schema_revision"] = IndexedEvidenceSelection.VERSION
        root["evidence_selection_version"] = IndexedEvidenceSelection.VERSION
        root["evidence_catalog"] = catalog + (
            "entries" to
                entries.mapIndexed { index, entry ->
                    (map(entry) - "id") + ("selection" to index)
                }
        )
        root["schema"] = responseSchema(capsule.snapshot)
        root["prompt"] = (root.getValue("prompt") as String)
            .replace(
                "$SERVER_EVIDENCE_CALENDAR_REQUEST_CONTRACT / $SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT",
                "$SERVER_EVIDENCE_INDEXED_REQUEST_CONTRACT / $SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT",
            ).replace("reference IDs", "integer selections")
            .replace("IDs from evidence_catalog", "integer selections from evidence_catalog.entries")
            .replace("arrays of IDs", "arrays of integer selections")
            .replace("one ID", "one integer selection")
            .replace("one evidence ID", "one integer evidence selection") + "\n\n" + INSTRUCTIONS
        return mapper.writeValueAsBytes(root).also { require(it.size <= 1_048_576) { "Indexed evidence request limit" } }
    }

    fun responseSchema(snapshot: ScheduleEvidenceSnapshot): Map<String, Any?> {
        val count = ServerEvidenceCatalog.create(snapshot).entries.size
        require(count > 0)

        fun convert(value: Any?): Any? =
            when (value) {
                is List<*> -> value.map(::convert)
                is Map<*, *> -> {
                    val node = map(value)
                    if (node["pattern"] == "^ev:[a-f0-9]{32}$") {
                        mapOf("type" to "integer", "minimum" to 0, "maximum" to count - 1)
                    } else {
                        node.mapValues { convert(it.value) }
                    }
                }
                else -> value
            }
        val root = map(convert(previous.responseSchema(snapshot)))
        return root +
            mapOf(
                "properties" to (
                    map(root["properties"]) + (
                        "evidence_selection_version" to
                            mapOf("type" to "string", "enum" to listOf(IndexedEvidenceSelection.VERSION))
                    )
                ),
                "required" to ((root["required"] as List<*>) + "evidence_selection_version"),
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
            require(json.toByteArray(Charsets.UTF_8).size <= 262_144) { "INDEXED_EVIDENCE_RAW_BYTE_LIMIT" }
            val root = reader.readValue<Map<String, Any?>>(json)
            require(root["evidence_selection_version"] == IndexedEvidenceSelection.VERSION) { "INDEXED_EVIDENCE_MARKER" }
            val entries = ServerEvidenceCatalog.create(snapshot).entries

            fun reference(value: Any?): String {
                require(value is Int || value is Long) { "INDEXED_EVIDENCE_INTEGER_REQUIRED" }
                val index = (value as Number).toLong()
                require(index >= 0 && index < entries.size) { "INDEXED_EVIDENCE_SELECTION_OUT_OF_RANGE" }
                return entries[index.toInt()].id
            }

            fun expand(
                value: Any?,
                depth: Int = 0,
            ): Any? {
                require(depth <= 32)
                return when (value) {
                    is List<*> -> value.map { expand(it, depth + 1) }
                    is Map<*, *> ->
                        map(value).mapValues { (name, field) ->
                            if (name in EVIDENCE_FIELDS) {
                                when (field) {
                                    null -> null
                                    is List<*> -> field.map(::reference)
                                    else -> reference(field)
                                }
                            } else {
                                expand(field, depth + 1)
                            }
                        }
                    else -> value
                }
            }
            val decoded = mapper.writeValueAsString(expand(root - "evidence_selection_version"))
            require(decoded.toByteArray(Charsets.UTF_8).size <= 262_144) { "INDEXED_EVIDENCE_RESOLVED_BYTE_LIMIT" }
            previous.parseProviderResponse(decoded, snapshot, semanticVersion, astVersion, vocabularyVersion)
        } catch (failure: NaturalLanguageParserException) {
            throw failure
        } catch (failure: RuntimeException) {
            val code =
                failure.message?.takeIf {
                    it in
                        setOf(
                            "INDEXED_EVIDENCE_RAW_BYTE_LIMIT",
                            "INDEXED_EVIDENCE_RESOLVED_BYTE_LIMIT",
                            "INDEXED_EVIDENCE_MARKER",
                            "INDEXED_EVIDENCE_INTEGER_REQUIRED",
                            "INDEXED_EVIDENCE_SELECTION_OUT_OF_RANGE",
                        )
                } ?: "INDEXED_EVIDENCE_INVALID_RESPONSE"
            throw NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE, cause = IllegalArgumentException(code))
        }

    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (it.key as String) to it.value }
            ?: throw IllegalArgumentException("Expected indexed evidence object")

    companion object {
        private val EVIDENCE_FIELDS = setOf("evidence", "date_evidence", "clock_evidence", "term_evidence", "place_evidence")
        private const val INSTRUCTIONS = """
            Server-issued evidence selections (server-evidence-selection-1):
            Return evidence_selection_version exactly as supplied. Every evidence reference is the INTEGER selection
            shown in evidence_catalog.entries, never a string, hash, quote, offset or invented identifier.
            The catalog is a single frozen zero-based selection list for this request. Pick only entries owned by the
            same input_ref and the relevant original or actual linked supplemental source. Do not reuse selections
            from another request or participant. All condition, graph, date, clock, approved-term and place evidence
            fields use these selections. The server connects each selection to unchanged native positions and hashes
            and verifies source ownership and revisions. A selection establishes provenance, not semantic correctness.
            Preserve all existing condition roles, time interpretation, corrections and date authority.
        """
    }
}
