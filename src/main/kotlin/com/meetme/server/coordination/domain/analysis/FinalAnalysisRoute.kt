package com.meetme.server.coordination.domain.analysis

import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.Instant
import java.util.UUID

/** Frozen interpretation contract; dispatch ownership remains an existing AnalysisInvocation. */
sealed interface FinalAnalysisRouteChoice {
    data object Legacy : FinalAnalysisRouteChoice

    data class DslContextV2(
        val identity: IrExecutionIdentity,
        val policy: IrMatchingPolicy,
        val context: ScheduleIrContext,
        val meetingMode: MeetingMode,
    ) : FinalAnalysisRouteChoice

    data class DslApprovedTermsV3(
        val identity: IrExecutionIdentity,
        val policy: IrMatchingPolicy,
        val context: ScheduleIrContext,
        val meetingMode: MeetingMode,
        val vocabularyVersion: String,
    ) : FinalAnalysisRouteChoice

    data class DslContextModeV4(
        val identity: IrExecutionIdentity,
        val policy: IrMatchingPolicy,
        val context: ScheduleIrContext,
        val meetingMode: MeetingMode,
        val vocabularyVersion: String,
    ) : FinalAnalysisRouteChoice

    data class DslStructuralV5(
        val identity: IrExecutionIdentity,
        val policy: IrMatchingPolicy,
        val context: ScheduleIrContext,
        val meetingMode: MeetingMode,
        val vocabularyVersion: String,
    ) : FinalAnalysisRouteChoice
}

data class FrozenFinalAnalysisRoute(
    val runId: UUID,
    val batchId: UUID,
    val roomId: UUID,
    val choice: FinalAnalysisRouteChoice,
    val selectedAt: Instant,
) {
    /** Selection identity excludes mutable attempts and audit time; it does not authorize dispatch. */
    fun fingerprint(): String {
        val parts = mutableListOf<String?>("final-analysis-route-1", runId.toString(), batchId.toString(), roomId.toString())
        when (val selected = choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                parts +=
                    listOf(
                        "DSL_STRUCTURAL_V5",
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        selected.identity.policyVersion,
                        selected.meetingMode.name,
                        selected.vocabularyVersion,
                        selected.policy.version,
                        selected.policy.allowExplicitUnconstrained.toString(),
                        selected.policy.eligibleInterpretations.size
                            .toString(),
                    )
                parts +=
                    selected.policy.eligibleInterpretations
                        .map { it.name }
                        .sorted()
                parts +=
                    selected.policy.alternativeChoices.size
                        .toString()
                selected.policy.alternativeChoices
                    .sortedWith(
                        compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative }),
                    ).forEach { parts += listOf(it.submissionVersionId.toString(), it.group, it.alternative) }
                appendContext(parts, selected.context)
            }
            is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                parts +=
                    listOf(
                        "DSL_CONTEXT_MODE_V4",
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        selected.identity.policyVersion,
                        selected.meetingMode.name,
                        selected.vocabularyVersion,
                        selected.policy.version,
                        selected.policy.allowExplicitUnconstrained.toString(),
                        selected.policy.eligibleInterpretations.size
                            .toString(),
                    )
                parts +=
                    selected.policy.eligibleInterpretations
                        .map { it.name }
                        .sorted()
                parts +=
                    selected.policy.alternativeChoices.size
                        .toString()
                selected.policy.alternativeChoices
                    .sortedWith(
                        compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative }),
                    ).forEach { parts += listOf(it.submissionVersionId.toString(), it.group, it.alternative) }
                appendContext(parts, selected.context)
            }
            FinalAnalysisRouteChoice.Legacy -> parts += "LEGACY"
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                parts +=
                    listOf(
                        "DSL_APPROVED_TERMS_V3",
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        selected.identity.policyVersion,
                        selected.meetingMode.name,
                        selected.vocabularyVersion,
                        selected.policy.version,
                        selected.policy.allowExplicitUnconstrained.toString(),
                        selected.policy.eligibleInterpretations.size
                            .toString(),
                    )
                parts +=
                    selected.policy.eligibleInterpretations
                        .map { it.name }
                        .sorted()
                parts +=
                    selected.policy.alternativeChoices.size
                        .toString()
                selected.policy.alternativeChoices
                    .sortedWith(
                        compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative }),
                    ).forEach { parts += listOf(it.submissionVersionId.toString(), it.group, it.alternative) }
                appendContext(parts, selected.context)
            }
            is FinalAnalysisRouteChoice.DslContextV2 -> {
                parts +=
                    listOf(
                        "DSL_CONTEXT_V2",
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        selected.identity.policyVersion,
                        selected.meetingMode.name,
                        selected.policy.version,
                        selected.policy.allowExplicitUnconstrained.toString(),
                        selected.policy.eligibleInterpretations.size
                            .toString(),
                    )
                parts +=
                    selected.policy.eligibleInterpretations
                        .map { it.name }
                        .sorted()
                parts +=
                    selected.policy.alternativeChoices.size
                        .toString()
                selected.policy.alternativeChoices
                    .sortedWith(
                        compareBy({ it.submissionVersionId.toString() }, { it.group }, { it.alternative }),
                    ).forEach {
                        parts += listOf(it.submissionVersionId.toString(), it.group, it.alternative)
                    }
                appendContext(parts, selected.context)
            }
        }
        return IrEvidenceBindings.sha256(
            parts.joinToString("") { value ->
                if (value == null) "-1:" else "${value.toByteArray(Charsets.UTF_8).size}:$value"
            },
        )
    }

    private fun appendContext(
        parts: MutableList<String?>,
        context: ScheduleIrContext,
    ) {
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
    }
}
