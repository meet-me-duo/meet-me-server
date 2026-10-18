package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.adapter.output.provider.GeminiPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 public policy transmission and unchanged transport/parse contracts only.
 * Fixed synthetic replies are deliberately independent of DEV gold: no free semantic accuracy claim.
 */
class SharedPrecheckPolicyV2WireTest {
    private val mapper = JsonMapper.builder().build()
    private val specification =
        requireNotNull(javaClass.getResourceAsStream("/precheck/shared-policy-v2-development.json")).use { mapper.readTree(it) }
    private val cases get() = specification.path("cases").values().toList()

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `SPV201 actual A HTTP instructions deliver each shared normative boundary`(clause: Int) {
        DecisionClassificationFixtures.Endpoint().use { endpoint ->
            endpoint.classifier().classify(classification(cases.first().path("input")))
            val sent = mapper.readTree(endpoint.sent.single().body)
            val instructions =
                sent
                    .path("questions")
                    .get(0)
                    .path("instructions")
                    .asText()
            val required = specification.path("common_normative_clauses").get(clause).asText()
            assertTrue(instructions.contains(required), "V2 normative clause $clause must reach the A HTTP body")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3])
    fun `SPV202 actual B count and generation carry the same shared normative boundary`(clause: Int) {
        GenerationEndpoint().use { endpoint ->
            endpoint.provider().check(generation(cases.first().path("input")))
            val gen = mapper.readTree(endpoint.sent.last().body)
            val required = specification.path("common_normative_clauses").get(clause).asText()
            val instructions =
                gen
                    .path("systemInstruction")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText()
            assertTrue(instructions.contains(required), "V2 normative clause $clause must reach B generation")
            assertCountIsEntireGeneration(endpoint)
        }
    }

    @Test
    fun `SPV203 A CLEAR description cannot imply unconditional private fact dependence is clear`() {
        DecisionClassificationFixtures.Endpoint().use { endpoint ->
            endpoint.classifier().classify(classification(cases.first().path("input")))
            val question = mapper.readTree(endpoint.sent.single().body).path("questions").get(0)
            assertEquals(
                listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE"),
                question.path("choices").values().map { it.path("value").asText() },
            )
            val clear = question.path("choices").values().single { it.path("value").asText() == "CLEAR" }
            assertEquals(specification.path("clear_choice_description").asText(), clear.path("description").asText())
        }
    }

    @Test
    fun `SPV204 public DEV contrast inputs stay exact on both real HTTP transports without gold metadata`() {
        assertEquals(12, cases.size)
        val binding = cases.single { it.path("policy_rule").asText() == "UNRESOLVED_BINDING" }
        val delegation = cases.single { it.path("policy_rule").asText() == "EXPLICIT_DELEGATION" }
        assertEquals(binding.path("contrast_pair"), delegation.path("contrast_pair"))
        assertEquals("NEEDS_REVIEW", binding.path("expected_a").asText())
        assertEquals("CLEAR", delegation.path("expected_a").asText())
        assertEquals("NEEDS_CHOICE", binding.path("expected_b").asText())
        assertEquals("CLEAR", delegation.path("expected_b").asText())
        for (example in cases) {
            val expected = example.path("input")
            // Both endpoints always return fixed CLEAR, including DEV gold REVIEW: not a classifier oracle.
            DecisionClassificationFixtures.Endpoint().use { endpoint ->
                endpoint.classifier().classify(classification(expected))
                val body = mapper.readTree(endpoint.sent.single().body)
                assertTrue(body.path("input").isString)
                assertEquals(expected, mapper.readTree(body.path("input").asText()))
                assertEquals("gpt-6-luna", body.path("model").asText())
                assertTrue(
                    endpoint.sent
                        .single()
                        .body
                        .toByteArray()
                        .size <= 16 * 1024,
                )
            }
            GenerationEndpoint().use { endpoint ->
                val request = generation(expected)
                endpoint.provider().check(request)
                val body = mapper.readTree(endpoint.sent.last().body)
                val actual =
                    mapper.readTree(
                        body
                            .path("contents")
                            .get(0)
                            .path("parts")
                            .get(0)
                            .path("text")
                            .asText(),
                    )
                assertEquals(expected.propertyNames().toSet() + "input_ref", actual.propertyNames().toSet())
                expected.propertyNames().forEach { field -> assertEquals(expected.path(field), actual.path(field), field) }
                assertEquals(request.checkId.toString(), actual.path("input_ref").asText())
                assertCountIsEntireGeneration(endpoint)
            }
        }
    }

    @Test
    fun `SPV205 five hundred Unicode originals and runtime schema caps remain unchanged`() {
        val raw = " " + "😀".repeat(498) + " "
        val original = classification(cases.first().path("input")).copy(rawText = raw)
        DecisionClassificationFixtures.Endpoint().use { endpoint ->
            endpoint.classifier().classify(original)
            val body = mapper.readTree(endpoint.sent.single().body)
            assertEquals(raw, mapper.readTree(body.path("input").asText()).path("raw_text").asText())
        }
        GenerationEndpoint().use { endpoint ->
            val request = generation(cases.first().path("input")).copy(rawText = raw)
            endpoint.provider().check(request)
            val body = mapper.readTree(endpoint.sent.last().body)
            val config = body.path("generationConfig")
            assertEquals(1, config.path("candidateCount").asInt())
            assertEquals(2048, config.path("maxOutputTokens").asInt())
            assertEquals("LOW", config.path("thinkingConfig").path("thinkingLevel").asText())
            assertEquals("application/json", config.path("responseMimeType").asText())
            val schema = config.path("responseJsonSchema")
            assertFalse(schema.path("additionalProperties").asBoolean(true))
            assertEquals(setOf("verdict", "prompt", "sourceExcerpt", "choices"), schema.path("properties").propertyNames().toSet())
            assertEquals(listOf("verdict"), schema.path("required").values().map { it.asText() })
            assertEquals(
                raw,
                mapper
                    .readTree(
                        body
                            .path("contents")
                            .get(0)
                            .path("parts")
                            .get(0)
                            .path("text")
                            .asText(),
                    ).path("raw_text")
                    .asText(),
            )
            assertCountIsEntireGeneration(endpoint)
        }
    }

    @Test
    fun `SPV206 oversized raw is rejected before either stage makes HTTP calls`() {
        val raw = "😀".repeat(501)
        DecisionClassificationFixtures.Endpoint().use { endpoint ->
            assertEquals(
                "INVALID_REQUEST",
                assertFailsWith<AmbiguityClassificationException> {
                    endpoint.classifier().classify(classification(cases.first().path("input")).copy(rawText = raw))
                }.failureCode,
            )
            assertTrue(endpoint.sent.isEmpty())
        }
        GenerationEndpoint().use { endpoint ->
            assertEquals(
                "INVALID_REQUEST",
                assertFailsWith<PrecheckProviderException> {
                    endpoint.provider().check(generation(cases.first().path("input")).copy(rawText = raw))
                }.failureCode,
            )
            assertTrue(endpoint.sent.isEmpty())
        }
    }

    @Test
    fun `SPV207 supplied complete proposals survive parsing without replacing the original`() {
        val example = cases.single { it.path("policy_rule").asText() == "UNRESOLVED_BINDING" }
        val expected = example.path("safe_complete_proposals")
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "verdict" to "NEEDS_CHOICE",
                    "prompt" to "온라인 전용인 요일은 어느 쪽인가요?",
                    "sourceExcerpt" to "하루는 온라인만",
                    "choices" to expected,
                ),
            )
        GenerationEndpoint(payload).use { endpoint ->
            val request = generation(example.path("input"))
            val raw = request.rawText
            val result = endpoint.provider().check(request)
            assertEquals(PrecheckVerdict.NEEDS_CHOICE, result.verdict)
            assertEquals(listOf("A", "B"), result.choices.map { it.id })
            assertEquals(expected.values().map { it.path("proposedText").asText() }, result.choices.map { it.proposedText })
            assertEquals(raw, request.rawText)
            val sent = mapper.readTree(endpoint.sent.last().body)
            assertEquals(
                raw,
                mapper
                    .readTree(
                        sent
                            .path("contents")
                            .get(0)
                            .path("parts")
                            .get(0)
                            .path("text")
                            .asText(),
                    ).path("raw_text")
                    .asText(),
            )
        }
    }

    @Test
    fun `SPV208 safe edit remains questionless and does not add a reason DTO field`() {
        val example = cases.single { it.path("policy_rule").asText() == "REQUIRED_PERSONAL_FACT" }
        GenerationEndpoint("""{"verdict":"NEEDS_EDIT"}""").use { endpoint ->
            val request = generation(example.path("input"))
            val result = endpoint.provider().check(request)
            assertEquals(PrecheckVerdict.NEEDS_EDIT, result.verdict)
            assertNull(result.prompt)
            assertNull(result.sourceExcerpt)
            assertTrue(result.choices.isEmpty())
            assertEquals(example.path("input").path("raw_text").asText(), request.rawText)
        }
    }

    private fun classification(input: JsonNode) =
        AmbiguityClassificationRequest(
            input.path("raw_text").asText(),
            Instant.parse(input.path("reference_at").asText()),
            ZoneId.of(input.path("time_zone").asText()),
            input.path("locale").asText(),
            LocalDate.parse(input.path("search_start_date").asText()),
            LocalDate.parse(input.path("search_end_date").asText()),
            Duration.ofSeconds(3),
        )

    private fun generation(input: JsonNode): PrecheckProviderRequest {
        val original = classification(input)
        return PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000001302"),
            original.rawText,
            original.referenceAt,
            original.timeZone,
            original.locale,
            original.searchStartDate,
            original.searchEndDate,
            Duration.ofSeconds(3),
        )
    }

    private fun assertCountIsEntireGeneration(endpoint: GenerationEndpoint) {
        assertEquals(2, endpoint.sent.size)
        assertEquals(listOf("countTokens", "generateContent"), endpoint.sent.map { it.path.substringAfterLast(':') })
        endpoint.sent.forEach { assertTrue(it.body.toByteArray().size <= 16 * 1024) }
        val count = mapper.readTree(endpoint.sent.first().body)
        val generation = mapper.readTree(endpoint.sent.last().body)
        val counted = count.path("generateContentRequest")
        assertEquals(generation.propertyNames().toSet() + "model", counted.propertyNames().toSet())
        generation.propertyNames().forEach { field -> assertEquals(generation.path(field), counted.path(field), field) }
        assertEquals("models/gemini-3.8-flash", counted.path("model").asText())
    }

    private data class Sent(
        val path: String,
        val body: String,
    )

    private inner class GenerationEndpoint(
        private val payload: String = """{"verdict":"CLEAR"}""",
    ) : AutoCloseable {
        val sent = CopyOnWriteArrayList<Sent>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    sent += Sent(exchange.requestURI.path, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                    val body =
                        if (exchange.requestURI.path.endsWith(":countTokens")) {
                            """{"totalTokens":300}"""
                        } else {
                            mapper.writeValueAsString(
                                mapOf(
                                    "candidates" to
                                        listOf(
                                            mapOf(
                                                "finishReason" to "STOP",
                                                "content" to mapOf("parts" to listOf(mapOf("text" to payload))),
                                            ),
                                        ),
                                    "usageMetadata" to
                                        mapOf("promptTokenCount" to 300, "candidatesTokenCount" to 40, "thoughtsTokenCount" to 0),
                                ),
                            )
                        }
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun provider() =
            GeminiPreSubmissionCheckProvider.runtime(
                GeminiProperties(apiKey = "synthetic-policy-v2-key"),
                mapper,
                PrecheckProviderRuntimeProperties(true),
                PrecheckProviderHttpTransport(OkHttpClient()),
                endpoint = URI("http://127.0.0.1:${server.address.port}"),
            )

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
