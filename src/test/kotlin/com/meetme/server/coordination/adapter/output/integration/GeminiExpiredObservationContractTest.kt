package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoInteractions
import tools.jackson.databind.json.JsonMapper
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Real gate expiry, fake SDK timeout and no HTTP send. This does not identify the cause of any live result. */
class GeminiExpiredObservationContractTest {
    @Test
    fun `real expired gate observation preserves the typed SDK timeout rather than inventing configuration`() {
        val nanos = AtomicLong(100)
        val cutoff = 1000L
        val wall = Instant.parse("2026-11-02T03:00:00Z")
        val budget = mock(DurableAiBudgetPort::class.java)
        val gate =
            AiHttpDispatchGate(
                AiDispatchGateProperties(enabled = true),
                budget,
                Clock.fixed(wall, ZoneOffset.UTC),
                LongSupplier(nanos::get),
                AiRuntimeDispatchGuardPort { _, _ -> wall.plusSeconds(60) },
                enforceBudget = false,
            )
        val primary = RuntimeException(SocketTimeoutException("synthetic SDK timeout"))
        val request = request(cutoff)

        mockStatic(Client::class.java).use { factory ->
            factory.`when`<Any> { Client.builder() }.thenAnswer {
                nanos.set(cutoff)
                throw primary
            }
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    GeminiNaturalLanguageParserAdapter(
                        GeminiProperties(apiKey = "synthetic-placeholder-never-used"),
                        JsonMapper.builder().build(),
                        dispatchGate = gate,
                    ).parse(request)
                }

            assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
            assertEquals(true, failure.retryable)
            assertSame(primary, failure.cause)
            factory.verify({ Client.builder() }, times(1))
        }
        verifyNoInteractions(budget)
    }

    private fun request(cutoff: Long): NaturalLanguageBatchRequest {
        val date = LocalDate.of(2026, 11, 2)
        val zone = ZoneId.of("Asia/Seoul")
        val inputs = listOf(NaturalLanguageInput(UUID(0, 1).toString(), "11월 2일 19시부터 21시 가능", Locale.KOREAN, date))
        val snapshots = inputs.map { AiDispatchInputSnapshot(it.inputRef, it.rawText, it.locale.toLanguageTag(), it.referenceDate) }
        val execution =
            AiBudgetExecution(
                UUID(0, 2),
                AiBudgetExecutionKind.FINAL_ANALYSIS,
                UUID(0, 3),
                0,
                UUID(0, 4),
                0,
                AiDispatchFingerprints.batchSource(snapshots),
                AiDispatchFingerprints.batchContext(zone, date, date.plusDays(7), snapshots),
            )
        return NaturalLanguageBatchRequest(
            zone,
            date,
            date.plusDays(7),
            inputs,
            dispatchContext = AiProviderDispatchContext(execution, cutoff, UUID(0, 5)),
        )
    }
}
