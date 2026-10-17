package com.meetme.server.submission.adapter.output.integration

import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.Participant
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import com.meetme.server.submission.application.port.output.ClarificationOwnerPort
import org.springframework.stereotype.Component
import java.time.Clock

/** Isolated guest proof for synthetic tests only. This class is excluded from production artifacts.
 * Real wiring and account/invalid-Bearer behavior are owned by #109, not implemented by this test double.
 */
@Component
class SyntheticClarificationOwnershipAdapter(
    private val sessions: GuestSessionRepository,
    private val participants: ParticipantRepository,
    private val credentials: GuestCredentialPort,
    private val clock: Clock,
) : ClarificationOwnerPort {
    override fun requireOwner(
        roomId: MeetingRoomId,
        rawGuestCredential: String?,
    ): Participant {
        if (rawGuestCredential.isNullOrBlank()) throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_REQUIRED)
        val session =
            sessions.findByCredentialDigest(credentials.digest(rawGuestCredential))?.takeIf { it.isActive(clock.instant()) }
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.GUEST_SESSION_INVALID)
        return participants.findByRoomAndGuestSession(roomId, session.id)
            ?: throw SubmissionException(SubmissionErrorCode.PARTICIPANT_REQUIRED)
    }
}
