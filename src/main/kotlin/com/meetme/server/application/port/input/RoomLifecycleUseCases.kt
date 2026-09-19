package com.meetme.server.application.port.input

import com.meetme.server.domain.meeting.ClosureReason
import com.meetme.server.domain.meeting.CollectionStatus
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.participant.ParticipantRole
import com.meetme.server.domain.time.SearchRangeSource
import java.time.Instant
import java.time.LocalDate

data class CreateRoomCommand(
    val rawCredential: String?,
    val hostDisplayName: String,
    val purpose: String,
    val durationMinutes: Long,
    val meetingMode: MeetingMode,
    val expectedParticipants: Int?,
    val submissionDeadline: Instant?,
    val manualOnly: Boolean,
    val searchStartDate: LocalDate?,
    val searchEndDate: LocalDate?,
)

data class JoinRoomCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val displayName: String,
)

data class CloseRoomCommand(
    val inviteCode: String,
    val rawCredential: String?,
    val confirmEarly: Boolean,
)

data class ViewerParticipation(
    val joined: Boolean,
    val displayName: String?,
    val role: ParticipantRole?,
)

enum class PublicRoomStatus {
    COLLECTING,
    ANALYZING,
    INSUFFICIENT_PARTICIPANTS,
    ANALYSIS_DELAYED,
}

data class RoomView(
    val inviteCode: String,
    val purpose: String,
    val durationMinutes: Long,
    val meetingMode: MeetingMode,
    val timeZoneId: String,
    val searchStartDate: LocalDate,
    val searchEndDate: LocalDate,
    val searchRangeSource: SearchRangeSource,
    val expectedParticipants: Int?,
    val submissionDeadline: Instant?,
    val manualOnly: Boolean,
    val collectionStatus: CollectionStatus,
    val closureReason: ClosureReason?,
    val closedAt: Instant?,
    val publicStatus: PublicRoomStatus,
    val viewer: ViewerParticipation,
)

data class RoomAccessResult(
    val room: RoomView,
    val newCredential: String?,
    val participantCreated: Boolean,
)

interface CreateRoomUseCase {
    fun create(command: CreateRoomCommand): RoomAccessResult
}

interface GetRoomUseCase {
    fun get(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView
}

interface JoinRoomUseCase {
    fun join(command: JoinRoomCommand): RoomAccessResult
}

interface CloseRoomUseCase {
    fun close(command: CloseRoomCommand): RoomView
}

enum class RoomLifecycleErrorCode {
    ROOM_NOT_FOUND,
    ROOM_CLOSED,
    GUEST_SESSION_REQUIRED,
    GUEST_SESSION_INVALID,
    HOST_PERMISSION_REQUIRED,
    EARLY_CLOSE_CONFIRMATION_REQUIRED,
    INVITE_CODE_GENERATION_FAILED,
    VALIDATION_FAILED,
    ORIGIN_NOT_ALLOWED,
    ROOM_PARTICIPANT_LIMIT_REACHED,
}

class RoomLifecycleException(
    val code: RoomLifecycleErrorCode,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(code.name)
