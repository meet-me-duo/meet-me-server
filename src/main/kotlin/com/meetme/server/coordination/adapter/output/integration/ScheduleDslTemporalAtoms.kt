package com.meetme.server.coordination.adapter.output.integration

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Owned producer declarations, not language interpretation or repair of older responses. */
internal object ScheduleDslTemporalAtoms {
    private val locatorFields = setOf("source_id", "revision", "quote")
    private val clockPattern = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?$")

    fun schema(original: Map<String, Any?>): Map<String, Any?> {
        val properties = map(original["properties"])
        val results = map(properties["results"])
        val result = map(results["items"])
        val fields = map(result["properties"])
        val conditions = map(fields["conditions"])
        val condition = map(conditions["items"])
        val conditionFields = map(condition["properties"])
        val scopes =
            list(map(conditionFields["scope"])["anyOf"]).filter {
                val scopeFields = map(it)["properties"] as? Map<*, *>
                (scopeFields?.get("start") as? Map<*, *>)?.get("format") != "date"
            }
        val rawScope = mapOf("anyOf" to scopes)
        val timestamp = mapOf("type" to "string", "pattern" to "^[+-]?\\d{4,}-\\d{2}-\\d{2}T")
        val modelScope =
            nullable(
                obj(mapOf("dimension" to mapOf("type" to "string", "enum" to listOf("TIME")), "start" to timestamp, "end" to timestamp)),
            )
        val terms = map(map(map(fields["approved_terms"])["items"])["properties"])
        val directions = map(map(map(fields["directional_times"])["items"])["properties"])
        val date = terms.getValue("local_date")
        val dates = terms.getValue("date_evidence")
        val bound = obj(mapOf("clock" to directions.getValue("clock"), "clock_evidence" to directions.getValue("clock_evidence")))
        val variants =
            listOf(
                arm("WHOLE_DAY", mapOf("local_date" to date, "date_evidence" to dates)),
                arm("APPROVED_TERM", terms.filterKeys { it != "condition_id" } + ("model_scope" to modelScope)),
                arm(
                    "BOUNDS",
                    mapOf("local_date" to date, "date_evidence" to dates, "lower" to nullable(bound), "upper" to nullable(bound)),
                ),
                arm("UNKNOWN", emptyMap()),
                arm("RAW_SCOPE", mapOf("scope" to rawScope)),
            )
        val ownedFields =
            conditionFields.filterKeys { it !in setOf("scope", "approved_term") } +
                ("temporal" to mapOf("anyOf" to variants))
        val owned = condition + mapOf("properties" to ownedFields, "required" to ownedFields.keys.toList())
        val next =
            result + (
                "properties" to (
                    fields +
                        mapOf(
                            "conditions" to (conditions + ("items" to owned)),
                            "directional_times" to (map(fields["directional_times"]) + ("maxItems" to 0)),
                        )
                )
            )
        return original + ("properties" to (properties + ("results" to (results + ("items" to next)))))
    }

    /** New syntax declares a bundle. Older effect-only evidence is never expanded by this decoder. */
    fun results(
        values: List<Any?>,
        timeZone: String,
    ): List<Map<String, Any?>> {
        val results = values.map(::map)
        val claims = results.flatMap { list(it["conditions"]).map(::map) }
        if (claims.none { "temporal" in it }) return results
        require(claims.all { "temporal" in it && "scope" !in it && "approved_term" !in it }) { "Mixed temporal syntax" }
        val zone = ZoneId.of(timeZone)
        return results.map { result ->
            require(list(result["approved_terms"]).isEmpty() && list(result["directional_times"]).isEmpty()) { "Mixed temporal channels" }
            val sides = mutableListOf<Map<String, Any?>>()
            val canonical =
                list(result["conditions"]).map { raw ->
                    val claim = map(raw)
                    val meaning = meaningEvidence(claim["evidence"])
                    val atom = map(claim["temporal"])
                    val parts = mutableListOf<Any?>()
                    var scope: Any? = null
                    var term: Any? = null
                    when (atom["kind"]) {
                        "WHOLE_DAY" -> {
                            fields(atom, setOf("kind", "local_date", "date_evidence"))
                            val date = date(atom["local_date"])
                            parts.addAll(proofs(atom["date_evidence"]))
                            scope = mapOf("dimension" to "TIME", "start" to date.toString(), "end" to null)
                        }
                        "APPROVED_TERM" -> {
                            fields(atom, setOf("kind", "term", "local_date", "term_evidence", "date_evidence", "model_scope"))
                            date(atom["local_date"])
                            parts.addAll(proofs(atom["date_evidence"]))
                            parts.add(locator(atom["term_evidence"]))
                            scope = atom["model_scope"]
                            if (scope != null) {
                                val model = map(scope)
                                require(claim["interpretation"] == "INFERRED" && claim["temporal_resolution"] == "NON_PRECISE")
                                require(model["dimension"] == "TIME" && model["start"] is String && model["end"] is String)
                                require((model["start"] as String).contains('T') && (model["end"] as String).contains('T'))
                            }
                            term = atom - setOf("kind", "model_scope")
                        }
                        "BOUNDS" -> {
                            fields(atom, setOf("kind", "local_date", "date_evidence", "lower", "upper"))
                            val date = date(atom["local_date"])
                            val dates = proofs(atom["date_evidence"])
                            parts.addAll(dates)
                            val lower = atom["lower"]?.let { bound(it, date, zone, parts) }
                            val upper = atom["upper"]?.let { bound(it, date, zone, parts) }
                            require(lower != null || upper != null) { "Missing temporal bounds" }
                            if (lower != null && upper != null) {
                                require(lower < upper) { "Bounds need an explicit positive civil interval" }
                                require(
                                    (claim["interpretation"] == "EXPLICIT" && claim["temporal_resolution"] == "PRECISE_INTERVAL") ||
                                        (claim["interpretation"] == "INFERRED" && claim["temporal_resolution"] == "NON_PRECISE"),
                                ) { "Unsupported complete-bound precision" }
                                scope =
                                    mapOf(
                                        "dimension" to "TIME",
                                        "start" to date.atTime(lower).toString(),
                                        "end" to date.atTime(upper).toString(),
                                    )
                            } else {
                                require(claim["temporal_resolution"] == "NON_PRECISE")
                                val side = map(atom[if (lower != null) "lower" else "upper"])
                                sides +=
                                    mapOf(
                                        "condition_id" to claim["id"],
                                        "direction" to if (lower != null) "AFTER" else "BEFORE",
                                        "local_date" to date.toString(),
                                        "clock" to side["clock"],
                                        "date_evidence" to dates,
                                        "clock_evidence" to proofs(side["clock_evidence"]),
                                    )
                            }
                        }
                        "UNKNOWN" -> fields(atom, setOf("kind"))
                        "RAW_SCOPE" -> {
                            fields(atom, setOf("kind", "scope"))
                            scope = atom["scope"]
                            require(
                                scope == null ||
                                    !ScheduleDslStructuralWireGrammar.calendarDate.matches(map(scope)["start"] as? String ?: ""),
                            )
                        }
                        else -> throw IllegalArgumentException("Unknown temporal atom")
                    }
                    val evidence = (meaning + parts).distinct()
                    require(evidence.size <= 32) { "Declared evidence bundle exceeds its budget" }
                    (claim - "temporal") + mapOf("scope" to scope, "approved_term" to term, "evidence" to evidence)
                }
            result + mapOf("conditions" to canonical, "directional_times" to sides)
        }
    }

    private fun meaningEvidence(value: Any?): List<Any?> {
        val items = list(value)
        require(!items.isEmpty()) { "Missing meaning evidence" }
        require(items.size <= 32)
        items.forEach { locator(it) }
        return items
    }

    private fun proofs(value: Any?): List<Any?> =
        list(value).also {
            require(it.size in 1..32)
            it.forEach { item -> locator(item) }
        }

    private fun locator(value: Any?): Map<String, Any?> {
        val item = map(value)
        return fields(item, if ("catalog_id" in item) locatorFields + "catalog_id" else locatorFields)
    }

    private fun date(value: Any?): LocalDate = LocalDate.parse(value as String).also { require(it.toString() == value) }

    private fun bound(
        value: Any,
        date: LocalDate,
        zone: ZoneId,
        parts: MutableList<Any?>,
    ): LocalTime {
        val b = fields(map(value), setOf("clock", "clock_evidence"))
        val text = b["clock"] as String
        require(clockPattern.matches(text))
        val time = LocalTime.parse(text)
        require(zone.rules.getValidOffsets(date.atTime(time)).size == 1) { "Ambiguous or missing civil clock" }
        parts.addAll(proofs(b["clock_evidence"]))
        return time
    }

    private fun arm(
        kind: String,
        properties: Map<String, Any?>,
    ) = obj(
        mapOf("kind" to mapOf("type" to "string", "enum" to listOf(kind))) + properties,
    )

    private fun obj(properties: Map<String, Any?>): Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )

    private fun nullable(value: Any?) = mapOf("anyOf" to listOf(value, mapOf("type" to "null")))

    private fun fields(
        value: Map<String, Any?>,
        expected: Set<String>,
    ) = value.also { require(it.keys == expected) }

    private fun map(value: Any?): Map<String, Any?> =
        requireNotNull(value as? Map<*, *>).entries.associate {
            requireNotNull(it.key as? String) to
                it.value
        }

    private fun list(value: Any?): List<Any?> = requireNotNull(value as? List<*>)
}
