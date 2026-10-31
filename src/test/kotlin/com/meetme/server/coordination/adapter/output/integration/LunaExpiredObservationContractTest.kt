package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
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
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PRD failure taxonomy and ADR-049: a secondary observation denial cannot invent a different provider failure.
 * Real gate expiry with deterministic intercepted HTTP; no provider connection, paid call or production timeout change.
 */
class LunaExpiredObservationContractTest {
    @Test
    fun `observation at exact dispatch deadline preserves the primary timeout and cause`() {
        val fixture = Fixture(Reply.TIMEOUT)

        val failure = assertFailsWith<NaturalLanguageParserException> { fixture.parse() }

        assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
        assertTrue(failure.retryable)
        assertSame(fixture.primary, failure.cause)
        fixture.assertOneInterceptedAttempt()
    }

    @Test
    fun `observation at exact dispatch deadline preserves the primary network failure and cause`() {
        val fixture = Fixture(Reply.NETWORK)

        val failure = assertFailsWith<NaturalLanguageParserException> { fixture.parse() }

        assertEquals(ParserFailureKind.NETWORK, failure.kind)
        assertTrue(failure.retryable)
        assertSame(fixture.primary, failure.cause)
        fixture.assertOneInterceptedAttempt()
    }

    @Test
    fun `expired observation preserves a transient provider status and its retry after`() {
        val fixture = Fixture(Reply.SERVER)

        val failure = assertFailsWith<NaturalLanguageParserException> { fixture.parse() }

        assertEquals(ParserFailureKind.SERVER, failure.kind)
        assertTrue(failure.retryable)
        assertEquals(2_000L, failure.retryAfterMillis)
        fixture.assertOneInterceptedAttempt()
    }

    @Test
    fun `valid response still fails closed when its observation is rejected at exact dispatch deadline`() {
        val fixture = Fixture(Reply.SUCCESS)

        val failure = assertFailsWith<NaturalLanguageParserException> { fixture.parse() }

        assertEquals(ParserFailureKind.CONFIGURATION, failure.kind)
        assertFalse(failure.retryable)
        fixture.assertOneInterceptedAttempt()
    }

    @Test
    fun `valid response and observation before dispatch deadline preserve successful parsing`() {
        val fixture = Fixture(Reply.SUCCESS, expire = false)

        val result = fixture.parse()

        assertEquals(1, result.results.size)
        fixture.assertOneInterceptedAttempt()
    }

    private enum class Reply { TIMEOUT, NETWORK, SERVER, SUCCESS }

    private class Fixture(
        reply: Reply,
        expire: Boolean = true,
    ) {
        private val mapper = JsonMapper.builder().build()
        private val nanos = AtomicLong(0)
        private val cutoff = Duration.ofSeconds(10).toNanos()
        private val calls = AtomicInteger()
        private val dnsCalls = AtomicInteger()
        private val authorizations = AtomicInteger()
        private val budget = mock(DurableAiBudgetPort::class.java)
        private val wall = Instant.parse("2026-11-02T03:00:00Z")
        val primary: IOException =
            if (reply == Reply.TIMEOUT) SocketTimeoutException("synthetic timeout") else IOException("synthetic network failure")
        private val date = LocalDate.of(2026, 11, 2)
        private val zone = ZoneId.of("Asia/Seoul")
        private val inputs =
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "11월 2일 19시부터 21시 가능", Locale.KOREAN, date))
        private val snapshots =
            inputs.map { AiDispatchInputSnapshot(it.inputRef, it.rawText, it.locale.toLanguageTag(), it.referenceDate) }
        private val execution =
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
        private val request =
            NaturalLanguageBatchRequest(
                zone,
                date,
                date.plusDays(7),
                inputs,
                callTimeout = Duration.ofSeconds(27),
                dispatchContext = AiProviderDispatchContext(execution, cutoff, UUID(0, 5)),
            )
        private val gate =
            AiHttpDispatchGate(
                AiDispatchGateProperties(enabled = true),
                budget,
                Clock.fixed(wall, ZoneOffset.UTC),
                LongSupplier(nanos::get),
                AiRuntimeDispatchGuardPort { _, _ ->
                    authorizations.incrementAndGet()
                    wall.plusSeconds(60)
                },
                enforceBudget = false,
            )
        private val client =
            OkHttpClient
                .Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            dnsCalls.incrementAndGet()
                            throw UnknownHostException("Synthetic contract forbids network")
                        }
                    },
                ).addInterceptor { chain ->
                    calls.incrementAndGet()
                    check(authorizations.get() > 0)
                    if (expire) nanos.set(cutoff)
                    when (reply) {
                        Reply.TIMEOUT, Reply.NETWORK -> throw primary
                        Reply.SERVER ->
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(503)
                                .message("synthetic unavailable")
                                .header("Retry-After", "2")
                                .body("{\"error\":{\"code\":\"server_error\"}}".toResponseBody("application/json".toMediaType()))
                                .build()
                        Reply.SUCCESS ->
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("synthetic completed")
                                .body(envelope().toResponseBody("application/json".toMediaType()))
                                .build()
                    }
                    // Every branch terminates here; none calls chain.proceed or opens a connection.
                }.build()

        fun parse() =
            OpenAiLunaNaturalLanguageParserAdapter(
                OpenAiProperties(apiKey = "synthetic-placeholder-never-sent"),
                mapper,
                client,
                URI("https://api.openai.com/v1/responses"),
                dispatchGate = gate,
            ).parse(request)

        fun assertOneInterceptedAttempt() {
            assertEquals(1, calls.get())
            assertEquals(0, dnsCalls.get())
            assertTrue(authorizations.get() > 0)
            verifyNoInteractions(budget)
        }

        private fun envelope(): String {
            val text =
                mapper.writeValueAsString(
                    mapOf(
                        "schema_version" to "3",
                        "results" to
                            listOf(
                                mapOf(
                                    "input_ref" to inputs.single().inputRef,
                                    "rejection_code" to null,
                                    "conditions" to
                                        listOf(
                                            mapOf(
                                                "type" to "TIME_WINDOW",
                                                "polarity" to "AVAILABLE",
                                                "date" to date.toString(),
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
                                "content" to listOf(mapOf("type" to "output_text", "text" to text)),
                            ),
                        ),
                ),
            )
        }
    }
}
