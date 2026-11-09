package com.meetme.server.submission.domain.dsl

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import java.util.UUID

const val SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION = "schedule-ir-product-5"
const val SCHEDULE_DSL_CONTEXT_MODE_REQUEST_CONTRACT = "schedule-dsl-request-4"
const val SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT = "schedule-dsl-response-4"
const val SCHEDULE_CONTEXT_MODE_POLICY_VERSION = "schedule-context-mode-1"

/** A hard per-input restriction, independent of the availability AST. EITHER is never an atomic permitted mode. */
data class ProposedScheduleModeRestriction(
    val submissionVersionId: UUID,
    val id: String,
    val scope: IrScope?,
    val permittedModes: Set<MeetingMode>,
    val modeInterpretation: IrInterpretation,
    val timeInterpretation: IrInterpretation,
    val temporalResolution: IrTemporalResolution,
    val evidence: List<ProposedIrEvidence>,
    val assertion: IrAssertion = IrAssertion.ASSERTED,
    val supersedes: List<String> = emptyList(),
    val alternativeGroup: String? = null,
    val alternative: String? = null,
)

/** Evidence is checked against frozen native sources; quotes are not retained in this replayable declaration. */
data class ScheduleModeRestriction(
    val submissionVersionId: UUID,
    val id: String,
    val scope: IrScope?,
    val permittedModes: Set<MeetingMode>,
    val modeInterpretation: IrInterpretation,
    val timeInterpretation: IrInterpretation,
    val temporalResolution: IrTemporalResolution,
    val evidence: List<IrEvidenceSpan>,
    val assertion: IrAssertion,
    val supersedes: List<String>,
    val alternativeGroup: String?,
    val alternative: String?,
)

/** A non-vocabulary derivation. It never rewrites the base declaration or implies participant confirmation. */
data class ScheduleDslModelContextApplication(
    val submissionVersionId: UUID,
    val conditionId: String,
    val declaredScope: IrScope,
    val resolvedScope: IrScope,
    val evidence: List<IrEvidenceSpan>,
    val derivation: ApprovedTimeTermDerivation = ApprovedTimeTermDerivation.MODEL_CONTEXT,
)

data class ScheduleDslContextModeEnvelope(
    val semanticVersion: String,
    val astVersion: String,
    val base: ScheduleDslApprovedTermsEnvelope,
    val calculationProjection: ScheduleDslEnvelope,
    val contextApplications: List<ScheduleDslModelContextApplication>,
    val modeRestrictions: List<ScheduleModeRestriction>,
)

class ScheduleDslContextModeValidator {
    fun validateDeclaration(
        base: ScheduleDslApprovedTermsEnvelope,
        modeRestrictions: List<ProposedScheduleModeRestriction>,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslContextModeEnvelope {
        require(snapshot.context.policyVersion == SCHEDULE_CONTEXT_MODE_POLICY_VERSION) { "Context-mode policy mismatch" }
        require(base.declaration.context == snapshot.context) { "Context-mode snapshot mismatch" }
        val validated = ScheduleDslApprovedTermsValidator().validateRestored(base, snapshot)
        val inputs =
            validated.declaration.claims.results
                .associateBy { it.submissionVersionId }
        require(modeRestrictions.all { it.submissionVersionId in inputs }) { "Unknown mode input" }
        require(modeRestrictions.map { it.submissionVersionId to it.id }.distinct().size == modeRestrictions.size) { "Duplicate mode id" }
        modeRestrictions.groupBy { it.submissionVersionId }.forEach { (input, restrictions) ->
            val claims = inputs.getValue(input)
            require(claims.conditions.size + claims.quarantined.size + restrictions.size <= 32) { "Condition budget exceeded" }
            val ids = restrictions.map { it.id }.toSet()
            val claimIds = (claims.conditions.map { it.id } + claims.quarantined.mapNotNull { it.conditionId }).toSet()
            restrictions.forEach { restriction ->
                require(restriction.id.matches(token) && restriction.id !in claimIds) { "Invalid mode id" }
                require(restriction.permittedModes.isNotEmpty() && restriction.permittedModes.all { it != MeetingMode.EITHER }) {
                    "Mode restrictions require atomic modes"
                }
                val scope = restriction.scope
                require(scope == null || scope.dimension == IrDimension.TIME) { "Mode restriction requires time scope" }
                require(scope?.start == null || scope.end == null || scope.start < scope.end) { "Invalid mode interval" }
                if (restriction.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL) {
                    require(restriction.timeInterpretation == IrInterpretation.EXPLICIT && scope?.start != null && scope.end != null) {
                        "Model proposals must preserve non-precise time declarations"
                    }
                }
                require(restriction.evidence.size in 1..32) { "Missing mode evidence" }
                require(restriction.supersedes.distinct() == restriction.supersedes) { "Duplicate mode correction" }
                require(restriction.supersedes.all { it in ids && it != restriction.id }) { "Invalid mode correction" }
                require((restriction.alternativeGroup == null) == (restriction.alternative == null)) { "Incomplete mode alternative" }
                require(restriction.alternativeGroup == null || restriction.alternativeGroup.matches(token)) { "Invalid mode alternative" }
                require(restriction.alternative == null || restriction.alternative.matches(token)) { "Invalid mode alternative" }
            }
            val byId = restrictions.associateBy { it.id }
            val complete = mutableSetOf<String>()
            val visiting = mutableSetOf<String>()

            fun visit(id: String) {
                if (id in complete) return
                require(visiting.add(id)) { "Cyclic mode correction" }
                byId.getValue(id).supersedes.forEach(::visit)
                visiting.remove(id)
                complete += id
            }
            ids.forEach(::visit)
        }
        val bound =
            modeRestrictions.map { restriction ->
                ScheduleModeRestriction(
                    restriction.submissionVersionId,
                    restriction.id,
                    restriction.scope,
                    restriction.permittedModes.toSet(),
                    restriction.modeInterpretation,
                    restriction.timeInterpretation,
                    restriction.temporalResolution,
                    restriction.evidence.map { verify(it, restriction.submissionVersionId, snapshot) },
                    restriction.assertion,
                    restriction.supersedes.toList(),
                    restriction.alternativeGroup,
                    restriction.alternative,
                )
            }
        val applications = mutableListOf<ScheduleDslModelContextApplication>()
        val termKeys = validated.termApplications.map { it.submissionVersionId to it.conditionId }.toSet()
        val original = inputs.mapValues { (_, result) -> result.conditions.associateBy { it.id } }
        val projection =
            validated.calculationProjection.copy(
                claims =
                    validated.calculationProjection.claims.copy(
                        results =
                            validated.calculationProjection.claims.results.map { result ->
                                result.copy(
                                    conditions =
                                        result.conditions.map { condition ->
                                            val declaration = original.getValue(result.submissionVersionId).getValue(condition.id)
                                            val scope = declaration.scope
                                            if ((result.submissionVersionId to declaration.id) !in termKeys &&
                                                declaration.interpretation == IrInterpretation.INFERRED &&
                                                declaration.temporalResolution == IrTemporalResolution.NON_PRECISE &&
                                                declaration.effect != IrEffect.UNCONSTRAINED &&
                                                completeTime(scope)
                                            ) {
                                                applications +=
                                                    ScheduleDslModelContextApplication(
                                                        result.submissionVersionId,
                                                        declaration.id,
                                                        requireNotNull(scope),
                                                        scope,
                                                        declaration.evidence,
                                                    )
                                                condition.copy(temporalResolution = IrTemporalResolution.PRECISE_INTERVAL)
                                            } else {
                                                condition
                                            }
                                        },
                                )
                            },
                    ),
            )
        bound
            .filter {
                it.timeInterpretation == IrInterpretation.INFERRED &&
                    it.temporalResolution == IrTemporalResolution.NON_PRECISE &&
                    completeTime(it.scope)
            }.forEach {
                applications +=
                    ScheduleDslModelContextApplication(
                        it.submissionVersionId,
                        it.id,
                        requireNotNull(it.scope),
                        it.scope,
                        it.evidence,
                    )
            }
        return ScheduleDslContextModeEnvelope(
            SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            validated,
            projection,
            applications,
            bound,
        )
    }

    fun validateRestored(
        envelope: ScheduleDslContextModeEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslContextModeEnvelope {
        require(envelope.semanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION && envelope.astVersion == SCHEDULE_AST_VERSION)
        val proposed =
            envelope.modeRestrictions.map { restriction ->
                ProposedScheduleModeRestriction(
                    restriction.submissionVersionId,
                    restriction.id,
                    restriction.scope,
                    restriction.permittedModes,
                    restriction.modeInterpretation,
                    restriction.timeInterpretation,
                    restriction.temporalResolution,
                    restriction.evidence.map { span ->
                        val quote =
                            extract(snapshot, restriction.submissionVersionId, span.sourceId, span.startCodePoint, span.endCodePoint)
                        require(IrEvidenceBindings.sha256(quote) == span.contentSha256) { "Mode evidence digest mismatch" }
                        ProposedIrEvidence(span.sourceId, span.revision, span.startCodePoint, span.endCodePoint, quote)
                    },
                    restriction.assertion,
                    restriction.supersedes,
                    restriction.alternativeGroup,
                    restriction.alternative,
                )
            }
        val rederived = validateDeclaration(envelope.base, proposed, snapshot)
        require(rederived == envelope) { "Context-mode derivation mismatch" }
        return rederived
    }

    private fun completeTime(scope: IrScope?): Boolean =
        scope?.dimension == IrDimension.TIME && scope.start != null && scope.end != null && scope.start < scope.end

    private fun verify(
        evidence: ProposedIrEvidence,
        input: UUID,
        snapshot: ScheduleEvidenceSnapshot,
    ): IrEvidenceSpan {
        val source =
            snapshot.context.inputs
                .singleOrNull { it.submissionVersionId == input }
                ?.sources
                ?.singleOrNull { it.sourceId == evidence.sourceId }
        require(source != null && source.revision == evidence.revision) { "Invalid mode evidence source" }
        val quote = extract(snapshot, input, evidence.sourceId, evidence.startCodePoint, evidence.endCodePoint)
        require(quote == evidence.quote) { "Invalid mode evidence quote" }
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
        val text = requireNotNull(snapshot.sourceText(input, source)) { "Missing native mode evidence" }
        require(start >= 0 && end > start && end <= text.codePointCount(0, text.length)) { "Invalid mode evidence span" }
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    }

    private companion object {
        val token = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
