package com.meetme.server.meetingroom.application.service

import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DeadlineClosureSchedulerTest {
    @Test
    fun `마감이 지난 수집 중 방을 잠금 조회해 동일 마감 서비스로 종료한다`() {
        val roomRepository = mock(MeetingRoomRepository::class.java)
        val submissionRepository = mock(SubmissionRepository::class.java)
        val closureService = mock(CollectionClosureService::class.java)
        val room =
            MeetingRoom.create(
                MeetingRoomId(UUID.randomUUID()),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "마감 테스트",
                MeetingMode.EITHER,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.defaultFrom(NOW.minusSeconds(3600), MeetingTimeZone.of("Asia/Seoul")),
                ClosurePolicy.of(deadline = NOW.minusSeconds(1)),
                NOW.minusSeconds(3600),
            )
        `when`(roomRepository.findDueForUpdate(NOW, 100)).thenReturn(listOf(room))
        `when`(submissionRepository.countSubmittedParticipants(room.id)).thenReturn(2)
        val scheduler =
            DeadlineClosureScheduler(
                roomRepository,
                submissionRepository,
                closureService,
                Clock.fixed(NOW, ZoneOffset.UTC),
            )

        scheduler.closeDueRooms()

        verify(closureService).close(room, ClosureReason.DEADLINE, 2, NOW)
    }

    companion object {
        private val NOW = Instant.parse("2026-09-19T12:00:00Z")
    }
}
