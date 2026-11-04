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
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomPreviewServiceTest {
    @Test
    fun `existing old and closed rooms retain their public name until physically deleted`() {
        val rooms = mock(MeetingRoomRepository::class.java)
        val service = RoomPreviewService(rooms)
        val code = InviteCode.of("abcdefghijklmnopqrstuv")
        val created = Instant.parse("2026-01-01T00:00:00Z")
        val zone = MeetingTimeZone.of("Asia/Seoul")
        val room =
            MeetingRoom
                .create(
                    MeetingRoomId(UUID.randomUUID()),
                    code,
                    "가족 모임",
                    MeetingMode.EITHER,
                    zone,
                    SearchDateRange.defaultFrom(created, zone),
                    ClosurePolicy.of(manualOnly = true),
                    created,
                ).close(ClosureReason.MANUAL, created.plusSeconds(3600), submittedParticipants = 2)
        `when`(rooms.findByInviteCode(code)).thenReturn(room, null)
        assertEquals("가족 모임", service.purpose(code.value))
        assertNull(service.purpose(code.value))
    }

    @Test
    fun `invalid code needs no persistence and missing or deleted room returns no name`() {
        val rooms = mock(MeetingRoomRepository::class.java)
        val service = RoomPreviewService(rooms)
        assertNull(service.purpose("invalid"))
        verifyNoInteractions(rooms)
        val code = InviteCode.of("abcdefghijklmnopqrstuv")
        `when`(rooms.findByInviteCode(code)).thenReturn(null)
        assertNull(service.purpose(code.value))
        org.mockito.Mockito
            .verify(rooms)
            .findByInviteCode(code)
        verifyNoMoreInteractions(rooms)
    }
}
