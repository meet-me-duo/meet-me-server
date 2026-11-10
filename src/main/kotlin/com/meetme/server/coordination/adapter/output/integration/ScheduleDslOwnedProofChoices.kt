package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy

/** Condition-local witness expansion. Exact aliases carry no new source, meaning or choice authority. */
internal object ScheduleDslOwnedProofChoices {
    const val REVISION = "c5-owned-proof-choice-2"
    private const val EXPRESSION = "c5_inline_expression"
    private const val TOKEN = "[A-Za-z0-9._:-]{1,64}"
    private const val PROOF = "[pt]:[A-Za-z0-9._:-]{1,62}"
    private const val TERM_PROOF = "t:[A-Za-z0-9._:-]{1,62}"
    private val roots = listOf("availability_root", "exclusion_root", "preference_root")
    private val conditionKeys =
        setOf("id", "effect", "interpretation", "temporal_resolution", "proofs", "evidence", "assertion", "supersedes", "temporal")
    private val arities = mapOf("CLAIM_REF" to 0..0, "ALL_OF" to 2..32, "ANY_OF" to 2..32, "EXCEPT" to 2..2, "PREFER" to 2..2)
    private val lexemes = ApprovedKoreanTimeTerm.entries.flatMap(ApprovedKoreanTimeTermPolicy::lexemes).toSet()

    fun schema(original: Map<String, Any?>): Map<String, Any?> {
        val properties = map(original["properties"])
        val results = map(properties["results"])
        val result = map(results["items"])
        val fields = map(result["properties"])
        val conditions = map(fields["conditions"])
        val condition = map(conditions["items"])
        val conditionFields = map(condition["properties"])
        val evidence = map(conditionFields["evidence"])
        val locator = map(map(evidence["items"])["properties"])
        val atoms = list(map(conditionFields["temporal"])["anyOf"])
        val term = atoms.map(::map).single { "term_evidence" in map(it["properties"]) }
        val termProperties = map(term["properties"])
        val termEvidence = map(termProperties["term_evidence"])
        val termQuote = map(termEvidence["properties"]).getValue("quote")
        val proof = mapOf("type" to "string", "pattern" to "^$PROOF$")
        val termRef = mapOf("type" to "string", "pattern" to "^$TERM_PROOF$")
        val refs = array(proof, 1, 32)
        val bank =
            array(
                mapOf(
                    "anyOf" to
                        listOf(
                            obj(mapOf("id" to mapOf("type" to "string", "pattern" to "^p:[A-Za-z0-9._:-]{1,62}$")) + locator),
                            obj(
                                mapOf("id" to mapOf("type" to "string", "pattern" to "^$TERM_PROOF$")) +
                                    locator + ("quote" to termQuote),
                            ),
                        ),
                ),
                1,
                32,
            )

        fun bound(value: Any?): Map<String, Any?> =
            map(value).let { field ->
                field + (
                    "anyOf" to
                        list(field["anyOf"]).map { raw ->
                            val arm = map(raw)
                            val side = arm["properties"] as? Map<*, *>
                            if (side == null) arm else arm + ("properties" to (map(side) + ("clock_evidence" to refs)))
                        }
                )
            }
        val temporal =
            atoms.map { raw ->
                val arm = map(raw)
                val next =
                    map(arm["properties"]).mapValues { (name, value) ->
                        when (name) {
                            "date_evidence" -> refs
                            "term_evidence" -> termRef
                            "lower", "upper" -> bound(value)
                            else -> value
                        }
                    }
                arm + ("properties" to next)
            }
        val owned =
            conditionFields.filterKeys { it !in setOf("alternative_group", "alternative") } +
                mapOf("proofs" to bank, "evidence" to refs, "temporal" to mapOf("anyOf" to temporal))
        val wholeDay = temporal.single { map(map(it["properties"])["kind"])["enum"] == listOf("WHOLE_DAY") }
        val wholeDayFields =
            owned +
                mapOf(
                    "effect" to (map(owned["effect"]) + ("enum" to listOf("AVAILABLE", "UNAVAILABLE", "PREFERRED"))),
                    "interpretation" to (map(owned["interpretation"]) + ("enum" to listOf("EXPLICIT", "INFERRED"))),
                    "temporal_resolution" to (map(owned["temporal_resolution"]) + ("enum" to listOf("NON_PRECISE"))),
                    "temporal" to wholeDay,
                )
        val otherFields = owned + ("temporal" to mapOf("anyOf" to temporal.filterNot { it == wholeDay }))

        fun conditionBranch(properties: Map<String, Any?>): Map<String, Any?> =
            condition.filterKeys { it != "description" } + mapOf("properties" to properties, "required" to properties.keys.toList())

        val nextCondition =
            mapOf("anyOf" to listOf(conditionBranch(wholeDayFields), conditionBranch(otherFields))) +
                condition.filterKeys { it == "description" }
        val definitions = map(original["\$defs"])
        val expression = map(map(definitions[EXPRESSION])["properties"])
        val ref = mapOf("\$ref" to "#/\$defs/$EXPRESSION")
        val token = mapOf("type" to "string", "pattern" to "^$TOKEN$")
        val common = expression.filterKeys { it in setOf("interpretation", "assertion", "evidence") }
        val normal =
            arities.map { (operator, arity) ->
                obj(
                    common +
                        mapOf(
                            "operator" to mapOf("type" to "string", "enum" to listOf(operator)),
                            "claim_id" to if (operator == "CLAIM_REF") token else mapOf("type" to "null"),
                            "children" to array(ref, arity.first, arity.last),
                        ),
                )
            }
        val choice =
            obj(
                common +
                    mapOf(
                        "operator" to mapOf("type" to "string", "enum" to listOf("CHOICE")),
                        "choice_group" to token,
                        "branches" to
                            array(
                                obj(mapOf("label" to token, "condition_ids" to array(token, 1, 32), "expression" to ref)),
                                2,
                                8,
                            ),
                    ),
            )
        val next = result + ("properties" to (fields + ("conditions" to (conditions + ("items" to nextCondition)))))
        return original +
            mapOf(
                "properties" to (properties + ("results" to (results + ("items" to next)))),
                "\$defs" to (definitions + (EXPRESSION to mapOf("anyOf" to normal + listOf(choice)))),
            )
    }

    fun results(
        values: List<Any?>,
        allowServerReferences: Boolean = false,
    ): List<Map<String, Any?>> {
        require(values.size in 2..50)
        val results = values.map(::map)
        val claims = results.flatMap { list(it["conditions"]).map(::map) }
        val referenced = allowServerReferences && claims.none { "proofs" in it }
        if (claims.none { "proofs" in it } && !referenced) return results
        require(claims.all { it.keys == if (referenced) conditionKeys - "proofs" else conditionKeys }) { "Mixed proof ownership syntax" }
        return results.map { result ->
            val expanded = list(result["conditions"]).map { if (referenced) map(it) else expand(map(it)) }
            val ids = expanded.map { token(it["id"]) }
            require(ids.size <= 32 && ids.distinct().size == ids.size)
            val bindings = mutableMapOf<String, Pair<String, String>>()
            val program = program(map(result["program"]), ids.toSet(), bindings)
            result +
                mapOf(
                    "program" to program,
                    "conditions" to
                        expanded.map { claim ->
                            val binding = bindings[text(claim["id"])]
                            claim + mapOf("alternative_group" to binding?.first, "alternative" to binding?.second)
                        },
                )
        }
    }

    private fun expand(claim: Map<String, Any?>): Map<String, Any?> {
        val entries = list(claim["proofs"])
        require(entries.size in 1..32)
        val proofs = linkedMapOf<String, Map<String, Any?>>()
        entries.forEach { raw ->
            val item = fields(map(raw), setOf("id", "source_id", "revision", "quote"))
            val id = text(item["id"])
            require(id.matches(Regex(PROOF)) && id !in proofs) { "Invalid or duplicate proof ID" }
            if (id.startsWith("t:")) require(item["quote"] in lexemes) { "Term proof needs an approved literal" }
            proofs[id] = item - "id"
        }
        val used = mutableSetOf<String>()

        fun resolve(
            value: Any?,
            term: Boolean = false,
        ): Map<String, Any?> {
            val id = text(value)
            require(id.matches(Regex(if (term) TERM_PROOF else PROOF))) { "Invalid proof namespace" }
            val proof = requireNotNull(proofs[id]) { "Unknown condition-local proof" }
            used += id
            return proof
        }

        fun refs(value: Any?): List<Map<String, Any?>> = list(value).also { require(it.size in 1..32) }.map { resolve(it) }
        val meaning = refs(claim["evidence"])
        val atom =
            map(claim["temporal"]).mapValues { (name, value) ->
                when (name) {
                    "date_evidence" -> refs(value)
                    "term_evidence" -> resolve(value, true)
                    "lower", "upper" ->
                        value?.let {
                            map(it).mapValues { (field, side) ->
                                if (field ==
                                    "clock_evidence"
                                ) {
                                    refs(side)
                                } else {
                                    side
                                }
                            }
                        }
                    else -> value
                }
            }
        requireUsedProofs(proofs, used)
        return (claim - "proofs") + mapOf("evidence" to meaning, "temporal" to atom)
    }

    private fun requireUsedProofs(
        declared: Map<String, Map<String, Any?>>,
        used: Set<String>,
    ) {
        val usedLocators = used.mapTo(mutableSetOf()) { declared.getValue(it) }
        require(declared.values.all { it in usedLocators }) { "Unused condition proof" }
    }

    private fun program(
        value: Map<String, Any?>,
        ids: Set<String>,
        bindings: MutableMap<String, Pair<String, String>>,
    ): Map<String, Any?> {
        fields(value, roots.toSet() + setOf("nodes", "history_condition_ids"))
        require(list(value["nodes"]).isEmpty()) { "Mixed branch ownership program" }
        val contexts = mutableMapOf<String, MutableList<Map<String, String>>>()
        var visits = 0

        fun compile(
            raw: Any?,
            depth: Int,
            choices: Map<String, String>,
        ): Pair<Map<String, Any?>, Set<String>> {
            require(depth <= 8 && ++visits <= 128) { "Owned expression limit" }
            val expression = map(raw)
            val operator = text(expression["operator"])
            if (operator != "CHOICE") {
                fields(expression, setOf("operator", "claim_id", "children", "interpretation", "assertion", "evidence"))
                val children = list(expression["children"])
                require(children.size in requireNotNull(arities[operator])) { "Invalid operator arity" }
                val expanded = children.map { compile(it, depth + 1, choices) }
                val references = expanded.flatMap { it.second }.toMutableSet()
                if (operator == "CLAIM_REF") {
                    val id = token(expression["claim_id"])
                    contexts.getOrPut(id) { mutableListOf() }.add(choices)
                    references += id
                } else {
                    require(expression["claim_id"] == null)
                }
                return (
                    expression +
                        mapOf("children" to expanded.map { it.first }, "choice_group" to null, "branch_labels" to emptyList<String>())
                ) to
                    references
            }
            fields(expression, setOf("operator", "choice_group", "branches", "interpretation", "assertion", "evidence"))
            val group = token(expression["choice_group"])
            val branches = list(expression["branches"])
            require(branches.size in 2..8)
            val labels = mutableSetOf<String>()
            val compiled =
                branches.map { rawBranch ->
                    val branch = fields(map(rawBranch), setOf("label", "condition_ids", "expression"))
                    val label = token(branch["label"])
                    require(labels.add(label) && (choices[group] == null || choices[group] == label)) { "Conflicting choice branch" }
                    val nested = compile(branch["expression"], depth + 1, choices + (group to label))
                    val members = list(branch["condition_ids"]).map(::token)
                    require(members.size in 1..32 && members.distinct().size == members.size)
                    require(members.all { it in ids && it in nested.second }) { "Unowned choice binding" }
                    members.forEach { id ->
                        val pair = group to label
                        val existing = bindings.putIfAbsent(id, pair)
                        require(existing == null || existing == pair) { "Conflicting condition choice ownership" }
                    }
                    label to nested
                }
            return (
                (expression - "branches") +
                    mapOf(
                        "claim_id" to null,
                        "children" to compiled.map { it.second.first },
                        "branch_labels" to compiled.map { it.first },
                    )
            ) to compiled.flatMap { it.second.second }.toSet()
        }
        val expanded = roots.associateWith { root -> value[root]?.let { compile(it, 1, emptyMap()).first } }
        bindings.forEach { (id, binding) ->
            require(contexts[id]?.all { it[binding.first] == binding.second } == true) { "Choice-bound condition used outside its branch" }
        }
        return value + expanded
    }

    private fun token(value: Any?): String = text(value).also { require(it.matches(Regex(TOKEN))) }

    private fun text(value: Any?): String = requireNotNull(value as? String)

    private fun map(value: Any?): Map<String, Any?> = requireNotNull(value as? Map<*, *>).entries.associate { text(it.key) to it.value }

    private fun list(value: Any?): List<Any?> = requireNotNull(value as? List<*>)

    private fun fields(
        value: Map<String, Any?>,
        expected: Set<String>,
    ) = value.also { require(it.keys == expected) }

    private fun obj(properties: Map<String, Any?>): Map<String, Any?> =
        mapOf("type" to "object", "properties" to properties, "required" to properties.keys.toList(), "additionalProperties" to false)

    private fun array(
        items: Any?,
        min: Int,
        max: Int,
    ) = mapOf("type" to "array", "items" to items, "minItems" to min, "maxItems" to max)
}
