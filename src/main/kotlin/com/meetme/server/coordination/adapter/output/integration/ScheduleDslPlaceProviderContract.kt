package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.dsl.ProposedLiteralQuoteLocator
import com.meetme.server.submission.domain.dsl.SCHEDULE_PLACE_CONSTRAINT_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslPlaceConstraint
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralNormalizer
import com.meetme.server.submission.domain.dsl.SchedulePlaceConstraintKind
import com.meetme.server.submission.domain.dsl.SchedulePlaceReferenceKind
import com.meetme.server.submission.domain.dsl.SchedulePlaceTimeScope
import com.meetme.server.submission.domain.dsl.ScheduleTravelMode
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.util.UUID

/** Opt-in side declarations never become time claims, AST nodes, approved terms or meeting venues. */
object ScheduleDslPlaceProviderContract {
    data class Decoded(
        val timeDocument: Map<String, Any?>,
        val constraints: List<ScheduleDslPlaceConstraint>,
    )

    fun decode(
        root: Map<String, Any?>,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ): Decoded {
        require(root["place_constraints_version"] == SCHEDULE_PLACE_CONSTRAINT_VERSION) { "Unsupported place wire version" }

        fun evidence(
            raw: Any?,
            input: UUID,
        ) = if (serverCatalog == null) {
            locate(raw, input, snapshot)
        } else {
            val value = fields(raw)
            require(value.keys == setOf("source_id", "revision", "quote", "catalog_id"))
            serverCatalog.resolve(input, string(value["catalog_id"])).also { proof ->
                val native = requireNotNull(snapshot.sourceText(input, proof.span.sourceId))
                require(value["source_id"] == proof.span.sourceId && value["revision"] == proof.span.revision)
                require(
                    value["quote"] ==
                        native.substring(
                            native.offsetByCodePoints(0, proof.span.startCodePoint),
                            native.offsetByCodePoints(0, proof.span.endCodePoint),
                        ),
                )
            }
        }
        val values = mutableListOf<ScheduleDslPlaceConstraint>()
        val results = list(root["results"])
        require(results.size in 2..50)
        val stripped =
            results.map { raw ->
                val result = fields(raw)
                require(result.containsKey("place_constraints")) { "Missing place declarations" }
                val inputText = string(result["input_ref"])
                val input = UUID.fromString(inputText)
                require(input.toString() == inputText && snapshot.context.inputs.any { it.submissionVersionId == input })
                val declarations = list(result["place_constraints"])
                require(declarations.size <= 32)
                declarations.forEach { declaration ->
                    val item = fields(declaration)
                    require(
                        item.keys ==
                            setOf("id", "kind", "reference_kind", "place_evidence", "evidence", "max_minutes", "travel_mode", "time_scope"),
                    )
                    values +=
                        ScheduleDslPlaceConstraint(
                            input,
                            string(item["id"]),
                            SchedulePlaceConstraintKind.valueOf(string(item["kind"])),
                            SchedulePlaceReferenceKind.valueOf(string(item["reference_kind"])),
                            item["place_evidence"]?.let { evidence(it, input) },
                            evidence(item["evidence"], input),
                            item["max_minutes"]?.let { integer(it).also { n -> require(n in 1..10080) } },
                            item["travel_mode"]?.let { ScheduleTravelMode.valueOf(string(it)) },
                            SchedulePlaceTimeScope.valueOf(string(item["time_scope"])),
                        )
                }
                result - "place_constraints"
            }
        return Decoded((root - "place_constraints_version") + ("results" to stripped), values)
    }

    fun schema(original: Map<String, Any?>): Map<String, Any?> {
        val properties = fields(original["properties"])
        val results = fields(properties["results"])
        val result = fields(results["items"])
        val resultProperties = fields(result["properties"])
        val locator =
            obj(
                mapOf(
                    "source_id" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 64),
                    "revision" to mapOf("type" to "integer", "minimum" to 1),
                    "quote" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 500),
                ),
            )
        val constraint =
            obj(
                linkedMapOf(
                    "id" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 64, "pattern" to "[A-Za-z0-9._:-]{1,64}"),
                    "kind" to enumeration(SchedulePlaceConstraintKind.entries.map { it.name }),
                    "reference_kind" to enumeration(SchedulePlaceReferenceKind.entries.map { it.name }),
                    "place_evidence" to nullable(locator),
                    "evidence" to locator,
                    "max_minutes" to nullable(mapOf("type" to "integer", "minimum" to 1, "maximum" to 10080)),
                    "travel_mode" to nullable(enumeration(ScheduleTravelMode.entries.map { it.name })),
                    "time_scope" to enumeration(SchedulePlaceTimeScope.entries.map { it.name }),
                ),
            )
        val nextResult =
            result +
                mapOf(
                    "properties" to
                        (
                            resultProperties +
                                ("place_constraints" to mapOf("type" to "array", "items" to constraint, "minItems" to 0, "maxItems" to 32))
                        ),
                    "required" to (list(result["required"]) + "place_constraints"),
                )
        return original +
            mapOf(
                "properties" to (
                    properties +
                        mapOf(
                            "place_constraints_version" to enumeration(listOf(SCHEDULE_PLACE_CONSTRAINT_VERSION)),
                            "results" to (results + ("items" to nextResult)),
                        )
                ),
                "required" to (list(original["required"]) + "place_constraints_version"),
            )
    }

    const val INSTRUCTIONS = """
        Also preserve every actual meeting-place or travel requirement in each input's place_constraints, separately from time conditions and the time program.
        Return place_constraints_version=schedule-place-constraints-1 and a place_constraints array in EVERY result, including [] when no place requirement was stated.
        A named location can be a travel origin: 'from a station within one hour' is TRAVEL_TIME_LIMIT with that exact native place_evidence, max_minutes=60, not a requirement to meet at the station.
        Use MEETING_PLACE only for an explicit meeting-place requirement; its max_minutes and travel_mode are null. Incidental locations of performances, appointments or return trips in a time explanation are not meeting-place requirements.
        Each declaration owns an exact full-expression evidence locator and its contained exact place_evidence locator from the same supplied source and revision. Locator keys are source_id,revision,quote. Never normalize or append '역' inside a native quote, emit offsets, borrow another input's source, or broaden a place name into the full sentence.
        IDs are separate from all time condition/node/mode IDs; time claims plus mode and place declarations total at most32 per input. Place declaration IDs must never be referenced by time programs or approved terms.
        reference_kind is PUBLIC_NAMED_PLACE for a named public landmark, PRIVATE_REFERENCE for personal home/company/private origins, UNKNOWN when unresolved. This label does not authorize disclosure; the server checks its own public-name dictionary.
        TRAVEL_TIME_LIMIT expresses an independent positive upper travel bound FROM the named origin TO a future meeting place. max_minutes must match one literal native duration, with deterministic hour/minute conversion, within1..10080 minutes. Multiple independent bounds need separate declarations. Never turn a minimum, exclusion, preference or unsupported meaning into a positive maximum: preserve it as UNRESOLVED with max_minutes=null.
        travel_mode is WALK,TRANSIT,CAR or BICYCLE only if explicitly stated in this declaration's native evidence, otherwise null. No assumed transit, walking, coordinates, route, store or actual travel-time feasibility.
        time_scope=ALL_AVAILABLE_TIMES only for a restriction independent of time. If time depends on an unresolved place/movement condition, retain UNRESOLVED_TIME_LINK so the server cannot promote it to confirmed availability. Never flatten time-and-place alternatives.
        All actual place feasibility remains unverified. Missing-place input is not a claim that every location was explicitly accepted. Preserve original/supplement scope, negation, corrections, source ownership and all time constraints.
    """

    private fun locate(
        raw: Any?,
        input: UUID,
        snapshot: ScheduleEvidenceSnapshot,
    ) = fields(raw).let {
        require(it.keys == setOf("source_id", "revision", "quote"))
        ScheduleDslStructuralNormalizer.locate(
            input,
            ProposedLiteralQuoteLocator(string(it["source_id"]), integer(it["revision"]), string(it["quote"])),
            snapshot,
        )
    }

    private fun string(value: Any?): String =
        (value as? String)?.also { require(it.isNotEmpty()) } ?: throw IllegalArgumentException("Expected text")

    private fun integer(value: Any?): Int {
        require(value is Int || value is Long) { "Expected integer" }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }

    private fun fields(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String })
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun list(value: Any?): List<*> = value as? List<*> ?: throw IllegalArgumentException("Expected array")

    private fun enumeration(values: List<String>) = mapOf("type" to "string", "enum" to values)

    private fun nullable(value: Map<String, Any?>) = mapOf("anyOf" to listOf(value, mapOf("type" to "null")))

    private fun obj(properties: Map<String, Any?>) =
        mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )
}
