package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy
import java.time.LocalDate
import java.util.UUID

const val SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION = "schedule-ir-product-4"
const val SCHEDULE_DSL_APPROVED_TERMS_REQUEST_CONTRACT = "schedule-dsl-request-3"
const val SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT = "schedule-dsl-response-3"

data class ProposedApprovedTimeTermRef(
    val submissionVersionId: UUID,
    val conditionId: String,
    val term: ApprovedKoreanTimeTerm,
    val localDate: LocalDate,
    val termEvidence: ProposedIrEvidence,
    val dateEvidence: List<ProposedIrEvidence>,
)

data class ScheduleDslApprovedTermsProposal(
    val semanticVersion: String,
    val astVersion: String,
    val vocabularyVersion: String,
    val base: ScheduleDslProposal,
    val termRefs: List<ProposedApprovedTimeTermRef>,
)

enum class ApprovedTimeTermDerivation { SERVER_VOCABULARY, MODEL_CONTEXT }

data class ApprovedTimeTermApplication(
    val submissionVersionId: UUID,
    val conditionId: String,
    val term: ApprovedKoreanTimeTerm,
    val localDate: LocalDate,
    val termEvidence: IrEvidenceSpan,
    val dateEvidence: List<IrEvidenceSpan>,
    val declaredScope: IrScope?,
    val resolvedScope: IrScope,
    val derivation: ApprovedTimeTermDerivation = ApprovedTimeTermDerivation.SERVER_VOCABULARY,
)

data class ScheduleDslApprovedTermsEnvelope(
    val semanticVersion: String,
    val astVersion: String,
    val vocabularyVersion: String,
    val declaration: ScheduleDslEnvelope,
    val calculationProjection: ScheduleDslEnvelope,
    val termApplications: List<ApprovedTimeTermApplication>,
)

class ScheduleDslApprovedTermsValidator {
    fun validate(
        proposal: ScheduleDslApprovedTermsProposal,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslApprovedTermsEnvelope {
        checkIdentity(proposal.semanticVersion, proposal.astVersion, proposal.vocabularyVersion)
        return validateDeclaration(ScheduleDslValidator().validate(proposal.base, snapshot), proposal.termRefs, snapshot)
    }

    fun validateRestored(
        envelope: ScheduleDslApprovedTermsEnvelope,
        snapshot: ScheduleEvidenceSnapshot,
    ): ScheduleDslApprovedTermsEnvelope {
        checkIdentity(envelope.semanticVersion, envelope.astVersion, envelope.vocabularyVersion)
        val refs =
            envelope.termApplications.map { application ->
                ProposedApprovedTimeTermRef(
                    application.submissionVersionId,
                    application.conditionId,
                    application.term,
                    application.localDate,
                    restoreEvidence(application.termEvidence, application.submissionVersionId, snapshot),
                    application.dateEvidence.map { restoreEvidence(it, application.submissionVersionId, snapshot) },
                )
            }
        val recomputed = validateDeclaration(envelope.declaration, refs, snapshot)
        require(recomputed == envelope) { "Invalid stored approved term derivation" }
        return recomputed
    }

    /** The explicit response3 adapter may supply an already validated declaration, never a numeric fallback. */
    internal fun validateDeclaration(
        declaration: ScheduleDslEnvelope,
        refs: List<ProposedApprovedTimeTermRef>,
        snapshot: ScheduleEvidenceSnapshot,
        rejectedInputs: Set<UUID> = emptySet(),
    ): ScheduleDslApprovedTermsEnvelope {
        ScheduleDslValidator().validateRestored(declaration, snapshot)
        val inputs = declaration.claims.results.associateBy { it.submissionVersionId }
        require(rejectedInputs.all { it in inputs }) { "Unknown rejected term input" }
        require(refs.map { it.submissionVersionId to it.conditionId }.distinct().size == refs.size) { "Duplicate approved term binding" }
        refs.forEach { ref ->
            val input = requireNotNull(inputs[ref.submissionVersionId]) { "Unknown approved term input" }
            require(input.conditions.any { it.id == ref.conditionId } || input.quarantined.any { it.conditionId == ref.conditionId }) {
                "Unknown approved term condition"
            }
        }
        val invalid = rejectedInputs.toMutableSet()
        val applications = mutableListOf<ApprovedTimeTermApplication>()
        refs.forEach { ref ->
            val claim = inputs.getValue(ref.submissionVersionId).conditions.singleOrNull { it.id == ref.conditionId }
            try {
                require(claim != null) { "Rejected approved term condition" }
                require(claim.effect != IrEffect.UNCONSTRAINED) {
                    "Contradictory approved term declaration"
                }
                val declaredScope = claim.scope
                require(ref.dateEvidence.size in 1..32) { "Missing approved term date evidence" }
                val termEvidence = verifyEvidence(ref.termEvidence, ref.submissionVersionId, snapshot)
                require(ref.termEvidence.quote in ApprovedKoreanTimeTermPolicy.lexemes(ref.term)) { "Approved lexeme mismatch" }
                requireWholeApprovedLexeme(ref, snapshot)
                val dates = ref.dateEvidence.map { verifyEvidence(it, ref.submissionVersionId, snapshot) }
                (dates + termEvidence).forEach { span ->
                    require(
                        claim.evidence.any { parent ->
                            parent.sourceId == span.sourceId &&
                                parent.revision == span.revision &&
                                parent.startCodePoint <= span.startCodePoint &&
                                parent.endCodePoint >= span.endCodePoint
                        },
                    ) { "Approved term evidence is outside its condition" }
                }
                val numericScope =
                    declaredScope?.takeIf {
                        it.dimension == IrDimension.TIME && it.start != null && it.end != null
                    }
                // Literal numeric declarations win; contextual proposals retain a separate derivation.
                if (claim.temporalResolution == IrTemporalResolution.PRECISE_INTERVAL) {
                    require(claim.interpretation == IrInterpretation.EXPLICIT && numericScope != null) {
                        "Contextual proposals must preserve their non-precise declaration"
                    }
                    return@forEach
                }
                val modelContext = claim.interpretation == IrInterpretation.INFERRED && numericScope != null
                require(
                    modelContext ||
                        declaredScope == null ||
                        (declaredScope.dimension == IrDimension.TIME && declaredScope.start == null && declaredScope.end == null),
                ) { "Invalid approved term proposal scope" }
                applications +=
                    ApprovedTimeTermApplication(
                        ref.submissionVersionId,
                        ref.conditionId,
                        ref.term,
                        ref.localDate,
                        termEvidence,
                        dates,
                        declaredScope,
                        if (modelContext) requireNotNull(numericScope) else ApprovedKoreanTimeTermPolicy.resolve(ref.term, ref.localDate),
                        if (modelContext) ApprovedTimeTermDerivation.MODEL_CONTEXT else ApprovedTimeTermDerivation.SERVER_VOCABULARY,
                    )
            } catch (_: IllegalArgumentException) {
                invalid += ref.submissionVersionId
            } catch (_: java.time.DateTimeException) {
                invalid += ref.submissionVersionId
            }
        }
        val original =
            declaration.copy(
                programs =
                    declaration.programs.map { participant ->
                        if (participant.submissionVersionId !in invalid) {
                            participant
                        } else {
                            participant.copy(
                                program = null,
                                problems = participant.problems + ScheduleDslProblem.REFERENCE,
                            )
                        }
                    },
                rejectedPreferenceWitnesses = declaration.rejectedPreferenceWitnesses.filter { it.submissionVersionId !in invalid },
            )
        ScheduleDslValidator().validateRestored(original, snapshot)
        val validPrograms =
            original.programs
                .filter { it.program != null && it.problems.isEmpty() }
                .map { it.submissionVersionId }
                .toSet()
        val applied =
            applications
                .filter { it.submissionVersionId in validPrograms }
                .sortedWith(compareBy({ it.submissionVersionId }, { it.conditionId }))
        val byCondition = applied.associateBy { it.submissionVersionId to it.conditionId }
        val projected =
            original.copy(
                claims =
                    original.claims.copy(
                        results =
                            original.claims.results.map { result ->
                                result.copy(
                                    conditions =
                                        result.conditions.map { condition ->
                                            val application = byCondition[result.submissionVersionId to condition.id]
                                            if (application == null) {
                                                condition
                                            } else {
                                                condition.copy(
                                                    temporalResolution = IrTemporalResolution.PRECISE_INTERVAL,
                                                    scope = application.resolvedScope,
                                                )
                                            }
                                        },
                                )
                            },
                    ),
            )
        ScheduleDslValidator().validateRestored(projected, snapshot)
        return ScheduleDslApprovedTermsEnvelope(
            SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION,
            SCHEDULE_AST_VERSION,
            APPROVED_KOREAN_TIME_TERM_VERSION,
            original,
            projected,
            applied,
        )
    }

    private fun checkIdentity(
        semantic: String,
        ast: String,
        vocabulary: String,
    ) {
        require(
            semantic == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION &&
                ast == SCHEDULE_AST_VERSION &&
                vocabulary == APPROVED_KOREAN_TIME_TERM_VERSION,
        ) { "Unsupported approved term contract" }
    }

    private fun requireWholeApprovedLexeme(
        ref: ProposedApprovedTimeTermRef,
        snapshot: ScheduleEvidenceSnapshot,
    ) {
        val evidence = ref.termEvidence
        val source = requireNotNull(snapshot.sourceText(ref.submissionVersionId, evidence.sourceId))
        val start = source.offsetByCodePoints(0, evidence.startCodePoint)
        val quote = evidence.quote
        for (longer in ApprovedKoreanTimeTerm.entries.flatMap(ApprovedKoreanTimeTermPolicy::lexemes)) {
            if (longer.length <= quote.length) continue
            var offset = longer.indexOf(quote)
            while (offset >= 0) {
                val candidateStart = start - offset
                require(candidateStart < 0 || !source.startsWith(longer, candidateStart)) { "Partial approved lexeme" }
                offset = longer.indexOf(quote, offset + 1)
            }
        }
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
        require(source != null && source.revision == evidence.revision) { "Invalid approved term evidence source" }
        val quote = extract(snapshot, input, evidence.sourceId, evidence.startCodePoint, evidence.endCodePoint)
        require(quote == evidence.quote) { "Invalid approved term evidence quote" }
        return IrEvidenceSpan(
            evidence.sourceId,
            evidence.revision,
            evidence.startCodePoint,
            evidence.endCodePoint,
            IrEvidenceBindings.sha256(quote),
        )
    }

    private fun restoreEvidence(
        span: IrEvidenceSpan,
        input: UUID,
        snapshot: ScheduleEvidenceSnapshot,
    ): ProposedIrEvidence {
        val quote = extract(snapshot, input, span.sourceId, span.startCodePoint, span.endCodePoint)
        require(IrEvidenceBindings.sha256(quote) == span.contentSha256) { "Approved term evidence digest mismatch" }
        return ProposedIrEvidence(span.sourceId, span.revision, span.startCodePoint, span.endCodePoint, quote)
    }

    private fun extract(
        snapshot: ScheduleEvidenceSnapshot,
        input: UUID,
        source: String,
        start: Int,
        end: Int,
    ): String {
        val text = requireNotNull(snapshot.sourceText(input, source)) { "Missing approved term evidence source" }
        require(start >= 0 && end > start && end <= text.codePointCount(0, text.length)) { "Invalid approved term evidence span" }
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    }
}
