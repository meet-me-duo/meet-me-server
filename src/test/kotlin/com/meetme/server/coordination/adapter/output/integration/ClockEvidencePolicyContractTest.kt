package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.ServerEvidenceKind
import com.meetme.server.submission.domain.dsl.TemporalRangeAdmission
import com.meetme.server.submission.domain.dsl.WeeklyTimeKind
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** Invented declarations verify request capability and provenance, not a language model's semantic accuracy. */
class ClockEvidencePolicyContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val policy =
        mapOf(
            "version" to "clock-evidence-policy-1",
            "atomic_when_available" to true,
            "context_fallback_without_atomic" to true,
            "genuine_unknown" to "PRESERVE_UNKNOWN",
        )

    @Test
    fun `CEP01 generation six declares the static clock evidence capability without changing native inputs or response schema`() {
        val f = F()
        val old = mapper.readTree(WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER))
        val actual = mapper.readTree(TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER))
        assertEquals(mapper.valueToTree<JsonNode>(policy), actual.path("clock_evidence_policy"))
        for (field in listOf("inputs", "evidence_catalog", "calendar_context", "relative_date_context", "meeting_date_scope")) {
            assertEquals(old.path(field), actual.path(field), field)
        }
        assertEquals("server-evidence-catalog-1", actual.path("evidence_catalog_version").asText())
        val expectedSchema = mapper.valueToTree<JsonNode>(TemporalRangeEvidenceProviderContract(mapper).responseSchema(f.snapshot))
        assertEquals(expectedSchema, actual.path("schema"))
        val withoutAdmission = mapper.readTree(mapper.writeValueAsBytes(expectedSchema)) as ObjectNode
        (withoutAdmission.path("properties") as ObjectNode).remove("temporal_range_admission_version")
        withoutAdmission.replace("required", old.path("schema").path("required"))
        assertEquals(old.path("schema"), withoutAdmission)
        assertFalse(expectedSchema.path("properties").has("clock_evidence_policy"))
        assertFalse(expectedSchema.path("required").any { it.asText() == "clock_evidence_policy" })
        assertEquals(f.raw, f.snapshot.sourceText(f.first, "original"))
    }

    @Test
    fun `CEP02 both provider wires receive the same policy and instructions while output schema and budgets remain unchanged`() {
        val f = F()
        val request = TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val ai = wire(request, AnalysisProvider.OPENAI)
        val gemini = wire(request, AnalysisProvider.GEMINI)
        val aiData = mapper.readTree(ai.path("input").asText())
        val geminiData =
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            )
        assertEquals(aiData, geminiData)
        assertEquals(mapper.valueToTree<JsonNode>(policy), aiData.path("clock_evidence_policy"))
        val instructions = ai.path("instructions").asText()
        assertEquals(
            instructions,
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        for (term in listOf("clock-evidence-policy-1", "atomic_when_available", "context_fallback_without_atomic", "genuine_unknown")) {
            assertTrue(instructions.contains(term), term)
        }
        assertFalse(instructions.contains(f.raw))
        for (schema in listOf(ai.path("text").path("format").path("schema"), gemini.path("generationConfig").path("responseJsonSchema"))) {
            assertFalse(schema.path("properties").has("clock_evidence_policy"))
            assertTrue(schema.path("required").any { it.asText() == "temporal_range_admission_version" })
        }
        assertEquals(32768, ai.path("max_output_tokens").asInt())
        assertEquals(32768, gemini.path("generationConfig").path("maxOutputTokens").asInt())
        assertEquals("low", ai.path("reasoning").path("effort").asText())
        assertEquals(
            "LOW",
            gemini
                .path("generationConfig")
                .path("thinkingConfig")
                .path("thinkingLevel")
                .asText(),
        )
    }

    @Test
    fun `CEP03 old five neutral and native wire bytes remain stable and have no new capability or instructions`() {
        val f = F()
        val old = WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        assertEquals(
            "0bcdcb82841cf3da2c40a3f054790bf107879a1e4f345a573f53be9378a85d6f",
            MessageDigest.getInstance("SHA-256").digest(old).joinToString("") { "%02x".format(it) },
        )
        val oldWires = AnalysisProvider.entries.associateWith { wireBytes(old, it) }
        TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val after = WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        assertContentEquals(old, after)
        val tree = mapper.readTree(after)
        assertFalse(tree.has("clock_evidence_policy"))
        assertFalse(tree.path("prompt").asText().contains("clock-evidence-policy-1"))
        assertEquals("schedule-dsl-evidence-response-5", tree.path("response_contract_version").asText())
        for (provider in AnalysisProvider.entries) {
            assertContentEquals(oldWires.getValue(provider), wireBytes(after, provider))
        }
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "오전 여덟 시 반부터 오전 열 시까지 가능합니다.|08:30|10:00",
            "오후여섯시부터 오후아홉시까지 가능해요.|18:00|21:00",
        ],
        delimiter = '|',
    )
    fun `CEP04 literal word clocks may retain both proposed bounds using their own issued context`(
        raw: String,
        lower: String,
        upper: String,
    ) {
        val f = F(raw)
        assertTrue(f.catalog.entries.none { it.inputId == f.first && it.kind == ServerEvidenceKind.CLOCK })
        assertContextBounds(f, lower, upper, listOf(f.whole()))
    }

    @Test
    fun `CEP05 an explicit word duration relation may propose its upper bound without an invented atomic clock`() {
        val f = F("오후 세 시부터 두 시간 동안 가능합니다.")
        assertTrue(f.catalog.entries.none { it.inputId == f.first && it.kind == ServerEvidenceKind.CLOCK })
        assertContextBounds(f, "15:00", "17:00", listOf(f.whole()))
    }

    @Test
    fun `CEP06 atomic preference is per endpoint so an issued lower clock coexists with context for a duration derived upper`() {
        val f = F("오후 2시부터 세 시간 동안 가능합니다.")
        val atomic = f.clock("오후 2시")
        assertEquals(1, f.catalog.entries.count { it.inputId == f.first && it.kind == ServerEvidenceKind.CLOCK })
        assertContextBounds(f, "14:00", "17:00", listOf(atomic, f.whole()))
    }

    @Test
    fun `CEP07 a personal event without a known ending remains UNKNOWN and receives no guessed numeric window`() {
        val f = F("리허설이 끝난 뒤부터 가능합니다.")
        val unknown = f.weekly(f.time(lower = null, upper = null, clocks = emptyList(), kind = "UNKNOWN"))
        val document = f.newDocument(unknown)
        val before = mapper.writeValueAsBytes(document)
        val actual = f.newParse(document)
        val app = actual.weeklyApplications.single { it.inputId == f.first }
        assertEquals(WeeklyTimeKind.UNKNOWN, app.rule.time.kind)
        assertFalse(app.resolved)
        assertTrue(app.occurrences.isEmpty())
        assertEquals(null, app.rule.time.lower)
        assertEquals(null, app.rule.time.upper)
        assertTrue(
            app.rule.time.clockEvidence
                .isEmpty(),
        )
        assertContentEquals(before, mapper.writeValueAsBytes(document))
        assertEquals(actual, ScheduleDslStructuralValidator().validateRestored(actual, f.snapshot))
    }

    @Test
    fun `CEP08 fallback never grants another participant context authority over a proposed bound`() {
        val f = F("오전 일곱 시부터 오전 아홉 시까지 가능합니다.")
        val atom = f.weekly(f.time("07:00", "09:00", listOf(f.whole(f.second))))
        val document = f.newDocument(atom)
        f.condition(document)["interpretation"] = "INFERRED"
        val before = mapper.writeValueAsBytes(document)
        val failure = assertFailsWith<NaturalLanguageParserException> { f.newParse(document) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertContentEquals(before, mapper.writeValueAsBytes(document))
    }

    @Test
    fun `CEP09 issued numeric endpoint clocks remain mandatory when context fallback is available`() {
        val f = F()
        val document = f.newDocument(f.weekly(f.time(clocks = listOf(f.whole()))))
        val before = mapper.writeValueAsBytes(document)
        val failure = assertFailsWith<NaturalLanguageParserException> { f.newParse(document) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        assertContentEquals(before, mapper.writeValueAsBytes(document))
    }

    private fun assertContextBounds(
        f: F,
        lower: String,
        upper: String,
        evidence: List<Int>,
    ) {
        val document = f.newDocument(f.weekly(f.time(lower, upper, evidence)))
        f.condition(document)["interpretation"] = "INFERRED"
        val before = mapper.writeValueAsBytes(document)
        val actual = f.newParse(document)
        val app = actual.weeklyApplications.single { it.inputId == f.first }
        assertTrue(app.resolved)
        assertEquals(WeeklyTimeKind.BOUNDS, app.rule.time.kind)
        assertEquals(
            lower,
            app.rule.time.lower
                .toString(),
        )
        assertEquals(
            upper,
            app.rule.time.upper
                .toString(),
        )
        assertEquals(
            evidence.map { f.catalog.entries[it].id }.toSet(),
            app.rule.time.clockEvidence
                .map { it.catalogId }
                .toSet(),
        )
        val claim =
            actual.base.base.declaration.claims.results
                .single { it.submissionVersionId == f.first }
                .conditions
                .single()
        assertEquals(IrInterpretation.INFERRED, claim.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, claim.temporalResolution)
        assertEquals(7, app.occurrences.size)
        assertTrue(
            app.occurrences.all {
                it.localScope.start
                    ?.toLocalTime()
                    .toString() == lower
            },
        )
        assertTrue(
            app.occurrences.all {
                it.localScope.end
                    ?.toLocalTime()
                    .toString() == upper
            },
        )
        assertEquals(actual, ScheduleDslStructuralValidator().validateRestored(actual, f.snapshot))
        assertEquals(f.oldParse(document - "temporal_range_admission_version"), actual)
        assertContentEquals(before, mapper.writeValueAsBytes(document))
        assertEquals(f.raw, f.snapshot.sourceText(f.first, "original"))
        assertEquals(TemporalRangeAdmission.VERSION, document["temporal_range_admission_version"])
    }

    private fun wireBytes(
        request: ByteArray,
        provider: AnalysisProvider,
    ) = ServerEvidenceWire.bytes(mapper, request, provider, if (provider == AnalysisProvider.OPENAI) "gpt-6-luna" else "gemini-3.8-flash")

    private fun wire(
        request: ByteArray,
        provider: AnalysisProvider,
    ) = mapper.readTree(wireBytes(request, provider))
}
