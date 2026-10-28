package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Independent review follow-up: all Unicode directional marks are data, not rendering commands. */
class PrecheckEditGuidanceBidiContractTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = PrecheckProviderContract(mapper)

    @ParameterizedTest
    @CsvSource("LUNA,1564", "GEMINI,1564", "LUNA,8206", "GEMINI,8206", "LUNA,8207", "GEMINI,8207")
    fun `EGB01 EDIT guidance rejects Arabic Letter Mark and left or right directional marks`(
        kind: String,
        codepoint: Int,
    ) {
        val request =
            PrecheckProviderRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000533"),
                "목요일은 예약이 끝난 뒤 가능해요.",
                Instant.parse("2026-10-28T06:00:00Z"),
                ZoneId.of("Asia/Seoul"),
                "ko-KR",
                LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 11, 15),
                Duration.ofSeconds(2),
            )
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "verdict" to "NEEDS_EDIT",
                    "prompt" to "예약이 끝나는${codepoint.toChar()}시각을 적어 주세요.",
                    "sourceExcerpt" to "예약이 끝난 뒤",
                    "choices" to null,
                ),
            )
        val failure =
            assertFailsWith<PrecheckProviderException> {
                if (kind == "LUNA") {
                    contract.parseLuna(
                        mapper.writeValueAsBytes(
                            mapOf(
                                "status" to "completed",
                                "output" to
                                    listOf(
                                        mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload))),
                                    ),
                            ),
                        ),
                        request,
                    )
                } else {
                    contract.parse(
                        mapper.writeValueAsBytes(
                            mapOf(
                                "candidates" to
                                    listOf(
                                        mapOf(
                                            "finishReason" to "STOP",
                                            "content" to mapOf("parts" to listOf(mapOf("text" to payload))),
                                        ),
                                    ),
                            ),
                        ),
                        request,
                    )
                }
            }
        assertEquals("INVALID_RESPONSE", failure.failureCode)
    }
}
