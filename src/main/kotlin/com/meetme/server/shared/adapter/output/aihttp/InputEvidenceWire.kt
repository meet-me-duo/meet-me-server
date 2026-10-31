package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.domain.InputEvidence

/** Provider data only; ownership identifiers and dispatch permissions never enter this map. */
fun InputEvidence.toProviderEvidence(): Map<String, Any?> =
    mapOf(
        "protocol" to protocol,
        "original_revision" to originalRevision,
        "reference_at" to referenceAt.toString(),
        "interpretation" to interpretation.name,
        "unresolved_reason" to unresolvedReason,
        "supplement" to
            supplement?.let {
                mapOf(
                    "question_id" to it.questionId.toString(),
                    "source_revision" to it.sourceRevision,
                    "source_excerpt" to it.sourceExcerpt,
                    "source_start_code_point" to it.sourceStartCodePoint,
                    "prompt" to it.prompt,
                    "answer_text" to it.answerText,
                    "choice_id" to it.choiceId,
                    "answered_at" to it.answeredAt.toString(),
                    "reference_at" to it.referenceAt.toString(),
                )
            },
    )
