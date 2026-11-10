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
    val placeConstraints: List<ScheduleDslPlaceConstraint> = emptyList(),
    val placeConstraintsVersion: String? = null,
    val evidenceCatalogVersion: String? = null,
    val weeklyApplications: List<WeeklyScheduleApplication> = emptyList(),
    val weeklyRulesVersion: String? = null,
)

class ScheduleDslStructuralValidator {
    fun validateDeclaration(
        base: ScheduleDslContextModeEnvelope,
        locatedEvidence: List<ScheduleDslLocatedEvidence>,
        directionalApplications: List<DirectionalTimeApplication>,
        snapshot: ScheduleEvidenceSnapshot,
        placeConstraints: List<ScheduleDslPlaceConstraint> = emptyList(),
        placeConstraintsVersion: String? = null,
        evidenceCatalogVersion: String? = null,
        weeklyApplications: List<WeeklyScheduleApplication> = emptyList(),
        weeklyRulesVersion: String? = null,
    ): ScheduleDslStructuralEnvelope {
        require(evidenceCatalogVersion == null || evidenceCatalogVersion == ServerEvidenceCatalog.VERSION) {
            "Unsupported evidence catalog version"
        }
        require(weeklyRulesVersion == WeeklyScheduleRules.VERSION || weeklyRulesVersion == null && weeklyApplications.isEmpty()) {
            "Unsupported weekly rules version"
        }
        require(weeklyRulesVersion == null || evidenceCatalogVersion == ServerEvidenceCatalog.VERSION) { "Weekly native catalog required" }

        fun weeklyProofs(app: WeeklyScheduleApplication) =
            app.rule.weekdayEvidence + app.rule.filter.evidence +
                app.rule.time.clockEvidence + listOfNotNull(app.rule.time.termEvidence)
        val allProofs =
            locatedEvidence.map { it.evidence } +
                directionalApplications.flatMap { it.declaration.dateEvidence + it.declaration.clockEvidence } +
                placeConstraints.flatMap { listOfNotNull(it.evidence, it.placeEvidence) } +
                weeklyApplications.flatMap(::weeklyProofs)
        require(
            allProofs.all {
                (it.derivation == EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE) == (evidenceCatalogVersion != null)
            },
        ) { "Mixed catalog and legacy provenance" }
        require(base.base.declaration.context == snapshot.context) { "Structural native context mismatch" }
        val checked = ScheduleDslContextModeValidator(allowStructuralUnionPolicy = true).validateRestored(base, snapshot)
        require(locatedEvidence.distinct().size == locatedEvidence.size) { "Duplicate structural evidence witness" }
        val serverCatalog = if (evidenceCatalogVersion != null) ServerEvidenceCatalog.create(snapshot) else null
        locatedEvidence.forEach {
            ScheduleDslStructuralNormalizer.restoreEvidence(
                it.submissionVersionId,
                it.evidence,
                snapshot,
                serverCatalog,
            )
        }
        val scoped = locatedEvidence.filter { it.evidence.derivation == EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE }

        fun owns(
            term: ApprovedTimeTermApplication,
            located: ScheduleDslLocatedEvidence,
        ): Boolean {
            if (term.submissionVersionId != located.submissionVersionId || term.termEvidence != located.evidence.span) return false
            val condition =
                checked.base.declaration.claims.results
                    .single { it.submissionVersionId == term.submissionVersionId }
                    .conditions
                    .single { it.id == term.conditionId }
            return located.evidence.contextSpan in condition.evidence + term.dateEvidence
        }
        scoped.forEach { located ->
            require(checked.base.termApplications.any { owns(it, located) }) {
                "Scoped term context is not declared by its owning condition"
            }
        }
        val required = mutableSetOf<ScheduleDslLocatedEvidence>()

        fun evidence(
            input: UUID,
            spans: List<IrEvidenceSpan>,
            conditionId: String? = null,
        ) {
            spans.forEach { span ->
                val scopedForSpan = scoped.filter { it.submissionVersionId == input && it.evidence.span == span }
                if (scopedForSpan.isNotEmpty()) {
                    require(
                        conditionId != null &&
                            checked.base.termApplications.any { term ->
                                term.submissionVersionId == input &&
                                    term.conditionId == conditionId &&
                                    scopedForSpan.any { owns(term, it) }
                            },
                    ) { "A scoped term witness cannot authorize another condition or proof role" }
                }
                required += ScheduleDslLocatedEvidence(input, LocatedLiteralEvidence(span))
            }
        }

        fun collect(view: ScheduleDslEnvelope) {
            view.claims.results.forEach { result ->
                result.conditions.forEach { evidence(result.submissionVersionId, it.evidence, it.id) }
            }
            view.programs.forEach { result ->
                result.program
                    ?.nodes
                    .orEmpty()
                    .forEach { evidence(result.submissionVersionId, it.evidence) }
            }
            view.rejectedPreferenceWitnesses.forEach { evidence(it.submissionVersionId, it.evidence, it.conditionId) }
        }
        listOf(checked.base.declaration, checked.base.calculationProjection, checked.calculationProjection).forEach(::collect)
        checked.contextApplications.forEach { evidence(it.submissionVersionId, it.evidence, it.conditionId) }
        checked.base.termApplications.forEach {
            evidence(it.submissionVersionId, it.dateEvidence)
            evidence(it.submissionVersionId, listOf(it.termEvidence), it.conditionId)
        }
        checked.modeRestrictions.forEach { evidence(it.submissionVersionId, it.evidence) }
        val applications = directionalApplications.associateBy { it.submissionVersionId to it.declaration.conditionId }
        require(applications.size == directionalApplications.size) { "Duplicate directional association" }
        directionalApplications.forEach { application ->
            require(
                (application.declaration.dateEvidence + application.declaration.clockEvidence)
                    .all {
                        it.derivation in
                            setOf(EvidenceLocationDerivation.SERVER_LITERAL_QUOTE, EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE) &&
                            it.contextSpan == null
                    },
            ) { "Directional evidence cannot reuse scoped term authority" }
            ScheduleDslStructuralNormalizer.restoreTime(application.submissionVersionId, application, snapshot, serverCatalog)
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
        require(weeklyApplications.map { it.inputId to it.conditionId }.distinct().size == weeklyApplications.size) {
            "Duplicate weekly association"
        }
        weeklyApplications.groupBy { it.inputId }.values.forEach { apps ->
            require(apps.size <= 32 && apps.sumOf { it.occurrences.size } <= WeeklyScheduleRules.MAX_WINDOWS) { "Weekly window limit" }
        }
        weeklyApplications.forEach { app ->
            WeeklyScheduleRules.restore(app, snapshot)
            val condition =
                checked.base.declaration.claims.results
                    .single { it.submissionVersionId == app.inputId }
                    .conditions
                    .single { it.id == app.conditionId }
            require(condition.scope == null && condition.temporalResolution == IrTemporalResolution.NON_PRECISE) {
                "Weekly original declaration must retain unknown numeric scope"
            }
            require(condition.effect != IrEffect.UNCONSTRAINED) { "Weekly rules are scoped declarations" }
            if (condition.effect == IrEffect.UNRESOLVED ||
                condition.interpretation == com.meetme.server.submission.domain.ir.IrInterpretation.UNRESOLVED
            ) {
                require(!app.resolved) { "Unresolved weekly meaning cannot acquire windows" }
            }
            require(
                directionalApplications.none { it.submissionVersionId == app.inputId && it.declaration.conditionId == app.conditionId } &&
                    checked.contextApplications.none { it.submissionVersionId == app.inputId && it.conditionId == app.conditionId } &&
                    checked.base.termApplications.none { it.submissionVersionId == app.inputId && it.conditionId == app.conditionId },
            ) {
                "Mixed weekly and single date authority"
            }
            weeklyProofs(app).forEach { proof ->
                require(
                    condition.evidence.any { parent ->
                        parent.sourceId == proof.span.sourceId &&
                            parent.revision == proof.span.revision &&
                            parent.startCodePoint <= proof.span.startCodePoint &&
                            parent.endCodePoint >= proof.span.endCodePoint
                    },
                ) {
                    "Weekly evidence lies outside its owning condition"
                }
                required += ScheduleDslLocatedEvidence(app.inputId, proof)
            }
        }
        val recordedSpans = locatedEvidence.map { it.submissionVersionId to it.evidence.span }.toSet()
        require(required.all { (it.submissionVersionId to it.evidence.span) in recordedSpans }) { "Missing structural evidence witness" }
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
        require(
            placeConstraintsVersion == SCHEDULE_PLACE_CONSTRAINT_VERSION ||
                (placeConstraintsVersion == null && placeConstraints.isEmpty()),
        ) { "Unsupported place declaration version" }
        val result =
            ScheduleDslStructuralEnvelope(
                SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                snapshot.context,
                checked,
                locatedEvidence.toList(),
                directionalApplications.toList(),
                projection,
                placeConstraints.toList(),
                placeConstraintsVersion,
                evidenceCatalogVersion,
                weeklyApplications.toList(),
                weeklyRulesVersion,
            )
        ScheduleDslPlaceConstraintPolicy.validate(placeConstraints, result, snapshot, serverCatalog)
        return result
    }

    fun validateRestored(
        envelope: ScheduleDslStructuralEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslStructuralEnvelope {
        require(envelope.semanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION && envelope.astVersion == SCHEDULE_AST_VERSION)
        require(envelope.context == snapshot.context && envelope.base.base.declaration.context == envelope.context) {
            "Stored structural context mismatch"
        }
        val checked =
            validateDeclaration(
                envelope.base,
                envelope.locatedEvidence,
                envelope.directionalApplications,
                snapshot,
                envelope.placeConstraints,
                envelope.placeConstraintsVersion,
                envelope.evidenceCatalogVersion,
                envelope.weeklyApplications,
                envelope.weeklyRulesVersion,
            )
        require(checked == envelope) { "Stored structural declaration or derivation mismatch" }
        return checked
    }
}
