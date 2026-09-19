package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.RetryAnalysisUseCase
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.meetingroom.application.port.input.GetRoomUseCase
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.input.RoomView
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class RetryAnalysisService(
    private val roomRepository: MeetingRoomRepository,
    private val guestSessionRepository: GuestSessionRepository,
    private val participantRepository: ParticipantRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val outboxRepository: OutboxRepository,
    private val credentialPort: GuestCredentialPort,
    private val idGenerator: IdGenerator,
    private val getRoom: GetRoomUseCase,
    private val clock: Clock,
) : RetryAnalysisUseCase {
    @Transactional
    override fun retry(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView {
        val code =
            runCatching { InviteCode.of(inviteCode) }.getOrNull()
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val room =
            roomRepository.findByInviteCodeForUpdate(code)
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        if (rawCredential.isNullOrBlank()) throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED)
        val session =
            guestSessionRepository
                .findByCredentialDigest(credentialPort.digest(rawCredential))
                ?.takeIf { it.isActive(clock.instant()) }
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_INVALID)
        val participant = participantRepository.findByRoomAndGuestSession(room.id, session.id)
        if (participant?.role != ParticipantRole.HOST) throw RoomLifecycleException(RoomLifecycleErrorCode.HOST_PERMISSION_REQUIRED)
        val run =
            coordinationRunRepository.findLatestByRoom(room.id)
                ?: throw SubmissionException(SubmissionErrorCode.ANALYSIS_NOT_DELAYED)
        val hasPending =
            outboxRepository.existsPending(run.id.value, CollectionClosureService.STRUCTURING_REQUESTED) ||
                outboxRepository.existsPending(run.id.value, CollectionClosureService.MATCHING_REQUESTED)
        if (run.status == CoordinationStatus.QUEUED && hasPending) {
            return getRoom.get(inviteCode, rawCredential)
        }
        if (run.status != CoordinationStatus.ANALYSIS_DELAYED) {
            throw SubmissionException(SubmissionErrorCode.ANALYSIS_NOT_DELAYED)
        }
        if (!hasPending) {
            val retried = run.retryAnalysis()
            coordinationRunRepository.update(retried)
            val eventId = OutboxEventId(idGenerator.next())
            val eventType =
                if (retried.status == CoordinationStatus.MATCHING) {
                    CollectionClosureService.MATCHING_REQUESTED
                } else {
                    CollectionClosureService.STRUCTURING_REQUESTED
                }
            outboxRepository.insert(
                OutboxEvent(
                    eventId,
                    "CoordinationRun",
                    run.id.value,
                    eventType,
                    "{\"event_id\":\"${eventId.value}\",\"submission_batch_id\":\"${run.batch.id.value}\"}",
                    occurredAt = clock.instant(),
                ),
            )
        }
        return getRoom.get(inviteCode, rawCredential)
    }
}
