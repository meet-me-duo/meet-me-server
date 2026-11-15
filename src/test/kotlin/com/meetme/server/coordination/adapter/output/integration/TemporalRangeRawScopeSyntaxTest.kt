package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** New generation syntax admission only; no actual response, repaired timestamp, or natural-language oracle. */
class TemporalRangeRawScopeSyntaxTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val raw = "2036-04-16의 명시된 시각 구간을 사용합니다."

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2036-04-16T",
            "2036-19-12T18:25",
            "2036-04-00T18:25",
            "2036-02-30T18:25",
            "2035-02-29T18:25",
            "2036-04-16T24:00",
            "2036-04-16T18:60",
            "2036-04-16T18:25:60",
            "2036-04-16T18:25:12.",
            "2036-04-16T18:25:12.1234567890",
            "2036-04-16t18:25",
            "+2036-04-16T18:25",
            "+1000000000-04-16T18:25",
            "2036-04-16T18:25+08:00",
            "2036-04-16T18:25+19:00",
            "2036-04-16",
            "18:25",
            "2036-04-16 18:25",
        ],
    )
    fun `RSS01 malformed nonnull TIME start is INVALID_RESPONSE instead of successful parsing with a dropped claim`(start: String) {
        val f = F(raw)
        val document = document(f, start, null)
        val before = mapper.writeValueAsBytes(document)
        invalid { f.newParse(document) }
        assertContentEquals(before, mapper.writeValueAsBytes(document))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2036-04-16T", "2036-02-30T20:15", "2036-04-16T20:15+08:00"])
    fun `RSS02 end is validated independently before a valid start can mask its malformed syntax`(end: String) {
        val f = F(raw)
        invalid { f.newParse(document(f, "2036-04-16T18:25", end)) }
    }

    @Test
    fun `RSS03 nonstring nonnull TIME endpoints cannot be coerced to absent or unresolved boundaries`() {
        val f = F(raw)
        for (bad in listOf(1845, true, listOf("2036-04-16T18:25"))) {
            invalid { f.newParse(document(f, bad, null)) }
            invalid { f.newParse(document(f, null, bad)) }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "2036-04-16T18:25",
            "2036-04-16T18:25:12",
            "2036-04-16T18:25:12.5",
            "2036-04-16T18:25:12.123456789",
            "2036-04-16T18:25+09:00",
            "2036-04-16T18:25+09",
            "2036-04-16T18:25:12.125+09:00:00",
        ],
    )
    fun `RSS04 valid local timestamps and compatible offsets retain native claims and prior room local values`(start: String) {
        val f = F(raw)
        val document = document(f, start, "2036-04-16T20:15", precise = true)
        val before = mapper.writeValueAsBytes(document)
        val old = f.oldParse(document - "temporal_range_admission_version")
        val actual = f.newParse(document)
        assertEquals(old, actual)
        val result =
            actual.base.base.declaration.claims.results
                .single { it.submissionVersionId == f.first }
        val claim = result.conditions.single { it.id == "range" }
        assertTrue(result.quarantined.isEmpty())
        val local =
            if ('+' in start) {
                java.time.OffsetDateTime
                    .parse(start)
                    .toLocalDateTime()
            } else {
                LocalDateTime.parse(start)
            }
        assertEquals(local, claim.scope?.start)
        assertEquals(LocalDateTime.parse("2036-04-16T20:15"), claim.scope?.end)
        assertContentEquals(before, mapper.writeValueAsBytes(document))
    }

    @Test
    fun `RSS05 null scopes and boundaries preserve old semantics without supplying endpoints or claiming precision`() {
        val f = F(raw)
        for ((start, end) in listOf(null to null, "2036-04-16T18:25" to null, null to "2036-04-16T20:15")) {
            val doc = document(f, start, end)
            assertEquals(f.oldParse(doc - "temporal_range_admission_version"), f.newParse(doc))
        }
        val doc = f.newDocument(linkedMapOf("kind" to "RAW_SCOPE", "scope" to null))
        assertEquals(f.oldParse(doc - "temporal_range_admission_version"), f.newParse(doc))
        val unknown = f.newDocument(linkedMapOf("kind" to "UNKNOWN"))
        assertEquals(f.oldParse(unknown - "temporal_range_admission_version"), f.newParse(unknown))
    }

    @Test
    fun `RSS06 strict TIME syntax does not alter non TIME RAW_SCOPE historical handling`() {
        val f = F(raw)
        for (dimension in listOf("PLACE", "ALL")) {
            val doc = document(f, "not-a-timestamp", null, dimension = dimension)
            val old = f.oldParse(doc - "temporal_range_admission_version")
            assertEquals(old, f.newParse(doc))
        }
    }

    @Test
    fun `RSS07 old five still returns the historical dropped claim and dangling reference rather than a parser exception`() {
        val f = F(raw)
        val doc = document(f, "2036-04-16T", null) - "temporal_range_admission_version"
        val before = mapper.writeValueAsBytes(doc)
        val old = f.oldParse(doc)
        val result =
            old.base.base.declaration.claims.results
                .single { it.submissionVersionId == f.first }
        assertTrue(result.conditions.none { it.id == "range" })
        assertTrue(result.quarantined.any { it.conditionId == "range" })
        val program =
            old.base.base.declaration.programs
                .single { it.submissionVersionId == f.first }
        assertTrue(ScheduleDslProblem.REFERENCE in program.problems)
        assertContentEquals(before, mapper.writeValueAsBytes(doc))
        val default = F()
        assertEquals(
            "0bcdcb82841cf3da2c40a3f054790bf107879a1e4f345a573f53be9378a85d6f",
            sha(WeeklyEvidenceProviderContract(mapper).requestBytes(default.capsule, MeetingMode.EITHER)),
        )
    }

    @Test
    fun `RSS08 native six alone narrows RAW_SCOPE TIME strings to anchored ISO shapes and preserves other temporal schemas`() {
        val f = F()
        val neutral = TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val oldNeutral = WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val six = wire(neutral, AnalysisProvider.OPENAI).path("text").path("format").path("schema")
        val five = wire(oldNeutral, AnalysisProvider.OPENAI).path("text").path("format").path("schema")
        val scope = rawScopeSchema(six)
        val oldScope = rawScopeSchema(five)
        val valid =
            listOf(
                "2036-04-16T18:25",
                "2036-04-16T18:25:12.123456789",
                "2036-04-16T18:25+09",
                "2036-04-16T18:25+09:00:00",
                "0000-04-16T18:25",
                "-0001-04-16T18:25",
                "+10000-04-16T18:25",
            )
        for (value in valid) assertTrue(accepts(scope, scopeValue("TIME", value, null)), value)
        val malformed =
            listOf(
                "2036-04-16T",
                "2036-19-00T18:25",
                "2036-04-16T25:61",
                "2036-04-16T18:25:12.",
                "2036-04-16T18:25:12.1234567890",
                "2036-04-16t18:25",
                "2036-04-16T18:25junk",
            )
        for (value in malformed) {
            assertFalse(accepts(scope, scopeValue("TIME", value, null)), "start:$value")
            assertFalse(accepts(scope, scopeValue("TIME", null, value)), "end:$value")
        }
        assertTrue(accepts(scope, mapper.readTree("null")))
        assertTrue(accepts(scope, scopeValue("TIME", null, null)))
        for (dimension in listOf("PLACE", "ALL")) {
            for (value in valid + malformed) {
                val sample = scopeValue(dimension, value, null)
                assertEquals(accepts(oldScope, sample), accepts(scope, sample), "$dimension:$value")
            }
        }
        assertEquals(withoutRawScope(five), withoutRawScope(six))
        val oldTree = mapper.readTree(oldNeutral)
        val sixTree = mapper.readTree(neutral)
        val stripped = mapper.readTree(mapper.writeValueAsBytes(sixTree.path("schema"))) as ObjectNode
        (stripped.path("properties") as ObjectNode).remove("temporal_range_admission_version")
        stripped.replace("required", oldTree.path("schema").path("required"))
        assertEquals(oldTree.path("schema"), stripped)
    }

    @Test
    fun `RSS09 both native providers enforce the same RAW_SCOPE shape without request or response rewrites`() {
        val f = F()
        val neutral = TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val ai = wire(neutral, AnalysisProvider.OPENAI)
        val gemini = wire(neutral, AnalysisProvider.GEMINI)
        assertEquals(
            rawScopeSchema(ai.path("text").path("format").path("schema")),
            rawScopeSchema(gemini.path("generationConfig").path("responseJsonSchema")),
        )
        assertEquals(
            mapper.readTree(ai.path("input").asText()),
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            ),
        )
        assertEquals(
            ai.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
    }

    private fun document(
        f: F,
        start: Any?,
        end: Any?,
        precise: Boolean = false,
        dimension: String = "TIME",
    ): MutableMap<String, Any?> =
        f
            .newDocument(
                linkedMapOf("kind" to "RAW_SCOPE", "scope" to linkedMapOf("dimension" to dimension, "start" to start, "end" to end)),
            ).also { if (precise) f.condition(it)["temporal_resolution"] = "PRECISE_INTERVAL" }

    private fun rawScopeSchema(schema: JsonNode): JsonNode =
        branches(schema)
            .flatMap {
                it
                    .path("properties")
                    .path("temporal")
                    .path("anyOf")
                    .toList()
            }.single {
                it
                    .path("properties")
                    .path("kind")
                    .path("enum")
                    .any { name -> name.asText() == "RAW_SCOPE" }
            }.path("properties")
            .path("scope")

    private fun branches(schema: JsonNode) =
        schema
            .path("properties")
            .path("results")
            .path("items")
            .path("properties")
            .path("conditions")
            .path("items")
            .path("anyOf")
            .toList()

    private fun withoutRawScope(schema: JsonNode): List<JsonNode> =
        branches(schema).filterNot { branch ->
            branch.path("properties").path("temporal").path("anyOf").any { temporal ->
                temporal
                    .path("properties")
                    .path("kind")
                    .path("enum")
                    .any { it.asText() == "RAW_SCOPE" }
            }
        }

    private fun scopeValue(
        dimension: String,
        start: Any?,
        end: Any?,
    ): JsonNode = mapper.valueToTree(mapOf("dimension" to dimension, "start" to start, "end" to end))

    /** Generic JSON Schema subset used by these scope schemas, independent of the production narrowing algorithm. */
    private fun accepts(
        schema: JsonNode,
        value: JsonNode,
    ): Boolean {
        if (schema.has("anyOf") && schema.path("anyOf").none { accepts(it, value) }) return false
        if (schema.has("allOf") && schema.path("allOf").any { !accepts(it, value) }) return false
        if (schema.has("if")) {
            val selected = if (accepts(schema.path("if"), value)) "then" else "else"
            if (schema.has(selected) && !accepts(schema.path(selected), value)) return false
        }
        if (schema.has("const") && schema.path("const") != value) return false
        if (schema.has("enum") && schema.path("enum").none { it == value }) return false
        when (schema.path("type").asText()) {
            "null" -> if (!value.isNull) return false
            "string" -> if (!value.isString) return false
            "object" -> if (!value.isObject) return false
        }
        if (value.isString && schema.has("pattern") && !Regex(schema.path("pattern").asText()).containsMatchIn(value.asText())) return false
        if (value.isObject) {
            if (schema.path("required").any { !value.has(it.asText()) }) return false
            for ((name, child) in schema.path("properties").properties()) {
                if (value.has(name) && !accepts(child, value.path(name))) return false
            }
        }
        return true
    }

    private fun wire(
        neutral: ByteArray,
        provider: AnalysisProvider,
    ): JsonNode =
        mapper.readTree(
            ServerEvidenceWire.bytes(
                mapper,
                neutral,
                provider,
                if (provider ==
                    AnalysisProvider.OPENAI
                ) {
                    "gpt-6-luna"
                } else {
                    "gemini-3.8-flash"
                },
            ),
        )

    private fun invalid(block: () -> Unit) {
        val failure = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
