package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.TimePolarity
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #99 / approved D2: real adapters on loopback only, synthetic inputs and placeholder keys. */
class D2PreferenceProviderContractTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = NaturalLanguageProviderContract(mapper)
    private val request =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 12),
            listOf(
                NaturalLanguageInput(
                    UUID(0, 1).toString(),
                    "수요일 18–22시 가능, 20–21시 선호, 강남 또는 홍대 선호",
                    Locale.KOREAN,
                    LocalDate.of(2026, 10, 7),
                ),
            ),
        )

    @Test
    fun `both live adapters transmit the same strict v3 schema and restore independent hard and preferred conditions`() {
        val wire = response(listOf(hardTime(), preferredTime(), preferredPlace(), preferredPlace("홍대", "AREA_2", "홍대")))
        val captured = mutableListOf<JsonNode>()
        val parsed =
            listOf("gemini", "luna").map { provider ->
                Endpoint(provider, wire).use { endpoint ->
                    val result = endpoint.parse()
                    assertEquals(1, endpoint.bodies.size)
                    captured.add(mapper.readTree(endpoint.bodies.single()))
                    result.results.single()
                }
            }
        assertEquals(parsed.first(), parsed.last())
        assertNull(parsed.first().rejectionCode)
        assertEquals(
            listOf(
                StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0)),
                StructuredCondition.PreferredTimeWindow(DATE, null, LocalTime.of(20, 0), LocalTime.of(21, 0)),
                StructuredCondition.PreferredPlace("강남", "AREA_1", "강남"),
                StructuredCondition.PreferredPlace("홍대", "AREA_2", "홍대"),
            ),
            parsed.first().conditions,
        )
        val geminiSchema = captured[0]["generationConfig"]["responseJsonSchema"]
        val lunaFormat = captured[1]["text"]["format"]
        assertEquals(geminiSchema, lunaFormat["schema"])
        assertEquals("meet_me_conditions_v3", lunaFormat["name"].asText())
        assertTrue(lunaFormat["strict"].asBoolean())
        assertEquals(listOf("3"), geminiSchema["properties"]["schema_version"]["enum"].asSequence().map { it.asText() }.toList())
        assertStrictObjects(geminiSchema)
        val alternatives = geminiSchema["properties"]["results"]["items"]["properties"]["conditions"]["items"]["anyOf"]
        val byType = alternatives.asSequence().groupBy { it["properties"]["type"]["enum"][0].asText() }
        assertEquals(2, byType.getValue("PREFERRED_TIME_WINDOW").size, "Dated and recurring preferred scopes remain alternatives")
        byType.getValue("PREFERRED_TIME_WINDOW").forEach { assertEquals("null", it["properties"]["polarity"]["type"].asText()) }
        assertEquals("null", byType.getValue("PREFERRED_PLACE").single()["properties"]["radius_meters"]["type"].asText())
        val geminiPrompt = captured[0]["contents"][0]["parts"][0]["text"].asText()
        assertEquals(geminiPrompt, captured[1]["input"].asText())
        assertTrue(geminiPrompt.contains("PREFERRED_TIME_WINDOW"))
        assertTrue(geminiPrompt.contains("PREFERRED_PLACE"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "2"])
    fun `live providers reject legacy versions while compatibility restores them without preferences`(version: String) {
        val wire = response(listOf(hardTime()), version)
        listOf("gemini", "luna").forEach { provider ->
            Endpoint(provider, wire).use { endpoint ->
                assertEquals(ParserFailureKind.INVALID_RESPONSE, assertFailsWith<NaturalLanguageParserException> { endpoint.parse() }.kind)
            }
        }
        val restored = contract.parseProviderResponse(wire, request, strict = false).single()
        assertEquals(
            listOf(StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0))),
            restored.conditions,
        )
        assertNull(restored.rejectionCode)
    }

    @Test
    fun `legacy versions cannot introduce preferred conditions through the compatibility path`() {
        listOf("1", "2").forEach { version ->
            val restored = contract.parseProviderResponse(response(listOf(hardTime(), preferredTime()), version), request).single()
            assertEquals("CONDITION_VALIDATION_FAILED", restored.rejectionCode)
            assertEquals(
                listOf(StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0))),
                restored.conditions,
            )
        }
    }

    @Test
    fun `dated recurring and next day preferred boundaries remain typed without availability polarity`() {
        val recurring = preferredTime() + mapOf("date" to null, "day_of_week" to "THURSDAY", "end_time" to "24:00")
        val parsed = contract.parseProviderResponse(response(listOf(hardTime(), recurring)), request, strict = true).single()
        assertEquals(
            StructuredCondition.PreferredTimeWindow(null, DayOfWeek.THURSDAY, LocalTime.of(20, 0), LocalTime.MIDNIGHT, true),
            parsed.conditions.last(),
        )
        assertEquals(TimePolarity.AVAILABLE, (parsed.conditions.first() as StructuredCondition.TimeWindow).polarity)
    }

    @Test
    fun `invalid preference cannot silently disappear while the valid hard condition is retained`() {
        val invalid =
            listOf(
                preferredTime() + ("polarity" to "UNAVAILABLE"),
                preferredTime() + ("date" to "2026-02-30"),
                preferredTime() + ("day_of_week" to "THURSDAY"),
                preferredTime() + ("start_time" to "24:00"),
                preferredTime() + ("end_time" to "19:00"),
                preferredTime() + ("unknown" to null),
                preferredTime() - "polarity",
                preferredTime() - "day_of_week",
                preferredTime() + ("date" to null),
                preferredPlace() + ("radius_meters" to 1000),
                preferredPlace() + ("area_name" to ""),
                preferredPlace() + ("area_key" to "AREA_0"),
                preferredPlace() + ("query" to "x".repeat(501)),
                preferredPlace() + ("coordinates" to null),
                preferredPlace() - "radius_meters",
                preferredPlace() - "area_key",
                preferredPlace() + ("area_name" to "x".repeat(501)),
            )
        invalid.forEach { condition ->
            val result = contract.parseProviderResponse(response(listOf(hardTime(), condition)), request, strict = true).single()
            assertEquals("CONDITION_VALIDATION_FAILED", result.rejectionCode, condition.toString())
            assertEquals(
                listOf(StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0))),
                result.conditions,
                condition.toString(),
            )
        }
    }

    @Test
    fun `hard exclusion polarity is never reinterpreted as preference`() {
        val wire =
            response(
                listOf(
                    hardTime(),
                    hardTime() + mapOf("polarity" to "UNAVAILABLE", "start_time" to "19:00", "end_time" to "20:00"),
                    preferredTime(),
                ),
            )
        val result = contract.parseProviderResponse(wire, request, strict = true).single()
        assertEquals(
            listOf(
                StructuredCondition.TimeWindow(TimePolarity.AVAILABLE, DATE, null, LocalTime.of(18, 0), LocalTime.of(22, 0)),
                StructuredCondition.TimeWindow(TimePolarity.UNAVAILABLE, DATE, null, LocalTime.of(19, 0), LocalTime.of(20, 0)),
                StructuredCondition.PreferredTimeWindow(DATE, null, LocalTime.of(20, 0), LocalTime.of(21, 0)),
            ),
            result.conditions,
        )
        assertNull(result.rejectionCode)
    }

    @Test
    fun `strict envelope integrity refs rejection codes and byte ceiling remain enforced`() {
        val result = mapOf("input_ref" to request.inputs.single().inputRef, "conditions" to listOf(hardTime()), "rejection_code" to null)
        val invalid =
            listOf(
                mapper.writeValueAsString(mapOf("schema_version" to "3", "results" to listOf(result), "unknown" to null)),
                responseResults(listOf(result + ("unknown" to null))),
                responseResults(listOf(result - "rejection_code")),
                responseResults(listOf(result + ("rejection_code" to "invented"))),
                responseResults(emptyList()),
                responseResults(listOf(result, result)),
                responseResults(listOf(result + ("input_ref" to UUID(0, 2).toString()))),
                response(listOf(hardTime(), preferredPlace("x".repeat(262_145)))),
            )
        invalid.forEach { wire ->
            assertEquals(
                ParserFailureKind.INVALID_RESPONSE,
                assertFailsWith<NaturalLanguageParserException> {
                    contract.parseProviderResponse(wire, request, strict = true)
                }.kind,
            )
        }
    }

    @Test
    fun `preferred conditions share the existing 32 condition ceiling and repeated alternatives stay separate`() {
        val allowed =
            contract
                .parseProviderResponse(
                    response(listOf(hardTime()) + List(31) { preferredTime() }),
                    request,
                    strict = true,
                ).single()
        assertEquals(32, allowed.conditions.size)
        assertEquals(31, allowed.conditions.filterIsInstance<StructuredCondition.PreferredTimeWindow>().size)
        assertNull(allowed.rejectionCode)
        val failure =
            assertFailsWith<NaturalLanguageParserException> {
                contract.parseProviderResponse(response(listOf(hardTime()) + List(32) { preferredTime() }), request, strict = true)
            }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @Test
    fun `hard and preferred places with the same area key must agree on one display name`() {
        val hard = mapOf("type" to "SPECIFIC_PLACE", "query" to "강남역", "area_key" to "AREA_1", "area_name" to "강남")
        val invalid = listOf(listOf(hard, preferredPlace(name = "역삼")), listOf(preferredPlace(), preferredPlace("역삼", name = "역삼")))
        invalid.forEach { places ->
            assertEquals(
                ParserFailureKind.INVALID_RESPONSE,
                assertFailsWith<NaturalLanguageParserException> {
                    contract.parseProviderResponse(
                        response(
                            listOf(hardTime()) + places,
                        ),
                        request,
                        strict = true,
                    )
                }.kind,
            )
        }
        val valid =
            contract
                .parseProviderResponse(
                    response(listOf(hardTime(), hard, preferredPlace("역삼"))),
                    request,
                    strict = true,
                ).single()
        assertEquals(3, valid.conditions.size)
        assertNull(valid.rejectionCode)
    }

    @Test
    fun `unsupported conditional rejection cannot be changed to a usable preference-only result`() {
        val wire = response(emptyList(), rejection = "UNSUPPORTED_CONDITIONAL_CONSTRAINT")
        listOf("gemini", "luna").forEach { provider ->
            Endpoint(provider, wire).use { endpoint ->
                val result = endpoint.parse().results.single()
                assertTrue(result.conditions.isEmpty())
                assertEquals("UNSUPPORTED_CONDITIONAL_CONSTRAINT", result.rejectionCode)
            }
        }
    }

    private fun hardTime() =
        mapOf(
            "type" to "TIME_WINDOW",
            "polarity" to "AVAILABLE",
            "date" to "2026-10-07",
            "day_of_week" to null,
            "start_time" to "18:00",
            "end_time" to "22:00",
        )

    private fun preferredTime() =
        mapOf(
            "type" to "PREFERRED_TIME_WINDOW",
            "polarity" to null,
            "date" to "2026-10-07",
            "day_of_week" to null,
            "start_time" to "20:00",
            "end_time" to "21:00",
        )

    private fun preferredPlace(
        query: String = "강남",
        key: String = "AREA_1",
        name: String = "강남",
    ) = mapOf(
        "type" to "PREFERRED_PLACE",
        "query" to query,
        "radius_meters" to null,
        "area_key" to key,
        "area_name" to name,
    )

    private fun response(
        conditions: List<Map<String, Any?>>,
        version: String = "3",
        rejection: String? = null,
    ) = mapper.writeValueAsString(
        mapOf(
            "schema_version" to version,
            "results" to
                listOf(mapOf("input_ref" to request.inputs.single().inputRef, "conditions" to conditions, "rejection_code" to rejection)),
        ),
    )

    private fun responseResults(results: List<Map<String, Any?>>) =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "3",
                "results" to results,
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
        provider: String,
        wire: String,
    ) : AutoCloseable {
        val bodies = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val response =
            if (provider == "gemini") {
                mapOf(
                    "candidates" to
                        listOf(
                            mapOf(
                                "content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to wire))),
                                "finishReason" to "STOP",
                            ),
                        ),
                )
            } else {
                mapOf(
                    "status" to "completed",
                    "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to wire)))),
                )
            }
        private val adapter =
            if (provider == "gemini") {
                GeminiNaturalLanguageParserAdapter(
                    GeminiProperties(apiKey = "synthetic-loopback-placeholder"),
                    mapper,
                    URI.create("http://127.0.0.1:${server.address.port}"),
                )
            } else {
                OpenAiLunaNaturalLanguageParserAdapter(
                    OpenAiProperties(apiKey = "synthetic-loopback-placeholder"),
                    mapper,
                    OkHttpClient.Builder().build(),
                    URI.create("http://127.0.0.1:${server.address.port}/responses"),
                )
            }

        init {
            server.createContext("/") { exchange ->
                bodies += exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
                val bytes = mapper.writeValueAsBytes(response)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

        fun parse() = adapter.parse(request)

        override fun close() = server.stop(0)
    }

    companion object {
        private val DATE = LocalDate.of(2026, 10, 7)
    }
}
