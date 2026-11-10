package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import java.util.UUID

data class IrV2ConditionCalculationEvidence(
    val conditionId: String,
    val effect: IrEffect,
    val interpretation: IrInterpretation,
    val temporalResolution: IrTemporalResolution,
    val disposition: IrConditionDisposition,
    val scope: IrScope?,
    val alternativeGroup: String?,
    val alternative: String?,
)

data class IrV2ParticipantMatchingEvidence(
    val submissionVersionId: UUID,
    val participantId: ParticipantId,
    val unresolvedTime: Boolean,
    val unsupportedDimensions: Set<IrDimension>,
    val conditions: List<IrV2ConditionCalculationEvidence>,
    val quarantineCount: Int,
)

data class IrV2PreparedMatchingInputs(
    val participants: List<ParticipantMatchInput>,
    val evidence: Map<ParticipantId, IrV2ParticipantMatchingEvidence>,
)

object ScheduleIrV2MatchInputPolicy {
    fun project(
        envelope: ScheduleIrV2Envelope,
        policy: IrMatchingPolicy,
    ): IrV2PreparedMatchingInputs {
        require(envelope.semanticVersion == SCHEDULE_IR_V2_VERSION)
        val original = envelope.results.associateBy { it.submissionVersionId }
        val projected =
            ScheduleIrMatchInputPolicy.projectNumeric(
                envelope.context,
                envelope.results.map { result ->
                    IrNumericProjectionInput(
                        result.submissionVersionId,
                        result.conditions.map { condition ->
                            IrCondition(
                                condition.id,
                                condition.effect,
                                condition.interpretation,
                                condition.scope,
                                condition.evidence,
                                condition.assertion,
                                condition.supersedes,
                                condition.alternativeGroup,
                                condition.alternative,
                            )
                        },
                        result.quarantined,
                        result.conditions
                            .filter { it.temporalResolution == IrTemporalResolution.NON_PRECISE }
                            .map { it.id }
                            .toSet(),
                        availabilityUnknown = false,
                        separatePreferenceInfluence = true,
                    )
                },
                policy,
            )
        return IrV2PreparedMatchingInputs(
            projected.participants,
            projected.evidence.mapValues { (_, value) ->
                val conditions = original.getValue(value.submissionVersionId).conditions.associateBy { it.id }
                IrV2ParticipantMatchingEvidence(
                    value.submissionVersionId,
                    value.participantId,
                    value.unresolvedTime,
                    value.unsupportedDimensions,
                    value.conditions.map { evidence ->
                        val condition = conditions.getValue(evidence.conditionId)
                        IrV2ConditionCalculationEvidence(
                            condition.id,
                            condition.effect,
                            condition.interpretation,
                            condition.temporalResolution,
                            evidence.disposition,
                            condition.scope,
                            condition.alternativeGroup,
                            condition.alternative,
                        )
                    },
                    value.quarantineCount,
                )
            },
        )
    }
}
