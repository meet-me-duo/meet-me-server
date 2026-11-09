package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.dsl.ScheduleDslQuestionContext
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.SubmissionRules
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Prospective test-only context. Never an evidence source, source repair, or production receipt store. */
internal class C5SelectedChoiceContexts private constructor(
    private val selections: List<Bound>,
) {
    companion object {
        fun prepare(
            source: PrecheckExecution,
            expectedRevision: Int,
            expectedCheckVersion: Long,
            questionId: UUID,
            choiceId: String?,
            directText: String?,
            capturedAt: Instant,
        ): Pending? = Pending.capture(source, expectedRevision, expectedCheckVersion, questionId, choiceId, directText, capturedAt)

        fun freeze(
            capsule: ScheduleDslProviderInputCapsule,
            selections: List<Bound>,
        ): C5SelectedChoiceContexts {
            require(selections.map { it.versionId }.distinct().size == selections.size)
            selections.forEach { it.requireMatches(capsule) }
            return C5SelectedChoiceContexts(selections.toList())
        }

        private fun checkedText(
            value: String,
            maximum: Int,
        ) {
            require(value.isNotBlank() && !value.contains('\u0000') && value.codePointCount(0, value.length) <= maximum)
            require(value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value)
        }

        private fun questionHash(source: PrecheckExecution): String {
            val question = requireNotNull(source.question)
            val fields =
                listOf(question.id.toString(), question.prompt, question.sourceExcerpt, question.sourceStartCodePoint?.toString()) +
                    question.choices.flatMap { listOf(it.id, it.label, it.proposedText) }
            return IrEvidenceBindings.sha256(
                fields.joinToString("") {
                    if (it ==
                        null
                    ) {
                        "-1:"
                    } else {
                        "${it.toByteArray(Charsets.UTF_8).size}:$it"
                    }
                },
            )
        }

        private fun fields(value: Any?): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String })
            return value.entries.associate { (key, entry) -> key as String to entry }
        }

        private const val APPENDIX =
            "selected_option_context is a receipt-bound snapshot of the option actually chosen via choice_id, not an additional " +
                "native evidence source or participant assertion. The base choice_definitions_available flag describes the " +
                "unextended capsule; this extension exposes only the selected definition. Keep the linked answer_source_id text " +
                "unchanged. Never concatenate, relabel or quote option labels, proposed_text or context metadata as native " +
                "evidence. Do not choose a label or proposed_text as correct when they differ or conflict; preserve unsupported " +
                "or conflicting meaning as unresolved. Free-text answers have no selected option context; never infer a choice " +
                "from matching text."
    }

    class Pending private constructor(
        internal val source: PrecheckExecution,
        internal val selected: PrecheckChoice,
        internal val capturedAt: Instant,
    ) {
        companion object {
            internal fun capture(
                source: PrecheckExecution,
                expectedRevision: Int,
                expectedCheckVersion: Long,
                questionId: UUID,
                choiceId: String?,
                directText: String?,
                capturedAt: Instant,
            ): Pending? {
                require(source.status in setOf(PrecheckStatus.NEEDS_CHOICE, PrecheckStatus.NEEDS_EDIT))
                require(source.candidate.acceptedVersionId == null)
                require(expectedRevision == source.candidate.revision && expectedCheckVersion == source.version)
                val evidence = requireNotNull(source.candidate.evidence)
                evidence.validateAgainst(source.candidate.rawText)
                require(evidence.supplement == null && evidence.referenceAt == source.candidate.referenceAt)
                val question = requireNotNull(source.question)
                require(question.id == questionId && capturedAt >= source.candidate.createdAt)
                checkedText(question.prompt, 160)
                checkedText(question.sourceExcerpt, 200)
                val start = requireNotNull(question.sourceStartCodePoint)
                val raw = source.candidate.rawText
                require(start in 0..raw.codePointCount(0, raw.length))
                require(raw.startsWith(question.sourceExcerpt, raw.offsetByCodePoints(0, start)))
                if (directText != null) {
                    require(choiceId == null)
                    checkedText(SubmissionRules.trimRawText(directText), 500)
                    return null
                }
                require(
                    question.choices.size in 1..2 &&
                        question.choices
                            .map { it.id }
                            .distinct()
                            .size == question.choices.size,
                )
                question.choices.forEach {
                    checkedText(it.id, 32)
                    checkedText(it.label, 160)
                    checkedText(it.proposedText, 500)
                }
                val selected = requireNotNull(question.choices.singleOrNull { it.id == choiceId })
                val copied = question.copy(choices = Collections.unmodifiableList(question.choices.toList()))
                return Pending(source.copy(question = copied), selected.copy(), capturedAt)
            }
        }

        fun bind(
            derived: PrecheckExecution,
            accepted: Submission,
        ): Bound = Bound.receipt(this, derived, accepted)
    }

    class Bound private constructor(
        private val roomId: UUID,
        private val binding: IrInputBinding,
        private val question: ScheduleDslQuestionContext,
        private val texts: Map<String, String>,
        private val selected: PrecheckChoice,
        private val questionHash: String,
        private val capturedAt: Instant,
    ) {
        internal val versionId: UUID get() = binding.submissionVersionId

        companion object {
            internal fun receipt(
                pending: Pending,
                derived: PrecheckExecution,
                accepted: Submission,
            ): Bound {
                val source = pending.source
                val selected = pending.selected
                val capturedAt = pending.capturedAt
                val original = source.candidate
                val next = derived.candidate
                val originalEvidence = requireNotNull(original.evidence)
                val sourceQuestion = requireNotNull(source.question)
                require(derived.status in setOf(PrecheckStatus.CLEAR, PrecheckStatus.RECEIVED))
                require(next.sourceCandidateId == original.id && next.sourceQuestionId == sourceQuestion.id)
                require(original.revision < Int.MAX_VALUE && next.revision == original.revision + 1)
                require(
                    next.ownerBinding == original.ownerBinding &&
                        next.roomId == original.roomId &&
                        next.participantId == original.participantId,
                )
                require(accepted.roomId.value == original.roomId && accepted.participantId.value == original.participantId)
                require(next.acceptedVersionId == accepted.latest.id.value && next.selectedChoiceId == selected.id)
                require(next.rawText == original.rawText && accepted.latest.rawText == original.rawText)
                require(next.locale == original.locale && accepted.latest.locale.toLanguageTag() == original.locale)
                val evidence = requireNotNull(accepted.latest.evidence)
                evidence.validateAgainst(original.rawText)
                require(evidence.interpretation in setOf(InputInterpretation.CONFIRMED, InputInterpretation.UNRESOLVED))
                require(
                    evidence.originalRevision == originalEvidence.originalRevision && evidence.referenceAt == originalEvidence.referenceAt,
                )
                require(evidence.protocol == originalEvidence.protocol)
                val answer = requireNotNull(evidence.supplement)
                require(next.evidence?.supplement == answer)
                require(answer.questionId == sourceQuestion.id && answer.choiceId == selected.id)
                require(answer.sourceRevision == originalEvidence.originalRevision)
                require(
                    answer.sourceExcerpt == sourceQuestion.sourceExcerpt &&
                        answer.sourceStartCodePoint == sourceQuestion.sourceStartCodePoint,
                )
                require(answer.prompt == sourceQuestion.prompt && answer.answerText == SubmissionRules.trimRawText(selected.proposedText))
                require(
                    capturedAt <= answer.answeredAt &&
                        next.createdAt == answer.answeredAt &&
                        accepted.latest.createdAt >= answer.answeredAt,
                )
                require(answer.referenceAt == originalEvidence.referenceAt && next.referenceAt == original.referenceAt)
                val (binding, texts) = IrEvidenceBindings.create(accepted)
                val supplement = binding.sources.single { it.kind == IrSourceKind.SUPPLEMENT }
                val question =
                    ScheduleDslQuestionContext(
                        binding.submissionVersionId,
                        answer.questionId,
                        supplement.sourceId,
                        supplement.revision,
                        requireNotNull(supplement.originalStartCodePoint),
                        requireNotNull(supplement.originalEndCodePoint),
                        answer.prompt,
                        answer.choiceId,
                        answer.answeredAt,
                        answer.referenceAt,
                    )
                return Bound(
                    original.roomId,
                    binding,
                    question,
                    texts.toMap(),
                    selected.copy(),
                    C5SelectedChoiceContexts.questionHash(source),
                    capturedAt,
                )
            }
        }

        internal fun requireMatches(capsule: ScheduleDslProviderInputCapsule) {
            require(capsule.snapshot.context.roomId == roomId)
            require(
                capsule.snapshot.context.inputs
                    .singleOrNull { it.submissionVersionId == versionId } == binding,
            )
            require(
                capsule.questions.singleOrNull { it.submissionVersionId == versionId && it.questionId == question.questionId } == question,
            )
            require(texts.all { (id, text) -> capsule.snapshot.sourceText(versionId, id) == text })
        }

        internal fun render(context: Map<String, Any?>): Map<String, Any?> {
            require(context["question_id"] == question.questionId.toString() && context["choice_id"] == selected.id)
            require(context["answer_source_id"] == question.answerSourceId && context["source_revision"] == question.sourceRevision)
            require(context["prompt"] == question.prompt && context["answered_at"] == question.answeredAt.toString())
            require(context["answer_reference_at"] == question.answerReferenceAt.toString())
            require(context["choice_definitions_available"] == false && !context.containsKey("selected_option_context"))
            return context +
                (
                    "selected_option_context" to
                        mapOf(
                            "context_contract" to "selected-choice-context-1",
                            "authority" to "CONTEXT_ONLY_NOT_EVIDENCE",
                            "selection_path" to "choice_id",
                            "selection" to mapOf("id" to selected.id, "label" to selected.label, "proposed_text" to selected.proposedText),
                            "question_snapshot_sha256" to questionHash,
                            "accepted_input_fingerprint" to binding.contentSha256,
                            "captured_at" to capturedAt.toString(),
                            "selected_definition_available" to true,
                        )
                )
        }
    }

    fun apply(
        capsule: ScheduleDslProviderInputCapsule,
        data: Map<String, Any?>,
        instructions: String,
    ): Pair<Map<String, Any?>, String> {
        if (selections.isEmpty()) return data to instructions
        selections.forEach { it.requireMatches(capsule) }
        val byVersion = selections.associateBy { it.versionId.toString() }
        val inputs = requireNotNull(data["inputs"] as? List<*>)
        require(inputs.size == capsule.snapshot.context.inputs.size)
        require(
            inputs.map { fields(it)["input_ref"] }.toSet() ==
                capsule.snapshot.context.inputs
                    .map { it.submissionVersionId.toString() }
                    .toSet(),
        )
        var rendered = 0
        val copied =
            inputs.map { raw ->
                val input = fields(raw)
                val version = requireNotNull(input["input_ref"] as? String)
                val selected = byVersion[version] ?: return@map input
                val contexts = requireNotNull(input["clarification_contexts"] as? List<*>)
                require(contexts.size == 1)
                rendered++
                input + ("clarification_contexts" to listOf(selected.render(fields(contexts.single()))))
            }
        require(rendered == selections.size)
        return (data + ("inputs" to copied)) to (instructions + "\n\n" + APPENDIX)
    }
}
