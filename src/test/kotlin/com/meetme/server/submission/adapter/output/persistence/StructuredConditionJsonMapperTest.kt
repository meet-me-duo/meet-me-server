package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredConditionJsonMapperTest {
    @Test
    fun `Gemini 장소 호환 그룹과 대표 지역명을 저장하고 복원한다`() {
        val condition =
            StructuredCondition.SpecificPlace(
                query = "봉천역",
                areaKey = "AREA_1",
                areaName = "관악구 북부",
            )

        val stored = StructuredConditionJsonMapper.toMap(condition)
        val restored = StructuredConditionJsonMapper.fromMap(stored)

        assertEquals("AREA_1", stored["area_key"])
        assertEquals("관악구 북부", stored["area_name"])
        assertEquals(condition, restored)
    }

    @Test
    fun `다음 날 자정 경계를 24시로 저장하고 같은 의미로 복원한다`() {
        val condition =
            StructuredCondition.TimeWindow(
                polarity = TimePolarity.AVAILABLE,
                date = null,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = LocalTime.of(18, 0),
                endTime = LocalTime.MIDNIGHT,
                endsAtNextDayStart = true,
            )

        val stored = StructuredConditionJsonMapper.toMap(condition)
        val restored = StructuredConditionJsonMapper.fromMap(stored) as StructuredCondition.TimeWindow

        assertEquals("24:00", stored["end_time"])
        assertEquals(LocalTime.MIDNIGHT, restored.endTime)
        assertTrue(restored.endsAtNextDayStart)
    }
}
