package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassificationContract
import com.meetme.server.precheck.adapter.output.classification.JevAmbiguityClassificationContract
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure synthetic wire contracts; no classification, question quality or provider call is claimed. */
class PrecheckTemporalVocabularyParityTest {
    private val mapper = JsonMapper.builder().build()
    private val generation = PrecheckProviderContract(mapper)
    private val classification = JevAmbiguityClassificationContract(mapper)
    private val v1 = TwoStageFixtures.request()
    private val v2 =
        v1.copy(
            rawText = "2026-10-24 저녁 가능하고 19시가 좋아요.",
            evidence =
                InputEvidence(
                    3,
                    TwoStageFixtures.anchor,
                    InputSupplement(
                        UUID(0, 9341),
                        3,
                        "저녁",
                        11,
                        "저녁 중 가능한 시작은 언제인가요?",
                        "18시 이후 가능해요.",
                        "A",
                        TwoStageFixtures.anchor,
                        TwoStageFixtures.anchor,
                    ),
                ),
        )

    @Test
    fun `PV01 Jev and Luna V2 share the complete approved eleven term baseline and twelve native lexemes`() {
        val question =
            mapper
                .readTree(classification.requestBytes(classificationRequest(v2)))
                .path("questions")
                .path("material_ambiguity")
        val jev = requireNotNull(question.path("instructions").asString())
        val luna = requireNotNull(mapper.readTree(generation.lunaRequestBytes(v2)).path("instructions").asString())
        assertTrue(luna.startsWith("$jev\n"), "Question generation must receive the same complete V2 rubric")
        for (instructions in listOf(jev, luna)) {
            assertTrue(instructions.contains(TABLE_HEADER))
            assertTrue(instructions.contains(TABLE_SPEC))
            assertFalse(instructions.contains("Unspecified 아침, 저녁 and 밤 have no fixed default boundaries"))
        }
        assertEquals(11, TABLE_SPEC.split("; ").size)
        assertTrue(jev.contains("LATE_NIGHT=심야 or 한밤중 22:00–next-day 04:00"))
        assertEquals(setOf("type", "instructions", "criteria"), question.propertyNames().toSet())
        assertEquals(setOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE"), question.path("criteria").propertyNames().toSet())
    }

    @Test
    fun `PV02 V2 baseline keeps context explicit bounds unknown private facts and immutable scoped evidence separate`() {
        val jev = mapper.readTree(classification.requestBytes(classificationRequest(v2)))
        val luna = mapper.readTree(generation.lunaRequestBytes(v2))
        val jevInstructions =
            jev
                .path("questions")
                .path("material_ambiguity")
                .path("instructions")
                .asString()
                .orEmpty()
        val lunaInstructions = luna.path("instructions").asString().orEmpty()
        val preservedRules =
            listOf(
                CONTEXT_RULE,
                EXPLICIT_RULE,
                "Do not invent work, class, meal, sleep, travel or other private event times.",
                "Resolve original relative dates from the immutable evidence reference_at in time_zone.",
                "The supplement has its own reference_at and applies only to the quoted source revision/span, " +
                    "never resets original dates.",
                "Other participants' availability is unavailable here and cannot establish a private fact.",
                "Keep every unrelated constraint, alternative, negation and preference.",
            )
        for (instructions in listOf(jevInstructions, lunaInstructions)) {
            preservedRules.forEach { assertTrue(instructions.contains(it), "Missing V2 rule: $it") }
        }
        val state = jev.path("state")
        val input = mapper.readTree(requireNotNull(luna.path("input").asString()))
        assertEquals(state.path("evidence"), input.path("evidence"))
        assertEquals(v2.rawText, state.path("raw_text").asString())
        assertEquals(v2.rawText, input.path("raw_text").asString())
        assertEquals(3, input.path("evidence").path("original_revision").asInt())
        val answer = input.path("evidence").path("supplement")
        assertEquals(3, answer.path("source_revision").asInt())
        assertEquals(11, answer.path("source_start_code_point").asInt())
        assertEquals("저녁", answer.path("source_excerpt").asString())
        assertEquals("18시 이후 가능해요.", answer.path("answer_text").asString())
    }

    @Test
    fun `PV03 V2 construction preserves V1 request bytes schema and the existing V1 classification profile`() {
        val oldLuna = generation.lunaRequestBytes(v1)
        val oldGemini = generation.requestBytes(v1)
        val oldJev = classification.requestBytes(classificationRequest(v1))
        val luna = mapper.readTree(oldLuna)
        val schema = luna.path("text").path("format").path("schema")
        assertEquals(
            "meet_me_personal_check_v1",
            luna
                .path("text")
                .path("format")
                .path("name")
                .asString(),
        )
        assertEquals(setOf("verdict", "prompt", "sourceExcerpt", "choices"), schema.path("properties").propertyNames().toSet())
        assertEquals(
            setOf("CLEAR", "NEEDS_CHOICE", "NEEDS_EDIT"),
            schema
                .path("properties")
                .path("verdict")
                .path("enum")
                .values()
                .map { it.asString() }
                .toSet(),
        )
        val jevQuestion = mapper.readTree(oldJev).path("questions").path("material_ambiguity")
        val oldProfile =
            mapper
                .readTree(AmbiguityClassificationContract(mapper).requestBytes(classificationRequest(v1)))
                .path("questions")
                .get(0)
        assertEquals(oldProfile.path("instructions"), jevQuestion.path("instructions"))
        assertFalse(
            luna
                .path("instructions")
                .asString()
                .orEmpty()
                .contains(TABLE_HEADER),
        )
        assertFalse(
            jevQuestion
                .path("instructions")
                .asString()
                .orEmpty()
                .contains(TABLE_HEADER),
        )
        generation.lunaRequestBytes(v2)
        classification.requestBytes(classificationRequest(v2))
        assertContentEquals(oldLuna, generation.lunaRequestBytes(v1))
        assertContentEquals(oldGemini, generation.requestBytes(v1))
        assertContentEquals(oldJev, classification.requestBytes(classificationRequest(v1)))
        assertEquals(
            schema,
            mapper
                .readTree(generation.lunaRequestBytes(v1))
                .path("text")
                .path("format")
                .path("schema"),
        )
    }

    private fun classificationRequest(request: PrecheckProviderRequest) =
        AmbiguityClassificationRequest(
            request.rawText,
            request.referenceAt,
            request.timeZone,
            request.locale,
            request.searchStartDate,
            request.searchEndDate,
            request.timeout,
            evidence = request.evidence,
        )

    companion object {
        private const val TABLE_HEADER =
            "The approved ko-time-terms-1 baseline uses inclusive starts and exclusive local-calendar ends:"
        private const val TABLE_SPEC =
            "AM=오전 00:00–12:00; PM=오후 12:00–next-day 00:00; DAWN=새벽 00:00–06:00; " +
                "MORNING=아침 06:00–11:00; LUNCH=점심 11:00–15:00; DAYTIME=낮 09:00–18:00; " +
                "MIDDAY=한낮 11:00–15:00; EVENING=저녁 17:00–22:00; NIGHT=밤 20:00–next-day 06:00; " +
                "LATE_NIGHT=심야 or 한밤중 22:00–next-day 04:00; ALL_DAY=하루종일 00:00–next-day 00:00."
        private const val CONTEXT_RULE =
            "Use the approved vocabulary as a baseline; surrounding context may reasonably shrink or expand " +
                "it without clarification solely because a time word is imprecise."
        private const val EXPLICIT_RULE =
            "Explicit numeric bounds take precedence over an unadjusted vocabulary baseline. " +
                "Context-supported estimates do not establish participant confirmation."
    }
}
