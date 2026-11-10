package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.support.ScheduleDslWireFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleDslProviderContractTest {
    private val w = ScheduleDslWireFixture
    private val f = w.f
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslProviderContract(mapper)

    private fun parse(json: String) =
        contract.parseProviderResponse(json, f.ir.snapshot(), SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)

    @Test
    fun `DSLW01 strict wire retains nested registry and verified evidence through restored validation`() {
        val expected = f.envelope(w.claims, w.program)
        val parsed = parse(w.json())
        assertEquals(expected, parsed)
        assertEquals(expected, ScheduleDslValidator().validateRestored(parsed, f.ir.snapshot()))
        assertFalse(parsed.toString().contains(f.ir.FIRST_TEXT))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "unknown_root",
            "missing_semantic",
            "null_ast",
            "wrong_semantic",
            "wrong_ast",
            "results_object",
            "extra_result",
            "missing_input",
            "numeric_input",
            "conditions_object",
        ],
    )
    fun `DSLW02 malformed document or result never falls back to an older decoder`(defect: String) {
        val root = w.document()
        when (defect) {
            "unknown_root" -> root["availability"] = "UNKNOWN"
            "missing_semantic" -> root.remove("semantic_version")
            "null_ast" -> root["ast_version"] = null
            "wrong_semantic" -> root["semantic_version"] = SCHEDULE_IR_V2_VERSION
            "wrong_ast" -> root["ast_version"] = "schedule-ast-99"
            "results_object" -> root["results"] = emptyMap<String, String>()
            "extra_result" -> w.firstResult(root)["available_times"] = emptyList<String>()
            "missing_input" -> w.firstResult(root).remove("input_ref")
            "numeric_input" -> w.firstResult(root)["input_ref"] = 101
            else -> w.firstResult(root)["conditions"] = emptyMap<String, String>()
        }
        val failure = assertFailsWith<RuntimeException> { parse(w.encode(root)) }
        assertFalse(failure.toString().contains(f.ir.FIRST_TEXT))
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate_key", "trailing_object", "trailing_scalar", "truncated"])
    fun `DSLW03 duplicate JSON keys or trailing content cannot be silently accepted`(defect: String) {
        val json = w.json()
        val corrupt =
            when (defect) {
                "duplicate_key" -> json.replaceFirst("\"ast_version\":", "\"ast_version\":\"schedule-ast-1\",\"ast_version\":")
                "trailing_object" -> "$json {}"
                "trailing_scalar" -> "$json true"
                else -> json.dropLast(1)
            }
        assertFailsWith<RuntimeException> { parse(corrupt) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["contract", "semantic", "ast"])
    fun `DSLW04 response contract semantic and AST identities are independently selected`(changed: String) {
        val parser = StrictScheduleDslResponseParser(mapper, SCHEDULE_DSL_RESPONSE_CONTRACT)
        assertEquals(
            f.envelope(w.claims, w.program),
            parser.parse(w.json(), f.ir.snapshot(), SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION),
        )
        assertFailsWith<RuntimeException> {
            parser.parse(
                w.json(),
                f.ir.snapshot(),
                if (changed ==
                    "contract"
                ) {
                    "foreign-contract"
                } else {
                    SCHEDULE_DSL_RESPONSE_CONTRACT
                },
                if (changed ==
                    "semantic"
                ) {
                    SCHEDULE_IR_V2_VERSION
                } else {
                    SCHEDULE_DSL_SEMANTIC_VERSION
                },
                if (changed ==
                    "ast"
                ) {
                    "schedule-ast-99"
                } else {
                    SCHEDULE_AST_VERSION
                },
            )
        }
    }

    @Test
    fun `DSLW05 same trusted input and selected identities yield deterministic provider request bytes`() {
        val first =
            contract.requestBytes(
                f.ir.snapshot(),
                SCHEDULE_DSL_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        val second =
            contract.requestBytes(
                f.ir.snapshot(),
                SCHEDULE_DSL_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        assertTrue(first.isNotEmpty())
        assertContentEquals(first, second)
        // Parseable request JSON, without assuming provider-specific field names in this contract.
        assertTrue(mapper.readTree(first).isObject)
    }

    @Test
    fun `DSLW06 response schema is a closed flat node registry with every supported operator`() {
        val schema = mapper.valueToTree<tools.jackson.databind.JsonNode>(contract.responseSchema())
        assertEquals("object", schema.path("type").asString())
        assertEquals(false, schema.path("additionalProperties").asBoolean())
        val rootProperties = schema.path("properties")
        for (name in listOf("semantic_version", "ast_version", "results")) assertTrue(rootProperties.has(name))
        val text = mapper.writeValueAsString(schema)
        for (operator in listOf("CLAIM_REF", "ALL_OF", "ANY_OF", "EXCEPT", "CHOICE", "PREFER")) assertTrue(text.contains("\"$operator\""))
        assertTrue(text.contains("\"children\""))
        assertTrue(text.contains("\"history_condition_ids\""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "null_program",
            "extra_program",
            "missing_root",
            "nodes_object",
            "extra_node",
            "missing_operator",
            "numeric_operator",
            "children_null",
            "numeric_child",
            "revision_float",
            "revision_string",
            "span_null",
        ],
    )
    fun `DSLW07 malformed program wire is isolated while claims and trusted peer remain intact`(defect: String) {
        val root = w.document()
        val program = w.firstProgram(root)
        val node = w.nodes(root).last()
        when (defect) {
            "null_program" -> w.firstResult(root)["program"] = null
            "extra_program" -> program["available_times"] = emptyList<String>()
            "missing_root" -> program.remove("availability_root")
            "nodes_object" -> program["nodes"] = emptyMap<String, String>()
            "extra_node" -> node["assumed_true"] = true
            "missing_operator" -> node.remove("operator")
            "numeric_operator" -> node["operator"] = 1
            "children_null" -> node["children"] = null
            "numeric_child" -> node["children"] = listOf(101, "br")
            else -> {
                @Suppress("UNCHECKED_CAST")
                val evidence = (node.getValue("evidence") as List<MutableMap<String, Any?>>).single()
                when (defect) {
                    "revision_float" -> evidence["revision"] = 3.9
                    "revision_string" -> evidence["revision"] = "3"
                    else -> evidence["start_code_point"] = null
                }
            }
        }
        val actual = parse(w.encode(root))
        assertEquals(f.ir.envelope(w.claims), actual.claims)
        assertEquals(null, actual.programs.first().program)
        assertTrue(ScheduleDslProblem.SHAPE in actual.programs.first().problems)
        assertEquals(f.envelope(w.claims, w.program).programs.last(), actual.programs.last())
        assertEquals(actual, ScheduleDslValidator().validateRestored(actual, f.ir.snapshot()))
        assertFalse(actual.toString().contains(f.ir.FIRST_TEXT))
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "foreign"])
    fun `DSLW08 document input identity completeness is mandatory`(defect: String) {
        val root = w.document()

        @Suppress("UNCHECKED_CAST")
        val results = root.getValue("results") as MutableList<MutableMap<String, Any?>>
        when (defect) {
            "missing" -> results.removeAt(1)
            "duplicate" -> results[1]["input_ref"] = f.ir.first.toString()
            else -> results[1]["input_ref"] = "00000000-0000-0000-0000-000000000999"
        }
        assertFailsWith<RuntimeException> { parse(w.encode(root)) }
    }
}
