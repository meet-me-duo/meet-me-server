package com.meetme.server.support

import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.submission.domain.dsl.ParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.ProposedParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslNode
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslProposal
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition

/** Synthetic manually assigned claims/operators; no natural-language or model accuracy oracle. */
object ScheduleDslSyntheticFixture {
    val ir = ScheduleIrV2SyntheticFixture
    val policy = IrMatchingPolicy(ir.POLICY, setOf(IrInterpretation.EXPLICIT), emptyList(), false)

    fun claim(
        id: String,
        start: Int,
        end: Int,
        effect: IrEffect = IrEffect.AVAILABLE,
    ) = ir
        .precise(
            id,
            ir.scope("2027-01-08T${start.toString().padStart(2, '0')}:00:00", "2027-01-08T${end.toString().padStart(2, '0')}:00:00"),
        ).copy(effect = effect)

    fun ref(
        id: String,
        claimId: String,
    ) = node(id, ScheduleDslOperator.CLAIM_REF).copy(claimId = claimId)

    fun node(
        id: String,
        op: ScheduleDslOperator,
        vararg children: String,
    ) = ScheduleDslNode(
        id,
        op,
        null,
        children.toList(),
        null,
        emptyList(),
        IrInterpretation.EXPLICIT,
        IrAssertion.ASSERTED,
        listOf(ir.evidence()),
    )

    fun program(
        nodes: List<ScheduleDslNode<ProposedIrEvidence>>,
        allow: String?,
        block: String? = null,
        prefer: String? = null,
        history: List<String> = emptyList(),
    ) = ScheduleDslProgram(nodes, allow, block, prefer, history)

    fun proposal(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>?,
    ) = ScheduleDslProposal(
        SCHEDULE_DSL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        ir.proposal(claims),
        listOf(
            ProposedParticipantScheduleProgram(ir.first, program),
            ProposedParticipantScheduleProgram(
                ir.second,
                this.program(listOf(ref("peer", "clear").copy(evidence = listOf(ir.evidence(false)))), "peer"),
            ),
        ),
    )

    fun envelope(
        claims: List<ProposedIrV2Condition>,
        program: ScheduleDslProgram<ProposedIrEvidence>,
    ) = ScheduleDslEnvelope(
        SCHEDULE_DSL_SEMANTIC_VERSION,
        SCHEDULE_AST_VERSION,
        ir.envelope(claims),
        proposal(claims, program).programs.map { participant ->
            ParticipantScheduleProgram(
                participant.submissionVersionId,
                participant.program?.let { p ->
                    ScheduleDslProgram(
                        p.nodes.map { n ->
                            ScheduleDslNode(
                                n.id,
                                n.operator,
                                n.claimId,
                                n.children,
                                n.choiceGroup,
                                n.branchLabels,
                                n.interpretation,
                                n.assertion,
                                n.evidence.map {
                                    IrEvidenceSpan(
                                        it.sourceId,
                                        it.revision,
                                        it.startCodePoint,
                                        it.endCodePoint,
                                        ir.hash(it.quote),
                                    )
                                },
                            )
                        },
                        p.availabilityRoot,
                        p.exclusionRoot,
                        p.preferenceRoot,
                        p.historyConditionIds,
                    )
                },
                emptySet(),
            )
        },
    )
}
