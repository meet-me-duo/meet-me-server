package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrV2Result
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import java.util.UUID

const val SCHEDULE_DSL_SEMANTIC_VERSION = "schedule-ir-product-3"
const val SCHEDULE_AST_VERSION = "schedule-ast-1"
const val SCHEDULE_DSL_RESPONSE_CONTRACT = "schedule-dsl-response-1"

enum class ScheduleDslOperator { CLAIM_REF, ALL_OF, ANY_OF, EXCEPT, CHOICE, PREFER }

enum class ScheduleDslRole { ALLOW, BLOCK, PREFER }

enum class ScheduleDslProblem { SHAPE, REFERENCE, ROLE, COMPLEXITY, OPERATOR_EVIDENCE, HISTORY_AUTHORITY_MISMATCH, RELATION_SCOPE }

/** Node references form a bounded graph, never executable code or a flat condition union. */
data class ScheduleDslNode<E>(
    val id: String,
    val operator: ScheduleDslOperator,
    val claimId: String?,
    val children: List<String>,
    val choiceGroup: String?,
    val branchLabels: List<String>,
    val interpretation: IrInterpretation,
    val assertion: IrAssertion,
    val evidence: List<E>,
)

data class ScheduleDslProgram<E>(
    val nodes: List<ScheduleDslNode<E>>,
    val availabilityRoot: String?,
    val exclusionRoot: String?,
    val preferenceRoot: String?,
    val historyConditionIds: List<String>,
)

data class ProposedParticipantScheduleProgram(
    val submissionVersionId: UUID,
    val program: ScheduleDslProgram<ProposedIrEvidence>?,
    val problems: Set<ScheduleDslProblem> = emptySet(),
)

data class ParticipantScheduleProgram(
    val submissionVersionId: UUID,
    val program: ScheduleDslProgram<IrEvidenceSpan>?,
    val problems: Set<ScheduleDslProblem>,
)

data class ScheduleDslProposal(
    val semanticVersion: String,
    val astVersion: String,
    val claims: ScheduleIrV2Proposal,
    val programs: List<ProposedParticipantScheduleProgram>,
)

/** Claim meaning has IR2 semantics; explicit AST structure has its own identity and stored format. */
data class ScheduleDslEnvelope(
    val semanticVersion: String,
    val astVersion: String,
    val claims: ScheduleIrV2Envelope,
    val programs: List<ParticipantScheduleProgram>,
    val rejectedPreferenceWitnesses: List<RejectedPreferenceWitness> = emptyList(),
) {
    val context: ScheduleIrContext get() = claims.context
}

class ScheduleDslValidator {
    fun validate(
        proposal: ScheduleDslProposal,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslEnvelope {
        require(proposal.semanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION && proposal.astVersion == SCHEDULE_AST_VERSION) {
            "Unsupported schedule DSL"
        }
        val claims = ScheduleIrV2Validator().validate(proposal.claims, snapshot)
        val candidates = rejectionWitnesses(proposal.claims, claims, snapshot)
        val programs = validatePrograms(proposal.programs, claims, snapshot, candidates)
        val verifiedInputs = programs.filter { it.program != null }.map { it.submissionVersionId }.toSet()
        return ScheduleDslEnvelope(
            proposal.semanticVersion,
            proposal.astVersion,
            claims,
            programs,
            candidates.filter { it.submissionVersionId in verifiedInputs },
        )
    }

    fun validateRestored(
        envelope: ScheduleDslEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslEnvelope {
        require(envelope.semanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION && envelope.astVersion == SCHEDULE_AST_VERSION) {
            "Unsupported persisted schedule DSL"
        }
        ScheduleIrV2Validator().validateRestored(envelope.claims, snapshot)
        val proposed =
            envelope.programs.map { participant ->
                ProposedParticipantScheduleProgram(
                    participant.submissionVersionId,
                    participant.program?.let { program ->
                        ScheduleDslProgram(
                            program.nodes.map { node ->
                                ScheduleDslNode(
                                    node.id,
                                    node.operator,
                                    node.claimId,
                                    node.children,
                                    node.choiceGroup,
                                    node.branchLabels,
                                    node.interpretation,
                                    node.assertion,
                                    node.evidence.map { span ->
                                        val quote =
                                            extract(
                                                snapshot,
                                                participant.submissionVersionId,
                                                span.sourceId,
                                                span.startCodePoint,
                                                span.endCodePoint,
                                            )
                                        require(IrEvidenceBindings.sha256(quote) == span.contentSha256) { "DSL evidence digest mismatch" }
                                        ProposedIrEvidence(span.sourceId, span.revision, span.startCodePoint, span.endCodePoint, quote)
                                    },
                                )
                            },
                            program.availabilityRoot,
                            program.exclusionRoot,
                            program.preferenceRoot,
                            program.historyConditionIds,
                        )
                    },
                    participant.problems,
                )
            }
        if (envelope.rejectedPreferenceWitnesses.isEmpty()) {
            require(validatePrograms(proposed, envelope.claims, snapshot, emptyList()) == envelope.programs) {
                "Invalid persisted DSL program"
            }
        } else {
            val restoredClaims = restoreRejectedClaims(envelope, snapshot)
            val verified =
                validate(
                    ScheduleDslProposal(envelope.semanticVersion, envelope.astVersion, restoredClaims, proposed),
                    snapshot,
                )
            require(verified == envelope) { "Invalid persisted DSL rejection witness" }
        }
        return envelope
    }

    private fun rejectionWitnesses(
        proposal: ScheduleIrV2Proposal,
        claims: ScheduleIrV2Envelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): List<RejectedPreferenceWitness> {
        val results = claims.results.associateBy { it.submissionVersionId }
        return proposal.results
            .flatMap { original ->
                val result = results.getValue(original.submissionVersionId)
                val incoming = original.conditions.flatMap { it.supersedes }.toSet()
                original.conditions.mapNotNull { condition ->
                    val quarantine = result.quarantined.singleOrNull { it.conditionId == condition.id }
                    if (quarantine?.problem != IrProblem.SCOPE ||
                        quarantine.scope != null ||
                        condition.effect != IrEffect.PREFERRED ||
                        condition.scope?.dimension != IrDimension.PLACE ||
                        condition.assertion != IrAssertion.ASSERTED ||
                        condition.supersedes.isNotEmpty() ||
                        condition.alternativeGroup != null ||
                        condition.alternative != null ||
                        condition.id in incoming
                    ) {
                        return@mapNotNull null
                    }
                    val evidence =
                        try {
                            require(condition.evidence.size in 1..32)
                            condition.evidence.map { verifyEvidence(it, original.submissionVersionId, snapshot) }
                        } catch (_: IllegalArgumentException) {
                            return@mapNotNull null
                        }
                    RejectedPreferenceWitness(
                        original.submissionVersionId,
                        condition.id,
                        condition.effect,
                        condition.interpretation,
                        condition.temporalResolution,
                        condition.scope,
                        evidence,
                        condition.assertion,
                        condition.supersedes.toList(),
                        condition.alternativeGroup,
                        condition.alternative,
                    )
                }
            }.sortedWith(compareBy({ it.submissionVersionId }, { it.conditionId }))
    }

    private fun restoreRejectedClaims(
        envelope: ScheduleDslEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleIrV2Proposal {
        val witnesses = envelope.rejectedPreferenceWitnesses
        require(witnesses.map { it.submissionVersionId to it.conditionId }.distinct().size == witnesses.size) {
            "Duplicate DSL rejection witness"
        }
        val results = envelope.claims.results.associateBy { it.submissionVersionId }
        require(witnesses.all { it.submissionVersionId in results }) { "Unknown DSL rejection input" }
        witnesses.forEach { witness ->
            val result = results.getValue(witness.submissionVersionId)
            require(result.conditions.none { it.id == witness.conditionId }) { "Accepted DSL claim cannot be a rejection witness" }
            val quarantine = result.quarantined.singleOrNull { it.conditionId == witness.conditionId }
            require(quarantine?.problem == IrProblem.SCOPE && quarantine.scope == null) { "DSL rejection witness mismatch" }
        }
        val byInput = witnesses.groupBy { it.submissionVersionId }
        return ScheduleIrV2Proposal(
            envelope.claims.semanticVersion,
            envelope.claims.results.map { result ->
                val inputWitnesses = byInput[result.submissionVersionId].orEmpty().associateBy { it.conditionId }
                val accepted =
                    result.conditions.map { condition ->
                        ProposedIrV2Condition(
                            condition.id,
                            condition.effect,
                            condition.interpretation,
                            condition.temporalResolution,
                            condition.scope,
                            condition.evidence.map { restoreEvidence(it, result.submissionVersionId, snapshot) },
                            condition.assertion,
                            condition.supersedes,
                            condition.alternativeGroup,
                            condition.alternative,
                        )
                    }
                // Preserve rejection order while replacing each proof's existing budget slot, never adding a condition.
                val rejected =
                    result.quarantined.mapNotNull { quarantine ->
                        inputWitnesses[quarantine.conditionId]?.let { witness ->
                            ProposedIrV2Condition(
                                witness.conditionId,
                                witness.effect,
                                witness.interpretation,
                                witness.temporalResolution,
                                witness.declaredScope,
                                witness.evidence.map { restoreEvidence(it, result.submissionVersionId, snapshot) },
                                witness.assertion,
                                witness.supersedes,
                                witness.alternativeGroup,
                                witness.alternative,
                            )
                        }
                    }
                ProposedIrV2Result(
                    result.submissionVersionId,
                    accepted + rejected,
                    result.quarantined.filter { it.conditionId !in inputWitnesses },
                )
            },
        )
    }

    private fun restoreEvidence(
        span: IrEvidenceSpan,
        input: UUID,
        snapshot: ScheduleEvidenceSnapshot,
    ): ProposedIrEvidence {
        val quote = extract(snapshot, input, span.sourceId, span.startCodePoint, span.endCodePoint)
        require(IrEvidenceBindings.sha256(quote) == span.contentSha256) { "DSL evidence digest mismatch" }
        return ProposedIrEvidence(span.sourceId, span.revision, span.startCodePoint, span.endCodePoint, quote)
    }

    private fun validatePrograms(
        proposed: List<ProposedParticipantScheduleProgram>,
        claims: ScheduleIrV2Envelope,
        snapshot: ScheduleEvidenceSnapshot,
        witnesses: List<RejectedPreferenceWitness>,
    ): List<ParticipantScheduleProgram> {
        val expected = claims.results.associateBy { it.submissionVersionId }
        require(proposed.size == expected.size && proposed.map { it.submissionVersionId }.toSet() == expected.keys) {
            "DSL program batch identity mismatch"
        }
        return proposed.map { participant ->
            val program = participant.program
            val claimResult = expected.getValue(participant.submissionVersionId)
            val preferences = witnesses.filter { it.submissionVersionId == participant.submissionVersionId }
            val localizedIds = preferences.map { it.conditionId }.toSet()
            val problems =
                participant.problems +
                    if (claimResult.quarantined.all { it.conditionId in localizedIds }) {
                        emptySet()
                    } else {
                        setOf(ScheduleDslProblem.REFERENCE)
                    }
            if (problems.isNotEmpty() || program == null) {
                ParticipantScheduleProgram(
                    participant.submissionVersionId,
                    null,
                    problems.ifEmpty { setOf(ScheduleDslProblem.SHAPE) } +
                        if (claimResult.quarantined.isEmpty()) emptySet() else setOf(ScheduleDslProblem.REFERENCE),
                )
            } else {
                try {
                    val verified = verifyProgram(program, claimResult, snapshot, preferences)
                    ParticipantScheduleProgram(participant.submissionVersionId, verified, emptySet())
                } catch (failure: ProgramFailure) {
                    ParticipantScheduleProgram(
                        participant.submissionVersionId,
                        null,
                        setOf(failure.problem) +
                            if (claimResult.quarantined.isEmpty()) emptySet() else setOf(ScheduleDslProblem.REFERENCE),
                    )
                }
            }
        }
    }

    private fun verifyProgram(
        program: ScheduleDslProgram<ProposedIrEvidence>,
        result: IrV2Result,
        snapshot: ScheduleEvidenceSnapshot,
        witnesses: List<RejectedPreferenceWitness>,
    ): ScheduleDslProgram<IrEvidenceSpan> {
        ensure(program.nodes.size <= 128, ScheduleDslProblem.COMPLEXITY)
        val localizedIds = witnesses.map { it.conditionId }.toSet()
        ensure(result.quarantined.all { it.conditionId in localizedIds }, ScheduleDslProblem.REFERENCE)
        val nodes = program.nodes.associateBy { it.id }
        ensure(nodes.size == program.nodes.size && nodes.keys.all { it.matches(TOKEN) }, ScheduleDslProblem.REFERENCE)
        // Rejected declarations exist only in this role/coverage view, never in accepted IR conditions.
        val declarations =
            result.conditions.map {
                ClaimDeclaration(it.id, it.effect, it.assertion, it.supersedes, it.alternativeGroup, it.alternative)
            } +
                witnesses.map {
                    ClaimDeclaration(it.conditionId, it.effect, it.assertion, it.supersedes, it.alternativeGroup, it.alternative)
                }
        val claims = declarations.associateBy { it.id }
        ensure(claims.size == declarations.size, ScheduleDslProblem.REFERENCE)
        program.nodes.forEach { node ->
            val count = node.children.size
            ensure(node.children.distinct().size == count, ScheduleDslProblem.SHAPE)
            when (node.operator) {
                ScheduleDslOperator.CLAIM_REF ->
                    ensure(
                        node.claimId != null && count == 0 && node.choiceGroup == null && node.branchLabels.isEmpty(),
                        ScheduleDslProblem.SHAPE,
                    )
                ScheduleDslOperator.ALL_OF, ScheduleDslOperator.ANY_OF -> ensure(count in 2..32, ScheduleDslProblem.SHAPE)
                ScheduleDslOperator.EXCEPT, ScheduleDslOperator.PREFER -> ensure(count == 2, ScheduleDslProblem.SHAPE)
                ScheduleDslOperator.CHOICE -> {
                    ensure(
                        count in 2..8 && node.branchLabels.size == count && node.branchLabels.distinct().size == count,
                        ScheduleDslProblem.SHAPE,
                    )
                    ensure(
                        node.choiceGroup?.matches(TOKEN) == true && node.branchLabels.all { it.matches(TOKEN) },
                        ScheduleDslProblem.SHAPE,
                    )
                }
            }
            if (node.operator != ScheduleDslOperator.CLAIM_REF) ensure(node.claimId == null, ScheduleDslProblem.SHAPE)
            if (node.operator != ScheduleDslOperator.CHOICE) {
                ensure(node.choiceGroup == null && node.branchLabels.isEmpty(), ScheduleDslProblem.SHAPE)
            }
            ensure(node.children.all { it in nodes }, ScheduleDslProblem.REFERENCE)
            if (node.operator == ScheduleDslOperator.CLAIM_REF) {
                ensure(node.claimId != null && node.claimId in claims, ScheduleDslProblem.REFERENCE)
            }
        }
        val roots =
            listOfNotNull(
                program.availabilityRoot?.let { it to ScheduleDslRole.ALLOW },
                program.exclusionRoot?.let { it to ScheduleDslRole.BLOCK },
                program.preferenceRoot?.let { it to ScheduleDslRole.PREFER },
            )
        val visiting = mutableSetOf<String>()
        val heights = mutableMapOf<String, Int>()

        fun height(id: String): Int {
            heights[id]?.let { return it }
            val node = nodes[id] ?: fail(ScheduleDslProblem.REFERENCE)
            ensure(visiting.add(id), ScheduleDslProblem.REFERENCE)
            val value = 1 + (node.children.maxOfOrNull(::height) ?: 0)
            visiting.remove(id)
            ensure(value <= 8, ScheduleDslProblem.COMPLEXITY)
            heights[id] = value
            return value
        }
        roots.forEach { height(it.first) }
        ensure(heights.keys == nodes.keys, ScheduleDslProblem.REFERENCE)
        val history = program.historyConditionIds
        ensure(history.distinct().size == history.size && history.all { it in claims }, ScheduleDslProblem.REFERENCE)
        val syntacticallySuperseded = declarations.flatMap { it.supersedes }.toSet()
        ensure(
            history.all { claims.getValue(it).assertion != IrAssertion.ASSERTED || it in syntacticallySuperseded },
            ScheduleDslProblem.REFERENCE,
        )
        val referenced = program.nodes.mapNotNull { it.claimId }.toSet()
        ensure(referenced + history == claims.keys, ScheduleDslProblem.REFERENCE)
        val roles = mutableMapOf<String, ScheduleDslRole>()
        val states = mutableMapOf<ValidationState, Set<Pair<String, String>>>()
        val encountered = mutableSetOf<ValidationState>()

        fun visit(
            id: String,
            role: ScheduleDslRole,
            choices: Map<String, String>,
        ): Set<Pair<String, String>> {
            val state = ValidationState(id, role, choices)
            states[state]?.let { return it }
            ensure(encountered.add(state) && encountered.size <= 2048, ScheduleDslProblem.COMPLEXITY)
            ensure(roles.putIfAbsent(id, role)?.let { it == role } != false, ScheduleDslProblem.ROLE)
            val node = nodes.getValue(id)
            val grouped = linkedSetOf<Pair<String, String>>()
            if (node.operator == ScheduleDslOperator.CLAIM_REF) {
                val claim = claims.getValue(requireNotNull(node.claimId))
                val accepted =
                    when (role) {
                        ScheduleDslRole.ALLOW -> claim.effect in setOf(IrEffect.AVAILABLE, IrEffect.UNCONSTRAINED)
                        ScheduleDslRole.BLOCK -> claim.effect in setOf(IrEffect.UNAVAILABLE, IrEffect.UNRESOLVED)
                        ScheduleDslRole.PREFER -> claim.effect == IrEffect.PREFERRED
                    }
                ensure(accepted, ScheduleDslProblem.ROLE)
                claim.alternativeGroup?.let { group ->
                    val label = requireNotNull(claim.alternative)
                    ensure(choices[group] == label, ScheduleDslProblem.REFERENCE)
                    grouped += group to label
                }
            } else if (node.operator == ScheduleDslOperator.CHOICE) {
                val group = requireNotNull(node.choiceGroup)
                val declared =
                    declarations
                        .filter { it.alternativeGroup == group }
                        .mapNotNull { it.alternative }
                        .toSet()
                ensure(declared == node.branchLabels.toSet(), ScheduleDslProblem.REFERENCE)
                node.children.zip(node.branchLabels).forEach { (child, label) ->
                    ensure(choices[group] == null || choices[group] == label, ScheduleDslProblem.REFERENCE)
                    val found = visit(child, role, choices + (group to label))
                    ensure((group to label) in found, ScheduleDslProblem.REFERENCE)
                    grouped += found
                }
            } else {
                if (node.operator == ScheduleDslOperator.PREFER) ensure(role == ScheduleDslRole.ALLOW, ScheduleDslProblem.ROLE)
                node.children.forEachIndexed { index, child ->
                    val childRole =
                        when {
                            node.operator == ScheduleDslOperator.PREFER && index == 1 -> ScheduleDslRole.PREFER
                            node.operator == ScheduleDslOperator.EXCEPT && index == 1 ->
                                if (role == ScheduleDslRole.BLOCK) ScheduleDslRole.ALLOW else ScheduleDslRole.BLOCK
                            else -> role
                        }
                    grouped += visit(child, childRole, choices)
                }
            }
            states[state] = grouped.toSet()
            return grouped
        }
        roots.forEach { (id, role) -> visit(id, role, emptyMap()) }
        val verifiedNodes =
            program.nodes.map { node ->
                val spans =
                    try {
                        require(node.evidence.size in 1..32)
                        node.evidence.map { verifyEvidence(it, result.submissionVersionId, snapshot) }
                    } catch (_: IllegalArgumentException) {
                        fail(ScheduleDslProblem.OPERATOR_EVIDENCE)
                    }
                ScheduleDslNode(
                    node.id,
                    node.operator,
                    node.claimId,
                    node.children.toList(),
                    node.choiceGroup,
                    node.branchLabels.toList(),
                    node.interpretation,
                    node.assertion,
                    spans,
                )
            }
        return ScheduleDslProgram(verifiedNodes, program.availabilityRoot, program.exclusionRoot, program.preferenceRoot, history.toList())
    }

    private fun verifyEvidence(
        evidence: ProposedIrEvidence,
        input: UUID,
        snapshot: ScheduleEvidenceSnapshot,
    ): IrEvidenceSpan {
        val source =
            snapshot.context.inputs
                .single { it.submissionVersionId == input }
                .sources
                .singleOrNull { it.sourceId == evidence.sourceId }
        require(source != null && source.revision == evidence.revision) { "Invalid DSL evidence source" }
        val quote = extract(snapshot, input, evidence.sourceId, evidence.startCodePoint, evidence.endCodePoint)
        require(quote == evidence.quote) { "Invalid DSL evidence quote" }
        return IrEvidenceSpan(
            evidence.sourceId,
            evidence.revision,
            evidence.startCodePoint,
            evidence.endCodePoint,
            IrEvidenceBindings.sha256(quote),
        )
    }

    private fun extract(
        snapshot: ScheduleEvidenceSnapshot,
        input: UUID,
        source: String,
        start: Int,
        end: Int,
    ): String {
        val text = requireNotNull(snapshot.sourceText(input, source)) { "Missing DSL source" }
        require(start >= 0 && end > start && end <= text.codePointCount(0, text.length)) { "Invalid DSL evidence span" }
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    }

    private data class ValidationState(
        val id: String,
        val role: ScheduleDslRole,
        val choices: Map<String, String>,
    )

    private data class ClaimDeclaration(
        val id: String,
        val effect: IrEffect,
        val assertion: IrAssertion,
        val supersedes: List<String>,
        val alternativeGroup: String?,
        val alternative: String?,
    )

    private class ProgramFailure(
        val problem: ScheduleDslProblem,
    ) : RuntimeException()

    private fun ensure(
        condition: Boolean,
        problem: ScheduleDslProblem,
    ) {
        if (!condition) fail(problem)
    }

    private fun fail(problem: ScheduleDslProblem): Nothing = throw ProgramFailure(problem)

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
