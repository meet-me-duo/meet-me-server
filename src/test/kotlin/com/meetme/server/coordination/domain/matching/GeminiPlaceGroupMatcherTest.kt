package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class GeminiPlaceGroupMatcherTest {
    @Test
    fun `모든 참여자의 장소 대안에 같은 Gemini 호환 그룹이 있으면 대면 후보를 만든다`() {
        val result =
            DeterministicCandidateMatcher.generate(
                MeetingMode.IN_PERSON,
                listOf(
                    participant(1, CompatiblePlaceArea("AREA_1", "관악구 북부")),
                    participant(2, CompatiblePlaceArea("AREA_1", "관악구 북부")),
                ),
            )

        assertEquals(MatchOutcome.READY, result.outcome)
        assertEquals(
            "AREA_1",
            result.candidates
                .single()
                .representativeArea
                ?.key,
        )
        assertEquals(
            "관악구 북부",
            result.candidates
                .single()
                .representativeArea
                ?.displayName,
        )
    }

    @Test
    fun `장소 호환 그룹의 전원 교집합이 없으면 대면 후보를 만들지 않는다`() {
        val result =
            DeterministicCandidateMatcher.generate(
                MeetingMode.IN_PERSON,
                listOf(
                    participant(1, CompatiblePlaceArea("AREA_1", "관악구 북부")),
                    participant(2, CompatiblePlaceArea("AREA_2", "강남역 일대")),
                ),
            )

        assertEquals(MatchOutcome.NO_MATCH, result.outcome)
        assertEquals(emptyList(), result.candidates)
    }

    @Test
    fun `시간은 모두 겹치지만 한 참여자의 장소가 멀면 공통 그룹 참여자만 Plan C로 만든다`() {
        val result =
            DeterministicCandidateMatcher.generate(
                MeetingMode.IN_PERSON,
                listOf(
                    participant(1, CompatiblePlaceArea("AREA_1", "관악구 북부")),
                    participant(2, CompatiblePlaceArea("AREA_1", "관악구 북부")),
                    participant(3, CompatiblePlaceArea("AREA_2", "강남역 일대")),
                ),
            )

        val candidate = result.candidates.single()
        assertEquals(PlanType.C, candidate.planType)
        assertEquals(listOf(ParticipantId(UUID(0, 1)), ParticipantId(UUID(0, 2))), candidate.participantIds)
        assertEquals("AREA_1", candidate.representativeArea?.key)
    }

    private fun participant(
        id: Long,
        vararg areas: CompatiblePlaceArea,
    ) = ParticipantMatchInput(
        participantId = ParticipantId(UUID(0, id)),
        availableTimes =
            listOf(
                InstantTimeRange(
                    Instant.parse("2026-09-21T09:00:00Z"),
                    Instant.parse("2026-09-21T10:00:00Z"),
                ),
            ),
        offlineArea = ParticipantAllowedArea(areas.toList()),
    )
}
