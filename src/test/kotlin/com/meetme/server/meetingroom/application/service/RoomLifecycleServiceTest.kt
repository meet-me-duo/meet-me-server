package com.meetme.server.meetingroom.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.meetingroom.application.port.input.CreateRoomCommand
import com.meetme.server.meetingroom.application.port.output.InviteCodeGenerator
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.IssuedGuestCredential
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class RoomLifecycleServiceTest {
    @Test
    fun `초대 코드 DB 충돌은 새 코드로 제한 재시도한다`() {
        val roomRepository = mock(MeetingRoomRepository::class.java)
        val guestRepository = mock(GuestSessionRepository::class.java)
        val participantRepository = mock(ParticipantRepository::class.java)
        val submissionRepository = mock(SubmissionRepository::class.java)
        val credentialPort = mock(GuestCredentialPort::class.java)
        val coordinationRunRepository = mock(CoordinationRunRepository::class.java)
        val closureService = mock(CollectionClosureService::class.java)
        val inviteGenerator = mock(InviteCodeGenerator::class.java)
        val idGenerator = mock(IdGenerator::class.java)
        val firstCode = InviteCode.of("abcdefghijklmnopqrstuv")
        val secondCode = InviteCode.of("bcdefghijklmnopqrstuvw")
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).iterator()

        `when`(credentialPort.issue(NOW)).thenReturn(IssuedGuestCredential("raw", "digest", NOW.plusSeconds(2592000)))
        `when`(inviteGenerator.next()).thenReturn(firstCode, secondCode)
        `when`(idGenerator.next()).thenAnswer { ids.next() }
        `when`(roomRepository.insertIfInviteAvailable(anyValue())).thenReturn(false, true)
        val service =
            RoomLifecycleService(
                roomRepository,
                guestRepository,
                participantRepository,
                submissionRepository,
                coordinationRunRepository,
                credentialPort,
                inviteGenerator,
                idGenerator,
                closureService,
                Clock.fixed(NOW, ZoneOffset.UTC),
            )

        val result =
            service.create(
                CreateRoomCommand(
                    rawCredential = null,
                    hostDisplayName = "주최자",
                    purpose = "충돌 테스트",
                    meetingMode = MeetingMode.EITHER,
                    expectedParticipants = null,
                    submissionDeadline = null,
                    manualOnly = true,
                    searchStartDate = null,
                    searchEndDate = null,
                ),
            )

        assertEquals(secondCode.value, result.room.inviteCode)
        verify(roomRepository, times(2)).insertIfInviteAvailable(anyValue())
    }

    companion object {
        private val NOW = Instant.parse("2026-09-19T00:00:00Z")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }
}
