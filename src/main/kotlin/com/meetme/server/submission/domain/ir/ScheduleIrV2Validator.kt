package com.meetme.server.submission.domain.ir

class ScheduleIrV2Validator {
    fun validate(
        proposal: ScheduleIrV2Proposal,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleIrV2Envelope {
        require(proposal.semanticVersion == SCHEDULE_IR_V2_VERSION) { "Unsupported schedule IR2" }
        proposal.results.forEach { checkBudget(it.conditions.size, it.rejectedConditions) }
        // This private projection checks identity, evidence and relations only. Its aggregate is never returned.
        val proof =
            ScheduleIrValidator().validate(
                ScheduleIrProposal(
                    SCHEDULE_IR_VERSION,
                    proposal.results.map { result ->
                        ProposedIrResult(
                            result.submissionVersionId,
                            IrAvailabilityState.UNKNOWN,
                            result.conditions.map(::proofCondition),
                            result.rejectedConditions,
                        )
                    },
                ),
                snapshot,
            )
        return ScheduleIrV2Envelope(
            SCHEDULE_IR_V2_VERSION,
            proof.context,
            proposal.results.zip(proof.results).map { (original, verified) ->
                val byId = original.conditions.associateBy { it.id }
                IrV2Result(
                    verified.submissionVersionId,
                    verified.conditions.map { condition ->
                        val source = byId.getValue(condition.id)
                        IrV2Condition(
                            source.id,
                            source.effect,
                            source.interpretation,
                            source.temporalResolution,
                            source.scope,
                            condition.evidence.toList(),
                            source.assertion,
                            source.supersedes.toList(),
                            source.alternativeGroup,
                            source.alternative,
                        )
                    },
                    verified.quarantined.toList(),
                )
            },
        )
    }

    fun validateRestored(
        envelope: ScheduleIrV2Envelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleIrV2Envelope {
        require(envelope.semanticVersion == SCHEDULE_IR_V2_VERSION && envelope.context == snapshot.context) {
            "IR2 replay context mismatch"
        }
        envelope.results.forEach { checkBudget(it.conditions.size, it.quarantined) }
        val proof =
            ScheduleIrEnvelope(
                SCHEDULE_IR_VERSION,
                envelope.context,
                envelope.results.map { result ->
                    IrResult(
                        result.submissionVersionId,
                        IrAvailabilityState.UNKNOWN,
                        result.conditions.map(::proofCondition),
                        result.quarantined,
                    )
                },
            )
        ScheduleIrValidator().validateRestored(proof, snapshot)
        return envelope
    }

    private fun checkBudget(
        accepted: Int,
        rejected: List<IrQuarantine>,
    ) {
        require(accepted + rejected.size <= 32) { "Too many IR2 conditions" }
        require(rejected.none { it.problem == IrProblem.AVAILABILITY }) { "IR2 has no aggregate availability" }
    }

    private fun proofCondition(condition: ProposedIrV2Condition): ProposedIrCondition {
        val (effect, scope) = proofShape(condition.effect, condition.temporalResolution, condition.scope)
        return ProposedIrCondition(
            condition.id,
            effect,
            condition.interpretation,
            scope,
            condition.evidence,
            condition.assertion,
            condition.supersedes.toList(),
            condition.alternativeGroup,
            condition.alternative,
        )
    }

    private fun proofCondition(condition: IrV2Condition): IrCondition {
        val (effect, scope) = proofShape(condition.effect, condition.temporalResolution, condition.scope)
        return IrCondition(
            condition.id,
            effect,
            condition.interpretation,
            scope,
            condition.evidence,
            condition.assertion,
            condition.supersedes.toList(),
            condition.alternativeGroup,
            condition.alternative,
        )
    }

    /** Invalid resolution stays in the graph as a scope failure, so dependent relations also remain quarantined. */
    private fun proofShape(
        effect: IrEffect,
        resolution: IrTemporalResolution,
        scope: IrScope?,
    ): Pair<IrEffect, IrScope?> =
        when {
            resolution == IrTemporalResolution.PRECISE_INTERVAL && (scope == null || scope.start == null || scope.end == null) ->
                IrEffect.AVAILABLE to null
            resolution == IrTemporalResolution.NON_PRECISE && effect == IrEffect.UNCONSTRAINED ->
                IrEffect.AVAILABLE to null
            resolution == IrTemporalResolution.NON_PRECISE &&
                effect != IrEffect.UNRESOLVED &&
                scope != null &&
                scope.dimension != IrDimension.TIME -> IrEffect.AVAILABLE to null
            resolution == IrTemporalResolution.NON_PRECISE -> IrEffect.UNRESOLVED to scope
            else -> effect to scope
        }
}
