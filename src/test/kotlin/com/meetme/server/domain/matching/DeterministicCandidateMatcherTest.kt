package com.meetme.server.domain.matching

import com.meetme.server.domain.common.ParticipantId
import com.meetme.server.domain.location.GeoCoordinate
import com.meetme.server.domain.meeting.MeetingMode
import com.meetme.server.domain.time.InstantTimeRange
import com.meetme.server.domain.time.MeetingDuration
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeterministicCandidateMatcherTest {
    private val firstId = participantId(1)
    private val secondId = participantId(2)
    private val thirdId = participantId(3)
    private val fourthId = participantId(4)
    private val commonRegion = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("37.482", "126.947"))))

    @Test
    fun `IN_PERSON은 전원 시간과 장소가 겹치는 Plan A 하나를 만든다`() {
        val result =
            generate(
                MeetingMode.IN_PERSON,
                participant(firstId, "09:00", "12:00", commonRegion),
                participant(secondId, "10:00", "13:00", commonRegion),
            )

        assertEquals(listOf(PlanType.A), result.candidates.map { it.planType })
        assertEquals(listOf(range("10:00", "12:00")), result.candidates.single().timeRanges)
    }

    @Test
    fun `REMOTE는 전원 시간이 겹치는 Plan B 하나를 만든다`() {
        val result =
            generate(
                MeetingMode.REMOTE,
                participant(firstId, "09:00", "12:00"),
                participant(secondId, "10:00", "13:00"),
            )

        assertEquals(listOf(PlanType.B), result.candidates.map { it.planType })
        assertEquals(MeetingMode.REMOTE, result.candidates.single().meetingMode)
    }

    @Test
    fun `EITHER는 전원 오프라인 Plan A와 온라인 Plan B를 각각 하나만 만든다`() {
        val result =
            generate(
                MeetingMode.EITHER,
                participant(firstId, "09:00", "12:00", commonRegion),
                participant(secondId, "10:00", "13:00", commonRegion),
            )

        assertEquals(listOf(PlanType.A, PlanType.B), result.candidates.map { it.planType })
    }

    @Test
    fun `이동 제약이나 미확정 장소로 좌표가 없는 참여자는 오프라인 후보에서 제외한다`() {
        val result =
            generate(
                MeetingMode.EITHER,
                participant(firstId, "09:00", "12:00", commonRegion),
                participant(secondId, "10:00", "13:00", offlineRegion = null),
            )

        assertEquals(listOf(PlanType.B), result.candidates.map { it.planType })
        assertNull(result.candidates.single().representativePlace)
    }

    @Test
    fun `전원 교집합이 없으면 N-1명이 참석하는 Plan C를 N-2명보다 우선한다`() {
        val result =
            generate(
                MeetingMode.REMOTE,
                participant(firstId, "09:00", "10:00"),
                participant(secondId, "09:00", "10:00"),
                participant(thirdId, "09:00", "10:00"),
                participant(fourthId, "11:00", "12:00"),
            )

        val planC = result.candidates.single()
        assertEquals(PlanType.C, planC.planType)
        assertEquals(listOf(firstId, secondId, thirdId), planC.participantIds)
    }

    @Test
    fun `N-1 교집합이 없으면 최소 두 명을 유지하며 N-2 Plan C를 찾는다`() {
        val result =
            generate(
                MeetingMode.REMOTE,
                participant(firstId, "09:00", "10:00"),
                participant(secondId, "09:00", "10:00"),
                participant(thirdId, "11:00", "12:00"),
                participant(fourthId, "13:00", "14:00"),
            )

        assertEquals(listOf(firstId, secondId), result.candidates.single().participantIds)
    }

    @Test
    fun `Plan C 동률은 가장 이른 공통 시작 시각으로 해소한다`() {
        val result =
            generate(
                MeetingMode.REMOTE,
                participant(firstId, "09:00", "10:00"),
                ParticipantMatchInput(
                    participantId = secondId,
                    availableTimes = listOf(range("09:00", "10:00"), range("12:00", "13:00")),
                ),
                participant(thirdId, "12:00", "13:00"),
            )

        assertEquals(listOf(firstId, secondId), result.candidates.single().participantIds)
    }

    @Test
    fun `Plan C 동률은 이른 시작보다 공통 가능 총시간이 긴 조합을 우선한다`() {
        val result =
            generate(
                MeetingMode.REMOTE,
                participant(firstId, "12:00", "14:00"),
                ParticipantMatchInput(
                    participantId = secondId,
                    availableTimes = listOf(range("09:00", "10:00"), range("12:00", "14:00")),
                ),
                participant(thirdId, "09:00", "10:00"),
            )

        assertEquals(listOf(firstId, secondId), result.candidates.single().participantIds)
    }

    @Test
    fun `EITHER의 Plan C는 가능한 경우 오프라인을 우선한다`() {
        val result =
            generate(
                MeetingMode.EITHER,
                participant(firstId, "09:00", "10:00", commonRegion),
                participant(secondId, "09:00", "10:00", commonRegion),
                participant(thirdId, "12:00", "13:00", commonRegion),
            )

        assertEquals(MeetingMode.IN_PERSON, result.candidates.single().meetingMode)
    }

    @Test
    fun `EITHER Plan C는 짧은 오프라인보다 공통 가능 총시간이 긴 온라인 조합을 우선한다`() {
        val result =
            generate(
                MeetingMode.EITHER,
                participant(firstId, "09:00", "10:00", commonRegion),
                ParticipantMatchInput(
                    participantId = secondId,
                    availableTimes = listOf(range("09:00", "10:00"), range("12:00", "14:00")),
                    offlineRegion = commonRegion,
                ),
                participant(thirdId, "12:00", "14:00"),
            )

        val candidate = result.candidates.single()
        assertEquals(listOf(secondId, thirdId), candidate.participantIds)
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
    }

    @Test
    fun `최소 두 명의 유효 후보가 없으면 정상 완료된 NO_MATCH를 반환한다`() {
        val result =
            generate(
                MeetingMode.REMOTE,
                participant(firstId, "09:00", "10:00"),
                participant(secondId, "11:00", "12:00"),
            )

        assertEquals(MatchOutcome.NO_MATCH, result.outcome)
        assertEquals(emptyList(), result.candidates)
    }

    @Test
    fun `동일 입력을 반복 실행하면 후보 내용과 순서가 항상 같다`() {
        val participants =
            arrayOf(
                participant(firstId, "09:00", "10:00"),
                ParticipantMatchInput(secondId, listOf(range("09:00", "10:00"), range("12:00", "13:00"))),
                participant(thirdId, "12:00", "13:00"),
            )
        val firstResult = generate(MeetingMode.REMOTE, *participants)

        repeat(20) {
            assertEquals(firstResult, generate(MeetingMode.REMOTE, *participants))
        }
    }

    private fun generate(
        mode: MeetingMode,
        vararg participants: ParticipantMatchInput,
    ) = DeterministicCandidateMatcher.generate(mode, MeetingDuration.ofMinutes(60), participants.toList())

    private fun participant(
        id: ParticipantId,
        start: String,
        end: String,
        offlineRegion: ParticipantAllowedRegion? = null,
    ) = ParticipantMatchInput(id, listOf(range(start, end)), offlineRegion)

    private fun range(
        start: String,
        end: String,
    ) = InstantTimeRange(
        Instant.parse("2026-09-21T$start:00Z"),
        Instant.parse("2026-09-21T$end:00Z"),
    )

    private fun participantId(value: Long) = ParticipantId(UUID(0, value))

    private fun coordinate(
        latitude: String,
        longitude: String,
    ) = GeoCoordinate.of(BigDecimal(latitude), BigDecimal(longitude))
}
