package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

const val SCHEDULE_DSL_STRUCTURAL_REQUEST_CONTRACT = "schedule-dsl-request-5"
const val SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT = "schedule-dsl-response-5"
const val SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION = "schedule-ir-product-6"

/** Transient literal locator. Position and digest are derived from a frozen native source. */
data class ProposedLiteralQuoteLocator(
    val sourceId: String,
    val revision: Int,
    val quote: String,
) {
    override fun toString(): String = "ProposedLiteralQuoteLocator(sourceId=$sourceId, revision=$revision)"
}

enum class EvidenceLocationDerivation { SERVER_LITERAL_QUOTE, SERVER_CONDITION_TERM_QUOTE, SERVER_CATALOG_REFERENCE }

data class LocatedLiteralEvidence(
    val span: IrEvidenceSpan,
    val derivation: EvidenceLocationDerivation = EvidenceLocationDerivation.SERVER_LITERAL_QUOTE,
    val contextSpan: IrEvidenceSpan? = null,
    val catalogId: String? = null,
)

enum class TemporalDirection { AFTER, BEFORE, WHOLE_DAY }

/** A declared date and optional clock. WHOLE_DAY has no clock assertion; both bounds are server-derived. */
data class DirectionalTimeDeclaration(
    val conditionId: String,
    val direction: TemporalDirection,
    val localDate: LocalDate,
    val clock: LocalTime?,
    val dateEvidence: List<LocatedLiteralEvidence>,
    val clockEvidence: List<LocatedLiteralEvidence>,
    val interpretation: IrInterpretation = IrInterpretation.EXPLICIT,
    val temporalResolution: IrTemporalResolution = IrTemporalResolution.NON_PRECISE,
)

enum class StructuralTimeDerivation { SERVER_DATE_SCOPE, SERVER_CALENDAR_DAY }

/** Nonempty civil interval plus optional search support. Null support means outside the search, not unknown. */
data class DirectionalTimeApplication(
    val submissionVersionId: UUID,
    val declaration: DirectionalTimeDeclaration,
    val resolvedScope: IrScope,
    val searchSupport: IrScope?,
    val derivation: StructuralTimeDerivation = StructuralTimeDerivation.SERVER_DATE_SCOPE,
)

object ScheduleDslStructuralNormalizer {
    fun locateScopedTerm(
        inputId: UUID,
        locator: ProposedLiteralQuoteLocator,
        contextLocators: List<ProposedLiteralQuoteLocator>,
        snapshot: ScheduleEvidenceSnapshot,
    ): LocatedLiteralEvidence {
        require(locator.quote in ApprovedKoreanTimeTerm.entries.flatMap(ApprovedKoreanTimeTermPolicy::lexemes)) {
            "Scoped evidence must be an exact approved term"
        }
        require(contextLocators.size <= 32) { "Scoped context limit" }
        val native = source(inputId, locator.sourceId, locator.revision, snapshot)
        val first = native.indexOf(locator.quote)
        require(first >= 0) { "Missing native approved term" }
        if (native.indexOf(locator.quote, first + 1) < 0) return locate(inputId, locator, snapshot)
        require(contextLocators.isNotEmpty()) { "Repeated term needs declared context" }
        val candidates =
            contextLocators.distinct().mapNotNull { context ->
                require(context.sourceId == locator.sourceId && context.revision == locator.revision) {
                    "Scoped context source mismatch"
                }
                val parent = locate(inputId, context, snapshot).span
                val at = context.quote.indexOf(locator.quote)
                if (at < 0 || context.quote.indexOf(locator.quote, at + 1) >= 0) {
                    null
                } else {
                    val start = parent.startCodePoint + context.quote.codePointCount(0, at)
                    LocatedLiteralEvidence(
                        IrEvidenceSpan(
                            locator.sourceId,
                            locator.revision,
                            start,
                            start + locator.quote.codePointCount(0, locator.quote.length),
                            IrEvidenceBindings.sha256(locator.quote),
                        ),
                        EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE,
                        parent,
                    )
                }
            }
        require(candidates.isNotEmpty() && candidates.map { it.span }.distinct().size == 1) {
            "Declared contexts do not identify one approved term occurrence"
        }
        return candidates.minWith(
            compareBy(
                { requireNotNull(it.contextSpan).endCodePoint - it.contextSpan.startCodePoint },
                { requireNotNull(it.contextSpan).startCodePoint },
            ),
        )
    }

    fun locate(
        inputId: UUID,
        locator: ProposedLiteralQuoteLocator,
        snapshot: ScheduleEvidenceSnapshot,
    ): LocatedLiteralEvidence {
        require(locator.quote.isNotBlank() && validUnicode(locator.quote)) { "Invalid literal evidence" }
        val source = source(inputId, locator.sourceId, locator.revision, snapshot)
        val start = source.indexOf(locator.quote)
        require(start >= 0 && source.indexOf(locator.quote, start + 1) < 0) { "Literal evidence must have one exact occurrence" }
        return LocatedLiteralEvidence(
            IrEvidenceSpan(
                locator.sourceId,
                locator.revision,
                source.codePointCount(0, start),
                source.codePointCount(0, start + locator.quote.length),
                IrEvidenceBindings.sha256(locator.quote),
            ),
        )
    }

    fun restoreEvidence(
        inputId: UUID,
        evidence: LocatedLiteralEvidence,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ): LocatedLiteralEvidence {
        require(evidence.derivation == EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE || evidence.catalogId == null) {
            "Legacy evidence cannot declare a catalog reference"
        }
        val span = evidence.span
        val text = source(inputId, span.sourceId, span.revision, snapshot)
        require(span.startCodePoint >= 0 && span.endCodePoint > span.startCodePoint) { "Invalid literal evidence span" }
        require(span.endCodePoint <= text.codePointCount(0, text.length)) { "Invalid literal evidence span" }
        val quote = text.substring(text.offsetByCodePoints(0, span.startCodePoint), text.offsetByCodePoints(0, span.endCodePoint))
        val locator = ProposedLiteralQuoteLocator(span.sourceId, span.revision, quote)
        val restored =
            when (evidence.derivation) {
                EvidenceLocationDerivation.SERVER_CATALOG_REFERENCE -> {
                    require(evidence.contextSpan == null) { "Catalog references cannot borrow scoped provenance" }
                    (serverCatalog?.also { it.requireContext(snapshot) } ?: ServerEvidenceCatalog.create(snapshot))
                        .resolve(inputId, requireNotNull(evidence.catalogId))
                }
                EvidenceLocationDerivation.SERVER_LITERAL_QUOTE -> {
                    require(evidence.contextSpan == null) { "Literal evidence cannot have scoped provenance" }
                    locate(inputId, locator, snapshot)
                }
                EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE -> {
                    val context = requireNotNull(evidence.contextSpan) { "Missing scoped provenance" }
                    require(context.sourceId == span.sourceId && context.revision == span.revision) {
                        "Scoped provenance source mismatch"
                    }
                    restoreEvidence(inputId, LocatedLiteralEvidence(context), snapshot)
                    val contextQuote =
                        text.substring(
                            text.offsetByCodePoints(0, context.startCodePoint),
                            text.offsetByCodePoints(0, context.endCodePoint),
                        )
                    locateScopedTerm(
                        inputId,
                        locator,
                        listOf(ProposedLiteralQuoteLocator(context.sourceId, context.revision, contextQuote)),
                        snapshot,
                    )
                }
            }
        require(restored == evidence) { "Literal evidence restore mismatch" }
        return restored
    }

    fun resolve(
        inputId: UUID,
        declaration: DirectionalTimeDeclaration,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ): DirectionalTimeApplication {
        require(declaration.conditionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_:-]{0,63}"))) { "Invalid directional condition id" }
        require(declaration.interpretation != IrInterpretation.UNRESOLVED) { "Unresolved clock is not a directional declaration" }
        require(declaration.temporalResolution == IrTemporalResolution.NON_PRECISE) { "A single bound is not a precise interval" }
        val wholeDay = declaration.direction == TemporalDirection.WHOLE_DAY
        require(declaration.dateEvidence.size in 1..32) { "Missing date evidence" }
        require(
            if (wholeDay) {
                declaration.clock == null && declaration.clockEvidence.isEmpty()
            } else {
                declaration.clock != null && declaration.clockEvidence.size in 1..32
            },
        ) { "Clock evidence does not match the temporal derivation" }
        (declaration.dateEvidence + declaration.clockEvidence).forEach { restoreEvidence(inputId, it, snapshot, serverCatalog) }
        val context = snapshot.context
        require(context.searchStart < context.searchEnd) { "Invalid frozen search interval" }
        val zone = ZoneId.of(context.timeZone)
        val clock = declaration.clock?.let(declaration.localDate::atTime)
        if (!wholeDay) {
            require(zone.rules.getValidOffsets(requireNotNull(clock)).size == 1) { "Directional clock is not an unambiguous local time" }
        }
        // Civil-day boundaries follow the frozen zone, including daylight-saving transitions.
        // They are computation boundaries, never additional user-supplied clock endpoints.
        val dayStart = declaration.localDate.atStartOfDay(zone).toLocalDateTime()
        val dayEnd =
            declaration.localDate
                .plusDays(1)
                .atStartOfDay(zone)
                .toLocalDateTime()
        val start = if (declaration.direction == TemporalDirection.AFTER) requireNotNull(clock) else dayStart
        val end = if (declaration.direction == TemporalDirection.BEFORE) requireNotNull(clock) else dayEnd
        require(start < end) { "Empty directional civil interval" }
        val resolved = IrScope(IrDimension.TIME, start, end)
        val searchStart = context.searchStart.atStartOfDay(zone).toLocalDateTime()
        val searchEnd = context.searchEnd.atStartOfDay(zone).toLocalDateTime()
        val clippedStart = maxOf(start, searchStart)
        val clippedEnd = minOf(end, searchEnd)
        val support = if (clippedStart < clippedEnd) IrScope(IrDimension.TIME, clippedStart, clippedEnd) else null
        return DirectionalTimeApplication(
            inputId,
            declaration,
            resolved,
            support,
            if (wholeDay) StructuralTimeDerivation.SERVER_CALENDAR_DAY else StructuralTimeDerivation.SERVER_DATE_SCOPE,
        )
    }

    fun restoreTime(
        inputId: UUID,
        application: DirectionalTimeApplication,
        snapshot: ScheduleEvidenceSnapshot,
        serverCatalog: ServerEvidenceCatalog? = null,
    ): DirectionalTimeApplication {
        require(application.submissionVersionId == inputId) { "Directional input mismatch" }
        val restored = resolve(inputId, application.declaration, snapshot, serverCatalog)
        require(restored == application) { "Directional time restore mismatch" }
        return restored
    }

    private fun source(
        inputId: UUID,
        sourceId: String,
        revision: Int,
        snapshot: ScheduleEvidenceSnapshot,
    ): String {
        val input = snapshot.context.inputs.singleOrNull { it.submissionVersionId == inputId }
        require(input != null) { "Unknown literal evidence input" }
        val binding = input.sources.singleOrNull { it.sourceId == sourceId }
        require(binding != null && binding.revision == revision) { "Literal evidence source revision mismatch" }
        val text = snapshot.sourceText(inputId, sourceId)
        require(text != null && validUnicode(text)) { "Missing or invalid literal evidence source" }
        require(text.codePointCount(0, text.length) == binding.codePointLength) { "Literal evidence source length mismatch" }
        require(IrEvidenceBindings.sha256(text) == binding.contentSha256) { "Literal evidence source digest mismatch" }
        return text
    }

    private fun validUnicode(value: String): Boolean =
        '\u0000' !in value && value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value
}
