package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.SchedulePlaceTimeScope
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
        val knownWeeklyWindows =
            checked.weeklyApplications
                .filter { it.resolved }
                .associate { (it.inputId to it.conditionId) to it.occurrences.mapNotNull { occurrence -> occurrence.searchSupport } }
        val weeklyInferences =
            checked.weeklyApplications
                .filter { app ->
                    checked.base.base.declaration.claims.results
                        .single { it.submissionVersionId == app.inputId }
                        .conditions
                        .single { it.id == app.conditionId }
                        .interpretation == IrInterpretation.INFERRED
                }.map { it.inputId to it.conditionId }
        val inferenceKeys =
            checked.base.contextApplications
                .map { it.submissionVersionId to it.conditionId }
                .toSet() +
                checked.directionalApplications
                    .filter { it.declaration.interpretation == IrInterpretation.INFERRED }
                    .map { it.submissionVersionId to it.declaration.conditionId } + weeklyInferences
        val prepared =
            ScheduleDslContextModeCalculator.prepareProjection(
                checked.base.base.declaration,
                checked.calculationProjection,
                checked.base.modeRestrictions,
                inferenceKeys,
                snapshot,
                policy,
                roomMode,
                structuralPolicy = true,
                knownWeeklyWindows = knownWeeklyWindows,
            )
        if (checked.placeConstraints.isEmpty()) return prepared
        val owners = snapshot.context.inputs.associate { it.submissionVersionId to it.participantId }
        val places = checked.placeConstraints.groupBy { owners.getValue(it.submissionVersionId) }
        val linked = places.filterValues { values -> values.any { it.timeScope == SchedulePlaceTimeScope.UNRESOLVED_TIME_LINK } }.keys
        return prepared.copy(
            preparedByMode =
                prepared.preparedByMode.mapValues { (mode, input) ->
                    input.copy(
                        participants =
                            input.participants.map { participant ->
                                participant.copy(
                                    availableTimes =
                                        if (participant.participantId.value in
                                            linked
                                        ) {
                                            emptyList()
                                        } else {
                                            participant.availableTimes
                                        },
                                    offlinePlaceAssessment =
                                        if (mode == MeetingMode.IN_PERSON && participant.participantId.value in places) {
                                            OfflinePlaceAssessment.UNVERIFIED
                                        } else {
                                            participant.offlinePlaceAssessment
                                        },
                                )
                            },
                        evidence =
                            input.evidence.mapValues { (participant, evidence) ->
                                if (participant.value in linked) evidence.copy(unresolvedTime = true) else evidence
                            },
                    )
                },
            status =
                if (linked.isNotEmpty() || MeetingMode.IN_PERSON in prepared.preparedByMode) {
                    IrCalculationStatus.PARTIAL
                } else {
                    prepared.status
                },
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
