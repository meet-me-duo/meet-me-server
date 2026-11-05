package com.meetme.server.submission.domain.ir

import java.util.UUID

/** Explicit IR shape identity, independent of response contracts and matching policies. */
const val SCHEDULE_IR_V2_VERSION = "schedule-ir-product-2"

/** Numeric temporal resolution is independent of semantic effect and interpretation. */
enum class IrTemporalResolution { PRECISE_INTERVAL, NON_PRECISE }

data class ProposedIrV2Condition(
    val id: String,
    val effect: IrEffect,
    val interpretation: IrInterpretation,
    val temporalResolution: IrTemporalResolution,
    val scope: IrScope?,
    val evidence: List<ProposedIrEvidence>,
    val assertion: IrAssertion = IrAssertion.ASSERTED,
    val supersedes: List<String> = emptyList(),
    val alternativeGroup: String? = null,
    val alternative: String? = null,
)

data class IrV2Condition(
    val id: String,
    val effect: IrEffect,
    val interpretation: IrInterpretation,
    val temporalResolution: IrTemporalResolution,
    val scope: IrScope?,
    val evidence: List<IrEvidenceSpan>,
    val assertion: IrAssertion,
    val supersedes: List<String>,
    val alternativeGroup: String?,
    val alternative: String?,
)

data class ProposedIrV2Result(
    val submissionVersionId: UUID,
    val conditions: List<ProposedIrV2Condition>,
    val rejectedConditions: List<IrQuarantine> = emptyList(),
)

data class IrV2Result(
    val submissionVersionId: UUID,
    val conditions: List<IrV2Condition>,
    val quarantined: List<IrQuarantine>,
)

data class ScheduleIrV2Proposal(
    val semanticVersion: String,
    val results: List<ProposedIrV2Result>,
)

/** No aggregate availability field may replace the preserved semantic effect. */
data class ScheduleIrV2Envelope(
    val semanticVersion: String,
    val context: ScheduleIrContext,
    val results: List<IrV2Result>,
)
