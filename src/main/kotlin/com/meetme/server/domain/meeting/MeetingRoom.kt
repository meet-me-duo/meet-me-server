package com.meetme.server.domain.meeting

import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
import java.time.Instant

enum class MeetingMode {
    IN_PERSON,
    REMOTE,
    EITHER,
}

enum class CollectionStatus {
    COLLECTING,
    CLOSED,
}

enum class ClosureReason {
    EXPECTED_PARTICIPANTS,
    DEADLINE,
    MANUAL,
}

data class ClosurePolicy private constructor(
    val expectedParticipants: Int?,
    val deadline: Instant?,
    val manualOnly: Boolean,
) {
    companion object {
        fun of(
            expectedParticipants: Int? = null,
            deadline: Instant? = null,
            manualOnly: Boolean = false,
        ): ClosurePolicy {
            require(expectedParticipants == null || expectedParticipants >= 2) {
                "Expected participants must be at least 2"
            }
            val hasAutomaticCondition = expectedParticipants != null || deadline != null
            require(hasAutomaticCondition || manualOnly) { "At least one closure condition is required" }
            require(!(hasAutomaticCondition && manualOnly)) { "Manual-only policy cannot have automatic conditions" }
            return ClosurePolicy(expectedParticipants, deadline, manualOnly)
        }
    }
}

data class MeetingRoom private constructor(
    val id: MeetingRoomId,
    val inviteCode: InviteCode,
    val purpose: String,
    val duration: MeetingDuration,
    val mode: MeetingMode,
    val timeZone: MeetingTimeZone,
    val searchRange: SearchDateRange,
    val closurePolicy: ClosurePolicy,
    val collectionStatus: CollectionStatus,
    val closureReason: ClosureReason?,
    val closedAt: Instant?,
    val createdAt: Instant,
    val version: Long,
) {
    companion object {
        fun create(
            id: MeetingRoomId,
            inviteCode: InviteCode,
            purpose: String,
            duration: MeetingDuration,
            mode: MeetingMode,
            timeZone: MeetingTimeZone,
            searchRange: SearchDateRange,
            closurePolicy: ClosurePolicy,
            createdAt: Instant,
        ): MeetingRoom {
            require(purpose.isNotBlank()) { "Meeting purpose must not be blank" }
            require(closurePolicy.deadline == null || closurePolicy.deadline > createdAt) {
                "Submission deadline must be after room creation"
            }
            return MeetingRoom(
                id = id,
                inviteCode = inviteCode,
                purpose = purpose,
                duration = duration,
                mode = mode,
                timeZone = timeZone,
                searchRange = searchRange,
                closurePolicy = closurePolicy,
                collectionStatus = CollectionStatus.COLLECTING,
                closureReason = null,
                closedAt = null,
                createdAt = createdAt,
                version = 0,
            )
        }

        fun restore(
            id: MeetingRoomId,
            inviteCode: InviteCode,
            purpose: String,
            duration: MeetingDuration,
            mode: MeetingMode,
            timeZone: MeetingTimeZone,
            searchRange: SearchDateRange,
            closurePolicy: ClosurePolicy,
            collectionStatus: CollectionStatus,
            closureReason: ClosureReason?,
            closedAt: Instant?,
            createdAt: Instant,
            version: Long,
        ): MeetingRoom =
            MeetingRoom(
                id,
                inviteCode,
                purpose,
                duration,
                mode,
                timeZone,
                searchRange,
                closurePolicy,
                collectionStatus,
                closureReason,
                closedAt,
                createdAt,
                version,
            )
    }

    fun close(
        reason: ClosureReason,
        at: Instant,
        submittedParticipants: Int,
    ): MeetingRoom {
        if (collectionStatus == CollectionStatus.CLOSED) {
            return this
        }
        require(at >= createdAt) { "Closure time cannot precede room creation" }
        require(submittedParticipants >= 0) { "Submitted participant count cannot be negative" }
        when (reason) {
            ClosureReason.EXPECTED_PARTICIPANTS -> {
                val expected = checkNotNull(closurePolicy.expectedParticipants) { "Expected participant closure is not configured" }
                check(submittedParticipants >= expected) { "Expected participant count is not satisfied" }
            }

            ClosureReason.DEADLINE -> {
                val deadline = checkNotNull(closurePolicy.deadline) { "Deadline closure is not configured" }
                check(at >= deadline) { "Submission deadline has not been reached" }
            }

            ClosureReason.MANUAL -> Unit
        }
        return copy(
            collectionStatus = CollectionStatus.CLOSED,
            closureReason = reason,
            closedAt = at,
            version = version + 1,
        )
    }

    fun automaticClosureReason(
        at: Instant,
        submittedParticipants: Int,
    ): ClosureReason? =
        when {
            closurePolicy.expectedParticipants != null && submittedParticipants >= closurePolicy.expectedParticipants ->
                ClosureReason.EXPECTED_PARTICIPANTS
            closurePolicy.deadline != null && at >= closurePolicy.deadline -> ClosureReason.DEADLINE
            else -> null
        }
}
