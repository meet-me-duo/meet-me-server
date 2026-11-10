package com.meetme.server.coordination.adapter.output.integration

/** Projects the generated direct-local-def schema subset; the full service schema remains authoritative. */
internal object GeminiResponseSchemaProjection {
    fun project(schema: Map<String, Any?>): Map<String, Any?> {
        val definitions = objectMap(schema["\$defs"])
        if (definitions.isEmpty()) return schema
        val structural = graph(definitions, false)
        val structuralCycles = cyclic(structural)
        if (structuralCycles.isEmpty()) return schema
        val withoutDeadItems = definitions.mapValues { (_, value) -> removeDeadItems(value, structural, structuralCycles) }
        val required = graph(withoutDeadItems, true)
        val requiredCycles = cyclic(required)
        val projected =
            withoutDeadItems.mapValues { (name, value) ->
                if (name in requiredCycles) relax(value, required, setOf(name)) else value
            }
        require(cyclic(graph(projected, true)).isEmpty()) {
            "Gemini recursive schema requires an optional, nullable or empty-array guard"
        }
        return if (projected == definitions) schema else schema + ("\$defs" to projected)
    }

    private fun graph(
        definitions: Map<String, Any?>,
        requiredOnly: Boolean,
    ): Map<String, Set<String>> =
        definitions.mapValues { (_, value) -> references(value, requiredOnly).filter { it in definitions }.toSet() }

    private fun cyclic(graph: Map<String, Set<String>>): Set<String> =
        graph.keys.filterTo(linkedSetOf()) { start ->
            val visited = mutableSetOf<String>()
            val pending = ArrayDeque(graph[start].orEmpty())
            var found = false
            while (pending.isNotEmpty() && !found) {
                val next = pending.removeLast()
                if (next == start) {
                    found = true
                } else if (visited.add(next)) {
                    pending.addAll(graph[next].orEmpty())
                }
            }
            found
        }

    private fun reaches(
        references: Set<String>,
        graph: Map<String, Set<String>>,
        cycles: Set<String>,
    ): Boolean {
        val pending = ArrayDeque(references)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (next in cycles) return true
            if (visited.add(next)) pending.addAll(graph[next].orEmpty())
        }
        return false
    }

    private fun references(
        value: Any?,
        requiredOnly: Boolean,
    ): Set<String> {
        if (value is List<*>) return value.flatMap { references(it, requiredOnly) }.toSet()
        val node = objectMap(value)
        if (requiredOnly && (nullable(node) || node["type"] == "array" && minimum(node) == 0L)) return emptySet()
        val found = mutableSetOf<String>()
        localName(node["\$ref"] as? String)?.let(found::add)
        val required = (node["required"] as? List<*>).orEmpty().toSet()
        node.forEach { (key, child) ->
            when (key) {
                "\$ref", "\$defs" -> Unit
                "properties" ->
                    objectMap(child).forEach { (name, property) ->
                        if (!requiredOnly || name in required) found.addAll(references(property, requiredOnly))
                    }
                else -> found.addAll(references(child, requiredOnly))
            }
        }
        return found
    }

    private fun removeDeadItems(
        value: Any?,
        graph: Map<String, Set<String>>,
        cycles: Set<String>,
    ): Any? {
        if (value is List<*>) return value.map { removeDeadItems(it, graph, cycles) }
        if (value !is Map<*, *>) return value
        val node = objectMap(value)
        if (node["type"] == "array" &&
            (node["maxItems"] as? Number)?.toLong() == 0L &&
            reaches(references(node["items"], false), graph, cycles)
        ) {
            // No instance can contain an item; replacing its unused ref preserves exactly the empty array.
            return node + ("items" to mapOf("type" to "null"))
        }
        return node.mapValues { (_, child) -> removeDeadItems(child, graph, cycles) }
    }

    private fun relax(
        value: Any?,
        graph: Map<String, Set<String>>,
        cycles: Set<String>,
    ): Any? {
        if (value is List<*>) return value.map { relax(it, graph, cycles) }
        if (value !is Map<*, *>) return value
        val node = objectMap(value)
        if (nullable(node)) return value
        if (node["type"] == "array") {
            if (minimum(node) == 0L) return value
            if (reaches(references(node["items"], true), graph, cycles)) return node + ("minItems" to 0)
        }
        val required = (node["required"] as? List<*>).orEmpty().toSet()
        return node.mapValues { (key, child) ->
            if (key == "properties") {
                objectMap(child).mapValues { (name, property) ->
                    if (name in required) relax(property, graph, cycles) else property
                }
            } else {
                relax(child, graph, cycles)
            }
        }
    }

    private fun nullable(node: Map<String, Any?>): Boolean =
        node["type"] == "null" ||
            (node["type"] as? List<*>)?.contains("null") == true ||
            (node["anyOf"] as? List<*>)?.any { objectMap(it)["type"] == "null" } == true

    private fun minimum(node: Map<String, Any?>): Long = (node["minItems"] as? Number)?.toLong() ?: 0L

    private fun localName(reference: String?): String? {
        val prefix = "#/\$defs/"
        if (reference == null || !reference.startsWith(prefix)) return null
        val name = reference.removePrefix(prefix)
        if ('/' in name) return null
        return name.replace("~1", "/").replace("~0", "~")
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectMap(value: Any?): Map<String, Any?> =
        if (value is Map<*, *>) {
            require(value.keys.all { it is String }) { "Generated schema keys must be strings" }
            value as Map<String, Any?>
        } else {
            emptyMap()
        }
}
