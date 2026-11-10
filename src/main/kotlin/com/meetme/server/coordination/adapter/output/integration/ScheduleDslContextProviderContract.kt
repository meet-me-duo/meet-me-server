package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_REQUEST_CONTRACT
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import tools.jackson.databind.ObjectMapper

/** Explicit context2 rendering; existing DSL1 bytes and evidence sources are unchanged. */
class ScheduleDslContextProviderContract(
    private val mapper: ObjectMapper,
) {
    fun requestBytes(capsule: ScheduleDslProviderInputCapsule): ByteArray {
        val base =
            ScheduleDslProviderContract(mapper).requestBytes(
                capsule.snapshot,
                SCHEDULE_DSL_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        val original = mapper.readValue(base, Map::class.java)
        val root = original.entries.associate { (key, value) -> requireNotNull(key as? String) to value }.toMutableMap()
        val questions = capsule.questions.groupBy { it.submissionVersionId.toString() }
        val inputs = requireNotNull(root["inputs"] as? List<*>) { "Invalid base DSL inputs" }
        root["inputs"] =
            inputs.map { raw ->
                val entry = requireNotNull(raw as? Map<*, *>) { "Invalid base DSL input" }
                val fields = entry.entries.associate { (key, value) -> requireNotNull(key as? String) to value }.toMutableMap()
                val inputRef = requireNotNull(fields["input_ref"] as? String) { "Invalid base DSL input reference" }
                fields["clarification_contexts"] =
                    questions[inputRef].orEmpty().map { question ->
                        mapOf(
                            "question_id" to question.questionId.toString(),
                            "answer_source_id" to question.answerSourceId,
                            "source_revision" to question.sourceRevision,
                            "original_start_code_point" to question.originalStartCodePoint,
                            "original_end_code_point" to question.originalEndCodePoint,
                            "prompt" to question.prompt,
                            "choice_id" to question.choiceId,
                            "answered_at" to question.answeredAt.toString(),
                            "answer_reference_at" to question.answerReferenceAt.toString(),
                            "choice_definitions_available" to false,
                        )
                    }
                fields
            }
        val prompt = requireNotNull(root["prompt"] as? String) { "Invalid base DSL prompt" }
        root["prompt"] = prompt + "\n\n" + CONTEXT_INSTRUCTIONS
        root["request_contract_version"] = SCHEDULE_DSL_CONTEXT_REQUEST_CONTRACT
        root["response_contract_version"] = SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
        return mapper.writeValueAsBytes(root)
    }

    companion object {
        private val CONTEXT_INSTRUCTIONS =
            """
            This request explicitly uses schedule-dsl-request-2 and schedule-dsl-response-2.
            clarification_contexts are trusted question/answer linkage metadata, not additional evidence sources or user assertions.
            A question prompt is not a participant's availability, preference, exclusion or confirmation.
            Use it only to interpret the linked answer_source_id in the context of its original source span and revision.
            Never cite a question prompt or choice_id as a source_id or manufacture a condition from question metadata.
            Keep original and supplement evidence texts, identities, revisions, spans and reference times unchanged.
            Native data has no stored UI choice labels or proposed texts: choice_definitions_available is always false.
            Do not reconstruct missing choices, infer an ordinal choice's meaning, or resolve yes/no replies through isolated word rules.
            If an answer depends on unavailable choice definitions, preserve that dependency as unresolved.
            Question text may help interpret an answer only where the supplied context supports it; it is no proof of correctness.
            The semantic3/AST1 response schema and independent meaning/precision rules remain unchanged.
            """.trimIndent()
    }
}
