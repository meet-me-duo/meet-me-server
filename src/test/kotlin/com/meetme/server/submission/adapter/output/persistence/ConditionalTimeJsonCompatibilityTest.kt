package com.meetme.server.submission.adapter.output.persistence

import com.meetme.server.submission.domain.ConditionalTimeActivation
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The additive conditional representation must not rewrite or flatten existing persisted flat conditions. */
class ConditionalTimeJsonCompatibilityTest {
    private val mapper = JsonMapper.builder().build()
    private val weekly =
        StructuredCondition.ConditionalFallbackTimeWindow(
            ConditionalTimeActivation.WHOLE_ROOM_PRIMARY_UNAVAILABLE,
            null,
            DayOfWeek.SATURDAY,
            LocalTime.of(11, 0),
            LocalTime.of(15, 0),
        )

    @Test
    fun `conditional weekly and dated windows use only the agreed fields and restore their activation`() {
        for (condition in listOf(weekly, weekly.copy(date = LocalDate.of(2026, 11, 7), dayOfWeek = null))) {
            val stored = StructuredConditionJsonMapper.toMap(condition)
            assertEquals(
                setOf("type", "activation", "date", "day_of_week", "start_time", "end_time"),
                stored.keys,
            )
            assertEquals("CONDITIONAL_FALLBACK_TIME_WINDOW", stored["type"])
            assertEquals("WHOLE_ROOM_PRIMARY_UNAVAILABLE", stored["activation"])
            assertFalse("polarity" in stored)
            assertEquals(condition, StructuredConditionJsonMapper.fromMap(stored))
        }
    }

    @Test
    fun `primary preference and conditional remain three distinct categories after actual JSON serialization`() {
        val primary =
            StructuredCondition.TimeWindow(
                TimePolarity.AVAILABLE,
                null,
                DayOfWeek.WEDNESDAY,
                LocalTime.of(14, 0),
                LocalTime.of(18, 0),
            )
        val preferred =
            StructuredCondition.PreferredTimeWindow(
                null,
                DayOfWeek.WEDNESDAY,
                LocalTime.of(14, 0),
                LocalTime.of(18, 0),
            )
        val conditions = listOf(primary, preferred, weekly)
        val json = mapper.writeValueAsString(conditions.map(StructuredConditionJsonMapper::toMap))
        val decoded = mapper.readValue(json, object : TypeReference<List<Map<String, Any?>>>() {})
        assertEquals(conditions, decoded.map(StructuredConditionJsonMapper::fromMap))
        assertEquals(
            listOf("TIME_WINDOW", "PREFERRED_TIME_WINDOW", "CONDITIONAL_FALLBACK_TIME_WINDOW"),
            decoded.map { it["type"] },
        )
    }

    @Test
    fun `conditional 24 hour end retains the next day boundary rather than becoming an empty midnight interval`() {
        val condition = weekly.copy(startTime = LocalTime.of(22, 0), endTime = LocalTime.MIDNIGHT, endsAtNextDayStart = true)
        val stored = StructuredConditionJsonMapper.toMap(condition)
        assertEquals("24:00", stored["end_time"])
        assertEquals(condition, StructuredConditionJsonMapper.fromMap(stored))
    }

    @Test
    fun `old literal flat JSON keeps its exact field representation and semantic roundtrip`() {
        val legacyJson = """[
          {"type":"TIME_WINDOW","polarity":"AVAILABLE","date":null,"day_of_week":"WEDNESDAY","start_time":"14:00","end_time":"18:00"},
          {"type":"TIME_WINDOW","polarity":"UNAVAILABLE","date":"2026-11-06","day_of_week":null,"start_time":"22:00","end_time":"24:00"},
          {"type":"PREFERRED_TIME_WINDOW","polarity":null,"date":null,"day_of_week":"FRIDAY","start_time":"10:00","end_time":"12:00"},
          {"type":"SPECIFIC_PLACE","query":"봉천역","radius_meters":1000},
          {"type":"PREFERRED_PLACE","query":"강남역","radius_meters":null,"area_key":"AREA_1","area_name":"강남"},
          {"type":"TRAVEL_CONSTRAINT","expression":"집에서 30분 이내"},
          {"type":"UNRESOLVED_PLACE","query":"중앙역"}
        ]"""
        val legacy = mapper.readValue(legacyJson, object : TypeReference<List<Map<String, Any?>>>() {})
        val restored = legacy.map(StructuredConditionJsonMapper::fromMap)
        assertEquals(legacy, restored.map(StructuredConditionJsonMapper::toMap))
        assertEquals(2, restored.filterIsInstance<StructuredCondition.TimeWindow>().size)
        assertEquals(1, restored.filterIsInstance<StructuredCondition.PreferredTimeWindow>().size)
        assertEquals(0, restored.filterIsInstance<StructuredCondition.ConditionalFallbackTimeWindow>().size)
    }

    @Test
    fun `unknown persisted activation cannot be restored as ordinary availability`() {
        val stored = StructuredConditionJsonMapper.toMap(weekly) + ("activation" to "SUBSET_PRIMARY_UNAVAILABLE")
        assertFailsWith<IllegalArgumentException> { StructuredConditionJsonMapper.fromMap(stored) }
    }
}
