package com.meetme.server.coordination.domain.matching

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrQuarantine
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Explicit local calculation choices; no production preset or participant confirmation. */
data class IrAlternativeChoice(
    val submissionVersionId: UUID,
    val group: String,
    val alternative: String,
)

data class IrMatchingPolicy(
    val version: String,
    val eligibleInterpretations: Set<IrInterpretation>,
    val alternativeChoices: List<IrAlternativeChoice>,
    val allowExplicitUnconstrained: Boolean,
)

enum class IrConditionDisposition {
    APPLIED,
    WITHHELD,
    EXCLUDED_ASSERTION,
    SUPERSEDED,
    UNSELECTED_BRANCH,
}

data class IrConditionCalculationEvidence(
    val conditionId: String,
    val interpretation: IrInterpretation,
    val disposition: IrConditionDisposition,
    val scope: IrScope?,
    val alternativeGroup: String?,
    val alternative: String?,
)

data class IrParticipantMatchingEvidence(
    val submissionVersionId: UUID,
    val participantId: ParticipantId,
    val declaredAvailability: IrAvailabilityState,
    val unresolvedTime: Boolean,
    val unsupportedDimensions: Set<IrDimension>,
    val conditions: List<IrConditionCalculationEvidence>,
    val quarantineCount: Int,
)

data class IrPreparedMatchingInputs(
    val participants: List<ParticipantMatchInput>,
    val evidence: Map<ParticipantId, IrParticipantMatchingEvidence>,
)

internal data class IrNumericProjectionInput(
    val submissionVersionId: UUID,
    val conditions: List<IrCondition>,
    val quarantined: List<IrQuarantine>,
    val nonPreciseConditionIds: Set<String>,
    val availabilityUnknown: Boolean,
    val separatePreferenceInfluence: Boolean,
)

internal data class IrCorrectionInfluence(
    val correctingConditionId: String,
    val targetConditionId: String,
    val window: InstantTimeRange,
)

internal data class IrNumericProjectionEvidence(
    val submissionVersionId: UUID,
    val participantId: ParticipantId,
    val unresolvedTime: Boolean,
    val unsupportedDimensions: Set<IrDimension>,
    val conditions: List<IrConditionCalculationEvidence>,
    val quarantineCount: Int,
    val correctionInfluences: List<IrCorrectionInfluence>,
    val uncertainPreferenceTargets: Set<String>,
)

internal data class IrNumericProjection(
    val participants: List<ParticipantMatchInput>,
    val evidence: Map<ParticipantId, IrNumericProjectionEvidence>,
)

/** Versioned adapters share numeric mechanics, not a semantic downgrade through a V1 wire envelope. */
object ScheduleIrMatchInputPolicy {
    fun project(
        envelope: ScheduleIrEnvelope,
        policy: IrMatchingPolicy,
    ): IrPreparedMatchingInputs {
        require(envelope.semanticVersion == SCHEDULE_IR_VERSION)
        val original = envelope.results.associateBy { it.submissionVersionId }
        val projected =
            projectNumeric(
                envelope.context,
                envelope.results.map {
                    IrNumericProjectionInput(
                        it.submissionVersionId,
                        it.conditions,
                        it.quarantined,
                        emptySet(),
                        it.availability == IrAvailabilityState.UNKNOWN,
                        false,
                    )
                },
                policy,
            )
        return IrPreparedMatchingInputs(
            projected.participants,
            projected.evidence.mapValues { (_, value) ->
                IrParticipantMatchingEvidence(
                    value.submissionVersionId,
                    value.participantId,
                    original.getValue(value.submissionVersionId).availability,
                    value.unresolvedTime,
                    value.unsupportedDimensions,
                    value.conditions,
                    value.quarantineCount,
                )
            },
        )
    }

    internal fun projectNumeric(
        context: ScheduleIrContext,
        projections: List<IrNumericProjectionInput>,
        policy: IrMatchingPolicy,
    ): IrNumericProjection {
        require(policy.version == context.policyVersion && policy.version.matches(TOKEN))
        val inputs = context.inputs
        require(inputs.size in 2..50 && inputs.map { it.submissionVersionId }.distinct().size == inputs.size)
        require(inputs.map { it.participantId }.distinct().size == inputs.size)
        val results = projections.associateBy { it.submissionVersionId }
        require(results.size == projections.size && results.keys == inputs.map { it.submissionVersionId }.toSet())
        val choices = policy.alternativeChoices.associateBy { it.submissionVersionId to it.group }
        require(choices.size == policy.alternativeChoices.size)
        choices.forEach { (key, choice) ->
            require(
                results[key.first]?.conditions?.any { it.alternativeGroup == key.second && it.alternative == choice.alternative } == true,
            )
        }
        val zone = ZoneId.of(context.timeZone)
        require(ChronoUnit.DAYS.between(context.searchStart, context.searchEnd) in 1..31)
        val search =
            InstantTimeRange(
                instant(context.searchStart.atStartOfDay(), zone),
                instant(context.searchEnd.atStartOfDay(), zone),
            )
        require(search.startInclusive < search.endExclusive)
        val projected =
            inputs.sortedBy { it.participantId.toString() }.map { input ->
                prepare(input, results.getValue(input.submissionVersionId), policy, choices, zone, search)
            }
        return IrNumericProjection(projected.map { it.first }, projected.associate { it.first.participantId to it.second })
    }

    private fun prepare(
        input: IrInputBinding,
        result: IrNumericProjectionInput,
        policy: IrMatchingPolicy,
        choices: Map<Pair<UUID, String>, IrAlternativeChoice>,
        zone: ZoneId,
        search: InstantTimeRange,
    ): Pair<ParticipantMatchInput, IrNumericProjectionEvidence> {
        require(
            result.conditions.size <= 32 &&
                result.conditions
                    .map { it.id }
                    .distinct()
                    .size == result.conditions.size,
        )
        require(result.quarantined.all { it.scope == null }) { "Untrusted quarantine scope" }
        result.conditions.forEach { condition ->
            require((condition.alternativeGroup == null) == (condition.alternative == null))
            val scope = condition.scope
            require((scope?.start == null) == (scope?.end == null))
            if (scope?.start != null) {
                require(scope.start < requireNotNull(scope.end))
                interval(scope, zone)
            }
            val nonPrecise = condition.id in result.nonPreciseConditionIds
            require(
                condition.effect == IrEffect.UNRESOLVED ||
                    (nonPrecise && scope == null) ||
                    (scope != null && scope.dimension == IrDimension.TIME && (nonPrecise || scope.start != null)),
            )
            if (condition.effect == IrEffect.UNCONSTRAINED) require(!nonPrecise && interval(requireNotNull(scope), zone) == search)
        }

        fun choice(condition: IrCondition): IrAlternativeChoice? =
            condition.alternativeGroup?.let { choices[input.submissionVersionId to it] }

        fun selected(condition: IrCondition): Boolean =
            condition.alternativeGroup == null || choice(condition)?.alternative == condition.alternative

        // Branch selection precedes relations; interpretation eligibility does not resurrect corrected facts.
        val asserted = result.conditions.filter { it.assertion == IrAssertion.ASSERTED }
        val superseded = asserted.filter(::selected).flatMap { it.supersedes }.toSet()
        val available = mutableListOf<InstantTimeRange>()
        val blocked = mutableListOf<InstantTimeRange>()
        val preferred = linkedMapOf<String, InstantTimeRange>()
        val uncertainPreferenceTargets = mutableSetOf<String>()
        val correctionInfluences = mutableListOf<IrCorrectionInfluence>()
        val unsupported = mutableSetOf<IrDimension>()
        var unresolved = result.availabilityUnknown || result.quarantined.isNotEmpty()
        if (result.quarantined.isNotEmpty()) blocked += search

        val evidence =
            result.conditions.map { condition ->
                val nonPrecise = condition.id in result.nonPreciseConditionIds
                val groupMissing = condition.alternativeGroup != null && choice(condition) == null
                val eligible = condition.interpretation in policy.eligibleInterpretations
                val positiveAllowed = eligible && (condition.effect != IrEffect.UNCONSTRAINED || policy.allowExplicitUnconstrained)
                val disposition =
                    when {
                        condition.assertion != IrAssertion.ASSERTED -> IrConditionDisposition.EXCLUDED_ASSERTION
                        condition.alternativeGroup != null &&
                            !groupMissing &&
                            !selected(
                                condition,
                            ) -> IrConditionDisposition.UNSELECTED_BRANCH
                        condition.id in superseded -> IrConditionDisposition.SUPERSEDED
                        groupMissing || !positiveAllowed -> IrConditionDisposition.WITHHELD
                        else -> IrConditionDisposition.APPLIED
                    }
                if (disposition !in
                    setOf(
                        IrConditionDisposition.EXCLUDED_ASSERTION,
                        IrConditionDisposition.SUPERSEDED,
                        IrConditionDisposition.UNSELECTED_BRANCH,
                    )
                ) {
                    val scope = condition.scope
                    if (scope?.dimension in setOf(IrDimension.PLACE, IrDimension.ALL)) unsupported += requireNotNull(scope).dimension
                    val affectsTime = scope?.dimension != IrDimension.PLACE
                    if (affectsTime) {
                        if (nonPrecise ||
                            groupMissing ||
                            condition.alternativeGroup != null ||
                            condition.interpretation != IrInterpretation.EXPLICIT ||
                            !positiveAllowed ||
                            condition.effect == IrEffect.UNRESOLVED
                        ) {
                            unresolved = true
                        }
                        val window = if (scope?.start == null) search else interval(scope, zone)
                        when (condition.effect) {
                            IrEffect.UNAVAILABLE, IrEffect.UNRESOLVED -> blocked += window
                            IrEffect.AVAILABLE, IrEffect.UNCONSTRAINED ->
                                if (!groupMissing &&
                                    positiveAllowed &&
                                    !nonPrecise
                                ) {
                                    available += window
                                }
                            IrEffect.PREFERRED -> if (!groupMissing && positiveAllowed && !nonPrecise) preferred[condition.id] = window
                        }
                    }
                    // Correction influence follows its target, even when the correcting claim concerns PLACE.
                    // A withheld withdrawal does not restore an old negative as fact; its possible exclusion remains.
                    if (groupMissing || !positiveAllowed) {
                        condition.supersedes.forEach { targetId ->
                            val target = result.conditions.singleOrNull { it.id == targetId }
                            if (target?.assertion == IrAssertion.ASSERTED &&
                                (target.alternativeGroup == null || choice(target) == null || selected(target)) &&
                                (groupMissing || target.effect in setOf(IrEffect.UNAVAILABLE, IrEffect.UNRESOLVED))
                            ) {
                                val targetScope = target.scope
                                if (targetScope?.dimension in setOf(IrDimension.PLACE, IrDimension.ALL)) {
                                    unsupported += requireNotNull(targetScope).dimension
                                }
                                if (targetScope?.dimension != IrDimension.PLACE) {
                                    val influence = targetScope?.takeIf { it.start != null }?.let { interval(it, zone) } ?: search
                                    when {
                                        target.effect == IrEffect.PREFERRED && result.separatePreferenceInfluence ->
                                            uncertainPreferenceTargets +=
                                                target.id
                                        target.effect in setOf(IrEffect.AVAILABLE, IrEffect.UNCONSTRAINED) &&
                                            target.id in result.nonPreciseConditionIds -> Unit
                                        else -> {
                                            blocked += influence
                                            correctionInfluences += IrCorrectionInfluence(condition.id, target.id, influence)
                                        }
                                    }
                                    unresolved = true
                                }
                            }
                        }
                    }
                }
                IrConditionCalculationEvidence(
                    condition.id,
                    condition.interpretation,
                    disposition,
                    condition.scope,
                    condition.alternativeGroup,
                    condition.alternative,
                )
            }
        val times = TimeRangeMatcher.subtract(TimeRangeMatcher.intersect(available, listOf(search)), blocked)
        val preferences = TimeRangeMatcher.intersect(preferred.filterKeys { it !in uncertainPreferenceTargets }.values.toList(), times)
        val participantId = ParticipantId(input.participantId)
        return ParticipantMatchInput(
            participantId,
            times,
            explicitPreferences = ParticipantPreferences(timeRanges = preferences),
            offlinePlaceAssessment = OfflinePlaceAssessment.UNVERIFIED,
        ) to
            IrNumericProjectionEvidence(
                input.submissionVersionId,
                participantId,
                unresolved,
                unsupported.toSet(),
                evidence,
                result.quarantined.size,
                correctionInfluences.toList(),
                uncertainPreferenceTargets.toSet(),
            )
    }

    private fun interval(
        scope: IrScope,
        zone: ZoneId,
    ): InstantTimeRange = InstantTimeRange(instant(requireNotNull(scope.start), zone), instant(requireNotNull(scope.end), zone))

    private fun instant(
        value: LocalDateTime,
        zone: ZoneId,
    ): Instant {
        val offsets = zone.rules.getValidOffsets(value)
        require(offsets.size == 1) { "Unsupported ambiguous local boundary" }
        return value.toInstant(offsets.single())
    }

    private val TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
}
