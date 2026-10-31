package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.GatedAiHttpExchange
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Factory faults and in-process HTTP interception only: zero provider connection, key use or paid invocation. */
class GeminiObservationFailureContractTest {
    enum class Failure { TIMEOUT, SERVER, NETWORK, INVALID_RESPONSE, CONFIGURATION }

    private val mapper = JsonMapper.builder().build()
    private val properties = GeminiProperties(apiKey = "synthetic-placeholder-never-sent")

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `failed late observation cannot replace an already classified primary failure`(kind: Failure) {
        val primary =
            when (kind) {
                Failure.TIMEOUT -> RuntimeException(SocketTimeoutException("synthetic SDK timeout"))
                Failure.SERVER -> ApiException(503, "UNAVAILABLE", "synthetic provider unavailable")
                Failure.NETWORK -> RuntimeException(IOException("synthetic transport failure"))
                Failure.INVALID_RESPONSE -> RuntimeException("synthetic untyped response failure")
                Failure.CONFIGURATION -> RuntimeException(AiHttpDispatchException())
            }
        val observations = AtomicInteger()
        val gate = gate(observations, OkHttpClient())
        val request = request()

        mockStatic(Client::class.java).use { factory ->
            factory.`when`<Any> { Client.builder() }.thenThrow(primary)
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    GeminiNaturalLanguageParserAdapter(properties, mapper, dispatchGate = gate).parse(request)
                }

            assertEquals(ParserFailureKind.valueOf(kind.name), failure.kind)
            assertEquals(kind in setOf(Failure.TIMEOUT, Failure.SERVER, Failure.NETWORK), failure.retryable)
            if (kind != Failure.CONFIGURATION) assertSame(primary, failure.cause)
            assertEquals(1, observations.get())
            factory.verify({ Client.builder() }, times(1))
        }
    }

    @Test
    fun `a successful SDK response with rejected observation remains nonretryable configuration failure`() {
        val request = request()
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        listOf(
                            mapOf(
                                "input_ref" to request.inputs.single().inputRef,
                                "conditions" to
                                    listOf(
                                        mapOf(
                                            "type" to "TIME_WINDOW",
                                            "polarity" to "AVAILABLE",
                                            "date" to "2026-11-02",
                                            "day_of_week" to null,
                                            "start_time" to "19:00",
                                            "end_time" to "21:00",
                                        ),
                                    ),
                                "rejection_code" to null,
                                "clarification" to null,
                            ),
                        ),
                ),
            )
        val envelope =
            mapper.writeValueAsString(
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "finishReason" to "STOP",
                                "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to payload))),
                            ),
                        ),
                    "usageMetadata" to mapOf("promptTokenCount" to 2, "candidatesTokenCount" to 3, "thoughtsTokenCount" to 0),
                ),
            )
        val requests = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(envelope.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        val observations = AtomicInteger()

        val failure =
            assertFailsWith<NaturalLanguageParserException> {
                GeminiNaturalLanguageParserAdapter(properties, mapper, dispatchGate = gate(observations, client)).parse(request)
            }

        assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
        assertEquals(false, failure.retryable)
        assertEquals(1, requests.get())
        assertEquals(1, observations.get())
    }

    @Test
    fun `a genuine source fence mismatch stays configuration and invokes no SDK client`() {
        val valid = request()
        val context = requireNotNull(valid.dispatchContext)
        val request = valid.copy(dispatchContext = context.copy(execution = context.execution.copy(sourceFingerprint = "0".repeat(64))))
        val observations = AtomicInteger()
        val gate = gate(observations, OkHttpClient())

        mockStatic(Client::class.java).use { factory ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    GeminiNaturalLanguageParserAdapter(properties, mapper, dispatchGate = gate).parse(request)
                }

            assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
            assertEquals(false, failure.retryable)
            assertEquals(0, observations.get())
            factory.verify({ Client.builder() }, never())
        }
    }

    private fun gate(
        observations: AtomicInteger,
        client: OkHttpClient,
    ): AiHttpDispatchGate {
        val gate = mock(AiHttpDispatchGate::class.java)
        Mockito.`when`(gate.properties).thenReturn(AiDispatchGateProperties(enabled = true))
        val exchange =
            GatedAiHttpExchange(client) { _, _, _, _ ->
                observations.incrementAndGet()
                throw AiHttpDispatchException()
            }
        doReturn(exchange).`when`(gate).forLazySingleCall(anyValue(), anyValue(), anyValue(), anyValue())
        return gate
    }

    private fun request(): NaturalLanguageBatchRequest {
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
            dispatchContext = AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofMinutes(1).toNanos(), UUID(0, 5)),
        )
    }

    private fun <T> anyValue(): T = Mockito.any<T>()
}
