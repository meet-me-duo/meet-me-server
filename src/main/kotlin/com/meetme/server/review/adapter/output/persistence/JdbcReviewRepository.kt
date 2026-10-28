package com.meetme.server.review.adapter.output.persistence

import com.meetme.server.review.application.port.output.ReviewRepository
import com.meetme.server.review.application.service.ReviewErrorCode
import com.meetme.server.review.application.service.ReviewException
import com.meetme.server.review.domain.ReviewAnswer
import com.meetme.server.review.domain.ReviewOwner
import com.meetme.server.review.domain.ReviewOwnerKind
import com.meetme.server.review.domain.ReviewPolicy
import com.meetme.server.review.domain.ReviewPrompt
import com.meetme.server.review.domain.ReviewPromptState
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcReviewRepository(
    private val jdbc: JdbcTemplate,
) : ReviewRepository {
    override fun lockPolicy(owner: ReviewOwner): ReviewPolicy =
        stored {
            jdbc.update(
                "INSERT INTO review_user_policies(owner_key,user_id,guest_session_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                owner.key,
                owner.id.takeIf { owner.kind == ReviewOwnerKind.ACCOUNT },
                owner.id.takeIf { owner.kind == ReviewOwnerKind.GUEST },
            )
            jdbc
                .query(
                    "SELECT satisfied_at,hidden_until FROM review_user_policies WHERE owner_key=? FOR UPDATE",
                    { rs, _ -> ReviewPolicy(rs.getTimestamp("satisfied_at")?.toInstant(), rs.getTimestamp("hidden_until")?.toInstant()) },
                    owner.key,
                ).single()
        }

    override fun savePolicy(
        owner: ReviewOwner,
        policy: ReviewPolicy,
    ): Unit =
        stored {
            check(
                jdbc.update(
                    "UPDATE review_user_policies SET satisfied_at=?, hidden_until=? WHERE owner_key=?",
                    policy.satisfiedAt.timestamp(),
                    policy.hiddenUntil.timestamp(),
                    owner.key,
                ) == 1,
            )
        }

    override fun findPrompt(participantId: UUID): ReviewPrompt? =
        stored {
            jdbc
                .query(
                    "SELECT * FROM review_prompts WHERE participant_id=?",
                    { rs, _ ->
                        val ownerParts = rs.getString("owner_key").split(':')
                        val choices = (rs.getArray("choices")?.array as? Array<*>)?.map { (it as Number).toInt() }
                        ReviewPrompt(
                            rs.getObject("id", UUID::class.java),
                            participantId,
                            rs.getObject("room_id", UUID::class.java),
                            ReviewOwner(ReviewOwnerKind.valueOf(ownerParts[0]), UUID.fromString(ownerParts[1])),
                            rs.getObject("request_id", UUID::class.java),
                            ReviewPromptState.valueOf(rs.getString("state")),
                            rs.getTimestamp("created_at").toInstant(),
                            choices?.let { ReviewAnswer.of(it, rs.getString("other_text")) },
                            rs.getTimestamp("completed_at")?.toInstant(),
                            rs.getBoolean("hide_for_week"),
                        )
                    },
                    participantId,
                ).firstOrNull()
        }

    override fun insertPrompt(prompt: ReviewPrompt): Unit =
        stored {
            jdbc.update(
                """INSERT INTO review_prompts(id,participant_id,room_id,owner_key,request_id,state,created_at,completed_at)
                VALUES (?,?,?,?,?,?,?,?)""",
                prompt.id,
                prompt.participantId,
                prompt.roomId,
                prompt.owner.key,
                prompt.requestId,
                prompt.state.name,
                Timestamp.from(prompt.createdAt),
                prompt.completedAt.timestamp(),
            )
            Unit
        }

    override fun updatePrompt(prompt: ReviewPrompt): Unit =
        stored {
            val choices =
                prompt.answer
                    ?.choices
                    ?.sorted()
                    ?.joinToString(",", "{", "}")
            check(
                jdbc.update(
                    "UPDATE review_prompts SET state=?,choices=?::integer[],other_text=?,completed_at=?,hide_for_week=? WHERE id=? AND state='PENDING'",
                    prompt.state.name,
                    choices,
                    prompt.answer?.otherText,
                    prompt.completedAt.timestamp(),
                    prompt.hideForWeek,
                    prompt.id,
                ) == 1,
            )
        }

    private fun Instant?.timestamp(): Timestamp? = this?.let(Timestamp::from)

    // PostgreSQL constraint errors can include the failing row, including private feedback.
    // Never retain that exception as a cause or forward it to a logger/HTTP error.
    private fun <T> stored(operation: () -> T): T =
        try {
            operation()
        } catch (_: DataAccessException) {
            throw ReviewException(ReviewErrorCode.REVIEW_STORAGE_UNAVAILABLE)
        }
}
