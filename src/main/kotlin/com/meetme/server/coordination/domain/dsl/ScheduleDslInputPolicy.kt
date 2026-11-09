package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.IrNumericProjectionInput
import com.meetme.server.coordination.domain.matching.IrV2ConditionCalculationEvidence
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.coordination.domain.matching.ParticipantMatchInput
import com.meetme.server.coordination.domain.matching.ParticipantPreferences
import com.meetme.server.coordination.domain.matching.ScheduleIrMatchInputPolicy
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslRole
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

enum class ScheduleDslNodeDisposition { APPLIED, WITHHELD, EXCLUDED_ASSERTION, INACTIVE_REFERENCE, UNSELECTED_BRANCH }

data class ScheduleDslNodeCalculationEvidence(
    val nodeId: String,
    val operator: ScheduleDslOperator,
    val role: ScheduleDslRole,
    val disposition: ScheduleDslNodeDisposition,
    val claimId: String?,
    val children: List<String>,
    val selectedChildId: String? = null,
)

data class ScheduleDslParticipantEvidence(
    val submissionVersionId: UUID,
    val participantId: ParticipantId,
    val conditions: List<IrV2ConditionCalculationEvidence>,
    val nodes: List<ScheduleDslNodeCalculationEvidence>,
    val unresolvedTime: Boolean,
    val programProblems: Set<ScheduleDslProblem>,
    val possibleWindows: List<InstantTimeRange>,
)

data class ScheduleDslPreparedInputs(
    val participants: List<ParticipantMatchInput>,
    val evidence: Map<ParticipantId, ScheduleDslParticipantEvidence>,
)

object ScheduleDslInputPolicy {
    /** Explicit semantic4 defaults and preferred availability; frozen DSL projection stays compatible. */
    fun projectWithPreferredAvailability(
        envelope: ScheduleDslEnvelope,
        policy: IrMatchingPolicy,
    ): ScheduleDslPreparedInputs = project(envelope, policy, true)

    fun project(
        envelope: ScheduleDslEnvelope,
        policy: IrMatchingPolicy,
    ): ScheduleDslPreparedInputs = project(envelope, policy, false)

    private fun project(
        envelope: ScheduleDslEnvelope,
        policy: IrMatchingPolicy,
        approvedAvailability: Boolean,
    ): ScheduleDslPreparedInputs {
        require(envelope.semanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION && envelope.astVersion == SCHEDULE_AST_VERSION)
        require(envelope.claims.semanticVersion == SCHEDULE_IR_V2_VERSION)
        val context = envelope.context
        val core =
            ScheduleIrMatchInputPolicy.projectNumeric(
                context,
                envelope.claims.results.map { result ->
                    IrNumericProjectionInput(
                        result.submissionVersionId,
                        result.conditions.map { c ->
                            IrCondition(
                                c.id,
                                c.effect,
                                c.interpretation,
                                c.scope,
                                c.evidence,
                                c.assertion,
                                c.supersedes,
                                c.alternativeGroup,
                                c.alternative,
                            )
                        },
                        result.quarantined,
                        result.conditions
                            .filter {
                                it.temporalResolution == IrTemporalResolution.NON_PRECISE
                            }.map { it.id }
                            .toSet(),
                        false,
                        true,
                    )
                },
                policy,
            )
        val programs = envelope.programs.associateBy { it.submissionVersionId }
        require(
            programs.size == envelope.programs.size &&
                programs.keys ==
                envelope.claims.results
                    .map { it.submissionVersionId }
                    .toSet(),
        )
        val zone = ZoneId.of(context.timeZone)
        val search = InstantTimeRange(instant(context.searchStart.atStartOfDay(), zone), instant(context.searchEnd.atStartOfDay(), zone))
        val result = envelope.claims.results.associateBy { it.submissionVersionId }
        val projected =
            core.participants.map { participant ->
                val authority = core.evidence.getValue(participant.participantId)
                val claims = result.getValue(authority.submissionVersionId).conditions.associateBy { it.id }
                val conditionEvidence =
                    authority.conditions.map { value ->
                        val c = claims.getValue(value.conditionId)
                        IrV2ConditionCalculationEvidence(
                            c.id,
                            c.effect,
                            c.interpretation,
                            c.temporalResolution,
                            value.disposition,
                            c.scope,
                            c.alternativeGroup,
                            c.alternative,
                        )
                    }
                val rejectedPreferences =
                    envelope.rejectedPreferenceWitnesses
                        .filter { it.submissionVersionId == authority.submissionVersionId }
                        .associateBy { it.conditionId }
                val participantProgram = programs.getValue(authority.submissionVersionId)
                val problems = participantProgram.problems.toMutableSet()
                val program = participantProgram.program
                if (program == null ||
                    result.getValue(authority.submissionVersionId).quarantined.any { it.conditionId !in rejectedPreferences }
                ) {
                    problems += ScheduleDslProblem.REFERENCE
                }
                val refs =
                    program
                        ?.nodes
                        ?.mapNotNull { it.claimId }
                        ?.toSet()
                        .orEmpty()
                val dispositions = authority.conditions.associate { it.conditionId to it.disposition }
                val withheldCorrections = mutableSetOf<String>()
                val protectedNegatives = mutableSetOf<String>()
                if (program != null && problems.isEmpty()) {
                    val activeReferences = authoritativeReferences(program, authority.submissionVersionId, policy)
                    if (claims.values.any { claim ->
                            claim.supersedes.isNotEmpty() &&
                                dispositions[claim.id] == IrConditionDisposition.APPLIED &&
                                claim.id !in activeReferences
                        }
                    ) {
                        // A global correction cannot acquire authority through an excluded AST path.
                        problems += ScheduleDslProblem.RELATION_SCOPE
                    }
                    if (approvedAvailability) {
                        val explicitWithdrawals =
                            claims.values
                                .filter {
                                    it.assertion == IrAssertion.ASSERTED &&
                                        it.interpretation == IrInterpretation.EXPLICIT &&
                                        dispositions[it.id] == IrConditionDisposition.APPLIED &&
                                        it.id in activeReferences
                                }.flatMap { it.supersedes }
                                .toSet()
                        claims.values
                            .filter { claim ->
                                claim.assertion == IrAssertion.ASSERTED &&
                                    claim.interpretation == IrInterpretation.INFERRED &&
                                    dispositions[claim.id] == IrConditionDisposition.APPLIED &&
                                    claim.id in activeReferences
                            }.forEach { claim ->
                                val protectedTargets =
                                    claim.supersedes.filter { targetId ->
                                        val target = claims[targetId]
                                        target?.assertion == IrAssertion.ASSERTED &&
                                            target.effect in setOf(IrEffect.UNAVAILABLE, IrEffect.UNRESOLVED) &&
                                            dispositions[targetId] == IrConditionDisposition.SUPERSEDED &&
                                            targetId !in explicitWithdrawals
                                    }
                                if (protectedTargets.isNotEmpty()) {
                                    // Keep independent AST operands while withholding an estimated withdrawal.
                                    withheldCorrections += claim.id
                                    protectedNegatives += protectedTargets
                                }
                            }
                    }
                }
                val history = program?.historyConditionIds.orEmpty().toSet()
                if (history.any { id ->
                        id !in refs &&
                            dispositions[id] in setOf(IrConditionDisposition.APPLIED, IrConditionDisposition.WITHHELD)
                    }
                ) {
                    problems += ScheduleDslProblem.HISTORY_AUTHORITY_MISMATCH
                }
                if (authority.correctionInfluences.any { it.targetConditionId !in refs }) problems += ScheduleDslProblem.RELATION_SCOPE
                val nodes = mutableMapOf<String, ScheduleDslNodeCalculationEvidence>()
                var unresolved =
                    problems.isNotEmpty() ||
                        withheldCorrections.isNotEmpty() ||
                        authority.unresolvedTime ||
                        authority.unsupportedDimensions.isNotEmpty()
                val memo = mutableMapOf<Pair<String, ScheduleDslRole>, Bounds>()
                val visiting = mutableSetOf<Pair<String, ScheduleDslRole>>()
                val byId = program?.nodes?.associateBy { it.id }.orEmpty()

                fun window(scope: IrScope?): List<InstantTimeRange> =
                    if (scope?.dimension == IrDimension.PLACE) {
                        emptyList()
                    } else if (scope?.start == null) {
                        listOf(search)
                    } else {
                        TimeRangeMatcher.intersect(
                            listOf(InstantTimeRange(instant(scope.start, zone), instant(requireNotNull(scope.end), zone))),
                            listOf(search),
                        )
                    }

                fun mergeCredits(
                    values: List<Bounds>,
                    clip: List<InstantTimeRange>,
                ): Map<String, List<InstantTimeRange>> =
                    values
                        .flatMap { it.credits.entries }
                        .groupBy({ it.key }, { it.value })
                        .mapValues { (_, ranges) ->
                            TimeRangeMatcher.intersect(ranges.flatten(), clip)
                        }.filterValues { it.isNotEmpty() }

                fun protectedNegativeIsDefinite(id: String): Boolean {
                    val claim = claims.getValue(id)
                    val branchSelected =
                        claim.alternativeGroup == null ||
                            policy.alternativeChoices.any {
                                it.submissionVersionId == authority.submissionVersionId &&
                                    it.group == claim.alternativeGroup &&
                                    it.alternative == claim.alternative
                            }
                    return branchSelected &&
                        claim.interpretation in policy.eligibleInterpretations &&
                        claim.effect == IrEffect.UNAVAILABLE &&
                        claim.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL
                }

                fun eval(
                    id: String,
                    role: ScheduleDslRole,
                ): Bounds {
                    val key = id to role
                    memo[key]?.let { return it }
                    require(visiting.add(key) && visiting.size <= 8 && byId.size <= 128) { "Unvalidated DSL graph" }
                    val node = requireNotNull(byId[id]) { "Missing DSL node" }
                    var disposition = ScheduleDslNodeDisposition.APPLIED
                    var selectedChild: String? = null
                    val value =
                        if (node.assertion != IrAssertion.ASSERTED) {
                            disposition = ScheduleDslNodeDisposition.EXCLUDED_ASSERTION
                            Bounds.EMPTY
                        } else if (node.operator == ScheduleDslOperator.CLAIM_REF && node.claimId in rejectedPreferences) {
                            require(role == ScheduleDslRole.PREFER) { "Unvalidated rejected preference role" }
                            disposition = ScheduleDslNodeDisposition.WITHHELD
                            unresolved = true
                            Bounds.EMPTY
                        } else if (node.operator == ScheduleDslOperator.CLAIM_REF) {
                            val claim = requireNotNull(claims[node.claimId]) { "Missing DSL claim" }
                            val state =
                                if (claim.id in withheldCorrections) IrConditionDisposition.WITHHELD else dispositions.getValue(claim.id)
                            val influence = authority.correctionInfluences.filter { it.targetConditionId == claim.id }.map { it.window }
                            val bound = window(claim.scope)
                            val definiteInactive =
                                state in
                                    setOf(
                                        IrConditionDisposition.SUPERSEDED,
                                        IrConditionDisposition.EXCLUDED_ASSERTION,
                                        IrConditionDisposition.UNSELECTED_BRANCH,
                                    )
                            val eligible = state == IrConditionDisposition.APPLIED && node.interpretation in policy.eligibleInterpretations
                            val exact = eligible && claim.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL
                            val raw =
                                if (definiteInactive) {
                                    disposition =
                                        if (state == IrConditionDisposition.UNSELECTED_BRANCH) {
                                            ScheduleDslNodeDisposition.UNSELECTED_BRANCH
                                        } else {
                                            ScheduleDslNodeDisposition.INACTIVE_REFERENCE
                                        }
                                    unresolved = true
                                    Bounds.EMPTY
                                } else {
                                    if (!exact) {
                                        unresolved = true
                                        disposition = ScheduleDslNodeDisposition.WITHHELD
                                    }
                                    when (role) {
                                        ScheduleDslRole.ALLOW -> {
                                            require(claim.effect in setOf(IrEffect.AVAILABLE, IrEffect.UNCONSTRAINED))
                                            Bounds(if (exact) bound else emptyList(), bound)
                                        }
                                        ScheduleDslRole.BLOCK -> {
                                            require(claim.effect in setOf(IrEffect.UNAVAILABLE, IrEffect.UNRESOLVED))
                                            Bounds(if (exact && claim.effect == IrEffect.UNAVAILABLE) bound else emptyList(), bound)
                                        }
                                        ScheduleDslRole.PREFER -> {
                                            require(claim.effect == IrEffect.PREFERRED)
                                            val credit =
                                                if (exact &&
                                                    claim.id !in authority.uncertainPreferenceTargets
                                                ) {
                                                    bound
                                                } else {
                                                    emptyList()
                                                }
                                            Bounds(credit, bound, if (credit.isEmpty()) emptyMap() else mapOf(claim.id to credit))
                                        }
                                    }
                                }
                            if (influence.isEmpty()) {
                                raw
                            } else {
                                unresolved = true
                                disposition = ScheduleDslNodeDisposition.WITHHELD
                                if (role == ScheduleDslRole.BLOCK) {
                                    Bounds(emptyList(), TimeRangeMatcher.normalize(raw.upper + influence))
                                } else {
                                    raw.copy(lower = TimeRangeMatcher.subtract(raw.lower, influence), credits = emptyMap())
                                }
                            }
                        } else {
                            val children =
                                when (node.operator) {
                                    ScheduleDslOperator.EXCEPT ->
                                        listOf(
                                            eval(node.children[0], role),
                                            eval(
                                                node.children[1],
                                                if (role == ScheduleDslRole.BLOCK) ScheduleDslRole.ALLOW else ScheduleDslRole.BLOCK,
                                            ),
                                        )
                                    ScheduleDslOperator.PREFER -> {
                                        require(role == ScheduleDslRole.ALLOW)
                                        listOf(eval(node.children[0], role), eval(node.children[1], ScheduleDslRole.PREFER))
                                    }
                                    ScheduleDslOperator.CHOICE -> {
                                        val choice =
                                            policy.alternativeChoices.singleOrNull {
                                                it.submissionVersionId == authority.submissionVersionId &&
                                                    it.group == node.choiceGroup
                                            }
                                        val index = choice?.let { node.branchLabels.indexOf(it.alternative) } ?: -1
                                        if (index >= 0) {
                                            selectedChild = node.children[index]
                                            listOf(eval(requireNotNull(selectedChild), role))
                                        } else {
                                            disposition = ScheduleDslNodeDisposition.WITHHELD
                                            unresolved = true
                                            node.children.map { eval(it, role) }
                                        }
                                    }
                                    else -> node.children.map { eval(it, role) }
                                }
                            val possible = TimeRangeMatcher.normalize(children.flatMap { it.upper })
                            if (node.interpretation !in policy.eligibleInterpretations ||
                                node.operator == ScheduleDslOperator.CHOICE &&
                                selectedChild == null
                            ) {
                                disposition = ScheduleDslNodeDisposition.WITHHELD
                                unresolved = true
                                // Preference credit and excluded operands cannot create possible availability.
                                val withheldPossible =
                                    when (node.operator) {
                                        ScheduleDslOperator.PREFER, ScheduleDslOperator.EXCEPT -> children[0].upper
                                        else -> possible
                                    }
                                Bounds(emptyList(), withheldPossible)
                            } else {
                                when (node.operator) {
                                    ScheduleDslOperator.ALL_OF -> {
                                        val lower = children.map { it.lower }.reduce(TimeRangeMatcher::intersect)
                                        Bounds(
                                            lower,
                                            children.map { it.upper }.reduce(TimeRangeMatcher::intersect),
                                            mergeCredits(children, lower),
                                        )
                                    }
                                    ScheduleDslOperator.ANY_OF -> {
                                        val lower = TimeRangeMatcher.normalize(children.flatMap { it.lower })
                                        Bounds(lower, possible, mergeCredits(children, lower))
                                    }
                                    ScheduleDslOperator.EXCEPT -> {
                                        val lower = TimeRangeMatcher.subtract(children[0].lower, children[1].upper)
                                        Bounds(
                                            lower,
                                            TimeRangeMatcher.subtract(children[0].upper, children[1].lower),
                                            mergeCredits(listOf(children[0]), lower),
                                        )
                                    }
                                    ScheduleDslOperator.PREFER ->
                                        Bounds(
                                            children[0].lower,
                                            children[0].upper,
                                            mergeCredits(children, children[0].lower),
                                        )
                                    ScheduleDslOperator.CHOICE -> children.single()
                                    else -> error("Unexpected DSL operator")
                                }
                            }
                        }
                    nodes[id] =
                        ScheduleDslNodeCalculationEvidence(id, node.operator, role, disposition, node.claimId, node.children, selectedChild)
                    visiting.remove(key)
                    memo[key] = value
                    return value
                }
                val available =
                    if (problems.isEmpty() &&
                        program != null
                    ) {
                        program.availabilityRoot?.let { eval(it, ScheduleDslRole.ALLOW) }
                            ?: if (approvedAvailability &&
                                program.preferenceRoot == null &&
                                withheldCorrections.isEmpty() &&
                                result.getValue(authority.submissionVersionId).quarantined.isEmpty()
                            ) {
                                // A validated absence of restrictions uses policy support, never an invented claim.
                                Bounds(listOf(search), listOf(search))
                            } else {
                                Bounds.EMPTY
                            }
                    } else {
                        Bounds.EMPTY
                    }
                val excluded =
                    if (problems.isEmpty() &&
                        program != null
                    ) {
                        val original = program.exclusionRoot?.let { eval(it, ScheduleDslRole.BLOCK) } ?: Bounds.EMPTY
                        Bounds(
                            original.lower +
                                protectedNegatives.filter(::protectedNegativeIsDefinite).flatMap { window(claims.getValue(it).scope) },
                            original.upper + protectedNegatives.flatMap { window(claims.getValue(it).scope) },
                        )
                    } else {
                        Bounds.EMPTY
                    }
                val preferred =
                    if (problems.isEmpty() &&
                        program != null
                    ) {
                        program.preferenceRoot?.let { eval(it, ScheduleDslRole.PREFER) } ?: Bounds.EMPTY
                    } else {
                        Bounds.EMPTY
                    }
                val preferenceSuppliesAvailability = approvedAvailability && program?.availabilityRoot == null
                val lower = if (preferenceSuppliesAvailability) available.lower + preferred.lower else available.lower
                val upper = if (preferenceSuppliesAvailability) available.upper + preferred.upper else available.upper
                val precise = TimeRangeMatcher.subtract(lower, excluded.upper)
                val possible = TimeRangeMatcher.subtract(upper, excluded.lower)
                val credits =
                    if (preferenceSuppliesAvailability) {
                        emptyList()
                    } else {
                        mergeCredits(listOf(available, preferred), precise).values.flatten()
                    }
                ParticipantMatchInput(
                    participant.participantId,
                    precise,
                    explicitPreferences = ParticipantPreferences(timeRanges = TimeRangeMatcher.normalize(credits)),
                    offlinePlaceAssessment =
                        if (claims.values.none { it.scope?.dimension == IrDimension.PLACE } &&
                            result.getValue(authority.submissionVersionId).quarantined.isEmpty() &&
                            authority.unsupportedDimensions.isEmpty()
                        ) {
                            OfflinePlaceAssessment.UNRESTRICTED
                        } else {
                            OfflinePlaceAssessment.UNVERIFIED
                        },
                ) to
                    ScheduleDslParticipantEvidence(
                        authority.submissionVersionId,
                        participant.participantId,
                        conditionEvidence.map { evidence ->
                            when (evidence.conditionId) {
                                in withheldCorrections -> evidence.copy(disposition = IrConditionDisposition.WITHHELD)
                                in protectedNegatives ->
                                    evidence.copy(
                                        disposition =
                                            if (protectedNegativeIsDefinite(evidence.conditionId)) {
                                                IrConditionDisposition.APPLIED
                                            } else {
                                                IrConditionDisposition.WITHHELD
                                            },
                                    )
                                else -> evidence
                            }
                        },
                        program?.nodes?.mapNotNull { nodes[it.id] }.orEmpty(),
                        unresolved || precise != possible,
                        problems + if (withheldCorrections.isNotEmpty()) setOf(ScheduleDslProblem.RELATION_SCOPE) else emptySet(),
                        possible,
                    )
            }
        return ScheduleDslPreparedInputs(projected.map { it.first }, projected.associate { it.first.participantId to it.second })
    }

    private fun authoritativeReferences(
        program: ScheduleDslProgram<IrEvidenceSpan>,
        submissionVersionId: UUID,
        policy: IrMatchingPolicy,
    ): Set<String> {
        val nodes = program.nodes.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val references = mutableSetOf<String>()

        fun visit(id: String) {
            val node = requireNotNull(nodes[id]) { "Missing DSL node" }
            if (node.assertion != IrAssertion.ASSERTED || node.interpretation !in policy.eligibleInterpretations) return
            if (!visited.add(id)) return
            when (node.operator) {
                ScheduleDslOperator.CLAIM_REF -> references += requireNotNull(node.claimId)
                ScheduleDslOperator.CHOICE -> {
                    val choice =
                        policy.alternativeChoices.singleOrNull {
                            it.submissionVersionId == submissionVersionId && it.group == node.choiceGroup
                        }
                    val index = choice?.let { node.branchLabels.indexOf(it.alternative) } ?: -1
                    if (index >= 0) visit(node.children[index])
                }
                else -> node.children.forEach(::visit)
            }
        }
        listOfNotNull(program.availabilityRoot, program.exclusionRoot, program.preferenceRoot).forEach(::visit)
        return references
    }

    private data class Bounds(
        val lower: List<InstantTimeRange>,
        val upper: List<InstantTimeRange>,
        val credits: Map<String, List<InstantTimeRange>> = emptyMap(),
    ) {
        companion object {
            val EMPTY = Bounds(emptyList(), emptyList())
        }
    }

    private fun instant(
        value: LocalDateTime,
        zone: ZoneId,
    ): Instant {
        val offsets = zone.rules.getValidOffsets(value)
        require(offsets.size == 1) { "Ambiguous DSL boundary" }
        return value.toInstant(offsets.single())
    }
}
