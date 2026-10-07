package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #99 / D2: actual JSON bytes round-trip without converting preferences into required conditions. */
class D2PreferenceJsonMapperTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `mixed hard conditions and preferences survive serialization as independent typed values`() {
        val conditions =
            listOf(
                StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0)),
                StructuredCondition.TimeWindow(TimePolarity.UNAVAILABLE, DATE, null, LocalTime.of(19, 0), LocalTime.of(20, 0)),
                StructuredCondition.SpecificPlace("강남역", areaKey = "AREA_1", areaName = "강남"),
                StructuredCondition.PreferredTimeWindow(DATE, null, LocalTime.of(20, 0), LocalTime.of(21, 0)),
                StructuredCondition.PreferredPlace("홍대", "AREA_2", "홍대"),
                StructuredCondition.PreferredPlace("강남", "AREA_1", "강남"),
            )
        val stored = conditions.map(StructuredConditionJsonMapper::toMap)
        val restored = decode(mapper.writeValueAsString(stored)).map(StructuredConditionJsonMapper::fromMap)
        assertEquals(conditions, restored)
        assertEquals(
            listOf(
                "TIME_WINDOW",
                "TIME_WINDOW",
                "SPECIFIC_PLACE",
                "PREFERRED_TIME_WINDOW",
                "PREFERRED_PLACE",
                "PREFERRED_PLACE",
            ),
            stored.map {
                it["type"]
            },
        )
        assertFalse(stored[3]["polarity"] in setOf("AVAILABLE", "UNAVAILABLE"))
        assertFalse(stored[4]["radius_meters"] is Number, "A soft preferred place cannot acquire a hard radius constraint")
    }

    @Test
    fun `dated and recurring midnight preferences preserve the exclusive next day boundary in JSON`() {
        listOf(
            StructuredCondition.PreferredTimeWindow(DATE, null, LocalTime.of(20, 0), LocalTime.MIDNIGHT, true),
            StructuredCondition.PreferredTimeWindow(null, DayOfWeek.THURSDAY, LocalTime.of(20, 0), LocalTime.MIDNIGHT, true),
        ).forEach { condition ->
            val stored = StructuredConditionJsonMapper.toMap(condition)
            assertEquals("24:00", stored["end_time"])
            val restored = StructuredConditionJsonMapper.fromMap(decode(mapper.writeValueAsString(listOf(stored))).single())
            assertEquals(condition, restored)
            assertTrue((restored as StructuredCondition.PreferredTimeWindow).endsAtNextDayStart)
        }
    }

    @Test
    fun `pre D2 stored JSON restores unchanged hard conditions with no inferred preference`() {
        val legacy =
            """[
              {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"MONDAY","start_time":"18:00","end_time":"24:00"},
              {"type":"TIME_WINDOW","polarity":"UNAVAILABLE","date":"2026-10-07","day_of_week":null,"start_time":"19:00","end_time":"20:00"},
              {"type":"SPECIFIC_PLACE","query":"강남역","radius_meters":1000},
              {"type":"SPECIFIC_PLACE","query":"홍대입구역","radius_meters":1000,"area_key":"AREA_2","area_name":"홍대"},
              {"type":"TRAVEL_CONSTRAINT","expression":"집에서 30분 이내"},
              {"type":"UNRESOLVED_PLACE","query":"회사 근처"}
            ]"""
        val restored = decode(legacy).map(StructuredConditionJsonMapper::fromMap)
        assertEquals(
            listOf(
                StructuredCondition.TimeWindow(
                    TimePolarity.AVAILABLE,
                    null,
                    DayOfWeek.MONDAY,
                    LocalTime.of(18, 0),
                    LocalTime.MIDNIGHT,
                    true,
                ),
                StructuredCondition.TimeWindow(TimePolarity.UNAVAILABLE, DATE, null, LocalTime.of(19, 0), LocalTime.of(20, 0)),
                StructuredCondition.SpecificPlace("강남역"),
                StructuredCondition.SpecificPlace("홍대입구역", areaKey = "AREA_2", areaName = "홍대"),
                StructuredCondition.TravelConstraint("집에서 30분 이내"),
                StructuredCondition.UnresolvedPlace("회사 근처"),
            ),
            restored,
        )
        assertTrue(restored.none { it is StructuredCondition.PreferredTimeWindow || it is StructuredCondition.PreferredPlace })
    }

    @Suppress("UNCHECKED_CAST")
    private fun decode(json: String) = mapper.readValue(json, List::class.java) as List<Map<String, Any?>>

    companion object {
        private val DATE = LocalDate.of(2026, 10, 7)
    }
}
