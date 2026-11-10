package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent strict product wire; legacy parser/schema/transport are not changed by these tests. */
class ScheduleIrProviderContractTest {
    private val mapper = JsonMapper.builder().build()
    private val parser = ScheduleIrProviderContract(mapper)
    private val fixture = ScheduleIrTestFixture

    @Test
    fun `complete explicit product response produces the independently expected typed envelope`() {
        assertEquals(fixture.envelope(), parse(document()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "mode", "null_mode", "flat_available_times", "unknown_effect", "missing_nullable", "numeric_id", "wrong_assertion",
        ],
    )
    fun `malformed condition fields are quarantined without rewriting an independent valid condition`(defect: String) {
        val bad = condition("bad").toMutableMap()
        when (defect) {
            "mode" -> bad["mode"] = "REMOTE"
            "null_mode" -> bad["mode"] = null
            "flat_available_times" -> bad["availableTimes"] = emptyList<Any>()
            "unknown_effect" -> bad["effect"] = "OWNER_CONFIRMED"
            "missing_nullable" -> bad.remove("alternative_group")
            "numeric_id" -> bad["id"] = 12
            else -> bad["assertion"] = "PARTICIPANT_APPROVED"
        }
        val result = parse(document(listOf(condition(), bad))).results.single { it.submissionVersionId == fixture.firstId }

        assertEquals(listOf(fixture.expectedCondition()), result.conditions)
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, result.availability)
        assertEquals(1, result.quarantined.size)
        assertEquals(IrProblem.SHAPE, result.quarantined.single().problem)
        assertNull(result.quarantined.single().scope)
    }

    @ParameterizedTest
    @ValueSource(strings = ["quote", "revision", "span", "source"])
    fun `wire evidence claims require exact server registered source and codepoint quote`(defect: String) {
        val bad = condition("bad").toMutableMap()
        val evidence = evidence().toMutableMap()
        when (defect) {
            "quote" -> evidence["quote"] = "invented synthetic sensitive phrase"
            "revision" -> evidence["revision"] = 7
            "span" -> evidence["start_code_point"] = 99
            else -> evidence["source_id"] = "provider-owned-registry"
        }
        bad["evidence"] = listOf(evidence)
        val result = parse(document(listOf(condition(), bad))).results.single { it.submissionVersionId == fixture.firstId }

        assertEquals(listOf(fixture.expectedCondition()), result.conditions)
        assertEquals(listOf("bad"), result.quarantined.map { it.conditionId })
        assertEquals(IrProblem.EVIDENCE, result.quarantined.single().problem)
        assertNull(result.quarantined.single().scope)
        assertFalse(result.toString().contains("invented synthetic sensitive phrase"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["fraction_revision", "string_revision", "boolean_start", "extra_evidence_field", "missing_scope_end"])
    fun `strict scalar and null field shapes cannot be silently coerced`(defect: String) {
        val bad = condition("bad").toMutableMap()
        val evidence = evidence().toMutableMap()
        when (defect) {
            "fraction_revision" -> evidence["revision"] = 3.5
            "string_revision" -> evidence["revision"] = "3"
            "boolean_start" -> evidence["start_code_point"] = true
            "extra_evidence_field" -> evidence["confirmed"] = true
            else -> bad["scope"] = mapOf("dimension" to "TIME", "start" to fixture.window.start.toString())
        }
        bad["evidence"] = listOf(evidence)
        val result = parse(document(listOf(condition(), bad))).results.single { it.submissionVersionId == fixture.firstId }

        assertEquals(listOf(fixture.expectedCondition()), result.conditions)
        assertEquals(1, result.quarantined.size)
        assertEquals(IrProblem.SHAPE, result.quarantined.single().problem)
        assertNull(result.quarantined.single().scope)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "wrong_version", "missing_version", "extra_root", "provider_registry", "flat_available_times", "missing_result",
            "extra_input", "duplicate_input", "extra_result_field", "duplicate_condition",
        ],
    )
    fun `document identity and closed root schema failures reject the entire response`(defect: String) {
        val root = document().toMutableMap()
        val first = result(fixture.firstId, listOf(condition()))
        val second = result(fixture.secondId, emptyList(), "UNKNOWN")
        when (defect) {
            "wrong_version" -> root["semantic_version"] = "future-ir"
            "missing_version" -> root.remove("semantic_version")
            "extra_root" -> root["confirmed"] = true
            "provider_registry" -> root["sources"] = listOf(mapOf("id" to "original", "text" to "provider invented"))
            "flat_available_times" -> root["availableTimes"] = emptyList<Any>()
            "missing_result" -> root["results"] = listOf(first)
            "extra_input" -> root["results"] = listOf(first, second, result(UUID(0, 999), emptyList(), "UNKNOWN"))
            "duplicate_input" -> root["results"] = listOf(first, first)
            "extra_result_field" -> root["results"] = listOf(first + ("confirmed" to true), second)
            else -> root["results"] = listOf(result(fixture.firstId, listOf(condition(), condition())), second)
        }

        val error = assertFailsWith<NaturalLanguageParserException> { parse(root) }
        assertEquals(ParserFailureKind.INVALID_RESPONSE, error.kind)
        assertNull(error.cause)
    }

    @Test
    fun `caller selected IR version mismatch cannot select a different parser implicitly`() {
        val error =
            assertFailsWith<NaturalLanguageParserException> {
                parser.parseProviderResponse(mapper.writeValueAsString(document()), fixture.snapshot(), "future-ir-shape")
            }

        assertEquals(ParserFailureKind.INVALID_RESPONSE, error.kind)
        assertNull(error.cause)
    }

    @Test
    fun `malformed JSON exception does not retain original text in message or cause chain`() {
        val sensitive = "synthetic private payload that must stay out of exceptions"
        val malformed = "{\"semantic_version\":\"$SCHEDULE_IR_VERSION\",\"results\":[\"$sensitive\""
        val error =
            assertFailsWith<NaturalLanguageParserException> {
                parser.parseProviderResponse(malformed, fixture.snapshot(), SCHEDULE_IR_VERSION)
            }

        assertEquals(ParserFailureKind.INVALID_RESPONSE, error.kind)
        assertNull(error.cause)
        assertFalse(error.toString().contains(sensitive))
        assertFalse(error.stackTraceToString().contains(sensitive))
    }

    @Test
    fun `all malformed conditions produce unknown with retained diagnostics rather than unconstrained`() {
        val bad = condition("bad") + ("mode" to "REMOTE")
        val result = parse(document(listOf(bad))).results.single { it.submissionVersionId == fixture.firstId }

        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertTrue(result.conditions.isEmpty())
        assertTrue(result.quarantined.any { it.conditionId == "bad" && it.problem == IrProblem.SHAPE })
        assertTrue(result.quarantined.any { it.problem == IrProblem.AVAILABILITY })
    }

    private fun parse(root: Map<String, Any?>) =
        parser.parseProviderResponse(mapper.writeValueAsString(root), fixture.snapshot(), SCHEDULE_IR_VERSION)

    private fun document(conditions: List<Map<String, Any?>> = listOf(condition())): Map<String, Any?> =
        mapOf(
            "semantic_version" to SCHEDULE_IR_VERSION,
            "results" to listOf(result(fixture.firstId, conditions), result(fixture.secondId, emptyList(), "UNKNOWN")),
        )

    private fun result(
        id: UUID,
        conditions: List<Map<String, Any?>>,
        availability: String = "KNOWN_WINDOWS",
    ): Map<String, Any?> = mapOf("input_ref" to id.toString(), "availability" to availability, "conditions" to conditions)

    private fun condition(id: String = "thursday"): Map<String, Any?> =
        mapOf(
            "id" to id,
            "effect" to "AVAILABLE",
            "interpretation" to "EXPLICIT",
            "scope" to mapOf("dimension" to "TIME", "start" to fixture.window.start.toString(), "end" to fixture.window.end.toString()),
            "evidence" to listOf(evidence()),
            "assertion" to "ASSERTED",
            "supersedes" to emptyList<String>(),
            "alternative_group" to null,
            "alternative" to null,
        )

    private fun evidence(): Map<String, Any?> {
        val evidence = fixture.evidence()
        return mapOf(
            "source_id" to evidence.sourceId,
            "revision" to evidence.revision,
            "start_code_point" to evidence.startCodePoint,
            "end_code_point" to evidence.endCodePoint,
            "quote" to evidence.quote,
        )
    }
}
