package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.ir.IrEffect
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Synthetic contracts for one schema-description comparison; no natural-language accuracy assertion. */
class ScheduleDslRoleGuideContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val contract = ScheduleDslStructuralProviderContract(mapper)

    @Test
    fun `RG01 program description explains recursive root effect and operand roles with compact signatures`() {
        val result = properties(item(properties(contract.responseSchema()).getValue("results")))
        val description = fields(result.getValue("program"))["description"] as String
        listOf(
            "Roots: availability=ALLOW; exclusion=BLOCK; preference=PREFER.",
            "CLAIM_REF: ALLOW={AVAILABLE,UNCONSTRAINED}; BLOCK={UNAVAILABLE,UNRESOLVED}; PREFER={PREFERRED}.",
            "ALL_OF, ANY_OF, CHOICE: operands keep the expected role.",
            "EXCEPT: (R,BLOCK); if R=BLOCK, (BLOCK,ALLOW).",
            "PREFER: allowed only in ALLOW; (ALLOW,PREFER).",
        ).forEach { assertTrue(description.contains(it), "Missing general role signature: $it") }
        assertTrue(description.contains("Every active condition and node must be reachable in its actual role."))
    }

    @Test
    fun `RG02 owned generator preserves public historical contracts native data roles and C4 bytes`() {
        val capsule = N.capsule()
        val c4 = ScheduleDslContextModeProviderContract(mapper)
        val oldBytes = c4.requestBytes(capsule, MeetingMode.EITHER)
        val oldSchema = mapper.writeValueAsBytes(c4.responseSchema())
        val old = mapper.readTree(oldBytes)
        val request = mapper.readTree(contract.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals("c5-owned-proof-choice-2", request.path("producer_schema_revision").asString())
        assertEquals("schedule-dsl-request-5", request.path("request_contract_version").asString())
        assertEquals("schedule-dsl-response-5", request.path("response_contract_version").asString())
        assertEquals("schedule-ir-product-6", request.path("semantic_version").asString())
        assertEquals(old.path("inputs"), request.path("inputs"))
        assertEquals(old.path("context"), request.path("context"))
        assertEquals("server-calendar-facts-1", request.path("calendar_context").path("version").asString())
        assertEquals(1, capsule.questions.size)
        val historical = publicContract("p9-public-contract.json")
        assertEquals(P9_PROMPT_SHA256, sha256(historical.path("prompt").asString()!!.toByteArray(Charsets.UTF_8)))
        val historicalSchema = fields(mapper.readValue(mapper.writeValueAsBytes(historical.path("schema")), Map::class.java))
        val historicalResult = fields(fields(properties(historicalSchema).getValue("results"))["items"])
        fields(properties(historicalResult).getValue("program")).remove("description")
        assertEquals(P9_SCHEMA_EXCEPT_DESCRIPTION_SHA256, sha256(mapper.writeValueAsBytes(canonical(historicalSchema))))
        val owned = publicContract("owned-proof-choice-2-public-contract.json")
        assertEquals(OWNED_PROMPT_SHA256, sha256(owned.path("prompt").asString()!!.toByteArray(Charsets.UTF_8)))
        assertEquals(owned.path("prompt"), request.path("prompt"))
        val table =
            old
                .path("prompt")
                .asString()!!
                .substringAfter("The complete approved vocabulary is ")
                .substringBefore("These are local calendar boundaries,")
                .substringBefore("These are local calendar boundaries.")
                .trim()
        assertEquals(
            table,
            request
                .path("prompt")
                .asString()!!
                .substringAfter("The complete approved vocabulary is ")
                .trim(),
        )

        val schema = contract.responseSchema()
        assertEquals(owned.path("schema"), mapper.readTree(mapper.writeValueAsBytes(schema)))
        assertEquals(OWNED_SCHEMA_SHA256, sha256(mapper.writeValueAsBytes(canonical(schema))))

        fun schemaAlternatives(value: Any?): List<Map<String, Any?>> {
            val objectSchema = fields(value)
            val reference = objectSchema["\$ref"] as? String
            if (reference != null) {
                return schemaAlternatives(fields(schema["\$defs"])[reference.substringAfterLast('/')])
            }
            val choices = objectSchema["anyOf"] as? List<*>
            return choices?.flatMap(::schemaAlternatives) ?: listOf(objectSchema)
        }
        val structure = fields(mapper.readValue(mapper.writeValueAsBytes(schema), Map::class.java))
        val resultSchema = fields(fields(properties(structure).getValue("results"))["items"])
        val conditionContainer = fields(fields(properties(resultSchema).getValue("conditions"))["items"])
        val conditionVariants = schemaAlternatives(conditionContainer)
        val metadataKeys = setOf("effect", "interpretation", "temporal_resolution", "temporal")
        val common = properties(conditionVariants.first()).filterKeys { it !in metadataKeys }
        val outputKeys =
            setOf(
                "id",
                "effect",
                "interpretation",
                "temporal_resolution",
                "proofs",
                "evidence",
                "assertion",
                "supersedes",
                "temporal",
            )
        conditionVariants.forEach { conditionVariant ->
            assertEquals("object", conditionVariant["type"])
            assertEquals(false, conditionVariant["additionalProperties"])
            assertEquals(outputKeys, properties(conditionVariant).keys)
            assertEquals(outputKeys, strings(conditionVariant["required"]))
            assertEquals(common, properties(conditionVariant).filterKeys { it !in metadataKeys })
            assertTrue(conditionVariant["description"] == null || conditionVariant["description"] == conditionContainer["description"])
        }

        fun atomKind(atom: Map<String, Any?>) = strings(fields(properties(atom)["kind"])["enum"]).single()
        val atoms = conditionVariants.flatMap { schemaAlternatives(properties(it).getValue("temporal")) }
        val kinds = listOf("WHOLE_DAY", "APPROVED_TERM", "BOUNDS", "UNKNOWN", "RAW_SCOPE")
        assertEquals(kinds.toSet(), atoms.map(::atomKind).toSet())
        val triples = mutableSetOf<Triple<String, String, String>>()
        conditionVariants.forEach { conditionVariant ->
            val conditionProperties = properties(conditionVariant)
            if (schemaAlternatives(conditionProperties.getValue("temporal")).any { atomKind(it) == "WHOLE_DAY" }) {
                for (effect in strings(fields(conditionProperties["effect"])["enum"])) {
                    for (interpretation in strings(fields(conditionProperties["interpretation"])["enum"])) {
                        for (resolution in strings(fields(conditionProperties["temporal_resolution"])["enum"])) {
                            triples += Triple(effect, interpretation, resolution)
                        }
                    }
                }
            }
        }
        val wholeDayTriples =
            setOf("AVAILABLE", "UNAVAILABLE", "PREFERRED")
                .flatMap { effect ->
                    setOf("EXPLICIT", "INFERRED").map { interpretation -> Triple(effect, interpretation, "NON_PRECISE") }
                }.toSet()
        assertEquals(wholeDayTriples, triples)
        val other =
            conditionVariants.single { conditionVariant ->
                schemaAlternatives(properties(conditionVariant).getValue("temporal")).any { atomKind(it) != "WHOLE_DAY" }
            }
        val otherProperties = properties(other)
        assertEquals(
            setOf("AVAILABLE", "UNAVAILABLE", "PREFERRED", "UNCONSTRAINED", "UNRESOLVED"),
            strings(fields(otherProperties["effect"])["enum"]),
        )
        assertEquals(setOf("EXPLICIT", "INFERRED", "UNRESOLVED"), strings(fields(otherProperties["interpretation"])["enum"]))
        assertEquals(setOf("PRECISE_INTERVAL", "NON_PRECISE"), strings(fields(otherProperties["temporal_resolution"])["enum"]))
        // Historical P9 grammar and current owned grammar have independent frozen public fixtures above.
        // Current native schema/response/reader bytes are never reverse-projected or repaired.
        assertEquals(setOf("semantic_version", "ast_version", "vocabulary_version", "results"), properties(schema).keys)
        assertEquals(false, schema["additionalProperties"])
        val result = properties(item(properties(schema).getValue("results")))
        val conditions = schemaAlternatives(item(result.getValue("conditions")))
        conditions.forEach { conditionSchema ->
            val condition = properties(conditionSchema)
            assertEquals(
                setOf("id", "effect", "interpretation", "temporal_resolution", "proofs", "evidence", "assertion", "supersedes", "temporal"),
                condition.keys,
            )
            val id = fields(condition["id"])
            assertTrue(Regex(id.getValue("pattern") as String).matches("ordinary-id"))
        }
        val program = properties(result.getValue("program"))
        assertEquals(0, fields(program["nodes"])["maxItems"])
        val references =
            listOf("availability_root", "exclusion_root", "preference_root").map { root ->
                val choices = fields(program[root])["anyOf"] as List<*>
                fields(choices.first())["\$ref"] as String
            }
        assertEquals(1, references.toSet().size, "Owned producer keeps one shared expression grammar")
        val expression = fields(fields(schema["\$defs"])[references.first().substringAfterLast('/')])
        val arms = expression["anyOf"] as List<*>
        assertEquals(6, arms.size)
        for (operator in listOf("EXCEPT", "PREFER")) {
            val arm = arms.map(::fields).single { operator in strings(fields(properties(it)["operator"])["enum"]) }
            assertEquals(setOf("operator", "claim_id", "children", "interpretation", "assertion", "evidence"), properties(arm).keys)
            assertEquals(2, fields(properties(arm)["children"])["minItems"])
            assertEquals(2, fields(properties(arm)["children"])["maxItems"])
            assertFalse("base" in properties(arm))
        }
        val choice = arms.map(::fields).single { "CHOICE" in strings(fields(properties(it)["operator"])["enum"]) }
        assertEquals(setOf("operator", "choice_group", "branches", "interpretation", "assertion", "evidence"), properties(choice).keys)
        val branch = fields(fields(properties(choice)["branches"])["items"])
        assertEquals(setOf("label", "condition_ids", "expression"), properties(branch).keys)
        assertEquals(false, branch["additionalProperties"])
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(schema)), request.path("schema"))
        assertContentEquals(oldBytes, c4.requestBytes(capsule, MeetingMode.EITHER))
        assertContentEquals(oldSchema, mapper.writeValueAsBytes(c4.responseSchema()))
    }

    @Test
    fun `RG03 negative crossroot references stay ROLE withheld with every claim reachable and peers intact`() {
        for (effect in listOf("UNAVAILABLE", "UNRESOLVED")) {
            val fixture = DateScopeInlineFixture.numeric(true)
            val blocked = fixture.conditions().single { it["id"] == "block" }
            blocked["effect"] = effect
            if (effect == "UNRESOLVED") {
                blocked["interpretation"] = "UNRESOLVED"
                blocked["temporal_resolution"] = "NON_PRECISE"
                blocked["scope"] = null
            }
            program(fixture)["availability_root"] = expression(fixture, "ANY_OF", listOf(ref(fixture, "allow"), ref(fixture, "block")))
            val raw = F.json(fixture.document)
            val envelope = fixture.parse()
            assertEquals(2, fixture.firstClaims(envelope).size)
            assertEquals(IrEffect.valueOf(effect), fixture.firstClaims(envelope).single { it.id == "block" }.effect)
            assertNull(fixture.firstProgram(envelope).program)
            assertTrue(ScheduleDslProblem.ROLE in fixture.firstProgram(envelope).problems)
            assertTrue(fixture.available(envelope).isEmpty())
            assertTrue(
                envelope.base.base.declaration.claims.results
                    .single { it.submissionVersionId == F.ir.first }
                    .quarantined
                    .isEmpty(),
            )
            assertNotNull(
                envelope.base.base.declaration.programs
                    .single { it.submissionVersionId == F.ir.second }
                    .program,
            )
            assertEquals(raw, F.json(fixture.document))
        }
    }

    @Test
    fun `RG04 EXCEPT retains contextual roles and PREFER is accepted only under ALLOW`() {
        val ordinary = DateScopeInlineFixture.numeric(true)
        val original = ordinary.parse()
        assertNotNull(ordinary.firstProgram(original).program)
        assertEquals(listOf(window("14:00", "16:00"), window("17:00", "20:00")), ordinary.available(original))

        val block = enriched()
        block.conditions().add(claim(block, "exception", "AVAILABLE", "16:30", "16:45"))
        program(block)["availability_root"] = ref(block, "allow")
        program(block)["exclusion_root"] = expression(block, "EXCEPT", listOf(ref(block, "block"), ref(block, "exception")))
        val refined = block.parse()
        assertNotNull(block.firstProgram(refined).program)
        assertEquals(
            listOf(window("14:00", "16:00"), window("16:30", "16:45"), window("17:00", "20:00")),
            block.available(refined),
        )

        val preference = enriched()
        preference.conditions().add(claim(preference, "preferred", "PREFERRED", "15:00", "19:00"))
        program(preference)["availability_root"] =
            expression(preference, "PREFER", listOf(ref(preference, "allow"), ref(preference, "preferred")))
        var envelope = preference.parse()
        assertNotNull(preference.firstProgram(envelope).program)
        assertEquals(listOf(window("14:00", "16:00"), window("17:00", "20:00")), preference.available(envelope))
        program(preference)["availability_root"] = ref(preference, "allow")
        program(preference)["preference_root"] =
            expression(preference, "EXCEPT", listOf(ref(preference, "preferred"), ref(preference, "block")))
        envelope = preference.parse()
        assertNotNull(preference.firstProgram(envelope).program, "PREFER EXCEPT preserves PREFER then BLOCK")
        for (root in listOf("exclusion_root", "preference_root")) {
            program(preference)["preference_root"] = null
            program(preference)["exclusion_root"] = ref(preference, "block")
            val first = if (root == "exclusion_root") "block" else "preferred"
            val second = if (root == "exclusion_root") "preferred" else "allow"
            program(preference)[root] = expression(preference, "PREFER", listOf(ref(preference, first), ref(preference, second)))
            val rejected = preference.parse()
            assertNull(preference.firstProgram(rejected).program, root)
            assertTrue(ScheduleDslProblem.ROLE in preference.firstProgram(rejected).problems, root)
            assertTrue(preference.available(rejected).isEmpty(), root)
        }
    }

    private fun enriched(): DateScopeInlineFixture.Fixture {
        val original = DateScopeInlineFixture.numeric(true)
        val suffix = " 15:00부터 19:00까지 선호하고 불가 구간 중 16:30부터 16:45까지는 가능해요."
        return original.copy(
            input =
                original.input.copy(
                    inputs =
                        original.input.inputs.mapIndexed { index, value ->
                            if (index == 0) value.copy(rawText = value.rawText + suffix) else value
                        },
                ),
        )
    }

    private fun claim(
        fixture: DateScopeInlineFixture.Fixture,
        id: String,
        effect: String,
        start: String,
        end: String,
    ) = LinkedHashMap(fixture.conditions().first()).apply {
        this["id"] = id
        this["effect"] = effect
        this["scope"] = linkedMapOf("dimension" to "TIME", "start" to "2027-01-08T$start", "end" to "2027-01-08T$end")
        this["evidence"] = evidence(fixture)
    }

    private fun expression(
        fixture: DateScopeInlineFixture.Fixture,
        operator: String,
        children: List<Map<String, Any?>>,
    ) = linkedMapOf<String, Any?>(
        "operator" to operator,
        "claim_id" to null,
        "children" to children,
        "choice_group" to null,
        "branch_labels" to emptyList<String>(),
        "interpretation" to "EXPLICIT",
        "assertion" to "ASSERTED",
        "evidence" to evidence(fixture),
    )

    private fun ref(
        fixture: DateScopeInlineFixture.Fixture,
        id: String,
    ) = expression(fixture, "CLAIM_REF", emptyList()).apply { this["claim_id"] = id }

    private fun evidence(fixture: DateScopeInlineFixture.Fixture) =
        listOf(
            mapOf(
                "source_id" to "original",
                "revision" to 3,
                "quote" to
                    fixture.input.inputs
                        .first()
                        .rawText,
            ),
        )

    private fun program(fixture: DateScopeInlineFixture.Fixture) = fields(fixture.first()["program"])

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = value as MutableMap<String, Any?>

    private fun properties(value: Any?) = fields(fields(value)["properties"])

    private fun item(value: Any?) = fields(value)["items"]

    private fun strings(value: Any?) = (value as? List<*>)?.map { it.toString() }?.toSet().orEmpty()

    private fun publicContract(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/contracts/c5/$name")).use { mapper.readTree(it.readBytes()) }

    private fun canonical(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to canonical(it.value) }.toSortedMap()
            is List<*> -> value.map(::canonical)
            else -> value
        }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun window(
        start: String,
        end: String,
    ) = DateScopeInlineFixture.window("2027-01-08T$start", "2027-01-08T$end")

    companion object {
        // Independently frozen public contracts; fixtures contain no participant inputs or answers.
        private const val OWNED_PROMPT_SHA256 = "2e8b647ef9bd92d48b1b256159ddd406e42a24e995c54b6f7f08ec3bf206f047"
        private const val OWNED_SCHEMA_SHA256 = "35e885a6e05da9ac502f663c202a7c1d540112b2f3cdb3e71f6dc860ffbec0f1"
        private const val P9_PROMPT_SHA256 = "760aa7496e28ed98b2573ff391a2226d91e3d27eb9dcaec2447442202f5c8eeb"
        private const val P9_SCHEMA_EXCEPT_DESCRIPTION_SHA256 = "bea732841b2d5f1f7d934472ad896b96872d08d2061f0669f866ea3d536adc9b"
    }
}
