package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.application.port.output.ClockClassificationException
import com.meetme.server.precheck.domain.ClockCandidate
import com.meetme.server.precheck.domain.ClockPeriod
import com.meetme.server.precheck.domain.ClockRelevance
import com.meetme.server.precheck.domain.PeriodClarificationFixtures
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class JevClockClassificationPortsTest {
    private val mapper = JsonMapper.builder().build()
    private val key = "synthetic-relevance-contract-key"
    private val rules = "DRAFT_NOT_FROZEN"

    @Test
    fun `finite confidence below probability scale limit is retained across transport`() {
        val response = relevanceSuccess().replace("\"confidence\":0.01", "\"confidence\":1e-65")
        assertFalse(response == relevanceSuccess())
        LoopbackEndpoint(200, response).use { endpoint ->
            val accounting = AccountingRecorder()

            val result =
                ports(endpoint, accounting).relevancePort.classify(
                    PeriodClarificationFixtures.input(14),
                    relevanceCandidates(),
                )

            assertEquals(ClockRelevance.RELEVANT, result.getValue("q02").uniqueChoice())
            assertEquals("1e-65".toBigDecimal(), result.getValue("q02").confidence)
            assertAccounted(endpoint, accounting, "settle", 23L, 7L)
        }
    }

    @Test
    fun `reserved body fingerprint rejects an injected interceptor changing only model before HTTP`() {
        assertMutationRejectedBeforePhysicalDispatch { request ->
            val original = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val changed = original.replace("\"model\":\"jev-1.14.0\"", "\"model\":\"synthetic-other-model\"")
            assertFalse(original == changed)
            request.newBuilder().post(changed.toRequestBody(request.body!!.contentType())).build()
        }
    }

    @Test
    fun `reserved request rejects an injected interceptor changing authorization before HTTP`() {
        assertMutationRejectedBeforePhysicalDispatch { request ->
            request.newBuilder().header("Authorization", "Bearer synthetic-another-account-key").build()
        }
    }

    @Test
    fun `default off and unfrozen official policy reject before HTTP`() {
        LoopbackEndpoint(200, relevanceSuccess()).use { endpoint ->
            val accounting = AccountingRecorder()
            assertThrows(ClockClassificationException::class.java) {
                val ports =
                    JevClockClassificationPorts(
                        key,
                        mapper,
                        accounting,
                        JevClockPolicy(rules),
                        JevClassifierOptions(endpoint = endpoint.uri),
                    )
                ports.relevancePort.classify(PeriodClarificationFixtures.input(14), relevanceCandidates())
            }
            assertTrue(endpoint.requests.isEmpty())
            assertTrue(accounting.events.isEmpty())
        }
        val interceptions = AtomicInteger()
        val noNetwork =
            OkHttpClient
                .Builder()
                .addInterceptor {
                    interceptions.incrementAndGet()
                    throw IOException("SYNTHETIC_OFFICIAL_NETWORK_FORBIDDEN")
                }.build()
        assertThrows(ClockClassificationException::class.java) {
            val ports =
                JevClockClassificationPorts(
                    key,
                    mapper,
                    AccountingRecorder(),
                    JevClockPolicy(rules),
                    JevClassifierOptions(enabled = true),
                    noNetwork,
                )
            ports.relevancePort.classify(PeriodClarificationFixtures.input(14), relevanceCandidates())
        }
        assertEquals(0, interceptions.get())
    }

    @Test
    fun `relevance batches all candidates with exact raw and settles known usage once`() {
        LoopbackEndpoint(200, relevanceSuccess()).use { endpoint ->
            val accounting = AccountingRecorder()
            val candidates = relevanceCandidates()

            val result =
                ports(endpoint, accounting).relevancePort.classify(
                    PeriodClarificationFixtures.input(14),
                    candidates,
                )

            assertEquals(setOf("q01", "q02"), result.keys)
            assertEquals(ClockRelevance.BACKGROUND, result.getValue("q01").uniqueChoice())
            assertEquals(ClockRelevance.RELEVANT, result.getValue("q02").uniqueChoice())
            assertEquals(
                "0.900000000000000001".toBigDecimal(),
                result.getValue("q02").probabilities.getValue(ClockRelevance.RELEVANT),
            )
            assertEquals("0.01".toBigDecimal(), result.getValue("q02").confidence)
            assertWire(endpoint, 14, candidates, ClockRelevance.entries.map { it.name }.toSet())
            assertAccounted(endpoint, accounting, "settle", 23L, 7L)
        }
    }

    @Test
    fun `period four option response preserves raw tie confidence and exact probabilities`() {
        LoopbackEndpoint(200, periodSuccess()).use { endpoint ->
            val accounting = AccountingRecorder()
            val candidates = periodCandidates()

            val result =
                ports(endpoint, accounting).periodPort.classify(
                    PeriodClarificationFixtures.input(43),
                    candidates,
                )

            val tied = result.getValue("q01")
            assertEquals(ClockPeriod.NOT_STATED, tied.choice)
            assertNull(tied.uniqueChoice())
            assertEquals("0.45".toBigDecimal(), tied.probabilities.getValue(ClockPeriod.PM))
            assertEquals("0.45".toBigDecimal(), tied.probabilities.getValue(ClockPeriod.NOT_STATED))
            assertEquals("0.88".toBigDecimal(), tied.confidence)
            assertEquals(ClockPeriod.PM, result.getValue("q02").uniqueChoice())
            assertEquals("0.01".toBigDecimal(), result.getValue("q02").confidence)
            assertWire(endpoint, 43, candidates, ClockPeriod.entries.map { it.name }.toSet())
            assertAccounted(endpoint, accounting, "settle", 23L, 7L)
        }
    }

    @Test
    fun `503 retry after zero causes one physical request and unknown usage without retry`() {
        LoopbackEndpoint(503, "{}", mapOf("Retry-After" to "0")).use { endpoint ->
            val accounting = AccountingRecorder()

            assertThrows(ClockClassificationException::class.java) {
                ports(endpoint, accounting).relevancePort.classify(
                    PeriodClarificationFixtures.input(14),
                    relevanceCandidates(),
                )
            }

            assertAccounted(endpoint, accounting, "unknown")
        }
    }

    @Test
    fun `redirect is rejected rather than following a second request`() {
        LoopbackEndpoint(302, "{}", redirect = true).use { endpoint ->
            val accounting = AccountingRecorder()

            assertThrows(ClockClassificationException::class.java) {
                ports(endpoint, accounting).relevancePort.classify(
                    PeriodClarificationFixtures.input(14),
                    relevanceCandidates(),
                )
            }

            assertAccounted(endpoint, accounting, "unknown")
        }
    }

    @Test
    fun `known usage settles before rejecting returned choice outside maximum set`() {
        val invalid =
            periodSuccess().replace(
                "\"choice\":\"NOT_STATED\",\"probabilities\":{\"AM\":0.10,\"PM\":0.45,\"NOT_STATED\":0.45",
                "\"choice\":\"NOT_STATED\",\"probabilities\":{\"AM\":0.10,\"PM\":0.46,\"NOT_STATED\":0.44",
            )
        assertFalse(invalid == periodSuccess())
        LoopbackEndpoint(200, invalid).use { endpoint ->
            val accounting = AccountingRecorder()

            val failure =
                assertThrows(ClockClassificationException::class.java) {
                    ports(endpoint, accounting).periodPort.classify(
                        PeriodClarificationFixtures.input(43),
                        periodCandidates(),
                    )
                }

            assertEquals("CHOICE_ARGMAX_MISMATCH", failure.failureCode)
            assertAccounted(endpoint, accounting, "settle", 23L, 7L)
        }
    }

    @Test
    fun `known usage settles before missing answer qid is rejected`() {
        val invalid =
            envelope(
                "\"q01\":${relevanceAnswer("BACKGROUND", "0.1", "0.8", "0.1", "0.03")}",
            )
        LoopbackEndpoint(200, invalid).use { endpoint ->
            val accounting = AccountingRecorder()

            val failure =
                assertThrows(ClockClassificationException::class.java) {
                    ports(endpoint, accounting).relevancePort.classify(
                        PeriodClarificationFixtures.input(14),
                        relevanceCandidates(),
                    )
                }

            assertEquals("INVALID_RESPONSE", failure.failureCode)
            assertAccounted(endpoint, accounting, "settle", 23L, 7L)
        }
    }

    @Test
    fun `missing usage reports unknown once and never settles or returns answers`() {
        val response =
            relevanceSuccess().replace(
                ",\"usage\":{\"input_tokens\":23,\"output_tokens\":7}",
                "",
            )
        assertFalse(response == relevanceSuccess())
        LoopbackEndpoint(200, response).use { endpoint ->
            val accounting = AccountingRecorder()

            val failure =
                assertThrows(ClockClassificationException::class.java) {
                    ports(endpoint, accounting).relevancePort.classify(
                        PeriodClarificationFixtures.input(14),
                        relevanceCandidates(),
                    )
                }

            assertEquals("USAGE_UNKNOWN", failure.failureCode)
            assertAccounted(endpoint, accounting, "unknown")
        }
    }

    @Test
    fun `duplicate JSON keys reject before any untrusted usage is settled`() {
        val response =
            relevanceSuccess().replace(
                "\"model\":\"jev-1.14.0\"",
                "\"model\":\"jev-1.14.0\",\"model\":\"jev-1.14.0\"",
            )
        LoopbackEndpoint(200, response).use { endpoint ->
            val accounting = AccountingRecorder()

            assertThrows(ClockClassificationException::class.java) {
                ports(endpoint, accounting).relevancePort.classify(
                    PeriodClarificationFixtures.input(14),
                    relevanceCandidates(),
                )
            }

            assertAccounted(endpoint, accounting, "unknown")
        }
    }

    private fun ports(
        endpoint: LoopbackEndpoint,
        accounting: AccountingRecorder,
    ): JevClockClassificationPorts =
        JevClockClassificationPorts(
            key,
            mapper,
            accounting,
            JevClockPolicy(rules),
            JevClassifierOptions(endpoint = endpoint.uri, enabled = true),
        )

    private fun relevanceCandidates(): List<ClockCandidate> = PeriodClarificationFixtures.candidates(14, "7시 반" to 7, "8시" to 8)

    private fun periodCandidates(): List<ClockCandidate> = PeriodClarificationFixtures.candidates(43, "5시" to 5, "8시" to 8)

    private fun assertMutationRejectedBeforePhysicalDispatch(mutate: (Request) -> Request) {
        LoopbackEndpoint(200, relevanceSuccess()).use { endpoint ->
            val accounting = AccountingRecorder()
            var originalBody: ByteArray? = null
            var interceptCalls = 0
            val injectedClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        interceptCalls++
                        originalBody = Buffer().also { chain.request().body!!.writeTo(it) }.readByteArray()
                        chain.proceed(mutate(chain.request()))
                    }.build()
            val ports =
                JevClockClassificationPorts(
                    key,
                    mapper,
                    accounting,
                    JevClockPolicy(rules),
                    JevClassifierOptions(endpoint = endpoint.uri, enabled = true),
                    injectedClient,
                )

            val failure =
                assertThrows(ClockClassificationException::class.java) {
                    ports.relevancePort.classify(PeriodClarificationFixtures.input(14), relevanceCandidates())
                }

            assertEquals(1, interceptCalls)
            assertTrue(endpoint.requests.isEmpty())
            assertNull(failure.cause)
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBody!!))
            assertEquals(listOf("reserve:$sha", "unknown:$sha"), accounting.events.toList())
        }
    }

    private fun assertAccounted(
        endpoint: LoopbackEndpoint,
        accounting: AccountingRecorder,
        finalEvent: String,
        input: Long? = null,
        output: Long? = null,
    ) {
        assertEquals(1, endpoint.requests.size)
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.requests.single().body))
        val last = if (finalEvent == "settle") "settle:$sha:$input:$output" else "unknown:$sha"
        assertEquals(listOf("reserve:$sha", "dispatched:$sha", last), accounting.events.toList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertWire(
        endpoint: LoopbackEndpoint,
        originalId: Int,
        candidates: List<ClockCandidate>,
        choices: Set<String>,
    ) {
        val captured = endpoint.requests.single()
        assertEquals("POST", captured.method)
        assertEquals("/v1/systemone", captured.path)
        assertEquals("Bearer $key", captured.authorization)
        val wire = mapper.readValue(String(captured.body, StandardCharsets.UTF_8), Map::class.java) as Map<String, Any>
        assertEquals(setOf("model", "state", "questions"), wire.keys)
        assertEquals("jev-1.14.0", wire["model"])
        val state = wire["state"] as Map<String, Any>
        assertEquals(
            setOf(
                "raw_text",
                "reference_at",
                "time_zone",
                "locale",
                "search_start_date",
                "displayed_end_inclusive",
                "search_end_exclusive",
                "meeting_duration_minutes",
                "policy_rules",
                "candidates",
            ),
            state.keys,
        )
        assertEquals(PeriodClarificationFixtures.raw(originalId), state["raw_text"])
        assertEquals("2026-11-01T23:30:00Z", state["reference_at"])
        assertEquals("Asia/Seoul", state["time_zone"])
        assertEquals("ko-KR", state["locale"])
        assertEquals("2026-11-02", state["search_start_date"])
        assertEquals("2026-11-08", state["displayed_end_inclusive"])
        assertEquals("2026-11-09", state["search_end_exclusive"])
        assertEquals(60, (state["meeting_duration_minutes"] as Number).toInt())
        assertEquals(rules, state["policy_rules"])
        val wireCandidates = state["candidates"] as List<Map<String, Any>>
        assertEquals(candidates.size, wireCandidates.size)
        for ((actual, expected) in wireCandidates.zip(candidates)) {
            assertEquals(setOf("id", "start", "end", "span", "hour", "minute", "notation"), actual.keys)
            assertEquals(expected.id, actual["id"])
            assertEquals(expected.span, actual["span"])
            assertEquals(expected.start, (actual["start"] as Number).toInt())
            assertEquals(expected.end, (actual["end"] as Number).toInt())
            assertEquals(expected.hour, (actual["hour"] as Number).toInt())
            assertEquals(expected.minute, (actual["minute"] as Number).toInt())
            assertEquals(expected.notation.name, actual["notation"])
        }
        val questions = wire["questions"] as Map<String, Map<String, Any>>
        assertEquals(candidates.map { it.id }.toSet(), questions.keys)
        for (candidate in candidates) {
            val question = questions.getValue(candidate.id)
            assertEquals(setOf("type", "instructions", "criteria"), question.keys)
            assertEquals("choice", question["type"])
            val instructions = question["instructions"].toString()
            assertTrue(candidate.span in instructions)
            assertTrue(candidate.start.toString() in instructions)
            assertTrue(candidate.end.toString() in instructions)
            val criteria = question["criteria"] as Map<String, String>
            assertEquals(choices, criteria.keys)
            assertTrue(criteria.values.all { it.isNotBlank() })
        }
    }

    private fun relevanceSuccess(): String =
        envelope(
            "\"q01\":${relevanceAnswer("BACKGROUND", "0.1", "0.8", "0.1", "0.03")}," +
                "\"q02\":${relevanceAnswer("RELEVANT", "0.900000000000000001", "0.049999999999999999", "0.05", "0.01")}",
        )

    private fun relevanceAnswer(
        choice: String,
        relevant: String,
        background: String,
        unknown: String,
        conf: String,
    ): String =
        "{\"type\":\"choice\",\"choice\":\"$choice\",\"probabilities\":{" +
            "\"RELEVANT\":$relevant,\"BACKGROUND\":$background,\"UNRESOLVED\":$unknown},\"confidence\":$conf}"

    private fun periodSuccess(): String =
        envelope(
            "\"q01\":{\"type\":\"choice\",\"choice\":\"NOT_STATED\"," +
                "\"probabilities\":{\"AM\":0.10,\"PM\":0.45,\"NOT_STATED\":0.45,\"UNRESOLVED\":0.0},\"confidence\":0.88}," +
                "\"q02\":{\"type\":\"choice\",\"choice\":\"PM\"," +
                "\"probabilities\":{\"AM\":0.1,\"PM\":0.7,\"NOT_STATED\":0.1,\"UNRESOLVED\":0.1},\"confidence\":0.01}",
        )

    private fun envelope(answers: String): String =
        "{\"model\":\"jev-1.14.0\",\"answers\":{$answers},\"usage\":{\"input_tokens\":23,\"output_tokens\":7}}"
}

private class AccountingRecorder : JevClockAccounting {
    val events = CopyOnWriteArrayList<String>()

    override fun reserve(requestSha256: String) {
        events += "reserve:$requestSha256"
    }

    override fun dispatched(requestSha256: String) {
        events += "dispatched:$requestSha256"
    }

    override fun settle(
        requestSha256: String,
        inputTokens: Long,
        outputTokens: Long,
    ) {
        events += "settle:$requestSha256:$inputTokens:$outputTokens"
    }

    override fun unknownUsage(requestSha256: String) {
        events += "unknown:$requestSha256"
    }
}

private data class CapturedClockRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val body: ByteArray,
)

private class LoopbackEndpoint(
    status: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
    redirect: Boolean = false,
) : Closeable {
    val requests = CopyOnWriteArrayList<CapturedClockRequest>()
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "synthetic-clock-contract").apply { isDaemon = true }
        }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val uri = URI("http://127.0.0.1:${server.address.port}/v1/systemone")

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            requests +=
                CapturedClockRequest(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.use { it.readAllBytes() },
                )
            val response = body.toByteArray(StandardCharsets.UTF_8)
            headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
            if (redirect) exchange.responseHeaders.set("Location", "http://127.0.0.1:${server.address.port}/redirected")
            val actualStatus = if (exchange.requestURI.path == "/v1/systemone") status else 200
            exchange.sendResponseHeaders(actualStatus, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
