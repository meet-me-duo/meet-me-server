package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.time.zone.ZoneRulesException
import java.time.zone.ZoneRulesProvider
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Existing product/DB bounds at the real outbound boundary; loopback only, never a token or price oracle. */
class FinalOutboundInputBoundaryTest {
    enum class Provider { GEMINI, LUNA }

    @ParameterizedTest(name = "{0} rejects {1} before any HTTP")
    @MethodSource("invalidRequests")
    fun `invalid outbound inputs fail as invalid request without a provider exchange`(
        provider: Provider,
        reason: String,
        request: NaturalLanguageBatchRequest,
    ) {
        Endpoint(provider, request).use { endpoint ->
            val failure = assertFailsWith<NaturalLanguageParserException>(reason) { endpoint.parse(request) }

            assertEquals(ParserFailureKind.INVALID_REQUEST, failure.kind, reason)
            assertEquals(0, endpoint.requests.size, "Rejected $reason must not consume any physical provider request")
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `valid Unicode and escape rich input at five hundred code points is preserved`(provider: Provider) {
        val raw = "가😀\\\"\u0001".repeat(100)
        assertEquals(500, raw.codePointCount(0, raw.length))
        val request = batch(listOf(input(1, raw)))

        Endpoint(provider, request).use { endpoint ->
            val result = endpoint.parse(request)

            assertEquals(1, result.results.size)
            assertFrozenWire(endpoint.requests.single(), provider, request)
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `five hundred supplementary code points preserve the full one thousand UTF16 units`(provider: Provider) {
        val raw = "😀".repeat(500)
        assertEquals(500, raw.codePointCount(0, raw.length))
        assertEquals(1_000, raw.length)
        val request = batch(listOf(input(1, raw)))

        Endpoint(provider, request).use { endpoint ->
            assertEquals(1, endpoint.parse(request).results.size)
            assertFrozenWire(endpoint.requests.single(), provider, request)
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `ten thousand total code points are admitted without trimming or dropping input`(provider: Provider) {
        val raw = "가😀\\\"\u0001".repeat(100)
        val request = batch((1..20).map { input(it, raw) })
        assertEquals(10_000, request.inputs.sumOf { it.rawText.codePointCount(0, it.rawText.length) })

        Endpoint(provider, request).use { endpoint ->
            assertEquals(20, endpoint.parse(request).results.size)
            assertFrozenWire(endpoint.requests.single(), provider, request)
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `fifty participants and existing metadata storage boundaries remain accepted`(provider: Provider) {
        val locale = Locale.forLanguageTag("en-x-abcdefgh-abcdefgh-abcdefgh-abc")
        assertEquals(35, locale.toLanguageTag().length)
        val request =
            batch((1..50).map { input(it, "가😀\\\"\u0001".repeat(40)).copy(locale = locale) })
                .copy(timeZone = ZoneId.of(ZONE_64))
        assertEquals(64, request.timeZone.id.length)
        assertEquals(
            36,
            request.inputs
                .first()
                .inputRef.length,
        )

        Endpoint(provider, request).use { endpoint ->
            assertEquals(50, endpoint.parse(request).results.size)
            assertFrozenWire(endpoint.requests.single(), provider, request)
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `short synthetic references preserve the existing invalid response after one exchange`(provider: Provider) {
        val request = batch(listOf(input(1, "수요일 19–21시 가능").copy(inputRef = "input-1")))

        Endpoint(provider, request).use { endpoint ->
            val failure = assertFailsWith<NaturalLanguageParserException> { endpoint.parse(request) }

            assertEquals(
                ParserFailureKind.INVALID_RESPONSE,
                failure.kind,
                "Existing response UUID validation is not widened by input admission",
            )
            assertEquals(1, endpoint.requests.size)
            assertFrozenWire(endpoint.requests.single(), provider, request)
        }
    }

    private fun assertFrozenWire(
        bytes: ByteArray,
        provider: Provider,
        request: NaturalLanguageBatchRequest,
    ) {
        val root = mapper.readTree(bytes)
        val prompt =
            when (provider) {
                Provider.GEMINI -> root["contents"][0]["parts"][0]["text"].asText()
                Provider.LUNA -> root["input"].asText()
            }
        val prefix = prompt.substringBefore("Room time zone: ")
        assertEquals(
            "7417d035d2bbae49dfeaf8bebc5f0b0375db0e4b83e768275145473cd6cf7750",
            sha(prefix.toByteArray(Charsets.UTF_8)),
            "Instruction bytes are frozen from develop519215f; admission guards must not rewrite the provider contract",
        )
        val expectedSuffix =
            buildString {
                appendLine("Room time zone: ${request.timeZone.id}")
                appendLine("Search range: ${request.searchStartDate} until ${request.searchEndDate} (exclusive)")
                appendLine("Inputs:")
                request.inputs.forEach {
                    appendLine("reference_date=${it.referenceDate}\t${it.inputRef}\t${it.locale.toLanguageTag()}\t${it.rawText}")
                }
            }
        assertEquals(expectedSuffix, prompt.removePrefix(prefix), "Every accepted original and metadata field must survive byte-for-byte")
        assertConservativeWholeWireBound(bytes, provider, prefix)
        val schema =
            when (provider) {
                Provider.GEMINI -> {
                    assertEquals(32_768, root["generationConfig"]["maxOutputTokens"].asInt())
                    assertEquals(1, root["generationConfig"]["candidateCount"].asInt())
                    root["generationConfig"]["responseJsonSchema"]
                }
                Provider.LUNA -> {
                    assertEquals(setOf("model", "store", "input", "max_output_tokens", "text"), root.propertyNames().toSet())
                    assertEquals(32_768, root["max_output_tokens"].asInt())
                    assertFalse(root["store"].asBoolean())
                    root["text"]["format"]["schema"]
                }
            }
        assertEquals(
            "aa8850df347d2a5e553063ff84174bc783b8079f8565e3537179c9163c91b05a",
            sha(mapper.writeValueAsBytes(canonical(schema))),
            "Canonical complete conditional-v3 schema remains frozen, including clarification metadata",
        )
    }

    private fun assertConservativeWholeWireBound(
        bytes: ByteArray,
        provider: Provider,
        prefix: String,
    ) {
        // Measure the actual complete provider wrapper/schema, excluding its one prompt JSON string token.
        val wire = bytes.toString(Charsets.UTF_8)
        val key = if (provider == Provider.LUNA) "\"input\"" else "\"text\""
        var start = wire.indexOf(':', wire.indexOf(key)) + 1
        while (wire[start].isWhitespace()) start++
        assertEquals('"', wire[start])
        var end = start + 1
        while (end < wire.length) {
            when (wire[end]) {
                '\\' -> end += 2
                '"' -> break
                else -> end++
            }
        }
        assertTrue(end < wire.length)
        val actualPromptTokenBytes = wire.substring(start, end + 1).toByteArray(Charsets.UTF_8).size
        val fixedUpperBound = bytes.size - actualPromptTokenBytes + prefix.codePointCount(0, prefix.length) * 6 + 2
        assertTrue(fixedUpperBound <= 64 * 1024, "Actual wrapper/schema plus worst JSON-escaped frozen instructions must fit64KiB")
        // Supplementary raw scalars may use two escaped UTF-16 units. This deliberately overestimates final JSON's raw expansion.
        val allAcceptedInputsUpperBound = 64 * 1024 + 12 * 10_000 + 50 * 6 * (36 + 35 + 16 + 20) + 6 * (64 + 2 * 16 + 100)
        assertTrue(allAcceptedInputsUpperBound < 256 * 1024)
        assertTrue(bytes.size <= 256 * 1024, "Whole wire bound is bytes only, never an authoritative billed-token or price proof")
    }

    private fun canonical(node: JsonNode): Any =
        when {
            node.isObject -> node.propertyNames().sorted().associateWith { canonical(node[it]) }
            node.isArray -> node.asSequence().map(::canonical).toList()
            else -> node
        }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Endpoint(
        private val provider: Provider,
        request: NaturalLanguageBatchRequest,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<ByteArray>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val response = envelope(provider, request)

        init {
            server.createContext("/") { exchange ->
                requests += exchange.requestBody.use { it.readAllBytes() }
                val bytes = response.toByteArray(Charsets.UTF_8)
                try {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun parse(request: NaturalLanguageBatchRequest) =
            when (provider) {
                Provider.GEMINI ->
                    GeminiNaturalLanguageParserAdapter(
                        GeminiProperties(apiKey = "synthetic-offline-placeholder"),
                        mapper,
                        URI.create("http://127.0.0.1:${server.address.port}"),
                    ).parse(request)
                Provider.LUNA ->
                    OpenAiLunaNaturalLanguageParserAdapter(
                        OpenAiProperties(apiKey = "synthetic-offline-placeholder"),
                        mapper,
                        OkHttpClient.Builder().build(),
                        URI.create("http://127.0.0.1:${server.address.port}/v1/responses"),
                    ).parse(request)
            }

        override fun close() = server.stop(0)
    }

    companion object {
        private val mapper = JsonMapper.builder().build()
        private val date = LocalDate.of(2026, 10, 21)
        private val ZONE_64 = "Issue123/" + "A".repeat(55)
        private val ZONE_65 = "Issue123/" + "B".repeat(56)

        init {
            ZoneRulesProvider.registerProvider(
                object : ZoneRulesProvider() {
                    override fun provideZoneIds(): MutableSet<String> = mutableSetOf(ZONE_64, ZONE_65)

                    override fun provideRules(
                        zoneId: String,
                        forCaching: Boolean,
                    ): ZoneRules {
                        if (zoneId !in setOf(ZONE_64, ZONE_65)) throw ZoneRulesException("Unknown synthetic zone")
                        return ZoneOffset.UTC.rules
                    }

                    override fun provideVersions(zoneId: String): NavigableMap<String, ZoneRules> =
                        TreeMap<String, ZoneRules>().apply { put("synthetic", provideRules(zoneId, false)) }
                },
            )
        }

        private fun input(
            index: Int,
            raw: String = "수요일 19–21시 가능",
        ) = NaturalLanguageInput(UUID(0, index.toLong()).toString(), raw, Locale.KOREAN, date)

        private fun batch(inputs: List<NaturalLanguageInput>) =
            NaturalLanguageBatchRequest(ZoneId.of("Asia/Seoul"), date, date.plusDays(7), inputs, Duration.ofSeconds(5))

        @JvmStatic
        fun invalidRequests(): Stream<Arguments> {
            val one = input(1)
            val cases =
                listOf(
                    "no inputs" to batch(emptyList()),
                    "51 inputs" to batch((1..51).map { input(it, "가") }),
                    "duplicate reference" to batch(listOf(one, one.copy(rawText = "다른 입력"))),
                    "501 code points" to batch(listOf(input(1, "😀".repeat(501)))),
                    "501 ASCII code points below UTF16 length guard" to batch(listOf(input(1, "A".repeat(501)))),
                    "501 Hangul code points below UTF16 length guard" to batch(listOf(input(1, "가".repeat(501)))),
                    "10001 total code points" to batch((1..20).map { input(it, "가".repeat(500)) } + input(21, "나")),
                    "empty raw text" to batch(listOf(input(1, ""))),
                    "blank raw text" to batch(listOf(input(1, " \t\n"))),
                    "NUL raw text" to batch(listOf(input(1, "가\u0000나"))),
                    "unpaired high surrogate" to batch(listOf(input(1, "가\uD800나"))),
                    "unpaired low surrogate" to batch(listOf(input(1, "가\uDC00나"))),
                    "37 character reference" to batch(listOf(one.copy(inputRef = one.inputRef + "x"))),
                    "blank reference" to batch(listOf(one.copy(inputRef = " "))),
                    "NUL reference" to batch(listOf(one.copy(inputRef = "x\u0000y"))),
                    "unpaired surrogate reference" to batch(listOf(one.copy(inputRef = "x\uD800y"))),
                    "36 character locale" to
                        batch(listOf(one.copy(locale = Locale.forLanguageTag("en-x-abcdefgh-abcdefgh-abcdefgh-abcd")))),
                    "65 character timezone" to batch(listOf(one)).copy(timeZone = ZoneId.of(ZONE_65)),
                )
            return Provider.entries.flatMap { provider -> cases.map { (name, request) -> Arguments.of(provider, name, request) } }.stream()
        }

        private fun envelope(
            provider: Provider,
            request: NaturalLanguageBatchRequest,
        ): String {
            val payload =
                mapper.writeValueAsString(
                    mapOf(
                        "schema_version" to "3",
                        "results" to
                            request.inputs.distinctBy { it.inputRef }.map {
                                mapOf(
                                    "input_ref" to it.inputRef,
                                    "rejection_code" to "AMBIGUOUS_INPUT",
                                    "conditions" to emptyList<Any>(),
                                    "clarification" to null,
                                )
                            },
                    ),
                )
            return mapper.writeValueAsString(
                when (provider) {
                    Provider.GEMINI ->
                        mapOf(
                            "candidates" to
                                listOf(
                                    mapOf(
                                        "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to payload))),
                                        "finishReason" to "STOP",
                                    ),
                                ),
                            "usageMetadata" to mapOf("promptTokenCount" to 100, "candidatesTokenCount" to 30, "thoughtsTokenCount" to 0),
                        )
                    Provider.LUNA ->
                        mapOf(
                            "status" to "completed",
                            "output" to
                                listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload)))),
                            "usage" to mapOf("input_tokens" to 100, "output_tokens" to 30),
                        )
                },
            )
        }
    }
}
