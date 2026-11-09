package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.util.UUID

data class ScheduleDslLocatedEvidence(
    val submissionVersionId: UUID,
    val evidence: LocatedLiteralEvidence,
)

/** Original declarations and deterministic witnesses remain separate from the calculation view. */
data class ScheduleDslStructuralEnvelope(
    val semanticVersion: String,
    val astVersion: String,
    val context: ScheduleIrContext,
    val base: ScheduleDslContextModeEnvelope,
    val locatedEvidence: List<ScheduleDslLocatedEvidence>,
    val directionalApplications: List<DirectionalTimeApplication>,
    val calculationProjection: ScheduleDslEnvelope,
)

class ScheduleDslStructuralValidator {
    fun validateDeclaration(
        base: ScheduleDslContextModeEnvelope,
        locatedEvidence: List<ScheduleDslLocatedEvidence>,
        directionalApplications: List<DirectionalTimeApplication>,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslStructuralEnvelope {
        require(base.base.declaration.context == snapshot.context) { "Structural native context mismatch" }
        val checked = ScheduleDslContextModeValidator().validateRestored(base, snapshot)
        require(locatedEvidence.distinct().size == locatedEvidence.size) { "Duplicate structural evidence witness" }
        locatedEvidence.forEach { ScheduleDslStructuralNormalizer.restoreEvidence(it.submissionVersionId, it.evidence, snapshot) }
        val required = mutableSetOf<ScheduleDslLocatedEvidence>()

        fun evidence(
            input: UUID,
            spans: List<IrEvidenceSpan>,
        ) {
            spans.forEach { required += ScheduleDslLocatedEvidence(input, LocatedLiteralEvidence(it)) }
        }

        fun collect(view: ScheduleDslEnvelope) {
            view.claims.results.forEach { result ->
                result.conditions.forEach { evidence(result.submissionVersionId, it.evidence) }
            }
            view.programs.forEach { result ->
                result.program
                    ?.nodes
                    .orEmpty()
                    .forEach { evidence(result.submissionVersionId, it.evidence) }
            }
            view.rejectedPreferenceWitnesses.forEach { evidence(it.submissionVersionId, it.evidence) }
        }
        listOf(checked.base.declaration, checked.base.calculationProjection, checked.calculationProjection).forEach(::collect)
        checked.contextApplications.forEach { evidence(it.submissionVersionId, it.evidence) }
        checked.base.termApplications.forEach {
            evidence(it.submissionVersionId, it.dateEvidence + it.termEvidence)
        }
        checked.modeRestrictions.forEach { evidence(it.submissionVersionId, it.evidence) }
        val applications = directionalApplications.associateBy { it.submissionVersionId to it.declaration.conditionId }
        require(applications.size == directionalApplications.size) { "Duplicate directional association" }
        directionalApplications.forEach { application ->
            ScheduleDslStructuralNormalizer.restoreTime(application.submissionVersionId, application, snapshot)
            val declaration = application.declaration
            val condition =
                checked.base.declaration.claims.results
                    .single { it.submissionVersionId == application.submissionVersionId }
                    .conditions
                    .single { it.id == declaration.conditionId }
            require(condition.scope == null && condition.temporalResolution == IrTemporalResolution.NON_PRECISE) {
                "Directional declarations require an incomplete original interval"
            }
            require(condition.effect in setOf(IrEffect.AVAILABLE, IrEffect.PREFERRED, IrEffect.UNAVAILABLE)) {
                "An unresolved meaning cannot acquire a directional interval"
            }
            require(
                condition.interpretation == declaration.interpretation && condition.temporalResolution == declaration.temporalResolution,
            )
            if (declaration.direction == TemporalDirection.WHOLE_DAY) {
                require(declaration.dateEvidence.map { it.span } == condition.evidence) {
                    "Calendar-day derivation must retain its complete condition evidence"
                }
            }
            val spans = declaration.dateEvidence + declaration.clockEvidence
            spans.forEach { located ->
                require(
                    condition.evidence.any { parent ->
                        parent.sourceId == located.span.sourceId &&
                            parent.revision == located.span.revision &&
                            parent.startCodePoint <= located.span.startCodePoint &&
                            parent.endCodePoint >= located.span.endCodePoint
                    },
                ) { "Directional evidence lies outside its original condition" }
            }
            required += spans.map { ScheduleDslLocatedEvidence(application.submissionVersionId, it) }
        }
        require(locatedEvidence.containsAll(required)) { "Missing structural evidence witness" }
        val projection =
            checked.calculationProjection.copy(
                claims =
                    checked.calculationProjection.claims.copy(
                        results =
                            checked.calculationProjection.claims.results.map { result ->
                                result.copy(
                                    conditions =
                                        result.conditions.map { condition ->
                                            applications[result.submissionVersionId to condition.id]?.let { application ->
                                                condition.copy(
                                                    scope = application.resolvedScope,
                                                    temporalResolution = IrTemporalResolution.PRECISE_INTERVAL,
                                                )
                                            } ?: condition
                                        },
                                )
                            },
                    ),
            )
        return ScheduleDslStructuralEnvelope(
            SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            snapshot.context,
            checked,
            locatedEvidence.toList(),
            directionalApplications.toList(),
            projection,
        )
    }

    fun validateRestored(
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslStructuralEnvelope {
        require(envelope.semanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION && envelope.astVersion == SCHEDULE_AST_VERSION)
        require(envelope.context == snapshot.context && envelope.base.base.declaration.context == envelope.context) {
            "Stored structural context mismatch"
        }
        val checked = validateDeclaration(envelope.base, envelope.locatedEvidence, envelope.directionalApplications, snapshot)
        require(checked == envelope) { "Stored structural declaration or derivation mismatch" }
        return checked
    }
}
