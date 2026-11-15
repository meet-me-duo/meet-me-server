package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_TEMPORAL_RANGE_REQUEST_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_TEMPORAL_RANGE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.TemporalRangeAdmission
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.TemporalRangeAdmissionTestFixture as F

/** Explicit generation6 admission over unmodified synthetic JSON; no provider, credential, or network call. */
class TemporalRangeEvidenceProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `TRP01 new request freezes admission identity while preserving native context catalog and old weekly bytes`() {
        val f = F()
        val old = WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)
        val oldTree = mapper.readTree(old)
        val actual = mapper.readTree(TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER))
        assertEquals(SERVER_EVIDENCE_TEMPORAL_RANGE_REQUEST_CONTRACT, actual.path("request_contract_version").asText())
        assertEquals(SERVER_EVIDENCE_TEMPORAL_RANGE_RESPONSE_CONTRACT, actual.path("response_contract_version").asText())
        assertEquals(TemporalRangeAdmission.VERSION, actual.path("temporal_range_admission_version").asText())
        for (field in listOf(
            "inputs",
            "evidence_catalog",
            "calendar_context",
            "relative_date_context",
            "meeting_date_scope",
            "semantic_version",
            "ast_version",
            "vocabulary_version",
            "weekly_rules_version",
        )) {
            assertEquals(oldTree.path(field), actual.path(field), field)
        }
        assertTrue(actual.path("prompt").asText().contains(TemporalRangeAdmission.VERSION))
        assertFalse(actual.path("prompt").asText().contains(F.RAW))
        assertFalse(oldTree.has("temporal_range_admission_version"))
        assertContentEquals(old, WeeklyEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER))
    }

    @Test
    fun `TRP02 response schema adds only the required exact admission marker and both wires retain identical native data`() {
        val f = F()
        val old = mapper.valueToTree<JsonNode>(WeeklyEvidenceProviderContract(mapper).responseSchema(f.snapshot))
        val schema = mapper.valueToTree<JsonNode>(TemporalRangeEvidenceProviderContract(mapper).responseSchema(f.snapshot))
        assertEquals(
            listOf(
                TemporalRangeAdmission.VERSION,
            ),
            schema.path("properties").path("temporal_range_admission_version").path("enum").toList().map {
                it.asText()
            },
        )
        assertEquals(
            old
                .path("required")
                .toList()
                .map {
                    it.asText()
                }.toSet() + "temporal_range_admission_version",
            schema
                .path("required")
                .toList()
                .map { it.asText() }
                .toSet(),
        )
        val without = mapper.readTree(mapper.writeValueAsBytes(schema)) as ObjectNode
        (without.path("properties") as ObjectNode).remove("temporal_range_admission_version")
        without.replace("required", old.path("required"))
        assertEquals(old, without)
        val neutral = TemporalRangeEvidenceProviderContract(mapper).requestBytes(f.capsule, MeetingMode.EITHER)

        fun wire(provider: AnalysisProvider) =
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
        val ai = wire(AnalysisProvider.OPENAI)
        val gemini = wire(AnalysisProvider.GEMINI)
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
            ai.path("instructions"),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text"),
        )
        for (wireSchema in listOf(
            ai.path("text").path("format").path("schema"),
            gemini.path("generationConfig").path("responseJsonSchema"),
        )) {
            assertTrue(wireSchema.path("required").any { it.asText() == "temporal_range_admission_version" })
            assertEquals(
                listOf(
                    TemporalRangeAdmission.VERSION,
                ),
                wireSchema.path("properties").path("temporal_range_admission_version").path("enum").toList().map {
                    it.asText()
                },
            )
            assertEquals(old.path("properties").path("results"), schema.path("properties").path("results"))
        }
    }

    @Test
    fun `TRP03 valid generation six has the same format12 domain shape and leaves original response and sources untouched`() {
        val f = F()
        val document = f.newDocument()
        val frozen = mapper.writeValueAsBytes(document)
        val actual = f.newParse(document)
        assertEquals(f.oldParse(), actual)
        assertEquals(actual, ScheduleDslStructuralValidator().validateRestored(actual, f.snapshot))
        assertFalse(mapper.writeValueAsString(actual).contains("temporal_range_admission_version"))
        assertFalse(mapper.writeValueAsString(actual).contains(TemporalRangeAdmission.VERSION))
        assertContentEquals(frozen, mapper.writeValueAsBytes(document))
        assertEquals(F.RAW, f.snapshot.sourceText(f.first, "original"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["single-date", "missing-upper", "wrong-upper", "meaning-only-upper", "broad-clock-proof"])
    fun `TRP04 new admission rejects the incomplete or unsupported declaration rather than repairing its date or clocks`(fault: String) {
        val f = F()
        val atom =
            when (fault) {
                "single-date" -> f.dated()
                "missing-upper" -> f.weekly(f.time(upper = null, clocks = listOf(f.clock("오후 4시 20분"))))
                "wrong-upper" -> f.weekly(f.time(upper = "21:40"))
                "meaning-only-upper" -> f.weekly(f.time(clocks = listOf(f.clock("오후 4시 20분"))))
                else -> f.weekly(f.time(clocks = listOf(f.whole())))
            }
        val document = f.newDocument(atom)
        if (fault == "meaning-only-upper") f.condition(document)["evidence"] = listOf(f.whole(), f.clock("8시 40분"))
        val frozen = mapper.writeValueAsBytes(document)
        invalid { f.newParse(document) }
        assertContentEquals(frozen, mapper.writeValueAsBytes(document))
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "null", "wrong", "number"])
    fun `TRP05 generation six requires its exact noncoerced admission marker`(fault: String) {
        val f = F()
        val document = f.newDocument()
        when (fault) {
            "absent" -> document.remove("temporal_range_admission_version")
            "null" -> document["temporal_range_admission_version"] = null
            "wrong" -> document["temporal_range_admission_version"] = "temporal-range-admission-unknown"
            else -> document["temporal_range_admission_version"] = 1
        }
        invalid { f.newParse(document) }
    }

    @Test
    fun `TRP06 old response five parsing and restoration keep original one sided semantics without new admission`() {
        val f = F()
        val oldDoc = f.oldDocument(f.dated())
        val frozen = mapper.writeValueAsBytes(oldDoc)
        val old = f.oldParse(oldDoc)
        assertEquals(old, ScheduleDslStructuralValidator().validateRestored(old, f.snapshot))
        assertEquals(
            "16:20",
            old.directionalApplications
                .single { it.submissionVersionId == f.first && it.declaration.conditionId == "range" }
                .resolvedScope.start!!
                .toLocalTime()
                .toString(),
        )
        assertEquals(
            "00:00",
            old.directionalApplications
                .single { it.submissionVersionId == f.first && it.declaration.conditionId == "range" }
                .resolvedScope.end!!
                .toLocalTime()
                .toString(),
        )
        invalid { f.newParse(oldDoc) }
        invalid { f.oldParse(f.newDocument()) }
        assertEquals(old, f.oldParse(oldDoc))
        assertContentEquals(frozen, mapper.writeValueAsBytes(oldDoc))
    }

    @Test
    fun `TRP07 generation six preserves existing catalog ownership rejection`() {
        val f = F()
        val atom = f.weekly(f.time(clocks = listOf(f.clock("오후 4시 20분"), f.whole(f.second))))
        invalid { f.newParse(f.newDocument(atom)) }
    }

    @Test
    fun `TRP08 linked correction and genuine UNKNOWN keep native old behavior without guessed endpoint repair`() {
        val f = F(linkedAnswer = true)
        val atom = f.weekly(f.time("16:20", "19:50", listOf(f.clock("오후 4시 20분"), f.clock("오후 7시 50분"))))
        assertEquals(f.oldParse(f.oldDocument(atom)), f.newParse(f.newDocument(atom)))
        val plain = F()
        val unknown = linkedMapOf<String, Any?>("kind" to "UNKNOWN")
        val actual = plain.newParse(plain.newDocument(unknown))
        assertEquals(plain.oldParse(plain.oldDocument(unknown)), actual)
        assertTrue(actual.weeklyApplications.isEmpty())
        assertTrue(actual.directionalApplications.none { it.submissionVersionId == plain.first })
        assertTrue(actual.directionalApplications.any { it.submissionVersionId == plain.second })
    }

    private fun invalid(block: () -> Unit) {
        assertEquals(ParserFailureKind.INVALID_RESPONSE, assertFailsWith<NaturalLanguageParserException>(block = block).kind)
    }
}
