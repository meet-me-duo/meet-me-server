package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #96: loopback HTTP and synthetic placeholder configuration only, never a paid API call. */
class LunaProviderContractTest {
    private val mapper = JsonMapper.builder().build()
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(NaturalLanguageInput(UUID(0, 1).toString(), "수요일 19–21시", Locale.KOREAN, LocalDate.of(2026, 10, 7))),
        )

    @Test
    fun `strict nullable schema has required rejection code and every object property is required`() {
        val schema = mapper.valueToTree<JsonNode>(OpenAiLunaNaturalLanguageParserAdapter.RESPONSE_SCHEMA)
        val item = schema["properties"]["results"]["items"]
        assertTrue(item["required"].asSequence().map { it.asText() }.contains("rejection_code"))
        assertEquals(setOf("string", "null"), item["properties"]["rejection_code"]["type"].asSequence().map { it.asText() }.toSet())
        assertStrictObjects(schema)
    }

    @Test
    fun `Responses API sends pinned Luna model strict common schema and one HTTP request`() {
        val fixture = Endpoint(200, envelope(validResponse()))
        fixture.use {
            val result = fixture.adapter().parse(request)

            assertEquals(1, fixture.bodies.size)
            val body = mapper.readTree(fixture.bodies.single())
            assertEquals("gpt-6-luna", body["model"].asText())
            assertEquals("json_schema", body["text"]["format"]["type"].asText())
            assertTrue(body["text"]["format"]["strict"].asBoolean())
            assertStrictObjects(body["text"]["format"]["schema"])
            assertTrue(fixture.bodies.single().contains("2026-10-07"))
            assertEquals(
                request.inputs.single().inputRef,
                result.results
                    .single()
                    .submissionVersionId.value
                    .toString(),
            )
            assertNull(result.results.single().rejectionCode)
            assertEquals(100L, result.usage.inputTokens)
            assertEquals(30L, result.usage.outputTokens)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "401,invalid_api_key,AUTHENTICATION",
        "403,permission_denied,PERMISSION",
        "402,billing_error,BILLING",
        "400,invalid_request_error,INVALID_REQUEST",
        "429,insufficient_quota,QUOTA",
        "429,rate_limit_exceeded,RATE_LIMIT",
        "503,server_error,SERVER",
    )
    fun `typed OpenAI status and error code distinguish transient and permanent failures`(
        status: Int,
        code: String,
        expected: ParserFailureKind,
    ) {
        val fixture = Endpoint(status, """{"error":{"type":"$code","code":"$code","message":"synthetic error"}}""")
        fixture.use {
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.adapter().parse(request) }
            assertEquals(expected, failure.kind)
            assertEquals(expected in setOf(ParserFailureKind.RATE_LIMIT, ParserFailureKind.SERVER), failure.retryable)
            assertEquals(1, fixture.bodies.size, "The transport must not add an SDK or HTTP retry")
        }
    }

    @Test
    fun `arbitrary error text cannot turn invalid request into transient server failure`() {
        Endpoint(
            400,
            """{"error":{"type":"invalid_request_error","code":"invalid_request_error","message":"503 timeout connection reset"}}""",
        ).use { fixture ->
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.adapter().parse(request) }
            assertEquals(ParserFailureKind.INVALID_REQUEST, failure.kind)
            assertEquals(1, fixture.bodies.size)
        }
    }

    @Test
    fun `malformed successful response is invalid response without retry`() {
        Endpoint(200, """{"status":"completed","output":[]} """).use { fixture ->
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.adapter().parse(request) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(1, fixture.bodies.size)
        }
    }

    @Test
    fun `incomplete Luna response cannot publish even when it contains valid structured JSON`() {
        Endpoint(200, envelope(validResponse()).replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")).use { fixture ->
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.adapter().parse(request) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(1, fixture.bodies.size)
        }
    }

    @Test
    fun `Luna refusal cannot become structured input or an ambiguity rejection`() {
        val response =
            """{"status":"completed","output":[{"type":"message","content":[""" +
                """{"type":"refusal","refusal":"synthetic refusal"}]}]}"""
        Endpoint(200, response).use { fixture ->
            val failure = assertFailsWith<NaturalLanguageParserException> { fixture.adapter().parse(request) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(1, fixture.bodies.size)
        }
    }

    @Test
    fun `shared validators produce identical domain results for both provider surfaces`() {
        val luna = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper)
        val gemini =
            GeminiNaturalLanguageParserAdapter(
                com.meetme.server.config
                    .GeminiProperties(),
                mapper,
            )
        val response = validResponse()
        assertEquals(gemini.parseProviderResponse(response, request), luna.parseProviderResponse(response, request))
        assertEquals(gemini.prompt(request), luna.prompt(request))
    }

    @Test
    fun `OpenAI strict result requires explicit nullable rejection code`() {
        val adapter = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper)
        val missingRequired = validResponse().replace("\"rejection_code\":null,", "")
        val failure = assertFailsWith<NaturalLanguageParserException> { adapter.parseProviderResponse(missingRequired, request) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Test
    fun `configured per-call timeout bounds one loopback request`() {
        Endpoint(200, envelope(validResponse()), responseDelay = 1_000).use { fixture ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    fixture.adapter().parse(request.copy(callTimeout = Duration.ofMillis(250)))
                }
            assertEquals(ParserFailureKind.TIMEOUT, failure.kind)
            assertEquals(1, fixture.bodies.size)
        }
    }

    private fun validResponse() =
        """{"schema_version":"3","results":[{"input_ref":"${request.inputs.single().inputRef}","rejection_code":null,"conditions":[{"type":"TIME_WINDOW","polarity":"AVAILABLE","date":"2026-10-07","day_of_week":null,"start_time":"19:00","end_time":"21:00"}]}]}"""

    private fun envelope(providerResponse: String) =
        mapper.writeValueAsString(
            mapOf(
                "status" to "completed",
                "output" to
                    listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to providerResponse)))),
                "usage" to mapOf("input_tokens" to 100, "output_tokens" to 30),
            ),
        )

    private fun assertStrictObjects(node: JsonNode) {
        if (node.isObject) {
            if (node["type"]?.takeIf { it.isString }?.asString() == "object") {
                assertEquals(false, node["additionalProperties"]?.asBoolean())
                assertEquals(node["properties"].propertyNames().toSet(), node["required"].asSequence().map { it.asText() }.toSet())
            }
            node.forEach { assertStrictObjects(it) }
        } else if (node.isArray) {
            node.forEach { assertStrictObjects(it) }
        }
    }

    private inner class Endpoint(
        private val status: Int,
        private val response: String,
        private val responseDelay: Long = 0,
    ) : AutoCloseable {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/responses") { exchange ->
                bodies += exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
                if (responseDelay > 0) Thread.sleep(responseDelay)
                val bytes = response.toByteArray(Charsets.UTF_8)
                try {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun adapter() =
            OpenAiLunaNaturalLanguageParserAdapter(
                OpenAiProperties(apiKey = "synthetic-loopback-placeholder"),
                mapper,
                OkHttpClient.Builder().build(),
                URI.create("http://127.0.0.1:${server.address.port}/responses"),
            )

        override fun close() = server.stop(0)
    }
}
