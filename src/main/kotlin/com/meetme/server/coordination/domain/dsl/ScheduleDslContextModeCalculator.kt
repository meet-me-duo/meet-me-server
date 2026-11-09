package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.MatchOutcome
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_CONTEXT_MODE_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleModeRestriction
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

object ScheduleDslContextModePolicy {
    const val VERSION = SCHEDULE_CONTEXT_MODE_POLICY_VERSION

    fun contextual(choices: List<IrAlternativeChoice> = emptyList()): IrMatchingPolicy =
        IrMatchingPolicy(VERSION, setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED), choices.toList(), true)
}

data class ScheduleModeCalculationEvidence(
    val restriction: ScheduleModeRestriction,
    val disposition: IrConditionDisposition,
)

/** Atomic mode maps prevent time/mode flattening. Candidate and recommendation selection retain their separate contracts. */
data class ScheduleDslContextModeCalculation(
    val preparedByMode: Map<MeetingMode, ScheduleDslPreparedInputs>,
    val modeEvidence: Map<ParticipantId, List<ScheduleModeCalculationEvidence>>,
    val candidates: CandidateGenerationResult,
    val recommendations: RecommendationProjection,
    val status: IrCalculationStatus,
    val precisionOutcome: IrPrecisionOutcome,
)

data class ScheduleDslContextModePreparation(
    val preparedByMode: Map<MeetingMode, ScheduleDslPreparedInputs>,
    val modeEvidence: Map<ParticipantId, List<ScheduleModeCalculationEvidence>>,
    val status: IrCalculationStatus,
)

object ScheduleDslContextModeCalculator {
    /** Pure projection for stored diagnostics; no candidate or recommendation generation. */
    fun prepare(
        envelope: ScheduleDslContextModeEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
        policy: IrMatchingPolicy,
        roomMode: MeetingMode,
    ): ScheduleDslContextModePreparation {
        val checked = ScheduleDslContextModeValidator().validateRestored(envelope, snapshot)
        return prepareProjection(
            checked.base.declaration,
            checked.calculationProjection,
            checked.modeRestrictions,
            checked.contextApplications.map { it.submissionVersionId to it.conditionId }.toSet(),
            snapshot,
            policy,
            roomMode,
        )
    }

    /** Internal shared math; each public protocol entry verifies its complete declaration before calling. */
    internal fun prepareProjection(
        declaration: com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope,
        projection: com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope,
        modeRestrictions: List<ScheduleModeRestriction>,
        inferenceKeys: Set<Pair<UUID, String>>,
        snapshot: ScheduleEvidenceSnapshot,
        policy: IrMatchingPolicy,
        roomMode: MeetingMode,
    ): ScheduleDslContextModePreparation {
        require(policy == ScheduleDslContextModePolicy.contextual(policy.alternativeChoices)) { "Context-mode policy mismatch" }
        require(policy.version == snapshot.context.policyVersion) { "Frozen policy mismatch" }
        require(
            policy.alternativeChoices
                .map { it.submissionVersionId to it.group }
                .distinct()
                .size == policy.alternativeChoices.size,
        )
        require(
            policy.alternativeChoices.all { choice ->
                snapshot.context.inputs.any { it.submissionVersionId == choice.submissionVersionId }
            },
        )
        val alternatives = mutableMapOf<Pair<UUID, String>, MutableSet<String>>()
        declaration.claims.results.forEach { result ->
            result.conditions.forEach { condition ->
                condition.alternativeGroup?.let { group ->
                    alternatives.getOrPut(result.submissionVersionId to group) { mutableSetOf() } += requireNotNull(condition.alternative)
                }
            }
        }
        declaration.programs.forEach { result ->
            result.program?.nodes.orEmpty().forEach { node ->
                node.choiceGroup?.let { group ->
                    alternatives.getOrPut(result.submissionVersionId to group) { mutableSetOf() } += node.branchLabels
                }
            }
        }
        val baseAlternativeKeys = alternatives.keys.toSet()
        modeRestrictions.forEach { restriction ->
            restriction.alternativeGroup?.let { group ->
                alternatives.getOrPut(restriction.submissionVersionId to group) { mutableSetOf() } +=
                    requireNotNull(restriction.alternative)
            }
        }
        require(policy.alternativeChoices.all { it.alternative in alternatives[it.submissionVersionId to it.group].orEmpty() }) {
            "Unknown context-mode alternative"
        }
        val basePolicy =
            policy.copy(
                alternativeChoices =
                    policy.alternativeChoices.filter { (it.submissionVersionId to it.group) in baseAlternativeKeys },
            )
        val prepared = ScheduleDslInputPolicy.projectWithPreferredAvailability(projection, basePolicy)
        val zone = ZoneId.of(snapshot.context.timeZone)
        val search =
            InstantTimeRange(
                instant(snapshot.context.searchStart.atStartOfDay(), zone),
                instant(snapshot.context.searchEnd.atStartOfDay(), zone),
            )
        val restrictions = modeRestrictions.groupBy { it.submissionVersionId }
        val states = restrictions.mapValues { (_, values) -> dispositions(values, policy) }
        val evidence =
            snapshot.context.inputs.associate { input ->
                input.participantId.let { id ->
                    ParticipantId(id) to
                        restrictions[input.submissionVersionId].orEmpty().map {
                            ScheduleModeCalculationEvidence(it, states.getValue(input.submissionVersionId).getValue(it.id))
                        }
                }
            }
        val modes = if (roomMode == MeetingMode.EITHER) listOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE) else listOf(roomMode)
        val byMode =
            modes.associateWith { mode ->
                val participantEvidence = prepared.evidence.toMutableMap()
                val participants =
                    prepared.participants.map { participant ->
                        val original = prepared.evidence.getValue(participant.participantId)
                        val records = evidence.getValue(participant.participantId)
                        val possibleBlocks = mutableListOf<InstantTimeRange>()
                        val definiteBlocks = mutableListOf<InstantTimeRange>()
                        var uncertain = false
                        for (record in records) {
                            val restriction = record.restriction
                            if (record.disposition in
                                setOf(
                                    IrConditionDisposition.EXCLUDED_ASSERTION,
                                    IrConditionDisposition.SUPERSEDED,
                                    IrConditionDisposition.UNSELECTED_BRANCH,
                                )
                            ) {
                                continue
                            }
                            if (restriction.id in unsafeCorrections(restrictions[original.submissionVersionId].orEmpty(), policy)) continue
                            val modeKnown = restriction.modeInterpretation in policy.eligibleInterpretations
                            if (modeKnown && mode in restriction.permittedModes) continue
                            val influence = window(restriction, search, zone)
                            possibleBlocks += influence
                            val timeKnown =
                                restriction.timeInterpretation in policy.eligibleInterpretations &&
                                    restriction.scope?.start != null &&
                                    restriction.scope.end != null &&
                                    (
                                        restriction.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL ||
                                            (
                                                restriction.timeInterpretation == IrInterpretation.INFERRED &&
                                                    restriction.temporalResolution == IrTemporalResolution.NON_PRECISE
                                            )
                                    )
                            if (record.disposition == IrConditionDisposition.APPLIED && modeKnown && timeKnown) {
                                definiteBlocks += influence
                            } else if (influence.isNotEmpty()) {
                                uncertain = true
                            }
                        }
                        val lower = TimeRangeMatcher.subtract(participant.availableTimes, possibleBlocks)
                        val upper = TimeRangeMatcher.subtract(original.possibleWindows, definiteBlocks)
                        participantEvidence[participant.participantId] =
                            original.copy(
                                possibleWindows = upper,
                                unresolvedTime = original.unresolvedTime || uncertain || lower != upper,
                            )
                        participant.copy(
                            availableTimes = lower,
                            explicitPreferences =
                                participant.explicitPreferences.copy(
                                    timeRanges = TimeRangeMatcher.intersect(participant.explicitPreferences.timeRanges, lower),
                                ),
                        )
                    }
                ScheduleDslPreparedInputs(participants, participantEvidence)
            }
        val appliedClaims =
            prepared.evidence.values
                .flatMap { input ->
                    input.conditions.filter { it.disposition == IrConditionDisposition.APPLIED }.map {
                        input.submissionVersionId to
                            it.conditionId
                    }
                }.toSet()
        val activeModelApplications =
            inferenceKeys.any { key ->
                key in appliedClaims ||
                    evidence.values.flatten().any {
                        it.restriction.submissionVersionId == key.first &&
                            it.restriction.id == key.second &&
                            it.disposition == IrConditionDisposition.APPLIED
                    }
            }
        val partial =
            activeModelApplications ||
                byMode.values.any { input ->
                    input.evidence.values.any {
                        it.unresolvedTime ||
                            it.programProblems.isNotEmpty() ||
                            it.conditions.any { condition -> condition.disposition == IrConditionDisposition.WITHHELD } ||
                            it.nodes.any { node -> node.disposition == ScheduleDslNodeDisposition.WITHHELD }
                    }
                } ||
                evidence.values.flatten().any {
                    it.disposition == IrConditionDisposition.WITHHELD ||
                        (it.disposition == IrConditionDisposition.APPLIED && it.restriction.modeInterpretation == IrInterpretation.INFERRED)
                }
        return ScheduleDslContextModePreparation(
            byMode,
            evidence,
            if (partial) IrCalculationStatus.PARTIAL else IrCalculationStatus.CALCULATED,
        )
    }

    fun calculate(
        envelope: ScheduleDslContextModeEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
        policy: IrMatchingPolicy,
        roomMode: MeetingMode,
    ): ScheduleDslContextModeCalculation = calculatePrepared(prepare(envelope, snapshot, policy, roomMode), snapshot.context.timeZone)

    internal fun calculatePrepared(
        preparation: ScheduleDslContextModePreparation,
        timeZone: String,
    ): ScheduleDslContextModeCalculation {
        val byMode = preparation.preparedByMode
        val zone = ZoneId.of(timeZone)
        val generated = byMode.flatMap { (mode, input) -> DeterministicCandidateMatcher.generate(mode, input.participants).candidates }
        val full = generated.filter { it.planType != PlanType.C }
        val candidates =
            if (full.isNotEmpty()) {
                full.sortedBy {
                    it.planType.ordinal
                }
            } else {
                generated.sortedWith(DeterministicCandidateMatcher.candidateComparator()).take(1)
            }
        val result = CandidateGenerationResult(if (candidates.isEmpty()) MatchOutcome.NO_MATCH else MatchOutcome.READY, candidates)
        val recommendations = RecommendationProjector.generateByMode(byMode.mapValues { it.value.participants }, zone)
        return ScheduleDslContextModeCalculation(
            byMode,
            preparation.modeEvidence,
            result,
            recommendations,
            preparation.status,
            if (candidates.isEmpty()) IrPrecisionOutcome.NO_PRECISE_CANDIDATES else IrPrecisionOutcome.PRECISE_CANDIDATES,
        )
    }

    private fun dispositions(
        values: List<ScheduleModeRestriction>,
        policy: IrMatchingPolicy,
    ): Map<String, IrConditionDisposition> {
        val unsafe = unsafeCorrections(values, policy)
        val superseded =
            values
                .filter {
                    it.assertion == IrAssertion.ASSERTED &&
                        it.id !in unsafe &&
                        selected(it, policy) == true &&
                        it.modeInterpretation == IrInterpretation.EXPLICIT &&
                        it.timeInterpretation == IrInterpretation.EXPLICIT &&
                        it.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL
                }.flatMap { it.supersedes }
                .toSet()
        return values.associate { value ->
            value.id to
                when {
                    value.assertion != IrAssertion.ASSERTED -> IrConditionDisposition.EXCLUDED_ASSERTION
                    value.id in superseded -> IrConditionDisposition.SUPERSEDED
                    selected(value, policy) == false -> IrConditionDisposition.UNSELECTED_BRANCH
                    value.id in unsafe ||
                        selected(value, policy) == null ||
                        value.modeInterpretation !in policy.eligibleInterpretations ||
                        value.timeInterpretation !in policy.eligibleInterpretations ||
                        value.scope?.start == null ||
                        value.scope.end == null ||
                        (
                            value.temporalResolution != IrTemporalResolution.PRECISE_INTERVAL &&
                                !(
                                    value.timeInterpretation == IrInterpretation.INFERRED &&
                                        value.temporalResolution == IrTemporalResolution.NON_PRECISE
                                )
                        ) -> IrConditionDisposition.WITHHELD
                    else -> IrConditionDisposition.APPLIED
                }
        }
    }

    private fun unsafeCorrections(
        values: List<ScheduleModeRestriction>,
        policy: IrMatchingPolicy,
    ): Set<String> {
        val byId = values.associateBy { it.id }
        return values
            .filter { value ->
                (
                    value.modeInterpretation != IrInterpretation.EXPLICIT ||
                        value.timeInterpretation != IrInterpretation.EXPLICIT ||
                        value.temporalResolution != IrTemporalResolution.PRECISE_INTERVAL
                ) &&
                    value.supersedes.any { target ->
                        val old = byId.getValue(target)
                        old.assertion == IrAssertion.ASSERTED && selected(old, policy) != false
                    }
            }.map { it.id }
            .toSet()
    }

    private fun selected(
        value: ScheduleModeRestriction,
        policy: IrMatchingPolicy,
    ): Boolean? =
        if (value.alternativeGroup == null) {
            true
        } else {
            policy.alternativeChoices
                .singleOrNull {
                    it.submissionVersionId == value.submissionVersionId && it.group == value.alternativeGroup
                }?.let { it.alternative == value.alternative }
        }

    private fun window(
        value: ScheduleModeRestriction,
        search: InstantTimeRange,
        zone: ZoneId,
    ): List<InstantTimeRange> {
        val start = value.scope?.start?.let { instant(it, zone) } ?: search.startInclusive
        val end = value.scope?.end?.let { instant(it, zone) } ?: search.endExclusive
        val clippedStart = maxOf(start, search.startInclusive)
        val clippedEnd = minOf(end, search.endExclusive)
        return if (clippedStart < clippedEnd) listOf(InstantTimeRange(clippedStart, clippedEnd)) else emptyList()
    }

    private fun instant(
        value: LocalDateTime,
        zone: ZoneId,
    ): java.time.Instant {
        val offsets = zone.rules.getValidOffsets(value)
        require(offsets.size == 1) { "Ambiguous mode boundary" }
        return value.toInstant(offsets.single())
    }
}
