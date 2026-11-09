package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F

class ScheduleDslApprovedTermsProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val parser = StrictScheduleDslApprovedTermsResponseParser(mapper)

    private fun parse(json: String = F.json()) =
        parser.parse(
            json,
            F.snapshot(),
            "schedule-dsl-response-3",
            "schedule-ir-product-4",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    @Test
    fun `AHC01 explicit new parser returns the wrapper and server-derived afternoon`() {
        assertEquals("schedule-dsl-response-3", parser.supportedResponseContractVersion)
        val document = F.document()
        val term = F.firstTerm(document)
        assertFalse(term.containsKey("start"))
        assertFalse(term.containsKey("end"))
        val result = parse(F.json(document))
        assertEquals("schedule-ir-product-4", result.semanticVersion)
        assertEquals(
            "NON_PRECISE",
            result.declaration.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution.name,
        )
        assertEquals(
            "PRECISE_INTERVAL",
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution.name,
        )
        assertEquals(F.scope("2027-01-08T12:00", "2027-01-09T00:00"), result.termApplications.single().resolvedScope)
    }

    @Test
    fun `AHC02 new request selects closed vocabulary while preserving native source and question bytes`() {
        val q = ScheduleDslQuestionContextFixture
        val capsule =
            com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
                .create(q.snapshot(), q.native())
        val old = ScheduleDslContextProviderContract(mapper).requestBytes(capsule)
        val contract = ScheduleDslApprovedTermsProviderContract(mapper)
        val bytes = contract.requestBytes(capsule, MeetingMode.REMOTE)
        val request = mapper.readTree(bytes)
        assertEquals("schedule-dsl-request-3", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-3", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-4", request.path("semantic_version").asString())
        assertEquals("ko-time-terms-1", request.path("vocabulary_version").asString())
        assertEquals("REMOTE", request.path("context").path("meeting_mode").asString())
        assertEquals(mapper.readTree(old).path("inputs"), request.path("inputs"))
        val prompt = requireNotNull(request.path("prompt").asString())
        val precedence =
            listOf(
                "If availability_root is present, keep its availability bounds; preferences add credit only inside those bounds.",
                "If availability_root is absent and preference_root is present, " +
                    "use the preference bounds as availability instead of the search-period default.",
                "Use the search-period default only when both availability_root and preference_root are absent.",
                "Explicit exclusions take precedence in every case.",
            )
        precedence.forEach { assertTrue(prompt.contains(it), "Missing approved availability precedence clause") }
        assertFalse(prompt.contains("A preference-only valid program therefore keeps the entire allowed search base"))
        assertFalse(prompt.contains("preference alone does not restrict the base to its own interval"))
        assertFalse(prompt.contains("a valid program with no availability_root uses the entire search range"))
        val schema = mapper.readTree(mapper.writeValueAsBytes(contract.responseSchema()))
        val term =
            schema
                .path("properties")
                .path("results")
                .path("items")
                .path("properties")
                .path("approved_terms")
                .path("items")
        val enumNode = term.path("properties").path("term").path("enum")
        assertTrue(enumNode.isArray)
        assertEquals(11, enumNode.size())
        val enumValues: Set<String> =
            (0 until enumNode.size())
                .map { index ->
                    requireNotNull(enumNode.path(index).asString())
                }.toSet()
        assertEquals(
            setOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY"),
            enumValues,
        )
        assertEquals(false, term.path("additionalProperties").asBoolean())
        assertContentEquals(old, ScheduleDslContextProviderContract(mapper).requestBytes(capsule))
    }

    @ParameterizedTest
    @ValueSource(strings = ["response", "semantic", "ast", "vocabulary"])
    fun `AHC03 exact selected versions cannot silently fall back`(wrong: String) {
        assertFailsWith<RuntimeException> {
            parser.parse(
                F.json(),
                F.snapshot(),
                if (wrong == "response") "schedule-dsl-response-2" else "schedule-dsl-response-3",
                if (wrong == "semantic") SCHEDULE_DSL_SEMANTIC_VERSION else "schedule-ir-product-4",
                if (wrong == "ast") "schedule-ast-unapproved" else SCHEDULE_AST_VERSION,
                if (wrong == "vocabulary") "unapproved-meals-1" else "ko-time-terms-1",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["response1", "context2"])
    fun `AHC04 old decoders reject approved term fields even under an otherwise old outer version`(old: String) {
        val document = F.document()
        document["semantic_version"] = SCHEDULE_DSL_SEMANTIC_VERSION
        document.remove("vocabulary_version")
        val failure =
            assertFailsWith<NaturalLanguageParserException> {
                if (old == "context2") {
                    StrictScheduleDslContextResponseParser(mapper).parse(
                        F.json(document),
                        F.snapshot(),
                        "schedule-dsl-response-2",
                        SCHEDULE_DSL_SEMANTIC_VERSION,
                        SCHEDULE_AST_VERSION,
                    )
                } else {
                    StrictScheduleDslResponseParser(mapper, SCHEDULE_DSL_RESPONSE_CONTRACT).parse(
                        F.json(document),
                        F.snapshot(),
                        SCHEDULE_DSL_RESPONSE_CONTRACT,
                        SCHEDULE_DSL_SEMANTIC_VERSION,
                        SCHEDULE_AST_VERSION,
                    )
                }
            }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @ParameterizedTest
    @ValueSource(strings = ["BRUNCH", "SIESTA", "TEATIME"])
    fun `AHC05 unknown term has no numeric fallback and cannot erase the independent peer`(term: String) {
        val document = F.document()
        F.firstTerm(document)["term"] = term
        val result = parse(F.json(document))
        val prepared = ScheduleDslInputPolicy.project(result.calculationProjection, F.policy)
        assertTrue(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
        assertFalse(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.secondParticipant) }
                .availableTimes
                .isEmpty(),
        )
        assertTrue(result.termApplications.isEmpty())
    }

    @Test
    fun `AHC06 extra root fields and malformed JSON use safe typed failures`() {
        val document = F.document()
        document["unapproved_default"] = "PRIVATE_SYNTHETIC_SENTINEL"
        for (json in listOf(F.json(document), "{PRIVATE_SYNTHETIC_SENTINEL")) {
            val failure = assertFailsWith<NaturalLanguageParserException> { parse(json) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertEquals(null, failure.cause)
            assertFalse(failure.message.orEmpty().contains("PRIVATE_SYNTHETIC_SENTINEL"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["claims", "bytes"])
    fun `AHC07 document limits fail closed rather than silently truncate dated applications`(limit: String) {
        val document = F.document()
        val json =
            if (limit == "claims") {
                @Suppress("UNCHECKED_CAST")
                val original = (F.firstResult(document)["conditions"] as List<Map<String, Any?>>).single()
                F.firstResult(document)["conditions"] = (1..33).map { original + ("id" to if (it == 1) "half" else "condition-$it") }
                F.json(document)
            } else {
                F.json(document) + " ".repeat(262_145)
            }
        val failure = assertFailsWith<NaturalLanguageParserException> { parse(json) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
    }

    @ParameterizedTest
    @ValueSource(strings = ["오후", "아침", "점심", "저녁", "밤", "날짜만"])
    fun `AHC08 raw words without explicit approved references never trigger hidden numeric normalization`(word: String) {
        val input = F.input("🙂 2027-01-08 $word 가능.")
        val proposal = F.proposal(input, terms = emptyList())
        val result =
            parser.parse(
                F.json(F.document(proposal)),
                F.snapshot(input),
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        assertTrue(result.termApplications.isEmpty())
        assertEquals(
            "NON_PRECISE",
            result.calculationProjection.claims.results
                .first()
                .conditions
                .single()
                .temporalResolution.name,
        )
        assertTrue(
            ScheduleDslInputPolicy
                .project(result.calculationProjection, F.policy)
                .participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "mode", "unapproved_default"])
    fun `AHC09 extra term fields are not silently accepted as numeric or mode authority`(field: String) {
        val document = F.document()
        F.firstTerm(document)[field] = "synthetic-extra"
        val result = parse(F.json(document))
        assertTrue(result.termApplications.isEmpty())
        val prepared = ScheduleDslInputPolicy.project(result.calculationProjection, F.policy)
        assertTrue(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.firstParticipant) }
                .availableTimes
                .isEmpty(),
        )
        assertFalse(
            prepared.participants
                .single { it.participantId == ParticipantId(F.ir.secondParticipant) }
                .availableTimes
                .isEmpty(),
        )
    }

    @Test
    fun `AHC10 explicit frozen room mode changes request bytes without altering source inputs or adding a place requirement`() {
        val capsule = F.capsule()
        val contract = ScheduleDslApprovedTermsProviderContract(mapper)
        val rendered = MeetingMode.entries.associateWith { contract.requestBytes(capsule, it) }
        val inputs = mapper.readTree(rendered.getValue(MeetingMode.REMOTE)).path("inputs")
        for ((mode, bytes) in rendered) {
            val request = mapper.readTree(bytes)
            assertEquals(mode.name, request.path("context").path("meeting_mode").asString())
            assertEquals(inputs, request.path("inputs"))
        }
        assertEquals(
            3,
            rendered.values
                .map { it.toString(Charsets.UTF_8) }
                .toSet()
                .size,
        )
    }
}
