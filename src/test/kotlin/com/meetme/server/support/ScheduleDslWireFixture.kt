package com.meetme.server.support

import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslNode
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import tools.jackson.databind.json.JsonMapper

/** Wire authored independently from the production codec. */
object ScheduleDslWireFixture {
    val f = ScheduleDslSyntheticFixture
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    val claims get() = listOf(f.claim("a", 18, 22), f.claim("b", 19, 21))
    val program get() =
        f.program(
            listOf(f.ref("ar", "a"), f.ref("br", "b"), f.node("root", ScheduleDslOperator.ALL_OF, "ar", "br")),
            "root",
        )

    fun document(): MutableMap<String, Any?> =
        linkedMapOf(
            "semantic_version" to SCHEDULE_DSL_SEMANTIC_VERSION,
            "ast_version" to SCHEDULE_AST_VERSION,
            "results" to
                mutableListOf(
                    result(f.ir.first.toString(), claims, program),
                    result(
                        f.ir.second.toString(),
                        listOf(f.ir.precise(firstSource = false)),
                        f.program(listOf(f.ref("peer", "clear").copy(evidence = listOf(f.ir.evidence(false)))), "peer"),
                    ),
                ),
        )

    fun encode(value: Any) = mapper.writeValueAsString(value)

    fun json() = encode(document())

    private fun result(
        input: String,
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ) = linkedMapOf<String, Any?>(
        "input_ref" to input,
        "conditions" to claims.map(f.ir::wire),
        "program" to this.program(program),
    )

    fun program(value: ScheduleDslProgram<ProposedIrEvidence>): MutableMap<String, Any?> =
        linkedMapOf(
            "nodes" to value.nodes.map(::node).toMutableList(),
            "availability_root" to value.availabilityRoot,
            "exclusion_root" to value.exclusionRoot,
            "preference_root" to value.preferenceRoot,
            "history_condition_ids" to value.historyConditionIds,
        )

    fun node(value: ScheduleDslNode<ProposedIrEvidence>): MutableMap<String, Any?> =
        linkedMapOf(
            "id" to value.id,
            "operator" to value.operator.name,
            "claim_id" to value.claimId,
            "children" to value.children,
            "choice_group" to value.choiceGroup,
            "branch_labels" to value.branchLabels,
            "interpretation" to value.interpretation.name,
            "assertion" to value.assertion.name,
            "evidence" to
                value.evidence.map {
                    linkedMapOf(
                        "source_id" to it.sourceId,
                        "revision" to it.revision,
                        "start_code_point" to it.startCodePoint,
                        "end_code_point" to it.endCodePoint,
                        "quote" to it.quote,
                    )
                },
        )

    @Suppress("UNCHECKED_CAST")
    fun firstResult(root: MutableMap<String, Any?>) = (root.getValue("results") as MutableList<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    fun firstProgram(root: MutableMap<String, Any?>) = firstResult(root).getValue("program") as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun nodes(root: MutableMap<String, Any?>) = firstProgram(root).getValue("nodes") as MutableList<MutableMap<String, Any?>>
}
