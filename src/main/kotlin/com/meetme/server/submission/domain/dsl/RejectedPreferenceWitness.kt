package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import java.util.UUID

/** Reproducible rejection provenance, never an accepted condition or preference credit. */
data class RejectedPreferenceWitness(
    val submissionVersionId: UUID,
    val conditionId: String,
    val effect: IrEffect,
    val interpretation: IrInterpretation,
    val temporalResolution: IrTemporalResolution,
    val declaredScope: IrScope?,
    val evidence: List<IrEvidenceSpan>,
    val assertion: IrAssertion,
    val supersedes: List<String>,
    val alternativeGroup: String?,
    val alternative: String?,
)
