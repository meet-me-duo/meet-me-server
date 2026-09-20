package com.meetme.server.meetingroom.application.service

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class DeadlineClosureScheduler(
    private val roomRepository: MeetingRoomRepository,
    private val submissionRepository: SubmissionRepository,
    private val closureService: CollectionClosureService,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${meetme.submission.deadline-scan-delay-ms:60000}")
    @Transactional
    fun closeDueRooms() {
        val now = clock.instant()
        roomRepository.findDueForUpdate(now, 100).forEach { room ->
            closureService.close(
                room,
                ClosureReason.DEADLINE,
                submissionRepository.countSubmittedParticipants(room.id),
                now,
            )
        }
    }
}
