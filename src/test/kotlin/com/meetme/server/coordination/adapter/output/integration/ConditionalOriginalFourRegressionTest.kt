package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recorded old response and manually corrected relation fixture are deliberately distinct evidence. */
class ConditionalOriginalFourRegressionTest {
    @Test
    fun `original 4 28 30 keep their recorded values while 48 requires an explicit relation contract`() {
        val mapper = JsonMapper.builder().build()
        val fixture =
            mapper.readTree(
                requireNotNull(javaClass.getResourceAsStream("/features/conditional-time/original-four-regression.json")),
            )
        val inputs =
            fixture["inputs"]
                .asSequence()
                .map {
                    NaturalLanguageInput(it["inputRef"].asText(), it["rawText"].asText(), Locale.KOREAN, LocalDate.of(2026, 11, 2))
                }.toList()
        val request = NaturalLanguageBatchRequest(ZoneId.of("Asia/Seoul"), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9), inputs)
        val contract = NaturalLanguageProviderContract(mapper)
        val old = contract.parseProviderResponse(mapper.writeValueAsString(fixture["recorded_response"]), request, strict = true)
        val corrected =
            contract.parseProviderResponse(
                mapper.writeValueAsString(fixture["expected_relation_response"]),
                request,
                strict = true,
            )
        assertEquals(old.take(3), corrected.take(3))
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).map { weekly(it, 10, 12) }, corrected[0].conditions)
        assertEquals(listOf(dated(3, 14, 16), dated(4, 10, 12)), corrected[1].conditions)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY).map { weekly(it, 19, 22) }, corrected[2].conditions)
        assertTrue(corrected.all { it.rejectionCode == null && it.clarification == null })
        val relation = corrected[3].conditions
        assertEquals(
            listOf(weekly(DayOfWeek.WEDNESDAY, 14, 18), weekly(DayOfWeek.FRIDAY, 10, 12)),
            relation.filterIsInstance<StructuredCondition.TimeWindow>(),
        )
        assertEquals(2, relation.filterIsInstance<StructuredCondition.PreferredTimeWindow>().size)
        assertEquals(1, relation.filterIsInstance<StructuredCondition.ConditionalFallbackTimeWindow>().size)
        // Raw text is never used to silently rewrite an already flat provider result.
        assertNull(old[3].rejectionCode)
        assertTrue(old[3].conditions.none { it is StructuredCondition.ConditionalFallbackTimeWindow })
        assertEquals(listOf(weekly(DayOfWeek.SATURDAY, 11, 15)), old[3].conditions.filterIsInstance<StructuredCondition.TimeWindow>())
    }

    private fun weekly(
        day: DayOfWeek,
        start: Int,
        end: Int,
    ) = StructuredCondition.TimeWindow(
        TimePolarity.AVAILABLE,
        null,
        day,
        LocalTime.of(start, 0),
        LocalTime.of(end, 0),
    )

    private fun dated(
        day: Int,
        start: Int,
        end: Int,
    ) = StructuredCondition.TimeWindow(
        TimePolarity.AVAILABLE,
        LocalDate.of(2026, 11, day),
        null,
        LocalTime.of(start, 0),
        LocalTime.of(end, 0),
    )
}
