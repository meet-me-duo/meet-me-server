package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #99 approved transport budget: injected interceptor returns synthetic bytes without proceeding to any network. */
class LunaExtendedTransportBudgetContractTest {
    private val mapper = JsonMapper.builder().build()
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "합성 수요일19–21", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )

    @ParameterizedTest
    @CsvSource("27000,27000", "60000,27000", "350,350")
    fun `actual Luna call timeout permits 27 seconds while respecting shorter caller time and one transport attempt`(
        requestedMillis: Long,
        maximumMillis: Long,
    ) {
        val observedTimeouts = mutableListOf<Duration>()
        val client =
            OkHttpClient
                .Builder()
                .callTimeout(Duration.ofSeconds(2))
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    observedTimeouts += Duration.ofNanos(chain.call().timeout().timeoutNanos())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("synthetic success")
                        .body(envelope().toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        val adapter =
            OpenAiLunaNaturalLanguageParserAdapter(
                OpenAiProperties(apiKey = "synthetic-unused-placeholder"),
                mapper,
                client,
                URI.create("http://127.0.0.1:9/responses"),
            )

        val result = adapter.parse(request.copy(callTimeout = Duration.ofMillis(requestedMillis)))

        assertEquals(1, observedTimeouts.size, "One adapter invocation must issue exactly one transport attempt")
        assertEquals(1, result.results.size)
        assertTrue(observedTimeouts.single() > Duration.ZERO)
        assertTrue(observedTimeouts.single() <= Duration.ofMillis(maximumMillis), "The transport must honor the caller and approved cap")
        if (maximumMillis == 27_000L) {
            assertTrue(
                observedTimeouts.single() > Duration.ofSeconds(20),
                "The adapter must not silently truncate the approved 27 seconds to 15",
            )
        }
    }

    private fun envelope(): String {
        val wire =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "3",
                    "results" to
                        listOf(
                            mapOf(
                                "input_ref" to request.inputs.single().inputRef,
                                "rejection_code" to null,
                                "conditions" to
                                    listOf(
                                        mapOf(
                                            "type" to "TIME_WINDOW",
                                            "polarity" to "AVAILABLE",
                                            "date" to "2026-10-07",
                                            "day_of_week" to null,
                                            "start_time" to "19:00",
                                            "end_time" to "21:00",
                                        ),
                                    ),
                            ),
                        ),
                ),
            )
        return mapper.writeValueAsString(
            mapOf(
                "status" to "completed",
                "output" to
                    listOf(
                        mapOf(
                            "type" to "message",
                            "content" to listOf(mapOf("type" to "output_text", "text" to wire)),
                        ),
                    ),
            ),
        )
    }
}
