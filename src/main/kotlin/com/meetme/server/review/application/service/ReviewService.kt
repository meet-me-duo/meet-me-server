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

enum class ReviewErrorCode { REVIEW_NOT_CONFIRMED, REVIEW_PROMPT_NOT_FOUND, REVIEW_ALREADY_COMPLETED, REVIEW_UNAVAILABLE, REVIEW_STORAGE_UNAVAILABLE }

class ReviewException(val code: ReviewErrorCode) : RuntimeException(code.name)

data class ReviewReceipt(val promptId: UUID?, val state: ReviewPromptState)

@Service
class ReviewService(
    private val rooms: MeetingRoomRepository,
    private val runs: CoordinationRunRepository,
    private val ownership: ParticipantOwnershipResolver,
    private val reviews: ReviewRepository,
    private val clock: Clock,
) {
    @Transactional
    fun claim(inviteCode: String, credential: String?, requestId: UUID): ReviewReceipt = TODO("TDD RED: review-claim")

    @Transactional
    fun submit(inviteCode: String, credential: String?, promptId: UUID, answer: ReviewAnswer): ReviewReceipt =
        TODO("TDD RED: review-submit")

    @Transactional
    fun close(inviteCode: String, credential: String?, promptId: UUID, hideForWeek: Boolean): ReviewReceipt =
        TODO("TDD RED: review-close")

    private fun participant(inviteCode: String, credential: String?): Participant {
        val room = rooms.findByInviteCodeForUpdate(InviteCode.of(inviteCode))
            ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val participant = ownership.resolve(room.id, credential, OwnershipOperation.READ_OWN)
        if (room.activeRevisionRoundId != null || runs.findLatestByRoom(room.id)?.isConfirmed != true) {
            throw ReviewException(ReviewErrorCode.REVIEW_NOT_CONFIRMED)
        }
        return participant
    }

    private fun owner(participant: Participant): ReviewOwner = participant.userId?.let { ReviewOwner(ReviewOwnerKind.ACCOUNT, it) }
        ?: ReviewOwner(ReviewOwnerKind.GUEST, requireNotNull(participant.guestSessionId).value)
}
