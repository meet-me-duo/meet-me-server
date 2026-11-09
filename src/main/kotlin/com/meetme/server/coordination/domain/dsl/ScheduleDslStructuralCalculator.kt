package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot

object ScheduleDslStructuralCalculator {
    fun prepare(
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
        policy: IrMatchingPolicy,
        roomMode: MeetingMode,
    ): ScheduleDslContextModePreparation {
        val checked = ScheduleDslStructuralValidator().validateRestored(envelope, snapshot)
        val inferenceKeys =
            checked.base.contextApplications
                .map { it.submissionVersionId to it.conditionId }
                .toSet() +
                checked.directionalApplications
                    .filter { it.declaration.interpretation == IrInterpretation.INFERRED }
                    .map { it.submissionVersionId to it.declaration.conditionId }
        return ScheduleDslContextModeCalculator.prepareProjection(
            checked.base.base.declaration,
            checked.calculationProjection,
            checked.base.modeRestrictions,
            inferenceKeys,
            snapshot,
            policy,
            roomMode,
        )
    }

    fun calculate(
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
        policy: IrMatchingPolicy,
        roomMode: MeetingMode,
    ): ScheduleDslContextModeCalculation =
        ScheduleDslContextModeCalculator.calculatePrepared(prepare(envelope, snapshot, policy, roomMode), snapshot.context.timeZone)
}
