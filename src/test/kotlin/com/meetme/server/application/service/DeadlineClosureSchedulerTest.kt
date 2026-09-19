package com.meetme.server.application.service

import com.meetme.server.application.port.output.MeetingRoomRepository
import com.meetme.server.application.port.output.SubmissionRepository
import com.meetme.server.domain.common.MeetingRoomId
import com.meetme.server.domain.meeting.ClosurePolicy
import com.meetme.server.domain.meeting.ClosureReason
import com.meetme.server.domain.meeting.InviteCode
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.meeting.MeetingRoom
import com.meetme.server.domain.time.MeetingDuration
import com.meetme.server.domain.time.MeetingTimeZone
import com.meetme.server.domain.time.SearchDateRange
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
                MeetingDuration.ofMinutes(60),
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
