package com.meetme.server.submission.application.port.input

import java.util.Locale
import java.util.UUID

enum class ClarificationChoice { A, B, DIRECT, CANCEL }

data class AnswerClarificationCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val questionId: UUID,
    val requestId: UUID,
    val revisionRoundId: UUID,
    val expectedRevision: Int,
    val choice: ClarificationChoice,
    val directText: String?,
    val locale: Locale,
)

data class ClarificationQuestionView(
    val questionId: UUID,
    val sourceRevision: Int,
    val excerpt: String,
    val question: String,
    val optionA: String,
    val optionB: String,
    val editable: Boolean,
)

data class ClarificationListView(
    val questions: List<ClarificationQuestionView>,
    val stateVersion: Long,
)

data class ClarificationAnswerView(
    val status: String,
    val submission: SubmissionView?,
)

interface ClarificationUseCase {
    fun get(
        inviteCode: String,
        rawCredential: String?,
    ): ClarificationListView

    fun answer(command: AnswerClarificationCommand): ClarificationAnswerView
}
