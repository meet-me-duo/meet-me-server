package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.ir.IrEffect
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic schema contracts, not general JSON Schema validation or live-provider acceptance. */
class GeminiResponseSchemaProjectionContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `GP01 a required positive recursive array gains only its zero-length guard`() {
        val source =
            root(
                mapOf(
                    "Node" to
                        obj(
                            mapOf(
                                "children" to array(ref("Node"), 2, 32),
                                "evidence" to array(mapOf("type" to "string", "enum" to listOf("owned-proof")), 1, 32),
                            ),
                        ),
                ),
            )
        val expected = copy(source)
        properties(definition(expected, "Node"))["children"] = array(ref("Node"), 0, 32)
        assertEquals(expected, project(source))
    }

    @Test
    fun `GP02 fixed zero recursive items become null while nonrecursive empty arrays stay exact`() {
        val source =
            root(
                mapOf(
                    "Node" to obj(mapOf("children" to array(ref("Node"), 0, 0))),
                    "Leaf" to obj(mapOf("label" to string())),
                ),
                mapOf("node" to ref("Node"), "empty_leaf" to array(ref("Leaf"), 0, 0)),
            )
        val expected = copy(source)
        fields(properties(definition(expected, "Node"))["children"])["items"] = mapOf("type" to "null")
        assertEquals(expected, project(source))
        assertEquals(ref("Leaf"), fields(properties(expected)["empty_leaf"])["items"])
    }

    @Test
    fun `GP03 recursive choice branches and mutual definitions preserve every other constraint`() {
        val branch =
            obj(
                mapOf(
                    "label" to string(),
                    "condition_ids" to array(string(), 1, 32),
                    "expression" to ref("Node"),
                ),
            )
        val choice =
            root(
                mapOf(
                    "Node" to
                        obj(
                            mapOf(
                                "operator" to mapOf("type" to "string", "enum" to listOf("CHOICE")),
                                "branches" to array(branch, 2, 8),
                            ),
                        ),
                ),
            )
        val expectedChoice = copy(choice)
        fields(properties(definition(expectedChoice, "Node"))["branches"])["minItems"] = 0
        assertEquals(expectedChoice, project(choice))

        val mutual =
            root(
                mapOf(
                    "Node" to obj(mapOf("children" to array(ref("Other"), 1, 8))),
                    "Other" to obj(mapOf("back" to ref("Node"), "unrelated" to array(string(), 3, 9))),
                ),
            )
        val expectedMutual = copy(mutual)
        fields(properties(definition(expectedMutual, "Node"))["children"])["minItems"] = 0
        assertEquals(expectedMutual, project(mutual))
    }

    @Test
    fun `GP04 optional nullable and already-zero guards prevent unnecessary relaxation`() {
        val optional =
            root(
                mapOf(
                    "Node" to
                        obj(
                            mapOf("children" to array(ref("Node"), 2, 32)),
                            required = emptyList(),
                        ),
                ),
            )
        val nullable =
            root(
                mapOf(
                    "Node" to
                        obj(
                            mapOf(
                                "next" to mapOf("anyOf" to listOf(mapOf("type" to "null"), array(ref("Node"), 2, 32))),
                            ),
                        ),
                ),
            )
        val zero = root(mapOf("Node" to obj(mapOf("children" to array(ref("Node"), 0, 32)))))
        for (source in listOf(optional, nullable, zero)) {
            val before = mapper.writeValueAsBytes(source)
            assertContentEquals(before, mapper.writeValueAsBytes(project(source)))
            assertContentEquals(before, mapper.writeValueAsBytes(source))
        }
    }

    @Test
    fun `GP05 unguarded required object cycles are rejected without making fields optional or nullable`() {
        val direct = root(mapOf("Node" to obj(mapOf("next" to ref("Node")))))
        val mutual =
            root(
                mapOf(
                    "Node" to obj(mapOf("next" to ref("Other"))),
                    "Other" to obj(mapOf("back" to ref("Node"))),
                ),
            )
        for (source in listOf(direct, mutual)) {
            val before = mapper.writeValueAsBytes(source)
            assertFailsWith<IllegalArgumentException> { project(source) }
            assertContentEquals(before, mapper.writeValueAsBytes(source))
        }
    }

    @Test
    fun `GP06 acyclic definitions and C2 through C4 schemas retain exact bytes`() {
        val acyclic =
            root(
                mapOf(
                    "Node" to obj(mapOf("children" to array(ref("Leaf"), 2, 32))),
                    "Leaf" to obj(mapOf("value" to mapOf("type" to "string", "enum" to listOf("A", "B")))),
                ),
            )
        val sources =
            listOf(
                acyclic,
                ScheduleDslProviderContract(mapper).responseSchema(),
                ScheduleDslApprovedTermsProviderContract(mapper).responseSchema(),
                ScheduleDslContextModeProviderContract(mapper).responseSchema(),
            )
        for (source in sources) {
            val before = mapper.writeValueAsBytes(source)
            val projected = project(source)
            assertContentEquals(before, mapper.writeValueAsBytes(projected))
            assertContentEquals(before, mapper.writeValueAsBytes(source))
        }
    }

    @Test
    fun `GP07 C5v2 projection changes only necessary recursive facets and never the full neutral schema`() {
        val contract = ScheduleDslStructuralProviderContract(mapper)
        val capsule = N.capsule()
        val beforeRequest = contract.requestBytes(capsule, MeetingMode.EITHER)
        val schema = contract.responseSchema()
        val beforeSchema = mapper.writeValueAsBytes(schema)
        val expected = copy(schema)
        val expression = definition(expected, "c5_inline_expression")
        val arms = expression.getValue("anyOf") as List<*>
        val changed = mutableSetOf<String>()
        for (raw in arms) {
            val arm = fields(raw)
            val props = properties(arm)
            val operator = (fields(props["operator"])["enum"] as List<*>).single() as String
            if (operator == "CHOICE") {
                fields(props["branches"])["minItems"] = 0
                changed += "CHOICE.branches.minItems"
            } else if (operator == "CLAIM_REF") {
                fields(props["children"])["items"] = mapOf("type" to "null")
                changed += "CLAIM_REF.children.items"
            } else {
                assertTrue(operator in setOf("ALL_OF", "ANY_OF", "EXCEPT", "PREFER"))
                fields(props["children"])["minItems"] = 0
                changed += "$operator.children.minItems"
            }
        }
        assertEquals(6, changed.size)
        val projected = project(schema)
        assertEquals(expected, projected, "Proofs, enums, roles, max arity and whole-day metadata must be identical")
        assertEquals(projected, project(projected), "Projection is idempotent")
        assertContentEquals(beforeSchema, mapper.writeValueAsBytes(schema))
        assertContentEquals(beforeRequest, contract.requestBytes(capsule, MeetingMode.EITHER))
        val neutral = fields(mapper.readValue(beforeRequest, Map::class.java))
        assertEquals("c5-owned-proof-choice-2", neutral["producer_schema_revision"])
        assertEquals(schema, neutral["schema"])
        val conditionItems = fields(properties(item(properties(schema)["results"]))["conditions"])["items"]
        val conditionArms = fields(conditionItems)["anyOf"] as List<*>
        val whole =
            conditionArms.map(::fields).single { candidate ->
                val temporal = fields(properties(candidate)["temporal"])
                "properties" in temporal && fields(properties(temporal)["kind"])["enum"] == listOf("WHOLE_DAY")
            }
        val wholeFields = properties(whole)
        assertEquals(listOf("AVAILABLE", "UNAVAILABLE", "PREFERRED"), fields(wholeFields["effect"])["enum"])
        assertEquals(listOf("EXPLICIT", "INFERRED"), fields(wholeFields["interpretation"])["enum"])
        assertEquals(listOf("NON_PRECISE"), fields(wholeFields["temporal_resolution"])["enum"])
    }

    @Test
    fun `GP08 empty recursive operator outputs remain rejected by the unchanged strict server`() {
        for (operator in listOf("ALL_OF", "ANY_OF", "EXCEPT", "PREFER", "CHOICE")) {
            val fixture = DateScopeInlineFixture.numeric(inline = false, subtract = false)
            val program = DateScopeInlineFixture.fields(fixture.first()["program"])
            val nodes = (program["nodes"] as List<*>).map { DateScopeInlineFixture.fields(it) }
            val bad =
                LinkedHashMap(nodes.first()).apply {
                    this["id"] = "empty-operator"
                    this["operator"] = operator
                    this["claim_id"] = null
                    this["children"] = emptyList<String>()
                    this["choice_group"] = if (operator == "CHOICE") "g" else null
                    this["branch_labels"] = emptyList<String>()
                }
            program["nodes"] = nodes + bad
            program["availability_root"] = "empty-operator"
            val before = F.json(fixture.document)
            val envelope = fixture.parse()
            val rejected = fixture.firstProgram(envelope)
            assertNull(rejected.program)
            assertTrue(ScheduleDslProblem.SHAPE in rejected.problems, operator)
            assertEquals(setOf(IrEffect.AVAILABLE, IrEffect.UNAVAILABLE), fixture.firstClaims(envelope).map { it.effect }.toSet())
            assertTrue(fixture.available(envelope).isEmpty())
            assertNotNull(
                envelope.base.base.declaration.programs
                    .single { it.submissionVersionId == F.ir.second }
                    .program,
            )
            assertEquals(before, F.json(fixture.document))
        }
    }

    @Test
    fun `GP09 a link into a separate recursive component keeps its positive minimum`() {
        val source =
            root(
                mapOf(
                    "Node" to
                        obj(
                            mapOf(
                                "self_children" to array(ref("Node"), 2, 32),
                                "links" to array(ref("Other"), 3, 9),
                            ),
                        ),
                    "Other" to obj(mapOf("self_children" to array(ref("Other"), 2, 32))),
                ),
            )
        val before = mapper.writeValueAsBytes(source)
        val expected = copy(source)
        fields(properties(definition(expected, "Node"))["self_children"])["minItems"] = 0
        fields(properties(definition(expected, "Other"))["self_children"])["minItems"] = 0
        val projected = project(source)
        assertEquals(expected, projected, "An outgoing link is not part of its owner's recursive component")
        assertEquals(3, fields(properties(definition(projected, "Node"))["links"])["minItems"])
        assertContentEquals(before, mapper.writeValueAsBytes(source))
    }

    private fun project(schema: Map<String, Any?>) = GeminiResponseSchemaProjection.project(schema)

    private fun root(
        definitions: Map<String, Any?>,
        fields: Map<String, Any?> = mapOf("node" to ref("Node")),
    ): Map<String, Any?> = obj(fields) + ("\$defs" to definitions)

    private fun obj(
        properties: Map<String, Any?>,
        required: List<String> = properties.keys.toList(),
    ) = linkedMapOf(
        "type" to "object",
        "properties" to properties,
        "required" to required,
        "additionalProperties" to false,
    )

    private fun array(
        items: Map<String, Any?>,
        minimum: Int,
        maximum: Int,
    ) = linkedMapOf(
        "type" to "array",
        "items" to items,
        "minItems" to minimum,
        "maxItems" to maximum,
    )

    private fun ref(name: String) = mapOf("\$ref" to "#/\$defs/$name")

    private fun string() = mapOf("type" to "string", "pattern" to "^[A-Za-z0-9._:-]{1,64}$")

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

    private fun properties(schema: Map<String, Any?>) = fields(schema["properties"])

    private fun item(schema: Any?) = fields(fields(schema)["items"])

    private fun definition(
        schema: Map<String, Any?>,
        name: String,
    ) = fields(fields(schema["\$defs"])[name])

    private fun copy(schema: Map<String, Any?>) = fields(mapper.readValue(mapper.writeValueAsBytes(schema), Map::class.java))
}
