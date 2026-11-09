package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.time.ZoneId

/** Pure calculation shared by guarded publication and immutable stored-result reads. */
object ScheduleDslCalculator {
    fun calculateApprovedTerms(
        envelope: ScheduleDslApprovedTermsEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
        policy: IrMatchingPolicy,
        meetingMode: MeetingMode,
    ): ScheduleDslCalculation {
        val validated = ScheduleDslApprovedTermsValidator().validateRestored(envelope, snapshot)
        return calculate(
            ScheduleDslInputPolicy.projectWithPreferredAvailability(validated.calculationProjection, policy),
            validated.declaration.context.timeZone,
            meetingMode,
        )
    }

    fun calculate(
        envelope: ScheduleDslEnvelope,
        policy: IrMatchingPolicy,
        meetingMode: MeetingMode,
    ): ScheduleDslCalculation = calculate(ScheduleDslInputPolicy.project(envelope, policy), envelope.context.timeZone, meetingMode)

    private fun calculate(
        prepared: ScheduleDslPreparedInputs,
        timeZone: String,
        meetingMode: MeetingMode,
    ): ScheduleDslCalculation {
        val candidates = DeterministicCandidateMatcher.generate(meetingMode, prepared.participants)
        val recommendations = RecommendationProjector.generate(meetingMode, prepared.participants, ZoneId.of(timeZone))
        val partial =
            prepared.evidence.values.any { evidence ->
                evidence.unresolvedTime ||
                    evidence.programProblems.isNotEmpty() ||
                    evidence.conditions.any { it.disposition == IrConditionDisposition.WITHHELD } ||
                    evidence.nodes.any { it.disposition == ScheduleDslNodeDisposition.WITHHELD }
            }
        return ScheduleDslCalculation(
            prepared,
            candidates,
            recommendations,
            if (partial) IrCalculationStatus.PARTIAL else IrCalculationStatus.CALCULATED,
            if (candidates.candidates.isEmpty()) IrPrecisionOutcome.NO_PRECISE_CANDIDATES else IrPrecisionOutcome.PRECISE_CANDIDATES,
        )
    }
}
