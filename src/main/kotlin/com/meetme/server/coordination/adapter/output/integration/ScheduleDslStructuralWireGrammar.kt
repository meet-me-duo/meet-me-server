package com.meetme.server.coordination.adapter.output.integration

/** Producer syntax only. Meaning, evidence and graph safety remain with the existing typed validators. */
internal object ScheduleDslStructuralWireGrammar {
    const val REVISION = "c5-temporal-atom-1"
    val calendarDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private const val EXPRESSION = "c5_inline_expression"
    private val roots = listOf("availability_root", "exclusion_root", "preference_root")
    private val programFields = roots.toSet() + setOf("nodes", "history_condition_ids")
    private val expressionFields =
        setOf("operator", "claim_id", "children", "choice_group", "branch_labels", "interpretation", "assertion", "evidence")
    private val termFields = setOf("term", "local_date", "term_evidence", "date_evidence")

    fun schema(original: Map<String, Any?>): Map<String, Any?> {
        val properties = map(original["properties"])
        val results = map(properties["results"])
        val result = map(results["items"])
        val fields = map(result["properties"])
        val conditions = map(fields["conditions"])
        val condition = map(conditions["items"])
        val conditionFields = map(condition["properties"])
        val originalScope = map(list(map(conditionFields["scope"])["anyOf"]).first())
        val scopeFields = map(originalScope["properties"])
        val timestamp = mapOf("type" to "string", "pattern" to "^[+-]?\\d{4,}-\\d{2}-\\d{2}T")
        val interval = obj(scopeFields + mapOf("start" to nullable(timestamp), "end" to nullable(timestamp)))
        val day =
            obj(
                linkedMapOf(
                    "dimension" to mapOf("type" to "string", "enum" to listOf("TIME")),
                    "start" to mapOf("type" to "string", "format" to "date", "pattern" to calendarDate.pattern),
                    "end" to mapOf("type" to "null"),
                ),
            )
        val scope = mapOf("anyOf" to listOf(day, interval, mapOf("type" to "null")))
        val program = map(fields["program"])
        val programProperties = map(program["properties"])
        val nodes = map(programProperties["nodes"])
        val nodeProperties = map(map(nodes["items"])["properties"])
        val ref = mapOf("\$ref" to "#/\$defs/$EXPRESSION")
        val expression =
            obj(
                nodeProperties.filterKeys { it != "id" } +
                    ("children" to mapOf("type" to "array", "items" to ref, "minItems" to 0, "maxItems" to 32)),
            )
        val newProgram =
            obj(
                programProperties + roots.associateWith { nullable(ref) } + ("nodes" to (nodes + ("maxItems" to 0))),
            ) + ("description" to program["description"])
        val terms = map(fields["approved_terms"])
        val termProperties = map(map(terms["items"])["properties"])
        val ownedTerm =
            obj(termProperties.filterKeys { it != "condition_id" }) +
                ("description" to "One approved term on one local date, owned by this condition; its native evidence must cover both.")
        val newConditionFields = conditionFields + mapOf("scope" to scope, "approved_term" to nullable(ownedTerm))
        val newCondition = condition + mapOf("properties" to newConditionFields, "required" to newConditionFields.keys.toList())
        val newResult =
            result +
                (
                    "properties" to
                        (
                            fields +
                                mapOf(
                                    "conditions" to (conditions + ("items" to newCondition)),
                                    "program" to newProgram,
                                    "approved_terms" to (terms + ("maxItems" to 0)),
                                )
                        )
                )
        return original +
            mapOf(
                "properties" to (properties + ("results" to (results + ("items" to newResult)))),
                "\$defs" to mapOf(EXPRESSION to expression),
            )
    }

    /** A condition owns one declaration. This never splits dates, joins claims or repairs a legacy response. */
    fun results(values: List<Any?>): List<Map<String, Any?>> {
        val results = values.map(::map)
        val claims = results.flatMap { list(it["conditions"]).map(::map) }
        if (claims.none { "approved_term" in it }) return results
        require(claims.all { "approved_term" in it }) { "Mixed condition term declaration styles" }
        return results.map { result ->
            require(list(result["approved_terms"]).isEmpty()) { "Mixed owned and flat term declarations" }
            val refs = mutableListOf<Map<String, Any?>>()
            val conditions =
                list(result["conditions"]).map { raw ->
                    val claim = map(raw)
                    claim["approved_term"]?.let { rawTerm ->
                        val term = map(rawTerm)
                        require(term.keys == termFields) { "Invalid owned term declaration fields" }
                        refs += term + ("condition_id" to claim["id"])
                    }
                    claim - "approved_term"
                }
            result + mapOf("conditions" to conditions, "approved_terms" to refs)
        }
    }

    /** Only lossless syntax compilation. Unreferenced claims and wrong roles are never repaired here. */
    fun program(value: Map<String, Any?>): Map<String, Any?> {
        require(value.keys == programFields) { "Invalid program fields" }
        val rootValues = roots.map { value[it] }
        if (rootValues.none { it is Map<*, *> }) return value
        require(list(value["nodes"]).isEmpty() && rootValues.all { it == null || it is Map<*, *> }) {
            "Mixed inline and flat programs"
        }
        val nodes = mutableListOf<Map<String, Any?>>()
        val memo = mutableMapOf<Map<String, Any?>, String>()
        var visits = 0

        fun compile(
            raw: Any?,
            depth: Int,
        ): String {
            require(depth <= 8 && ++visits <= 128) { "Inline expression limit" }
            val expression = map(raw)
            require(expression.keys == expressionFields) { "Invalid inline expression fields" }
            val children = list(expression["children"])
            require(children.size <= 32) { "Inline child limit" }
            val node = expression + ("children" to children.map { compile(it, depth + 1) })
            return memo.getOrPut(node) {
                val id = "server_${nodes.size}"
                nodes += node + ("id" to id)
                id
            }
        }
        val compiled = roots.associateWith { key -> value[key]?.let { compile(it, 1) } }
        return value + compiled + ("nodes" to nodes)
    }

    private fun obj(properties: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf("type" to "object", "properties" to properties, "required" to properties.keys.toList(), "additionalProperties" to false)

    private fun nullable(value: Any?): Map<String, Any?> = mapOf("anyOf" to listOf(value, mapOf("type" to "null")))

    private fun map(value: Any?): Map<String, Any?> =
        requireNotNull(value as? Map<*, *>).entries.associate {
            requireNotNull(it.key as? String) to
                it.value
        }

    private fun list(value: Any?): List<Any?> = requireNotNull(value as? List<*>)
}
