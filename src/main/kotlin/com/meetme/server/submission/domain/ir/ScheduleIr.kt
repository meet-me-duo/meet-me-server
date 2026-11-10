package com.meetme.server.submission.domain.ir

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** IR shape identity; independent of provider prompt and product policy versions. */
const val SCHEDULE_IR_VERSION = "schedule-ir-product-1"

enum class IrAvailabilityState { EMPTY, UNKNOWN, KNOWN_WINDOWS, UNCONSTRAINED }

/** Model interpretation, never evidence of participant confirmation. */
enum class IrInterpretation { EXPLICIT, INFERRED, UNRESOLVED }

enum class IrEffect { AVAILABLE, UNAVAILABLE, PREFERRED, UNCONSTRAINED, UNRESOLVED }

enum class IrDimension { TIME, PLACE, ALL }

enum class IrAssertion { ASSERTED, RETRACTED, QUOTED_OTHER }

enum class IrSourceKind { ORIGINAL, SUPPLEMENT }

data class IrSourceBinding(
    val sourceId: String,
    val kind: IrSourceKind,
    val revision: Int,
    val codePointLength: Int,
    val contentSha256: String,
    val referenceAt: Instant,
    val questionId: UUID? = null,
    val originalStartCodePoint: Int? = null,
    val originalEndCodePoint: Int? = null,
)

data class IrInputBinding(
    val submissionVersionId: UUID,
    val participantId: UUID,
    val submissionRevision: Int,
    val originalRevision: Int?,
    val contentSha256: String,
    val sources: List<IrSourceBinding>,
)

data class ScheduleIrContext(
    val runId: UUID,
    val batchId: UUID,
    val roomId: UUID,
    val timeZone: String,
    val searchStart: LocalDate,
    val searchEnd: LocalDate,
    val policyVersion: String,
    val inputs: List<IrInputBinding>,
)

/** Null interval means unknown scope, not an empty interval or the whole search range. */
data class IrScope(
    val dimension: IrDimension,
    val start: LocalDateTime? = null,
    val end: LocalDateTime? = null,
)

/** Transient provider claim. Quotes are checked against server-owned sources, then discarded. */
data class ProposedIrEvidence(
    val sourceId: String,
    val revision: Int,
    val startCodePoint: Int,
    val endCodePoint: Int,
    val quote: String,
) {
    override fun toString(): String = "ProposedIrEvidence(sourceId=$sourceId, span=$startCodePoint..$endCodePoint)"
}

data class IrEvidenceSpan(
    val sourceId: String,
    val revision: Int,
    val startCodePoint: Int,
    val endCodePoint: Int,
    val contentSha256: String,
)

data class ProposedIrCondition(
    val id: String,
    val effect: IrEffect,
    val interpretation: IrInterpretation,
    val scope: IrScope?,
    val evidence: List<ProposedIrEvidence>,
    val assertion: IrAssertion = IrAssertion.ASSERTED,
    val supersedes: List<String> = emptyList(),
    val alternativeGroup: String? = null,
    val alternative: String? = null,
)

data class ProposedIrResult(
    val submissionVersionId: UUID,
    val availability: IrAvailabilityState,
    val conditions: List<ProposedIrCondition>,
    val rejectedConditions: List<IrQuarantine> = emptyList(),
)

data class ScheduleIrProposal(
    val semanticVersion: String,
    val results: List<ProposedIrResult>,
)

data class IrCondition(
    val id: String,
    val effect: IrEffect,
    val interpretation: IrInterpretation,
    val scope: IrScope?,
    val evidence: List<IrEvidenceSpan>,
    val assertion: IrAssertion,
    val supersedes: List<String>,
    val alternativeGroup: String?,
    val alternative: String?,
)

enum class IrProblem { EVIDENCE, SCOPE, RELATION, SHAPE, AVAILABILITY }

/** No provider reason text or rejected quote is retained. Unknown scope must remain influential. */
data class IrQuarantine(
    val conditionId: String?,
    val problem: IrProblem,
    val scope: IrScope?,
)

data class IrResult(
    val submissionVersionId: UUID,
    val availability: IrAvailabilityState,
    val conditions: List<IrCondition>,
    val quarantined: List<IrQuarantine>,
)

data class ScheduleIrEnvelope(
    val semanticVersion: String,
    val context: ScheduleIrContext,
    val results: List<IrResult>,
)

/** Created by the application from immutable submissions, never from a provider source registry. */
class ScheduleEvidenceSnapshot(
    context: ScheduleIrContext,
    texts: Map<UUID, Map<String, String>>,
) {
    val context: ScheduleIrContext = context.copy(inputs = context.inputs.map { it.copy(sources = it.sources.toList()) })
    private val texts = texts.mapValues { it.value.toMap() }

    fun sourceText(
        submissionVersionId: UUID,
        sourceId: String,
    ): String? = texts[submissionVersionId]?.get(sourceId)

    override fun toString(): String = "ScheduleEvidenceSnapshot(runId=${context.runId}, inputCount=${context.inputs.size})"
}
