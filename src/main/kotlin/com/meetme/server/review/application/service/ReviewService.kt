package com.meetme.server.review.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.participant.application.service.OwnershipOperation
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import com.meetme.server.participant.domain.Participant
import com.meetme.server.review.application.port.output.ReviewRepository
import com.meetme.server.review.domain.ReviewAnswer
import com.meetme.server.review.domain.ReviewOwner
import com.meetme.server.review.domain.ReviewOwnerKind
import com.meetme.server.review.domain.ReviewPrompt
import com.meetme.server.review.domain.ReviewPromptState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

enum class ReviewErrorCode {
    REVIEW_NOT_CONFIRMED,
    REVIEW_PROMPT_NOT_FOUND,
    REVIEW_ALREADY_COMPLETED,
    REVIEW_UNAVAILABLE,
    REVIEW_STORAGE_UNAVAILABLE,
}

class ReviewException(
    val code: ReviewErrorCode,
) : RuntimeException(code.name)

data class ReviewReceipt(
    val promptId: UUID?,
    val state: ReviewPromptState,
)

@Service
class ReviewService(
    private val rooms: MeetingRoomRepository,
    private val runs: CoordinationRunRepository,
    private val ownership: ParticipantOwnershipResolver,
    private val reviews: ReviewRepository,
    private val clock: Clock,
) {
    @Transactional
    fun claim(
        inviteCode: String,
        credential: String?,
        requestId: UUID,
    ): ReviewReceipt {
        val participant = participant(inviteCode, credential)
        val owner = owner(participant)
        val policy = reviews.lockPolicy(owner)
        val now = clock.instant()
        val existing = reviews.findPrompt(participant.id.value)
        if (existing != null) {
            return if (existing.owner == owner &&
                existing.state == ReviewPromptState.PENDING &&
                existing.requestId == requestId &&
                policy.eligible(now)
            ) {
                receipt(existing)
            } else {
                suppressed()
            }
        }
        val eligible = policy.eligible(now)
        val prompt =
            ReviewPrompt(
                id = UUID.randomUUID(),
                participantId = participant.id.value,
                roomId = participant.roomId.value,
                owner = owner,
                requestId = requestId,
                state = if (eligible) ReviewPromptState.PENDING else ReviewPromptState.SUPPRESSED,
                createdAt = now,
                completedAt = if (eligible) null else now,
            )
        reviews.insertPrompt(prompt)
        return if (eligible) receipt(prompt) else suppressed()
    }

    @Transactional
    fun submit(
        inviteCode: String,
        credential: String?,
        promptId: UUID,
        answer: ReviewAnswer,
    ): ReviewReceipt {
        val participant = participant(inviteCode, credential)
        val owner = owner(participant)
        val policy = reviews.lockPolicy(owner)
        val prompt = prompt(participant, owner, promptId)
        if (prompt.state == ReviewPromptState.SUBMITTED && prompt.answer == answer) return receipt(prompt)
        if (prompt.state != ReviewPromptState.PENDING) throw ReviewException(ReviewErrorCode.REVIEW_ALREADY_COMPLETED)
        val now = clock.instant()
        if (!policy.eligible(now)) throw ReviewException(ReviewErrorCode.REVIEW_UNAVAILABLE)
        val completed = prompt.copy(state = ReviewPromptState.SUBMITTED, answer = answer, completedAt = now)
        reviews.updatePrompt(completed)
        reviews.savePolicy(owner, policy.submitted(answer, now))
        return receipt(completed)
    }

    @Transactional
    fun close(
        inviteCode: String,
        credential: String?,
        promptId: UUID,
        hideForWeek: Boolean,
    ): ReviewReceipt {
        val participant = participant(inviteCode, credential)
        val owner = owner(participant)
        val policy = reviews.lockPolicy(owner)
        val prompt = prompt(participant, owner, promptId)
        if (prompt.state != ReviewPromptState.PENDING) return receipt(prompt)
        val now = clock.instant()
        val completed = prompt.copy(state = ReviewPromptState.CLOSED, completedAt = now, hideForWeek = hideForWeek)
        reviews.updatePrompt(completed)
        // A stale screen cannot extend another room's cooldown or alter permanent opt-out.
        if (hideForWeek && policy.eligible(now)) reviews.savePolicy(owner, policy.hide(now))
        return receipt(completed)
    }

    private fun prompt(
        participant: Participant,
        owner: ReviewOwner,
        promptId: UUID,
    ): ReviewPrompt =
        reviews.findPrompt(participant.id.value)?.takeIf {
            it.id == promptId && it.owner == owner && it.roomId == participant.roomId.value
        } ?: throw ReviewException(ReviewErrorCode.REVIEW_PROMPT_NOT_FOUND)

    private fun receipt(prompt: ReviewPrompt): ReviewReceipt = ReviewReceipt(prompt.id, prompt.state)

    private fun suppressed(): ReviewReceipt = ReviewReceipt(null, ReviewPromptState.SUPPRESSED)

    private fun participant(
        inviteCode: String,
        credential: String?,
    ): Participant {
        val room =
            rooms.findByInviteCodeForUpdate(InviteCode.of(inviteCode))
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val participant = ownership.resolve(room.id, credential, OwnershipOperation.READ_OWN)
        if (room.activeRevisionRoundId != null || runs.findLatestByRoom(room.id)?.isConfirmed != true) {
            throw ReviewException(ReviewErrorCode.REVIEW_NOT_CONFIRMED)
        }
        return participant
    }

    private fun owner(participant: Participant): ReviewOwner =
        participant.userId?.let { ReviewOwner(ReviewOwnerKind.ACCOUNT, it) }
            ?: ReviewOwner(ReviewOwnerKind.GUEST, requireNotNull(participant.guestSessionId).value)
}
