package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.StructuredSubmissionResult
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #107 independent public provider contract. Synthetic wire fixtures; no network or paid inference. */
class ClarificationProviderContractTest {
    private val mapper = JsonMapper.builder().build()
    private val gemini = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)
    private val luna = OpenAiLunaNaturalLanguageParserAdapter(OpenAiProperties(), mapper)
    private val date = LocalDate.of(2026, 10, 17)
    private val raw = "내일 7~9시는 불가하고 강남역에서 19~22시 가능해요. 20~21시를 선호해요."

    @Test
    fun `both providers preserve ambiguity proposal and do not fabricate availability`() {
        val request = request()
        val response = response(request, proposal())
        val geminiResult = gemini.parseProviderResponse(response, request).single()
        val lunaResult = luna.parseProviderResponse(response, request).single()

        assertEquals(geminiResult, lunaResult)
        assertEquals("AMBIGUOUS_TIME_CONSTRAINT", geminiResult.rejectionCode)
        assertTrue(geminiResult.conditions.isEmpty())
        val clarification = assertNotNull(geminiResult.clarification)
        assertEquals("7~9시는 불가", clarification.excerpt)
        assertEquals("불가능한 시간은 오전인가요, 오후인가요?", clarification.question)
        assertEquals(optionA, clarification.optionA)
        assertEquals(optionB, clarification.optionB)
        assertEquals(request.inputs.single().inputRef, geminiResult.submissionVersionId.value.toString())
    }

    @Test
    fun `general ambiguity rejection accepts a safe clarification with no conditions`() {
        val request = request()
        val result = parseBoth(response(request, proposal(), rejection = "AMBIGUOUS_INPUT"), request)

        result.forEach {
            assertEquals("AMBIGUOUS_INPUT", it.rejectionCode)
            assertNotNull(it.clarification)
            assertTrue(it.conditions.isEmpty())
        }
    }

    @Test
    fun `clear live result with explicit null clarification keeps valid provider structure`() {
        val request = request("내일 19~21시 가능해요")
        val wire = response(request, null, rejection = null, conditions = listOf(window()))

        parseBoth(wire, request).forEach {
            assertNull(it.clarification)
            assertNull(it.rejectionCode)
            assertEquals(1, it.conditions.size)
        }
    }

    @Test
    fun `legacy Gemini response can omit clarification without creating a question`() {
        val request = request("내일 19~21시 가능해요")
        val wire =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to "2",
                    "results" to listOf(mapOf("input_ref" to request.inputs.single().inputRef, "conditions" to listOf(window()))),
                ),
            )
        val result = gemini.parseProviderResponse(wire, request).single()

        assertNull(result.clarification)
        assertNull(result.rejectionCode)
        assertEquals(1, result.conditions.size)
    }

    @Test
    fun `successful or unrelated rejection cannot carry a clarification`() {
        val request = request()
        listOf(null, "UNSUPPORTED_CONDITIONAL_CONSTRAINT", "CONDITION_VALIDATION_FAILED").forEach { rejection ->
            assertInvalidBoth(response(request, proposal(), rejection = rejection), request)
        }
    }

    @Test
    fun `clarification and any structured condition cannot coexist`() {
        val request = request()
        assertInvalidBoth(response(request, proposal(), conditions = listOf(window())), request)
    }

    @Test
    fun `excerpt must be nonempty exact substring of original and bounded to 200 codepoints`() {
        val request = request()
        listOf("", " ", "타인의 입력", "7~9시라고 했어요").forEach { excerpt ->
            assertInvalidBoth(response(request, proposal() + ("excerpt" to excerpt)), request)
        }
        val longRaw = "가".repeat(201)
        val longRequest = request(longRaw)
        assertInvalidBoth(response(longRequest, proposal() + ("excerpt" to longRaw)), longRequest)
    }

    @Test
    fun `question is required and bounded to 160 Unicode codepoints`() {
        val request = request()
        listOf("", " \uFEFF\u3000 ", "가".repeat(161), "😀".repeat(161)).forEach { question ->
            assertInvalidBoth(response(request, proposal() + ("question" to question)), request)
        }
        parseBoth(response(request, proposal() + ("question" to "😀".repeat(160))), request).forEach {
            assertEquals("😀".repeat(160), assertNotNull(it.clarification).question)
        }
    }

    @Test
    fun `replacement options are full nonempty distinct trimmed text bounded to 500 codepoints`() {
        val request = request()
        val invalid =
            listOf(
                proposal() + ("option_a" to ""),
                proposal() + ("option_b" to " \u3000\uFEFF"),
                proposal() + ("option_a" to "가".repeat(501)),
                proposal() + ("option_b" to "😀".repeat(501)),
                proposal() + ("option_b" to optionA),
                proposal() + ("option_b" to "\u3000$optionA\uFEFF"),
                proposal() + ("option_a" to raw),
                proposal() + ("option_a" to "\uFEFF$raw\u3000"),
            )
        invalid.forEach { assertInvalidBoth(response(request, it), request) }
    }

    @Test
    fun `500 supplementary Unicode codepoints are valid without UTF16 truncation`() {
        val request = request()
        val first = "😀".repeat(499) + "A"
        val second = "😀".repeat(499) + "B"
        val wire = response(request, proposal() + mapOf("option_a" to first, "option_b" to second))

        parseBoth(wire, request).forEach {
            val result = assertNotNull(it.clarification)
            assertEquals(first, result.optionA)
            assertEquals(second, result.optionB)
        }
    }

    @Test
    fun `proposal objects reject missing unknown wrong type and nested fields fail closed`() {
        val request = request()
        val invalid =
            listOf(
                proposal() - "option_b",
                proposal() + ("reason" to "비공개 사유"),
                proposal() + ("excerpt" to null),
                proposal() + ("question" to 1),
                proposal() + ("option_a" to listOf(optionA)),
                proposal() + ("option_b" to mapOf("text" to optionB)),
            )
        invalid.forEach { assertInvalidBoth(response(request, it), request) }
        listOf("text", listOf(proposal()), false).forEach {
            assertInvalidBoth(response(request, it), request)
        }
    }

    @Test
    fun `proposal at 200 excerpt codepoint boundary is accepted even with supplementary characters`() {
        val excerpt = "😀".repeat(200)
        val request = request(excerpt)
        val wire = response(request, proposal() + ("excerpt" to excerpt))

        parseBoth(wire, request).forEach { assertEquals(excerpt, assertNotNull(it.clarification).excerpt) }
    }

    @Test
    fun `live provider schemas expose required nullable clarification with all proposal fields`() {
        val schemas =
            listOf(
                mapper.valueToTree<JsonNode>(GeminiNaturalLanguageParserAdapter.RESPONSE_SCHEMA),
                mapper.valueToTree<JsonNode>(OpenAiLunaNaturalLanguageParserAdapter.RESPONSE_SCHEMA),
            )
        schemas.forEach { schema ->
            val item = schema["properties"]["results"]["items"]
            assertTrue("clarification" in item["required"].asSequence().map { it.asText() }.toSet())
            val proposal = assertNotNull(item["properties"]["clarification"])
            assertTrue(allTypes(proposal).contains("null"), "Live clarification must be nullable")
            val objectNode = objectVariant(proposal)
            val required = objectNode["required"].asSequence().map { it.asText() }.toSet()
            assertEquals(setOf("excerpt", "question", "option_a", "option_b"), required)
            assertEquals(required, objectNode["properties"].propertyNames().toSet())
        }
        assertStrictObjects(schemas.last())
    }

    @Test
    fun `additive v3 readers accept absent clarification in legacy clear responses`() {
        val request = request("내일 19~21시 가능해요")
        val result =
            mapOf("input_ref" to request.inputs.single().inputRef, "conditions" to listOf(window()), "rejection_code" to null)
        val wire = mapper.writeValueAsString(mapOf("schema_version" to "3", "results" to listOf(result)))
        parseBoth(wire, request).forEach {
            assertNull(it.clarification)
            assertNull(it.rejectionCode)
            assertEquals(1, it.conditions.size)
        }
    }

    @Test
    fun `shared prompt requests only material ambiguity and preserves full source meaning and dates`() {
        val request = request()
        val prompt = gemini.prompt(request)
        assertEquals(prompt, luna.prompt(request))
        val instructions = prompt.substringBefore("Inputs:")
        val lower = instructions.lowercase(Locale.ROOT)

        assertTrue("clarification" in lower && "option_a" in lower && "option_b" in lower)
        assertTrue(
            Regex(
                "(?i)(material|affect|impact).{0,80}(result|candidate|availability)|(?:result|candidate).{0,80}(material|affect|impact)",
            ).containsMatchIn(instructions),
        )
        assertTrue(
            Regex(
                "(?i)(clear|unambiguous).{0,100}(null|no clarification)|(?:null|no clarification).{0,100}(clear|unambiguous)",
            ).containsMatchIn(instructions),
        )
        assertTrue(Regex("(?i)(full|entire|complete).{0,60}(replacement|input|text)").containsMatchIn(instructions))
        assertTrue(Regex("(?i)(absolute|explicit).{0,60}date|date.{0,60}(absolute|explicit)").containsMatchIn(instructions))
        assertTrue(Regex("(?i)(preserve|keep).{0,100}(constraint|meaning|condition)").containsMatchIn(instructions))
        assertTrue("reference_date=2026-10-17" in prompt)
        assertTrue(raw in prompt)
    }

    private fun parseBoth(
        wire: String,
        request: NaturalLanguageBatchRequest,
    ): List<StructuredSubmissionResult> =
        listOf(gemini.parseProviderResponse(wire, request).single(), luna.parseProviderResponse(wire, request).single())

    private fun assertInvalidBoth(
        wire: String,
        request: NaturalLanguageBatchRequest,
    ) {
        listOf<() -> Unit>(
            { gemini.parseProviderResponse(wire, request) },
            { luna.parseProviderResponse(wire, request) },
        ).forEach { parse ->
            val failure = assertFailsWith<NaturalLanguageParserException> { parse() }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(false, failure.retryable, "Invalid clarification must not trigger technical retry or fallback")
        }
    }

    private fun request(text: String = raw) =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            date,
            date.plusDays(14),
            listOf(NaturalLanguageInput(UUID(0, 107).toString(), text, Locale.forLanguageTag("ko-KR"), date)),
        )

    private fun proposal(): Map<String, Any?> =
        mapOf(
            "excerpt" to "7~9시는 불가",
            "question" to "불가능한 시간은 오전인가요, 오후인가요?",
            "option_a" to optionA,
            "option_b" to optionB,
        )

    private fun response(
        request: NaturalLanguageBatchRequest,
        clarification: Any?,
        rejection: String? = "AMBIGUOUS_TIME_CONSTRAINT",
        conditions: List<Map<String, Any?>> = emptyList(),
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to "3",
                "results" to
                    listOf(
                        mapOf(
                            "input_ref" to request.inputs.single().inputRef,
                            "conditions" to conditions,
                            "rejection_code" to rejection,
                            "clarification" to clarification,
                        ),
                    ),
            ),
        )

    private fun window(): Map<String, Any?> =
        mapOf(
            "type" to "TIME_WINDOW",
            "polarity" to "AVAILABLE",
            "date" to "2026-10-18",
            "day_of_week" to null,
            "start_time" to "19:00",
            "end_time" to "21:00",
        )

    private fun allTypes(node: JsonNode): Set<String> {
        val own =
            node["type"]
                ?.let { type ->
                    if (type.isArray) type.asSequence().map { it.asText() }.toSet() else setOf(type.asText())
                }.orEmpty()
        return own +
            listOf("anyOf", "oneOf").flatMap { key ->
                node[key]
                    ?.asSequence()
                    ?.flatMap { allTypes(it) }
                    ?.toList()
                    .orEmpty()
            }
    }

    private fun objectVariant(node: JsonNode): JsonNode =
        if (node["properties"] != null) {
            node
        } else {
            listOf("anyOf", "oneOf").asSequence().flatMap { key -> node[key]?.asSequence() ?: emptySequence() }.first {
                it["properties"] != null
            }
        }

    private fun assertStrictObjects(node: JsonNode) {
        if (node.isObject) {
            if (node["properties"] != null) {
                assertEquals(false, node["additionalProperties"]?.asBoolean())
                assertEquals(node["properties"].propertyNames().toSet(), node["required"].asSequence().map { it.asText() }.toSet())
            }
            node.forEach { assertStrictObjects(it) }
        } else if (node.isArray) {
            node.forEach { assertStrictObjects(it) }
        }
    }

    private val optionA = "2026년 10월 18일 오전 7~9시는 불가하고 강남역에서 19~22시 가능해요. 20~21시를 선호해요."
    private val optionB = "2026년 10월 18일 오후 7~9시는 불가하고 강남역에서 19~22시 가능해요. 20~21시를 선호해요."
}
