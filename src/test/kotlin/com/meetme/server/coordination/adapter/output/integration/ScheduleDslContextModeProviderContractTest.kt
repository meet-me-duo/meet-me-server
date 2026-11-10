package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrTemporalResolution
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
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** C4 wire/decoder boundary only; existing native sources and numerical kernels are real and entirely synthetic. */
class ScheduleDslContextModeProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val parser = StrictScheduleDslContextModeResponseParser(mapper)

    private fun parse(json: String = F.json(N.document())) =
        parser.parse(
            json,
            N.snapshot(),
            "schedule-dsl-response-4",
            "schedule-ir-product-5",
            SCHEDULE_AST_VERSION,
            "ko-time-terms-1",
        )

    @Test
    fun `CMN01 request preserves native question context vocabulary and frozen mode with a closed mode schema`() {
        val capsule = N.capsule()
        assertEquals(1, capsule.questions.size)
        val old = ScheduleDslApprovedTermsProviderContract(mapper)
        val before = old.requestBytes(capsule, MeetingMode.EITHER)
        val contract = ScheduleDslContextModeProviderContract(mapper)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals("schedule-dsl-request-4", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-4", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-5", request.path("semantic_version").asString())
        assertEquals("ko-time-terms-1", request.path("vocabulary_version").asString())
        assertEquals("EITHER", request.path("context").path("meeting_mode").asString())
        assertEquals("schedule-context-mode-1", request.path("context").path("policy_version").asString())
        assertEquals(mapper.readTree(before).path("inputs"), request.path("inputs"))
        val prompt = requireNotNull(request.path("prompt").asString())
        listOf("INFERRED", "NON_PRECISE", "MODEL_CONTEXT", "mode_restrictions").forEach { assertTrue(prompt.contains(it)) }
        val remote = mapper.readTree(contract.requestBytes(capsule, MeetingMode.REMOTE))
        assertEquals("REMOTE", remote.path("context").path("meeting_mode").asString())
        assertEquals(request.path("inputs"), remote.path("inputs"))
        assertContentEquals(before, old.requestBytes(capsule, MeetingMode.EITHER))

        val schema = contract.responseSchema()
        val rootProperties = fields(schema["properties"])
        assertEquals(setOf("semantic_version", "ast_version", "vocabulary_version", "results"), rootProperties.keys)
        assertClosed(schema)
        val result = fields(fields(rootProperties["results"])["items"])
        val resultProperties = fields(result["properties"])
        assertEquals(setOf("input_ref", "conditions", "program", "approved_terms", "mode_restrictions"), resultProperties.keys)
        assertClosed(result)
        val mode = fields(fields(resultProperties["mode_restrictions"])["items"])
        val modeProperties = fields(mode["properties"])
        assertEquals(
            setOf(
                "id",
                "scope",
                "permitted_modes",
                "mode_interpretation",
                "time_interpretation",
                "temporal_resolution",
                "evidence",
                "assertion",
                "supersedes",
                "alternative_group",
                "alternative",
            ),
            modeProperties.keys,
        )
        assertClosed(mode)
        val permitted = fields(modeProperties["permitted_modes"])
        assertEquals("array", permitted["type"])
        assertEquals(1, (permitted["minItems"] as Number).toInt())
        assertEquals(setOf("REMOTE", "IN_PERSON"), strings(fields(permitted["items"])["enum"]))
        val term = fields(fields(resultProperties["approved_terms"])["items"])
        val terms = fields(fields(term["properties"])["term"])["enum"] as List<*>
        assertEquals(
            setOf("AM", "PM", "DAWN", "MORNING", "LUNCH", "DAYTIME", "MIDDAY", "EVENING", "NIGHT", "LATE_NIGHT", "ALL_DAY"),
            strings(terms),
        )
    }

    @Test
    fun `CMN02 strict C4 decoding preserves contextual declaration and hard mode through actual matching`() {
        assertEquals("schedule-dsl-response-4", parser.supportedResponseContractVersion)
        val envelope = parse()
        assertEquals(N.expectedEnvelope(), envelope)
        val declared =
            envelope.base.declaration.claims.results
                .first()
                .conditions
                .single { it.id == "context" }
        val projected =
            envelope.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "context" }
        assertEquals(IrInterpretation.INFERRED, declared.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertEquals(IrInterpretation.INFERRED, projected.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, projected.temporalResolution)
        assertEquals(declared.scope, projected.scope)
        assertEquals(declared.evidence, projected.evidence)
        assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, envelope.contextApplications.single().derivation)
        assertTrue(envelope.base.termApplications.isEmpty())
        assertFalse(N.FIRST.contains("오후"))
        assertEquals(setOf(MeetingMode.REMOTE), envelope.modeRestrictions.single().permittedModes)
        assertEquals(F.ir.first, envelope.modeRestrictions.single().submissionVersionId)
        val calculation = ScheduleDslContextModeCalculator.calculate(envelope, N.snapshot(), N.policy, MeetingMode.EITHER)
        val participant = ParticipantId(F.ir.firstParticipant)
        assertEquals(
            listOf(N.thursday, N.friday),
            calculation.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .single { it.participantId == participant }
                .availableTimes,
        )
        assertEquals(
            listOf(N.friday),
            calculation.preparedByMode
                .getValue(MeetingMode.IN_PERSON)
                .participants
                .single { it.participantId == participant }
                .availableTimes,
        )
        val candidates = calculation.candidates.candidates.associateBy { it.meetingMode }
        assertEquals(listOf(N.thursday, N.friday), candidates.getValue(MeetingMode.REMOTE).timeRanges)
        assertEquals(listOf(N.friday), candidates.getValue(MeetingMode.IN_PERSON).timeRanges)
        assertTrue(candidates.values.all { it.participantIds.size == 2 && it.representativePlace == null })
        val thursday = calculation.recommendations.options.single { it.window == N.thursday }
        assertEquals(setOf(MeetingMode.REMOTE), thursday.variants.map { it.meetingMode }.toSet())
    }

    @Test
    fun `CMN03 selected versions never fallback and C3 still rejects added mode declarations`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                F.json(N.document()),
                N.snapshot(),
                "schedule-dsl-response-3",
                "schedule-ir-product-5",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        }
        val oldBody = N.document().apply { this["semantic_version"] = "schedule-ir-product-4" }
        invalid { parse(F.json(oldBody)) }
        val old = StrictScheduleDslApprovedTermsResponseParser(mapper)
        invalid {
            old.parse(
                F.json(oldBody),
                N.snapshot(),
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        }
        val original = F.json(F.document(N.proposal()))
        val accepted =
            old.parse(
                original,
                N.snapshot(),
                "schedule-dsl-response-3",
                "schedule-ir-product-4",
                SCHEDULE_AST_VERSION,
                "ko-time-terms-1",
            )
        assertTrue(accepted.termApplications.isEmpty())
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            accepted.calculationProjection.claims.results
                .first()
                .conditions
                .single { it.id == "context" }
                .temporalResolution,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "extra", "scalar", "duplicate", "empty", "revision", "quote"])
    fun `CMN04 malformed mode declarations never disappear into unrestricted availability`(damage: String) {
        val document = N.document()
        val mode = N.firstMode(document)
        when (damage) {
            "missing" -> F.firstResult(document).remove("mode_restrictions")
            "extra" -> mode["unapproved_place"] = "PRIVATE_SYNTHETIC_MODE_SENTINEL"
            "scalar" -> mode["permitted_modes"] = "REMOTE"
            "duplicate" -> mode["permitted_modes"] = listOf("REMOTE", "REMOTE")
            "empty" -> mode["permitted_modes"] = emptyList<String>()
            "revision" -> firstEvidence(mode)["revision"] = 999
            "quote" -> firstEvidence(mode)["quote"] = "PRIVATE_SYNTHETIC_MODE_SENTINEL"
        }
        invalid { parse(F.json(document)) }
        if (damage == "duplicate") {
            val duplicateId = N.document()
            val first = N.firstMode(duplicateId)
            F.firstResult(duplicateId)["mode_restrictions"] = listOf(first, first.toMap())
            invalid { parse(F.json(duplicateId)) }
            val duplicateKey = F.json(N.document()).replaceFirst("\"permitted_modes\":[", "\"permitted_modes\":[],\"permitted_modes\":[")
            assertFalse(duplicateKey == F.json(N.document()))
            invalid { parse(duplicateKey) }
        }
    }

    private fun invalid(block: () -> Unit) {
        val error = assertFailsWith<NaturalLanguageParserException>(block = block)
        assertEquals(ParserFailureKind.INVALID_RESPONSE, error.kind)
        assertEquals(null, error.cause)
        assertFalse(error.message.orEmpty().contains("PRIVATE_SYNTHETIC_MODE_SENTINEL"))
        assertFalse(error.message.orEmpty().contains(N.FIRST))
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = requireNotNull(value as? Map<String, Any?>)

    private fun strings(value: Any?): Set<String> = (value as List<*>).map { requireNotNull(it as? String) }.toSet()

    private fun assertClosed(value: Map<String, Any?>) {
        assertEquals(false, value["additionalProperties"])
        assertEquals(fields(value["properties"]).keys, strings(value["required"]))
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstEvidence(mode: MutableMap<String, Any?>) = (mode["evidence"] as List<MutableMap<String, Any?>>).first()
}
