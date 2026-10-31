package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceFinalContractTest {
    @Test
    fun `EX-ISOLATION unresolved empty input is excluded without aborting other valid results`() {
        val anchor = Instant.parse("2026-11-01T03:00:00Z")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val inputs =
            listOf(
                NaturalLanguageInput(
                    first,
                    "끝나면 가능",
                    Locale.KOREAN,
                    LocalDate.parse("2026-11-01"),
                    InputEvidence(1, anchor, interpretation = InputInterpretation.UNRESOLVED),
                ),
                NaturalLanguageInput(second, "11월 5일 19시부터 21시 가능", Locale.KOREAN, LocalDate.parse("2026-11-01"), InputEvidence(1, anchor)),
            )
        val request =
            NaturalLanguageBatchRequest(ZoneId.of("Asia/Seoul"), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-15"), inputs)
        val json = """{"schema_version":"3","results":[
            {"input_ref":"$first","conditions":[],"rejection_code":null,"clarification":null},
            {"input_ref":"$second","conditions":[{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-11-05","day_of_week":null,"start_time":"19:00","end_time":"21:00"}],"rejection_code":null,"clarification":null}
        ]}"""
        val results = NaturalLanguageProviderContract(JsonMapper.builder().build()).parseProviderResponse(json, request, strict = true)
        assertEquals("AMBIGUOUS_INPUT", results.first().rejectionCode)
        assertTrue(results.first().conditions.isEmpty())
        assertEquals(null, results.last().rejectionCode)
        assertEquals(1, results.last().conditions.size)
    }

    @Test
    fun `EX-FINAL final provider receives frozen original scoped answer and unresolved state without replacement`() {
        val original = "목요일 7시부터 9시 가능, 장소는 아직 모르겠어"
        val anchor = Instant.parse("2026-11-01T03:00:00Z")
        val evidence =
            InputEvidence(
                1,
                anchor,
                InputSupplement(UUID.randomUUID(), 1, "7시부터 9시", 4, "오전인가요 오후인가요?", "오후", "B", anchor.plusSeconds(30), anchor),
                InputInterpretation.UNRESOLVED,
                "CLASSIFIER_UNCERTAIN",
            )
        val request =
            NaturalLanguageBatchRequest(
                ZoneId.of("Asia/Seoul"),
                LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-15"),
                listOf(
                    NaturalLanguageInput(UUID.randomUUID().toString(), original, Locale.KOREAN, LocalDate.parse("2026-11-01"), evidence),
                ),
            )
        val prompt = NaturalLanguageProviderContract(JsonMapper.builder().build()).prompt(request)
        assertTrue(prompt.contains(original))
        assertTrue(prompt.contains("source_excerpt"))
        assertTrue(prompt.contains("answer_text"))
        assertTrue(prompt.contains("UNRESOLVED"))
        assertTrue(prompt.contains("CLASSIFIER_UNCERTAIN"))
        assertFalse(prompt.contains("weekday 7–10 becomes 19–22"))
        assertTrue(prompt.contains("00:00"))
        assertTrue(prompt.contains("12:00"))
        assertTrue(prompt.contains("24:00"))
    }
}
