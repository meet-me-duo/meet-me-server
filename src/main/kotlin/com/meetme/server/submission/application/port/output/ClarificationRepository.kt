package com.meetme.server.submission.application.port.output

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.input.AnswerClarificationCommand
import com.meetme.server.submission.application.port.input.ClarificationAnswerView
import com.meetme.server.submission.domain.ClarificationProposal
import java.time.Instant
import java.util.UUID

data class StoredClarification(
    val questionId: UUID,
    val sourceVersionId: SubmissionVersionId,
    val participantId: ParticipantId,
    val sourceRevision: Int,
    val proposal: ClarificationProposal,
    val requestId: UUID?,
    val revisionRoundId: UUID?,
    val expectedRevision: Int?,
    val choice: String?,
    val directText: String?,
    val response: ClarificationAnswerView?,
)

interface ClarificationRepository {
    fun record(
        sourceVersionId: SubmissionVersionId,
        proposal: ClarificationProposal,
        at: Instant,
    )

    fun findById(questionId: UUID): StoredClarification?

    fun findBySource(sourceVersionId: SubmissionVersionId): StoredClarification?

    fun answer(
        questionId: UUID,
        command: AnswerClarificationCommand,
        savedVersionId: SubmissionVersionId?,
        response: ClarificationAnswerView,
        at: Instant,
    )
}
