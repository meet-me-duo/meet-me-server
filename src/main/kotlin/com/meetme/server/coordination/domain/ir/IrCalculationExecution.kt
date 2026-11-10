package com.meetme.server.coordination.domain.ir

import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.IrPreparedMatchingInputs
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import java.time.Instant

/** Three identities, not one enum or an ambient default switch. */
data class IrExecutionIdentity(
    val responseContractVersion: String,
    val irSemanticVersion: String,
    val policyVersion: String,
)

data class IrExecutionTicket(
    val expectedRunVersion: Long,
    val identity: IrExecutionIdentity,
    val policy: IrMatchingPolicy,
    val context: ScheduleIrContext,
    val meetingMode: MeetingMode,
    val preparedAt: Instant,
) {
    /** Stable metadata identity, not an authorization or semantic-correctness proof. */
    fun fingerprint(): String {
        val parts =
            mutableListOf<String?>(
                "schedule-ir-execution-selection-1",
                expectedRunVersion.toString(),
                identity.responseContractVersion,
                identity.irSemanticVersion,
                identity.policyVersion,
                meetingMode.name,
                policy.version,
                policy.allowExplicitUnconstrained.toString(),
                policy.eligibleInterpretations.size.toString(),
            )
        parts += policy.eligibleInterpretations.map { it.name }.sorted()
        parts += policy.alternativeChoices.size.toString()
        policy.alternativeChoices.sortedWith(compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative })).forEach {
            parts += listOf(it.submissionVersionId.toString(), it.group, it.alternative)
        }
        parts +=
            listOf(
                context.runId.toString(),
                context.batchId.toString(),
                context.roomId.toString(),
                context.timeZone,
                context.searchStart.toString(),
                context.searchEnd.toString(),
                context.policyVersion,
                context.inputs.size.toString(),
            )
        context.inputs.forEach { input ->
            parts +=
                listOf(
                    input.submissionVersionId.toString(),
                    input.participantId.toString(),
                    input.submissionRevision.toString(),
                    input.originalRevision?.toString(),
                    input.contentSha256,
                    input.sources.size.toString(),
                )
            input.sources.forEach { source ->
                parts +=
                    listOf(
                        source.sourceId,
                        source.kind.name,
                        source.revision.toString(),
                        source.codePointLength.toString(),
                        source.contentSha256,
                        source.referenceAt.toString(),
                        source.questionId?.toString(),
                        source.originalStartCodePoint?.toString(),
                        source.originalEndCodePoint?.toString(),
                    )
            }
        }
        parts += preparedAt.toString()
        return IrEvidenceBindings.sha256(parts.joinToString("") { if (it == null) "-1:" else "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    }
}

enum class IrCalculationStatus { CALCULATED, PARTIAL, REQUIRES_DECISION }

data class IrCalculationResult(
    val ticket: IrExecutionTicket,
    val envelope: ScheduleIrEnvelope,
    val prepared: IrPreparedMatchingInputs,
    val candidates: CandidateGenerationResult,
    val recommendations: RecommendationProjection,
    val status: IrCalculationStatus,
    val selectionFingerprint: String,
)
