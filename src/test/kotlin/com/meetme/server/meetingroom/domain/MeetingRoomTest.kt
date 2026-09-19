package com.meetme.server.meetingroom.domain

import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class MeetingRoomTest {
    private val createdAt = Instant.parse("2026-09-18T00:00:00Z")

    @Test
    fun `종료 정책은 자동 조건이나 명시적 수동 모드가 필요하다`() {
        assertThrows<IllegalArgumentException> { ClosurePolicy.of() }
        assertThrows<IllegalArgumentException> { ClosurePolicy.of(expectedParticipants = 1) }
        assertEquals(2, ClosurePolicy.of(expectedParticipants = 2).expectedParticipants)
        assertEquals(true, ClosurePolicy.of(manualOnly = true).manualOnly)
    }

    @Test
    fun `새 방은 입력 수집 중이고 생성자를 HOST 참여자로 만들 수 있다`() {
        val room = room(ClosurePolicy.of(manualOnly = true))
        val participant =
            Participant.host(
                ParticipantId(UUID.randomUUID()),
                room.id,
                GuestSessionId(UUID.randomUUID()),
                ParticipantDisplayName.of("주최자"),
                createdAt,
            )

        assertEquals(CollectionStatus.COLLECTING, room.collectionStatus)
        assertEquals(ParticipantRole.HOST, participant.role)
        assertEquals(room.id, participant.roomId)
    }

    @Test
    fun `예상 제출 인원을 충족하면 한 번만 마감한다`() {
        val room = room(ClosurePolicy.of(expectedParticipants = 2))
        val closed = room.close(ClosureReason.EXPECTED_PARTICIPANTS, createdAt.plusSeconds(60), 2)

        assertEquals(CollectionStatus.CLOSED, closed.collectionStatus)
        assertEquals(ClosureReason.EXPECTED_PARTICIPANTS, closed.closureReason)
        assertEquals(closed, closed.close(ClosureReason.MANUAL, createdAt.plusSeconds(120), 2))
    }

    @Test
    fun `예상 제출 인원이 부족하면 자동 마감을 거부하지만 수동 조기 마감은 허용한다`() {
        val room = room(ClosurePolicy.of(expectedParticipants = 3))

        assertThrows<IllegalStateException> {
            room.close(ClosureReason.EXPECTED_PARTICIPANTS, createdAt.plusSeconds(60), 2)
        }
        assertEquals(ClosureReason.MANUAL, room.close(ClosureReason.MANUAL, createdAt.plusSeconds(60), 2).closureReason)
    }

    private fun room(policy: ClosurePolicy) =
        MeetingRoom.create(
            id = MeetingRoomId(UUID.randomUUID()),
            inviteCode = InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
            purpose = "프로젝트 회의",
            mode = MeetingMode.EITHER,
            timeZone = MeetingTimeZone.of("Asia/Seoul"),
            searchRange = SearchDateRange.explicit(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27)),
            closurePolicy = policy,
            createdAt = createdAt,
        )
}
