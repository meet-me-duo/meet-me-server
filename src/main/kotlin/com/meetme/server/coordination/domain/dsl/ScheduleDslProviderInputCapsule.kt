package com.meetme.server.coordination.domain.dsl

import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import java.time.Instant
import java.util.Collections
import java.util.UUID

const val SCHEDULE_DSL_CONTEXT_REQUEST_CONTRACT = "schedule-dsl-request-2"
const val SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT = "schedule-dsl-response-2"

/** Native question metadata is context only, never an additional evidence source or user assertion. */
data class ScheduleDslQuestionContext(
    val submissionVersionId: UUID,
    val questionId: UUID,
    val answerSourceId: String,
    val sourceRevision: Int,
    val originalStartCodePoint: Int,
    val originalEndCodePoint: Int,
    val prompt: String,
    val choiceId: String?,
    val answeredAt: Instant,
    val answerReferenceAt: Instant,
    val choiceDefinitionsAvailable: Boolean = false,
)

class ScheduleDslProviderInputCapsule private constructor(
    val snapshot: ScheduleEvidenceSnapshot,
    val questions: List<ScheduleDslQuestionContext>,
) {
    companion object {
        fun create(
            snapshot: ScheduleEvidenceSnapshot,
            submissions: List<Submission>,
        ): ScheduleDslProviderInputCapsule {
            val context = snapshot.context
            val expected = context.inputs.associateBy { it.submissionVersionId }
            require(context.inputs.size in 2..50 && expected.size == context.inputs.size) { "Invalid capsule input registry" }
            require(submissions.size == expected.size && submissions.map { it.latest.id.value }.toSet() == expected.keys) {
                "Capsule frozen input mismatch"
            }
            require(submissions.map { it.id }.distinct().size == submissions.size)
            require(submissions.map { it.participantId }.distinct().size == submissions.size)
            val questions = mutableListOf<ScheduleDslQuestionContext>()
            val texts = linkedMapOf<UUID, Map<String, String>>()
            submissions.sortedBy { it.latest.id.value }.forEach { submission ->
                require(submission.roomId.value == context.roomId) { "Capsule room mismatch" }
                val (binding, sourceTexts) = IrEvidenceBindings.create(submission)
                require(binding == expected[binding.submissionVersionId]) { "Capsule immutable input mismatch" }
                require(sourceTexts.all { (id, text) -> snapshot.sourceText(binding.submissionVersionId, id) == text }) {
                    "Capsule source text mismatch"
                }
                texts[binding.submissionVersionId] = sourceTexts.toMap()
                submission.latest.evidence?.supplement?.let { answer ->
                    val source = binding.sources.single { it.kind == IrSourceKind.SUPPLEMENT }
                    questions +=
                        ScheduleDslQuestionContext(
                            binding.submissionVersionId,
                            answer.questionId,
                            source.sourceId,
                            source.revision,
                            requireNotNull(source.originalStartCodePoint),
                            requireNotNull(source.originalEndCodePoint),
                            answer.prompt,
                            answer.choiceId,
                            answer.answeredAt,
                            answer.referenceAt,
                            choiceDefinitionsAvailable = false,
                        )
                }
            }
            val ownedSnapshot = ScheduleEvidenceSnapshot(context, texts)
            ScheduleIrV2Validator().validate(
                ScheduleIrV2Proposal(
                    SCHEDULE_IR_V2_VERSION,
                    context.inputs.map { ProposedIrV2Result(it.submissionVersionId, emptyList()) },
                ),
                ownedSnapshot,
            )
            val orderedQuestions = questions.sortedWith(compareBy({ it.submissionVersionId }, { it.questionId }))
            return ScheduleDslProviderInputCapsule(ownedSnapshot, Collections.unmodifiableList(orderedQuestions))
        }
    }
}
