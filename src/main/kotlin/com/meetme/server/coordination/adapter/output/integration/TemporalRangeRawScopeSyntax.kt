package com.meetme.server.coordination.adapter.output.integration

/** Generation six only: reject malformed timestamps before legacy per-condition quarantine loses a graph target. */
internal object TemporalRangeRawScopeSyntax {
    private const val TIMESTAMP_PATTERN =
        """^(?:[0-9]{4}|-[0-9]{4,9}|\+[0-9]{5,9})-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?(?:[Zz]|[+-](?:0[0-9]|1[0-8])(?::[0-5][0-9](?::[0-5][0-9])?)?)?$"""
    private val timestamp = Regex(TIMESTAMP_PATTERN)

    fun validate(
        document: Map<String, Any?>,
        timeZoneId: String,
    ) {
        for (result in document["results"] as? List<*> ?: emptyList<Any?>()) {
            for (condition in map(result)["conditions"] as? List<*> ?: emptyList<Any?>()) {
                val atom = map(map(condition)["temporal"])
                if (atom["kind"] != "RAW_SCOPE") continue
                val scope = map(atom["scope"])
                if (scope["dimension"] != "TIME") continue
                for (field in listOf("start", "end")) {
                    val value = scope[field] ?: continue
                    require(
                        value is String &&
                            timestamp.matches(value) &&
                            runCatching { ScheduleDslRoomLocalTime.parse(value, timeZoneId) }.isSuccess,
                    ) { "TEMPORAL_RANGE_RAW_SCOPE_TIMESTAMP" }
                }
            }
        }
    }

    /** Native generation narrowing; neutral schemas and all non-TIME/non-RAW_SCOPE arms remain unchanged. */
    fun generationSchema(schema: Map<String, Any?>): Map<String, Any?> = map(narrowAtoms(schema))

    private fun narrowAtoms(value: Any?): Any? =
        when (value) {
            is List<*> -> value.map(::narrowAtoms)
            is Map<*, *> -> {
                val node = map(value)
                val properties = map(node["properties"])
                if (map(properties["kind"])["enum"] == listOf("RAW_SCOPE")) {
                    node + ("properties" to (properties + ("scope" to narrowScopes(properties["scope"]))))
                } else {
                    node.mapValues { (_, child) -> narrowAtoms(child) }
                }
            }
            else -> value
        }

    private fun narrowScopes(value: Any?): Any? =
        when (value) {
            is List<*> -> value.map(::narrowScopes)
            is Map<*, *> -> {
                val node = map(value)
                val properties = map(node["properties"])
                val dimension = map(properties["dimension"])
                val dimensions = dimension["enum"] as? List<*> ?: emptyList<Any?>()
                if ("TIME" in dimensions) {
                    val time =
                        node + (
                            "properties" to (
                                properties + ("dimension" to (dimension + ("enum" to listOf("TIME")))) +
                                    listOf("start", "end").associateWith { narrowStrings(properties.getValue(it)) }
                            )
                        )
                    val remaining = dimensions.filter { it != "TIME" }
                    if (remaining.isEmpty()) {
                        time
                    } else {
                        val other = node + ("properties" to (properties + ("dimension" to (dimension + ("enum" to remaining)))))
                        mapOf("anyOf" to listOf(time, other))
                    }
                } else {
                    node.mapValues { (_, child) -> narrowScopes(child) }
                }
            }
            else -> value
        }

    private fun narrowStrings(value: Any?): Any? =
        when (value) {
            is List<*> -> value.map(::narrowStrings)
            is Map<*, *> -> {
                val node = map(value)
                if (node["type"] == "string") {
                    node + ("pattern" to TIMESTAMP_PATTERN)
                } else {
                    node.mapValues { (_, child) -> narrowStrings(child) }
                }
            }
            else -> value
        }

    private fun map(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (it.key as String) to it.value } ?: emptyMap()
}
