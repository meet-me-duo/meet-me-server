package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ServerEvidenceCatalog
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as H
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Model-visible delivery and effective schema parity, without network or personal case data. */
class ServerEvidenceWireTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val source = "🙂 SYNTHETIC_DATA_DO_NOT_MOVE_61. 2027-01-08 11:00~13:00 가능."

    @Test
    fun `SEW01 both providers receive the same native catalogue data and instruction text in their instruction channels`() {
        val neutral = neutral()
        val original = mapper.readTree(neutral)
        val openAi = body(neutral, AnalysisProvider.OPENAI)
        val gemini = body(neutral, AnalysisProvider.GEMINI)
        val first = mapper.readTree(openAi.path("input").asText())
        val second =
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            )
        assertEquals(first, second)
        assertEquals(original.path("inputs"), first.path("inputs"))
        assertEquals(original.path("evidence_catalog"), first.path("evidence_catalog"))
        assertEquals(ServerEvidenceCatalog.VERSION, first.path("evidence_catalog_version").asText())
        assertFalse(first.has("prompt"))
        assertFalse(first.has("schema"))
        val instructions = openAi.path("instructions").asText()
        assertTrue(instructions.isNotBlank())
        assertEquals(
            instructions,
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertFalse(instructions.contains(source))
        assertFalse(instructions.contains("SYNTHETIC_DATA_DO_NOT_MOVE_61"))
        assertTrue(first.toString().contains("SYNTHETIC_DATA_DO_NOT_MOVE_61"))
    }

    @Test
    fun `SEW02 both providers retain low reasoning and the fixed output budget with strict OpenAI structured output`() {
        val neutral = neutral()
        val openAi = body(neutral, AnalysisProvider.OPENAI)
        val gemini = body(neutral, AnalysisProvider.GEMINI)
        assertEquals("gpt-6-luna", openAi.path("model").asText())
        assertFalse(openAi.path("store").asBoolean(true))
        assertEquals("low", openAi.path("reasoning").path("effort").asText())
        assertEquals(32768, openAi.path("max_output_tokens").asInt())
        assertEquals(
            "json_schema",
            openAi
                .path("text")
                .path("format")
                .path("type")
                .asText(),
        )
        assertTrue(
            openAi
                .path("text")
                .path("format")
                .path("strict")
                .asBoolean(),
        )
        val configuration = gemini.path("generationConfig")
        assertEquals("application/json", configuration.path("responseMimeType").asText())
        assertEquals(32768, configuration.path("maxOutputTokens").asInt())
        assertEquals("LOW", configuration.path("thinkingConfig").path("thinkingLevel").asText())
    }

    @Test
    fun `SEW03 effective provider schemas preserve reference only evidence and a finite acyclic logical expression graph`() {
        val neutral = neutral()
        val openAi = body(neutral, AnalysisProvider.OPENAI)
        val gemini = body(neutral, AnalysisProvider.GEMINI)
        val schema = openAi.path("text").path("format").path("schema")

        @Suppress("UNCHECKED_CAST")
        val parsed = mapper.convertValue(schema, Map::class.java) as Map<String, Any?>
        assertEquals(
            mapper.valueToTree<JsonNode>(GeminiResponseSchemaProjection.project(parsed)),
            gemini.path("generationConfig").path("responseJsonSchema"),
        )
        val fields = mutableSetOf<String>()
        val operators = mutableSetOf<String>()

        fun visit(node: JsonNode) {
            if (node.isObject) {
                node.path("properties").properties().forEach { (name, definition) ->
                    fields += name
                    if (name == "operator") definition.path("enum").forEach { operators += it.asText() }
                }
                node.properties().forEach { (_, child) -> visit(child) }
            } else if (node.isArray) {
                node.forEach(::visit)
            }
        }
        visit(schema)
        assertFalse(fields.any { it in setOf("quote", "proofs", "start_code_point", "end_code_point") })
        assertTrue(fields.containsAll(setOf("evidence", "date_evidence", "clock_evidence", "term_evidence", "place_evidence")))
        assertTrue(operators.containsAll(setOf("CLAIM_REF", "ANY_OF", "ALL_OF", "EXCEPT", "PREFER", "CHOICE")))
        assertAcyclic(schema)
    }

    @Test
    fun `SEW04 wire construction is deterministic and does not mutate the frozen neutral request bytes`() {
        val neutral = neutral()
        val frozen = neutral.copyOf()
        for ((provider, model) in providers()) {
            val first = ServerEvidenceWire.bytes(mapper, neutral, provider, model)
            assertContentEquals(first, ServerEvidenceWire.bytes(mapper, neutral, provider, model))
            assertContentEquals(frozen, neutral)
        }
    }

    @Test
    fun `SEW05 unknown or incomplete neutral evidence contracts cannot fall back to another wire format`() {
        val mutations: List<(MutableMap<String, Any?>) -> Unit> =
            listOf(
                { it.remove("prompt") },
                { it.remove("schema") },
                { it["evidence_catalog_version"] = "server-evidence-catalog-unknown" },
                { it["request_contract_version"] = "schedule-dsl-request-5" },
                { it["response_contract_version"] = "schedule-dsl-response-5" },
            )
        for (mutate in mutations) {
            @Suppress("UNCHECKED_CAST")
            val altered = mapper.readValue(neutral(), Map::class.java) as MutableMap<String, Any?>
            mutate(altered)
            val bytes = mapper.writeValueAsBytes(altered)
            for ((provider, model) in providers()) {
                assertFailsWith<IllegalArgumentException> { ServerEvidenceWire.bytes(mapper, bytes, provider, model) }
            }
        }
    }

    private fun neutral(): ByteArray {
        val input = H.input(source).copy(policy = N.policy, meetingMode = MeetingMode.EITHER)
        return ServerEvidenceProviderContract(mapper, allowPlaceConstraints = true).requestBytes(H.capsule(input), MeetingMode.EITHER)
    }

    private fun body(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ): JsonNode = mapper.readTree(ServerEvidenceWire.bytes(mapper, neutral, provider, providers().getValue(provider)))

    private fun providers() = mapOf(AnalysisProvider.OPENAI to "gpt-6-luna", AnalysisProvider.GEMINI to "gemini-3.8-flash")

    private fun assertAcyclic(schema: JsonNode) {
        val definitions = schema.path("\$defs").properties().associate { it.key to it.value }

        fun references(node: JsonNode): Set<String> {
            val found = mutableSetOf<String>()
            if (node.isObject) {
                node.path("\$ref").takeIf { it.isTextual }?.asText()?.let { reference ->
                    assertTrue(reference.startsWith("#/\$defs/"))
                    found += reference.removePrefix("#/\$defs/")
                }
                node.properties().forEach { (_, child) -> found += references(child) }
            } else if (node.isArray) {
                node.forEach { found += references(it) }
            }
            return found
        }
        val completed = mutableSetOf<String>()

        fun visit(
            name: String,
            path: Set<String>,
        ) {
            assertFalse(name in path, "Recursive schema path: $path -> $name")
            assertTrue(name in definitions, "Undefined schema reference: $name")
            if (name in completed) return
            assertTrue(path.size < 8, "Logical schema exceeds the existing maximum expression depth")
            references(definitions.getValue(name)).forEach { visit(it, path + name) }
            completed += name
        }
        definitions.keys.forEach { visit(it, emptySet()) }
    }
}
