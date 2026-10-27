package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.application.port.input.AnswerClarificationCommand
import com.meetme.server.submission.application.port.input.ClarificationAnswerView
import com.meetme.server.submission.application.port.output.ClarificationRepository
import com.meetme.server.submission.application.port.output.StoredClarification
import com.meetme.server.submission.domain.ClarificationProposal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JdbcClarificationRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) : ClarificationRepository {
    override fun record(
        sourceVersionId: SubmissionVersionId,
        proposal: ClarificationProposal,
        at: Instant,
    ) {
        // First proposal wins across retries/re-analysis. Answered descendants and repeated identical originals never ask again.
        jdbc.update(
            """
            INSERT INTO submission_clarifications(question_id, source_version_id, proposal, created_at)
            SELECT ?, v.id, CAST(? AS jsonb), ? FROM submission_versions v WHERE v.id = ?
              AND EXISTS (SELECT 1 FROM submission_heads h JOIN meeting_rooms r ON r.id=h.room_id WHERE h.id=v.submission_id AND r.submission_protocol='LEGACY')
              AND NOT EXISTS (
                SELECT 1 FROM submission_clarifications c JOIN submission_versions old ON old.id = c.source_version_id
                WHERE old.submission_id = v.submission_id AND c.answered_at IS NOT NULL
                  AND (old.raw_text = v.raw_text OR c.saved_version_id = v.id)
              )
              AND (SELECT count(*) FROM submission_clarifications c JOIN submission_versions old ON old.id = c.source_version_id
                   WHERE old.submission_id = v.submission_id) < 3
            ON CONFLICT (source_version_id) DO NOTHING
            """.trimIndent(),
            UUID.nameUUIDFromBytes("clarification:${sourceVersionId.value}".toByteArray(Charsets.UTF_8)),
            mapper.writeValueAsString(proposal),
            at.atOffset(ZoneOffset.UTC),
            sourceVersionId.value,
        )
    }

    override fun findById(questionId: UUID): StoredClarification? = find("c.question_id", questionId)

    override fun findBySource(sourceVersionId: SubmissionVersionId): StoredClarification? =
        find("c.source_version_id", sourceVersionId.value)

    private fun find(
        column: String,
        value: UUID,
    ): StoredClarification? =
        jdbc
            .query(
                "SELECT c.*, v.revision, h.participant_id FROM submission_clarifications c " +
                    "JOIN submission_versions v ON v.id = c.source_version_id JOIN submission_heads h ON h.id = v.submission_id WHERE $column = ?",
                { rs, _ ->
                    StoredClarification(
                        rs.getObject("question_id", UUID::class.java),
                        SubmissionVersionId(rs.getObject("source_version_id", UUID::class.java)),
                        ParticipantId(rs.getObject("participant_id", UUID::class.java)),
                        rs.getInt("revision"),
                        mapper.readValue(rs.getString("proposal"), ClarificationProposal::class.java),
                        rs.getObject("request_id", UUID::class.java),
                        rs.getObject("revision_round_id", UUID::class.java),
                        rs.getObject("expected_revision", Integer::class.java)?.toInt(),
                        rs.getString("answer_choice"),
                        rs.getString("direct_text"),
                        rs.getString("response")?.let { mapper.readValue(it, ClarificationAnswerView::class.java) },
                    )
                },
                value,
            ).singleOrNull()

    override fun answer(
        questionId: UUID,
        command: AnswerClarificationCommand,
        savedVersionId: SubmissionVersionId?,
        response: ClarificationAnswerView,
        at: Instant,
    ) {
        check(
            jdbc.update(
                """
                UPDATE submission_clarifications SET request_id = ?, revision_round_id = ?, expected_revision = ?, answer_choice = ?,
                    direct_text = ?, saved_version_id = ?, response = CAST(? AS jsonb), answered_at = ?
                WHERE question_id = ? AND answered_at IS NULL
                """.trimIndent(),
                command.requestId,
                command.revisionRoundId,
                command.expectedRevision,
                command.choice.name,
                command.directText,
                savedVersionId?.value,
                mapper.writeValueAsString(response),
                at.atOffset(ZoneOffset.UTC),
                questionId,
            ) == 1,
        ) { "Clarification answer was not persisted" }
    }
}
