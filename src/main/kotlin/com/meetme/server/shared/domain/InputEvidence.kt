package com.meetme.server.shared.domain

import java.time.Instant
import java.util.UUID

enum class InputInterpretation { PENDING, CONFIRMED, UNRESOLVED }

/** One immutable, scoped answer. The original text is never replaced by this answer. */
data class InputSupplement(
    val questionId: UUID,
    val sourceRevision: Int,
    val sourceExcerpt: String,
    val sourceStartCodePoint: Int,
    val prompt: String,
    val answerText: String,
    val choiceId: String?,
    val answeredAt: Instant,
    val referenceAt: Instant,
)

/** Versioned evidence is copied into a received submission and frozen with its version ID. */
data class InputEvidence(
    val originalRevision: Int,
    val referenceAt: Instant,
    val supplement: InputSupplement? = null,
    val interpretation: InputInterpretation = InputInterpretation.PENDING,
    val unresolvedReason: String? = null,
    val protocol: String = "PRE_SUBMIT_V2",
) {
    fun validateAgainst(rawText: String) {
        require(protocol == "PRE_SUBMIT_V2") { "Unsupported evidence protocol" }
        require(originalRevision > 0) { "Original revision must be positive" }
        require(validText(rawText, 500)) { "Invalid original text" }
        require(textCodePoints(rawText) <= 500) { "Original and answer exceed the input limit" }
        require(
            if (interpretation == InputInterpretation.UNRESOLVED) {
                unresolvedReason?.let { validText(it, 64) } == true
            } else {
                unresolvedReason == null
            },
        ) { "Interpretation and unresolved reason disagree" }
        supplement?.let {
            require(it.sourceRevision == originalRevision) { "Answer belongs to a different original revision" }
            require(validText(it.sourceExcerpt, 200) && validText(it.prompt, 160) && validText(it.answerText, 500))
            require(it.choiceId == null || validText(it.choiceId, 32)) { "Invalid choice" }
            require(it.sourceStartCodePoint in 0..rawText.codePointCount(0, rawText.length)) { "Invalid source position" }
            val start = rawText.offsetByCodePoints(0, it.sourceStartCodePoint)
            require(rawText.startsWith(it.sourceExcerpt, start)) { "Answer source does not match the original" }
            require(it.answeredAt >= referenceAt) { "Answer precedes original" }
            require(it.referenceAt == if (it.choiceId == null) it.answeredAt else referenceAt) { "Invalid answer reference time" }
        }
    }

    /** Length framed representation used by dispatch source fencing, never for display. */
    fun canonical(): String =
        buildString {
            val parts =
                listOf(protocol, originalRevision.toString(), referenceAt.toString(), interpretation.name, unresolvedReason) +
                    supplement
                        ?.let {
                            listOf(
                                it.questionId.toString(),
                                it.sourceRevision.toString(),
                                it.sourceExcerpt,
                                it.sourceStartCodePoint.toString(),
                                it.prompt,
                                it.answerText,
                                it.choiceId,
                                it.answeredAt.toString(),
                                it.referenceAt.toString(),
                            )
                        }.orEmpty()
            for (part in parts) {
                if (part == null) append("-1:") else append(part.toByteArray(Charsets.UTF_8).size).append(':').append(part)
            }
        }

    fun textCodePoints(rawText: String): Int =
        rawText.codePointCount(0, rawText.length) + (supplement?.answerText?.let { it.codePointCount(0, it.length) } ?: 0)

    private fun validText(
        text: String,
        maximum: Int,
    ): Boolean =
        text.isNotBlank() &&
            !text.contains('\u0000') &&
            text.codePointCount(0, text.length) <= maximum &&
            text.indices.none { index ->
                val character = text[index]
                (character.isHighSurrogate() && (index + 1 == text.length || !text[index + 1].isLowSurrogate())) ||
                    (character.isLowSurrogate() && (index == 0 || !text[index - 1].isHighSurrogate()))
            }
}
