package com.meetme.server.experiment

import com.meetme.server.coordination.adapter.output.integration.C5DirectLocatorProducer
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationSelectedHttpTransport
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationStrictJsonBoundary
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationStructuralProviderContract
import com.meetme.server.coordination.adapter.output.integration.C5SelectedChoiceContextRun
import com.meetme.server.coordination.adapter.output.integration.C5SelectedChoiceContexts
import com.meetme.server.coordination.adapter.output.integration.GeminiResponseSchemaProjection
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslContextModeProviderContract
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslOwnedProofChoices
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckChoice
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckQuestion
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslApprovedTermsRepository.StoredDslDeclaration
import com.meetme.server.submission.adapter.output.persistence.JdbcScheduleDslContextModeRepository.StoredContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.ScheduleDslContextModeNativeFixture
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.mockito.Mockito
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** No application start, admission, reservation, HTTP, database or provider call. */
@EnabledIfSystemProperty(named = "meetme.experiment.c5-candidate", matches = "true")
class C5CandidateRuntimeBindingTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `HR07 actual Luna body keeps the frozen low native schema and removes only duplicate input schema without dispatch`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val bytes = C5EvaluationSelectedHttpTransport(mapper, gate).body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val wire = mapper.readTree(bytes)
        assertEquals(
            setOf("model", "store", "input", "max_output_tokens", "text", "instructions", "reasoning"),
            wire.propertyNames().toSet(),
        )
        assertEquals("gpt-6-luna", wire.path("model").asText())
        assertFalse(wire.path("store").asBoolean())
        assertEquals(32768, wire.path("max_output_tokens").asInt())
        assertEquals(mapper.readTree("{\"effort\":\"low\"}"), wire.path("reasoning"))
        val neutral =
            mapper
                .readValue(
                    C5EvaluationStructuralProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER),
                    Map::class.java,
                ).toMutableMap()
        val instructions = neutral.remove("prompt")
        val schema = neutral.remove("schema")
        assertEquals(instructions, wire.path("instructions").asText())
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(neutral)), mapper.readTree(wire.path("input").asText()))
        val format = wire.path("text").path("format")
        assertEquals(setOf("type", "name", "strict", "schema"), format.propertyNames().toSet())
        assertEquals("json_schema", format.path("type").asText())
        assertEquals("meet_me_schedule_dsl_v5", format.path("name").asText())
        assertEquals(true, format.path("strict").asBoolean())
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(schema)), format.path("schema"))
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `HR10 optional term precision schema changes only the OpenAI output contract without dispatch`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate)
        val explicitBaseline = C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = false)
        val candidate = C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true)
        val baselineBytes = baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        assertContentEquals(baselineBytes, explicitBaseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        val baselineWire = mapper.readTree(baselineBytes)
        val baselineSchema = baselineWire.path("text").path("format").path("schema")

        fun condition(schema: JsonNode): ObjectNode =
            schema
                .path("properties")
                .path("results")
                .path("items")
                .path("properties")
                .path("conditions")
                .path("items") as ObjectNode

        fun kind(atom: JsonNode): String =
            atom
                .path("properties")
                .path("kind")
                .path("enum")
                .get(0)
                .asText()

        val originalBranches = condition(baselineSchema).path("anyOf").values().toList()
        assertEquals(2, originalBranches.size)
        val wholeDay = originalBranches[0]
        assertEquals("WHOLE_DAY", kind(wholeDay.path("properties").path("temporal")))
        val originalOther = originalBranches[1]
        val originalAtoms =
            originalOther
                .path("properties")
                .path("temporal")
                .path("anyOf")
                .values()
                .toList()
        assertEquals(listOf("APPROVED_TERM", "BOUNDS", "UNKNOWN", "RAW_SCOPE"), originalAtoms.map(::kind))
        val termBranch = mapper.readTree(mapper.writeValueAsBytes(originalOther)) as ObjectNode
        (termBranch.path("properties").path("temporal") as ObjectNode)
            .replace("anyOf", mapper.createArrayNode().add(originalAtoms[0]))
        (termBranch.path("properties").path("temporal_resolution") as ObjectNode)
            .replace("enum", mapper.createArrayNode().add("NON_PRECISE"))
        val remainingBranch = mapper.readTree(mapper.writeValueAsBytes(originalOther)) as ObjectNode
        val remainingAtoms = mapper.createArrayNode()
        originalAtoms.drop(1).forEach { remainingAtoms.add(it) }
        (remainingBranch.path("properties").path("temporal") as ObjectNode).replace("anyOf", remainingAtoms)
        val expectedSchema = mapper.readTree(mapper.writeValueAsBytes(baselineSchema))
        condition(expectedSchema).replace(
            "anyOf",
            mapper
                .createArrayNode()
                .add(wholeDay)
                .add(termBranch)
                .add(remainingBranch),
        )

        val candidateWire = mapper.readTree(candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertEquals(expectedSchema, candidateWire.path("text").path("format").path("schema"))
        assertFalse(baselineSchema == expectedSchema)
        val expectedWire = mapper.readTree(baselineBytes) as ObjectNode
        (expectedWire.path("text").path("format") as ObjectNode).replace("schema", expectedSchema)
        assertEquals(expectedWire, candidateWire, "Only text.format.schema may differ from the frozen baseline body")
        assertFalse(mapper.readTree(candidateWire.path("input").asText()).has("schema"))
        assertContentEquals(baselineBytes, baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertContentEquals(
            baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
            candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `HR11 presend schema-only comparison rejects an additional input or reasoning change`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate).body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val actual =
            C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        C5SchemaOnlyRequestCheck.verify(mapper, baseline, actual)

        val changedInput = mapper.readTree(actual) as ObjectNode
        val neutral = mapper.readTree(changedInput.path("input").asText()) as ObjectNode
        neutral.put("unexpected_input_change", true)
        changedInput.put("input", mapper.writeValueAsString(neutral))
        assertEquals(
            "TERM_SCHEMA_ONLY_REQUEST_MISMATCH",
            assertFailsWith<IllegalArgumentException> {
                C5SchemaOnlyRequestCheck.verify(mapper, baseline, mapper.writeValueAsBytes(changedInput))
            }.message,
        )

        val changedReasoning = mapper.readTree(actual) as ObjectNode
        (changedReasoning.path("reasoning") as ObjectNode).put("effort", "medium")
        assertEquals(
            "TERM_SCHEMA_ONLY_REQUEST_MISMATCH",
            assertFailsWith<IllegalArgumentException> {
                C5SchemaOnlyRequestCheck.verify(mapper, baseline, mapper.writeValueAsBytes(changedReasoning))
            }.message,
        )
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `HR23 finite generation rejects an unknown sibling definition constraint instead of deleting it`() {
        val coherent = C5DirectLocatorProducer.coherentTemporalSchema(C5EvaluationStructuralProviderContract(mapper).responseSchema())
        val changed = mapper.readTree(mapper.writeValueAsBytes(coherent)) as ObjectNode
        (changed.path("\$defs").path("c5_inline_expression") as ObjectNode).replace(
            "allOf",
            mapper.createArrayNode().add(mapper.createObjectNode().put("maxProperties", 0)),
        )
        @Suppress("UNCHECKED_CAST")
        val unknown = mapper.readValue(mapper.writeValueAsBytes(changed), Map::class.java) as Map<String, Any?>
        assertEquals(
            "UNKNOWN_FINITE_EXPRESSION_SCHEMA",
            assertFailsWith<IllegalArgumentException> { C5DirectLocatorProducer.finiteExpressionSchema(unknown) }.message,
        )
    }

    @Test
    fun `HR21 optional finite expressions expose the identical bounded native schema to both providers`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true, providerParityCandidate = true)
        val candidate =
            C5EvaluationSelectedHttpTransport(
                mapper,
                gate,
                schemaCoherenceCandidate = true,
                providerParityCandidate = true,
                commonFiniteExpressionSchemaCandidate = true,
            )
        val oldBytes = baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val actualBytes = candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val originalWire = mapper.readTree(oldBytes)
        val actualWire = mapper.readTree(actualBytes)
        val schema = actualWire.path("text").path("format").path("schema")
        val originalSchema = originalWire.path("text").path("format").path("schema")
        val originalOutside = mapper.readTree(mapper.writeValueAsBytes(originalSchema)) as ObjectNode
        val actualOutside = mapper.readTree(mapper.writeValueAsBytes(schema)) as ObjectNode
        originalOutside.remove("\$defs")
        actualOutside.remove("\$defs")
        assertEquals(originalOutside, actualOutside)
        val definitions = schema.path("\$defs")

        fun name(depth: Int) = if (depth == 1) "c5_inline_expression" else "c5_inline_expression_depth_$depth"
        assertEquals((1..8).map(::name).toSet(), definitions.propertyNames().toSet())
        val arities =
            mapOf(
                "CLAIM_REF" to (0 to 0),
                "ALL_OF" to (2 to 32),
                "ANY_OF" to (2 to 32),
                "EXCEPT" to (2 to 2),
                "PREFER" to (2 to 2),
                "CHOICE" to (2 to 8),
            )
        val originalBranches =
            originalSchema.path("\$defs").path(name(1)).path("anyOf").values().associateBy {
                it
                    .path("properties")
                    .path("operator")
                    .path("enum")
                    .path(0)
                    .asText()
            }
        for (depth in 1..8) {
            assertEquals(setOf("anyOf"), definitions.path(name(depth)).propertyNames().toSet())
            val branches =
                definitions
                    .path(name(depth))
                    .path("anyOf")
                    .values()
                    .toList()
            val operators =
                branches
                    .map {
                        it
                            .path("properties")
                            .path("operator")
                            .path("enum")
                            .path(0)
                            .asText()
                    }.toSet()
            assertEquals(if (depth == 8) setOf("CLAIM_REF") else arities.keys, operators)
            for (branch in branches) {
                val operator =
                    branch
                        .path("properties")
                        .path("operator")
                        .path("enum")
                        .path(0)
                        .asText()
                val collection = if (operator == "CHOICE") "branches" else "children"
                val array = branch.path("properties").path(collection)
                assertEquals(arities.getValue(operator).first, array.path("minItems").asInt())
                assertEquals(arities.getValue(operator).second, array.path("maxItems").asInt())
                val normalized = mapper.readTree(mapper.writeValueAsBytes(branch)) as ObjectNode
                val normalizedArray = normalized.path("properties").path(collection) as ObjectNode
                if (operator == "CLAIM_REF") {
                    assertEquals(mapper.readTree("{\"type\":\"null\"}"), array.path("items"))
                    normalizedArray.replace(
                        "items",
                        originalBranches
                            .getValue(operator)
                            .path("properties")
                            .path(collection)
                            .path("items"),
                    )
                } else {
                    val target =
                        if (operator ==
                            "CHOICE"
                        ) {
                            array.path("items").path("properties").path("expression")
                        } else {
                            array.path("items")
                        }
                    assertEquals(mapper.readTree("{\"\$ref\":\"#/\$defs/${name(depth + 1)}\"}"), target)
                    val normalizedTarget =
                        if (operator == "CHOICE") {
                            normalizedArray.path("items").path("properties").path("expression")
                        } else {
                            normalizedArray.path("items")
                        }
                    (normalizedTarget as ObjectNode).put("\$ref", "#/\$defs/${name(1)}")
                }
                assertEquals(originalBranches.getValue(operator), normalized, "All non-depth expression fields stay unchanged")
            }
        }
        val gemini = mapper.readTree(candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        assertEquals(schema, gemini.path("generationConfig").path("responseJsonSchema"))
        @Suppress("UNCHECKED_CAST")
        val schemaMap = mapper.readValue(mapper.writeValueAsBytes(schema), Map::class.java) as Map<String, Any?>
        assertEquals(schema, mapper.readTree(mapper.writeValueAsBytes(GeminiResponseSchemaProjection.project(schemaMap))))
        assertEquals(
            actualWire.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertEquals(
            actualWire.path("input").asText(),
            gemini
                .path("contents")
                .path(0)
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        val expectedWire = mapper.readTree(oldBytes) as ObjectNode
        (expectedWire.path("text").path("format") as ObjectNode).replace("schema", schema)
        assertEquals(expectedWire, actualWire)
        assertContentEquals(
            oldBytes,
            C5EvaluationSelectedHttpTransport(
                mapper,
                gate,
                schemaCoherenceCandidate = true,
                providerParityCandidate = true,
                commonFiniteExpressionSchemaCandidate = false,
            ).body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        assertContentEquals(
            baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
            C5EvaluationSelectedHttpTransport(
                mapper,
                gate,
                schemaCoherenceCandidate = true,
                providerParityCandidate = true,
                commonFiniteExpressionSchemaCandidate = false,
            ).body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        Mockito.verifyNoInteractions(budget)
        System.getProperty("meetme.free.finite-schema-output")?.let { output ->
            Files
                .newByteChannel(
                    Path.of(output),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                ).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(actualBytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
        }
    }

    @Test
    fun `HR22 existing owned proof kernel retains depth eight and shared visit one hundred twenty eight limits`() {
        // This expansion kernel is not the complete temporal, role, IR or recommendation validator.
        val locator = mapOf("source_id" to "original", "revision" to 1, "quote" to "Synthetic source.")
        val common = mapOf("interpretation" to "EXPLICIT", "assertion" to "ASSERTED", "evidence" to listOf(locator))

        fun leaf(id: String = "c3"): Map<String, Any?> =
            common + mapOf("operator" to "CLAIM_REF", "claim_id" to id, "children" to emptyList<Any>())

        fun normal(
            operator: String,
            children: List<Map<String, Any?>>,
        ): Map<String, Any?> = common + mapOf("operator" to operator, "claim_id" to null, "children" to children)

        fun choice(count: Int = 2): Map<String, Any?> =
            common +
                mapOf(
                    "operator" to "CHOICE",
                    "choice_group" to "g",
                    "branches" to
                        (1..count).map {
                            mapOf("label" to "b$it", "condition_ids" to listOf("c$it"), "expression" to leaf("c$it"))
                        },
                )

        fun chain(
            depth: Int,
            choiceEdge: Boolean,
        ): Map<String, Any?> {
            var root = if (choiceEdge) choice() else leaf()
            repeat(depth - if (choiceEdge) 2 else 1) { root = normal("ALL_OF", listOf(root, leaf())) }
            return root
        }
        val conditions =
            (1..3).map { id ->
                mapOf(
                    "id" to "c$id",
                    "effect" to "UNRESOLVED",
                    "interpretation" to "UNRESOLVED",
                    "temporal_resolution" to "NON_PRECISE",
                    "proofs" to listOf(locator + ("id" to "p:s")),
                    "evidence" to listOf("p:s"),
                    "assertion" to "ASSERTED",
                    "supersedes" to emptyList<String>(),
                    "temporal" to mapOf("kind" to "UNKNOWN"),
                )
            }

        fun program(roots: List<Map<String, Any?>?>): Map<String, Any?> =
            mapOf(
                "nodes" to emptyList<Any>(),
                "availability_root" to roots[0],
                "exclusion_root" to roots[1],
                "preference_root" to roots[2],
                "history_condition_ids" to emptyList<String>(),
            )

        fun decode(vararg roots: Map<String, Any?>?) =
            ScheduleDslOwnedProofChoices.results(
                listOf(
                    mapOf("input_ref" to UUID(0, 1).toString(), "conditions" to conditions, "program" to program(roots.toList())),
                    mapOf(
                        "input_ref" to UUID(0, 2).toString(),
                        "conditions" to emptyList<Any>(),
                        "program" to program(listOf(null, null, null)),
                    ),
                ),
            )
        for (choiceEdge in listOf(false, true)) {
            assertEquals(2, decode(chain(8, choiceEdge), null, null).size)
            assertEquals(
                "Owned expression limit",
                assertFailsWith<IllegalArgumentException> { decode(chain(9, choiceEdge), null, null) }.message,
            )
        }
        for (operator in listOf("ALL_OF", "ANY_OF", "EXCEPT", "PREFER", "CHOICE")) {
            for (count in listOf(0, 1, 2)) {
                val expression = if (operator == "CHOICE") choice(count) else normal(operator, List(count) { leaf() })
                if (count == 2) {
                    assertEquals(2, decode(expression, null, null).size)
                } else {
                    assertFailsWith<IllegalArgumentException> { decode(expression, null, null) }
                }
            }
        }

        fun width(children: Int) = normal("ALL_OF", List(children) { leaf() })
        val sixtyFive = normal("ALL_OF", List(31) { leaf() } + width(32))
        assertEquals(2, decode(sixtyFive, width(32), width(29)).size)
        assertEquals(
            "Owned expression limit",
            assertFailsWith<IllegalArgumentException> { decode(sixtyFive, width(32), width(30)) }.message,
        )
    }

    @Test
    fun `HR20 optional provider parity shares instructions and deduplicated input while retaining explicit schema projection`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true)
        val candidate = C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true, providerParityCandidate = true)
        val baselineOpenAi = baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val actualOpenAi = candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        assertContentEquals(baselineOpenAi, actualOpenAi)
        val openAi = mapper.readTree(actualOpenAi)
        val gemini = mapper.readTree(candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        assertEquals(
            openAi.path("instructions").asText(),
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        val geminiInput =
            gemini
                .path("contents")
                .path(0)
                .path("parts")
                .path(0)
                .path("text")
                .asText()
        assertEquals(openAi.path("input").asText(), geminiInput)
        val commonInput = mapper.readTree(geminiInput)
        assertFalse(commonInput.has("prompt"))
        assertFalse(commonInput.has("schema"))
        @Suppress("UNCHECKED_CAST")
        val schema =
            mapper.readValue(mapper.writeValueAsBytes(openAi.path("text").path("format").path("schema")), Map::class.java)
                as Map<String, Any?>
        assertEquals(
            mapper.readTree(mapper.writeValueAsBytes(GeminiResponseSchemaProjection.project(schema))),
            gemini.path("generationConfig").path("responseJsonSchema"),
            "Projected Gemini schema is checked explicitly; projection is not asserted to preserve every schema constraint",
        )
        assertContentEquals(
            baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
            C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true, providerParityCandidate = false)
                .body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `HR19 pinned schema candidate rejects actual drift and poisoned expected requests outside the schema`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline =
            C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val expected =
            C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        assertFalse(
            mapper
                .readTree(baseline)
                .path("text")
                .path("format")
                .path("schema") ==
                mapper
                    .readTree(expected)
                    .path("text")
                    .path("format")
                    .path("schema"),
        )
        C5SchemaOnlyRequestCheck.verifyPinnedCandidate(mapper, baseline, expected, expected)
        val mutations =
            listOf<(ObjectNode) -> Unit>(
                { wire ->
                    val neutral = mapper.readTree(wire.path("input").asText()) as ObjectNode
                    neutral.put("unexpected_input_change", true)
                    wire.put("input", mapper.writeValueAsString(neutral))
                },
                { wire -> (wire.path("reasoning") as ObjectNode).put("effort", "medium") },
                { wire -> wire.put("instructions", wire.path("instructions").asText() + " Unexpected instruction.") },
            )
        for (mutate in mutations) {
            val changed = mapper.readTree(expected) as ObjectNode
            mutate(changed)
            val bytes = mapper.writeValueAsBytes(changed)
            assertFailsWith<IllegalArgumentException> {
                C5SchemaOnlyRequestCheck.verifyPinnedCandidate(mapper, baseline, expected, bytes)
            }
            assertFailsWith<IllegalArgumentException> {
                C5SchemaOnlyRequestCheck.verifyPinnedCandidate(mapper, baseline, bytes, bytes)
            }
        }
        val changedSchema = mapper.readTree(expected) as ObjectNode
        (changedSchema.path("text").path("format").path("schema") as ObjectNode).put("unexpected_schema_change", true)
        assertFailsWith<IllegalArgumentException> {
            C5SchemaOnlyRequestCheck.verifyPinnedCandidate(mapper, baseline, expected, mapper.writeValueAsBytes(changedSchema))
        }
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `HR12 coherent schema opt-in changes only nine generation branches and preserves the complete RAW contract`() {
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate)
        val oldTerm = C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true)
        val oldTermBytes = oldTerm.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val candidate = C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true)
        val baselineBytes = baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val baselineWire = mapper.readTree(baselineBytes)
        val baselineSchema = baselineWire.path("text").path("format").path("schema")

        fun copy(node: JsonNode): ObjectNode = mapper.readTree(mapper.writeValueAsBytes(node)) as ObjectNode

        fun condition(schema: JsonNode): ObjectNode =
            schema
                .path("properties")
                .path("results")
                .path("items")
                .path("properties")
                .path("conditions")
                .path("items") as ObjectNode

        fun properties(node: JsonNode): ObjectNode = node.path("properties") as ObjectNode

        fun select(
            node: JsonNode,
            type: String,
        ): JsonNode = node.path("anyOf").values().single { it.path("type").asText() == type }

        val baselineBranches = condition(baselineSchema).path("anyOf").values().toList()
        assertEquals(2, baselineBranches.size)
        val originalOther = baselineBranches[1]
        val atoms =
            originalOther.path("properties").path("temporal").path("anyOf").values().associateBy {
                it
                    .path("properties")
                    .path("kind")
                    .path("enum")
                    .get(0)
                    .asText()
            }
        assertEquals(setOf("APPROVED_TERM", "BOUNDS", "UNKNOWN", "RAW_SCOPE"), atoms.keys)
        val effects = listOf("AVAILABLE", "UNAVAILABLE", "PREFERRED", "UNRESOLVED")
        val knownEffects = listOf("AVAILABLE", "UNAVAILABLE", "PREFERRED")
        val interpretations = listOf("EXPLICIT", "INFERRED", "UNRESOLVED")
        val nonPrecise = listOf("NON_PRECISE")

        fun branch(
            atom: JsonNode,
            allowedEffects: List<String>? = null,
            allowedInterpretations: List<String>? = null,
            resolutions: List<String>? = null,
        ): ObjectNode {
            val result = copy(originalOther)
            val fields = properties(result)
            (fields.path("temporal") as ObjectNode).replace("anyOf", mapper.createArrayNode().add(atom))
            val metadata =
                listOf("effect" to allowedEffects, "interpretation" to allowedInterpretations, "temporal_resolution" to resolutions)
            for ((name, values) in metadata) {
                if (values != null) {
                    val allowed = mapper.createArrayNode()
                    values.forEach { allowed.add(it) }
                    (fields.path(name) as ObjectNode).replace("enum", allowed)
                }
            }
            return result
        }

        val pureTerm = copy(atoms.getValue("APPROVED_TERM"))
        val originalModelScope = properties(pureTerm).path("model_scope")
        properties(pureTerm).replace("model_scope", select(originalModelScope, "null"))
        val contextTerm = copy(atoms.getValue("APPROVED_TERM"))
        properties(contextTerm).replace("model_scope", select(originalModelScope, "object"))
        val bounds = atoms.getValue("BOUNDS")
        val lowerObject = select(properties(bounds).path("lower"), "object")
        val upperObject = select(properties(bounds).path("upper"), "object")
        val nullBound = select(properties(bounds).path("lower"), "null")

        fun bounded(
            lower: JsonNode,
            upper: JsonNode,
        ): ObjectNode =
            copy(bounds).apply {
                properties(this).replace("lower", lower)
                properties(this).replace("upper", upper)
            }

        val expectedBranches =
            mapper
                .createArrayNode()
                .add(baselineBranches[0])
                .add(branch(pureTerm, effects, interpretations, nonPrecise))
                .add(branch(contextTerm, effects, listOf("INFERRED"), nonPrecise))
                .add(branch(bounded(lowerObject, nullBound), knownEffects, listOf("EXPLICIT", "INFERRED"), nonPrecise))
                .add(branch(bounded(nullBound, upperObject), knownEffects, listOf("EXPLICIT", "INFERRED"), nonPrecise))
                .add(branch(bounded(lowerObject, upperObject), effects, listOf("EXPLICIT"), listOf("PRECISE_INTERVAL")))
                .add(branch(bounded(lowerObject, upperObject), effects, listOf("INFERRED"), nonPrecise))
                .add(branch(atoms.getValue("UNKNOWN"), effects, interpretations, nonPrecise))
                .add(branch(atoms.getValue("RAW_SCOPE")))
        val expectedSchema = copy(baselineSchema)
        condition(expectedSchema).replace("anyOf", expectedBranches)
        val actualBytes = candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val actualWire = mapper.readTree(actualBytes)
        assertEquals(expectedSchema, actualWire.path("text").path("format").path("schema"))
        val expectedWire = copy(baselineWire)
        (expectedWire.path("text").path("format") as ObjectNode).replace("schema", expectedSchema)
        assertEquals(expectedWire, actualWire, "Only the nine-arm output schema may change")
        assertFalse(mapper.readTree(actualWire.path("input").asText()).has("schema"))
        assertContentEquals(baselineBytes, baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertContentEquals(oldTermBytes, oldTerm.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertContentEquals(
            baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
            candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        assertFailsWith<IllegalArgumentException> {
            C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true, schemaCoherenceCandidate = true)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        }
        Mockito.verifyNoInteractions(budget)
        System.getProperty("meetme.free.coherence-output")?.let { output ->
            Files
                .newByteChannel(
                    Path.of(output),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                ).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(actualBytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
        }
    }

    @Test
    fun `HR13 scoped unresolved instruction opt-in preserves all wire fields and fails closed on clause mismatch`() {
        val oldClause =
            "Unknown personal event or before/after boundaries stay UNKNOWN even on a known date; " +
                "retain their date and event context in complete condition evidence without inventing clocks or whole-day availability."
        val newClause =
            "Unknown personal event or before/after boundaries stay UNKNOWN unless complete source evidence explicitly confines " +
                "a condition with effect=UNRESOLVED to a finite influence range. Only then use RAW_SCOPE with " +
                "interpretation=UNRESOLVED and temporal_resolution=NON_PRECISE; its endpoints bound uncertainty, " +
                "never personal clocks or actual availability. A known date alone does not justify confinement or a change of effect. " +
                "Retain full date and event evidence without inventing missing clocks or whole-day availability."
        val knownAvailable =
            "Known availability with a genuinely unknown restriction stays AVAILABLE/NON_PRECISE with temporal.UNKNOWN, " +
                "rather than losing its effect."
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true)
        val candidate =
            C5EvaluationSelectedHttpTransport(
                mapper,
                gate,
                schemaCoherenceCandidate = true,
                scopedUnresolvedContextCandidate = true,
            )
        val baselineBytes = baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val baselineWire = mapper.readTree(baselineBytes)
        val originalInstructions = baselineWire.path("instructions").asText()
        assertEquals(1, originalInstructions.split(oldClause).size - 1)
        assertTrue(originalInstructions.contains(knownAvailable))
        val expectedInstructions = originalInstructions.replace(oldClause, newClause)
        val expectedWire = mapper.readTree(baselineBytes) as ObjectNode
        expectedWire.put("instructions", expectedInstructions)
        val actualBytes = candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val actual = mapper.readTree(actualBytes)
        assertEquals(expectedWire, actual, "Only the frozen single instruction clause may change")
        assertEquals(313, actualBytes.size - baselineBytes.size)
        assertTrue(actual.path("instructions").asText().contains(knownAvailable))
        assertEquals(expectedInstructions, C5DirectLocatorProducer.scopedUnresolvedInstructions(originalInstructions))
        assertFailsWith<IllegalArgumentException> {
            C5DirectLocatorProducer.scopedUnresolvedInstructions(originalInstructions.replace(oldClause, ""))
        }
        assertFailsWith<IllegalArgumentException> {
            C5DirectLocatorProducer.scopedUnresolvedInstructions("$originalInstructions $oldClause")
        }
        assertContentEquals(
            baselineBytes,
            C5EvaluationSelectedHttpTransport(mapper, gate, schemaCoherenceCandidate = true, scopedUnresolvedContextCandidate = false)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        assertContentEquals(
            C5EvaluationSelectedHttpTransport(mapper, gate).body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
            C5EvaluationSelectedHttpTransport(mapper, gate, scopedUnresolvedContextCandidate = false)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        assertContentEquals(
            C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
            C5EvaluationSelectedHttpTransport(mapper, gate, termPrecisionSchemaCandidate = true, scopedUnresolvedContextCandidate = false)
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        assertContentEquals(
            baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
            candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        val c4 =
            FinalAnalysisRouteChoice.DslContextModeV4(
                IrExecutionIdentity("schedule-dsl-response-4", "schedule-ir-product-5", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(c4), FinalAnalysisRouteTestFixture.invocation()),
        )
        assertContentEquals(
            baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
            candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        Mockito.verifyNoInteractions(budget)
        System.getProperty("meetme.free.unknown-instructions-output")?.let { output ->
            Files
                .newByteChannel(
                    Path.of(output),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                ).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(actualBytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
        }
    }

    @Test
    fun `HR14 selected choice context is receipt bound non evidence and changes only opt in V5 neutral context`() {
        val appendix =
            "selected_option_context is a receipt-bound snapshot of the option actually chosen via choice_id, not an additional " +
                "native evidence source or participant assertion. The base choice_definitions_available flag describes the " +
                "unextended capsule; this extension exposes only the selected definition. Keep the linked answer_source_id text " +
                "unchanged. Never concatenate, relabel or quote option labels, proposed_text or context metadata as native " +
                "evidence. Do not choose a label or proposed_text as correct when they differ or conflict; preserve unsupported " +
                "or conflicting meaning as unresolved. Free-text answers have no selected option context; never infer a choice " +
                "from matching text."
        val originalInput = ScheduleDslContextModeNativeFixture.input()
        val input =
            originalInput.copy(
                inputs =
                    originalInput.inputs.mapIndexed { index, item ->
                        if (index == 0) {
                            item.copy(evidence = assertNotNull(item.evidence).copy(interpretation = InputInterpretation.CONFIRMED))
                        } else {
                            item
                        }
                    },
            )
        val capsule = ScheduleDslContextModeNativeFixture.capsule(input)
        val accepted = ScheduleDslQuestionContextFixture.native(input).first()
        val evidence = assertNotNull(accepted.latest.evidence)
        val answer = assertNotNull(evidence.supplement)
        val capturedAt = answer.answeredAt.minusSeconds(1)
        val selected = PrecheckChoice(assertNotNull(answer.choiceId), "Synthetic conflicting label.", answer.answerText)
        val mutableChoices = mutableListOf(selected)
        val sourceCandidate =
            PrecheckCandidate(
                UUID(0, 9101),
                accepted.roomId.value,
                accepted.participantId.value,
                evidence.originalRevision,
                assertNotNull(accepted.latest.rawText),
                accepted.latest.locale.toLanguageTag(),
                evidence.referenceAt,
                evidence.referenceAt,
                "synthetic-owner",
                evidence = evidence.copy(supplement = null, interpretation = InputInterpretation.PENDING),
            )
        val question = PrecheckQuestion(answer.questionId, answer.prompt, answer.sourceExcerpt, mutableChoices, answer.sourceStartCodePoint)
        val source =
            PrecheckExecution(UUID(0, 9201), sourceCandidate, PrecheckStatus.NEEDS_CHOICE, 1, 1, 0, capturedAt.plusSeconds(30), question)
        val pending =
            assertNotNull(
                C5SelectedChoiceContexts.prepare(
                    source,
                    sourceCandidate.revision,
                    source.version,
                    question.id,
                    selected.id,
                    null,
                    capturedAt,
                ),
            )
        val derived =
            source.copy(
                id = UUID(0, 9202),
                candidate =
                    sourceCandidate.copy(
                        id = UUID(0, 9102),
                        revision = sourceCandidate.revision + 1,
                        createdAt = answer.answeredAt,
                        sourceCandidateId = sourceCandidate.id,
                        sourceQuestionId = question.id,
                        selectedChoiceId = selected.id,
                        acceptedVersionId = accepted.latest.id.value,
                        evidence = evidence.copy(interpretation = InputInterpretation.PENDING),
                    ),
                status = PrecheckStatus.CLEAR,
                question = null,
            )
        mutableChoices[0] = selected.copy(label = "Mutated label.", proposedText = "Mutated answer.")
        val canonical = evidence.canonical()
        val bound = pending.bind(derived, accepted)
        val run = C5SelectedChoiceContextRun(enabled = true)
        assertFailsWith<IllegalArgumentException> { run.resolve(capsule) }
        run.begin(capsule.snapshot.context.roomId)
        assertFailsWith<IllegalArgumentException> { run.resolve(capsule) }
        run.record(bound)
        assertFailsWith<IllegalArgumentException> { run.record(bound) }
        assertFailsWith<IllegalArgumentException> { run.seal(UUID.randomUUID()) }
        run.seal(capsule.snapshot.context.roomId)
        assertNotNull(run.resolve(capsule))
        assertFailsWith<IllegalArgumentException> { run.record(bound) }
        assertFailsWith<IllegalArgumentException> { run.begin(capsule.snapshot.context.roomId) }
        assertFailsWith<IllegalArgumentException> { run.resolve(ScheduleDslContextModeNativeFixture.capsule()) }
        run.clear()
        assertFailsWith<IllegalArgumentException> { run.resolve(capsule) }
        val off = C5SelectedChoiceContextRun(enabled = false)
        assertEquals(null, off.resolve(capsule))
        off.begin(capsule.snapshot.context.roomId)
        off.seal(capsule.snapshot.context.roomId)
        off.clear()
        assertEquals(null, off.resolve(capsule))
        val contexts = C5SelectedChoiceContexts.freeze(capsule, listOf(bound))
        assertFailsWith<IllegalArgumentException> { C5SelectedChoiceContexts.freeze(capsule, listOf(bound, bound)) }
        assertFailsWith<IllegalArgumentException> {
            C5SelectedChoiceContexts.freeze(ScheduleDslContextModeNativeFixture.capsule(), listOf(bound))
        }
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate)
        val candidate = C5EvaluationSelectedHttpTransport(mapper, gate, selectedChoiceContexts = contexts)
        val lateRun = C5SelectedChoiceContextRun(enabled = true)
        val lateCandidate = C5EvaluationSelectedHttpTransport(mapper, gate, selectedChoiceContextResolver = lateRun::resolve)
        assertFailsWith<IllegalArgumentException> { lateCandidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna") }
        lateRun.begin(capsule.snapshot.context.roomId)
        lateRun.record(bound)
        lateRun.seal(capsule.snapshot.context.roomId)
        val baselineBytes = baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        val actualBytes = candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna")
        assertContentEquals(actualBytes, lateCandidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertContentEquals(actualBytes, lateCandidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertContentEquals(
            candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
            lateCandidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        lateRun.clear()
        assertFailsWith<IllegalArgumentException> { lateCandidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna") }
        val baselineWire = mapper.readTree(baselineBytes)
        val actualWire = mapper.readTree(actualBytes)
        val baselineData = mapper.readTree(baselineWire.path("input").asText())
        val actualData = mapper.readTree(actualWire.path("input").asText())

        fun matchingQuestion(data: JsonNode): ObjectNode =
            data
                .path("inputs")
                .values()
                .single {
                    it.path("input_ref").asText() ==
                        accepted.latest.id.value
                            .toString()
                }.path("clarification_contexts")
                .values()
                .single { it.path("question_id").asText() == question.id.toString() } as ObjectNode
        val actualQuestion = matchingQuestion(actualData)
        val block = actualQuestion.path("selected_option_context")
        assertEquals(
            setOf(
                "context_contract",
                "authority",
                "selection_path",
                "selection",
                "question_snapshot_sha256",
                "accepted_input_fingerprint",
                "captured_at",
                "selected_definition_available",
            ),
            block.propertyNames().toSet(),
        )
        assertEquals("selected-choice-context-1", block.path("context_contract").asText())
        assertEquals("CONTEXT_ONLY_NOT_EVIDENCE", block.path("authority").asText())
        assertEquals("choice_id", block.path("selection_path").asText())
        assertEquals(true, block.path("selected_definition_available").asBoolean())
        assertEquals(false, actualQuestion.path("choice_definitions_available").asBoolean())
        assertEquals(setOf("id", "label", "proposed_text"), block.path("selection").propertyNames().toSet())
        assertEquals(selected.id, block.path("selection").path("id").asText())
        assertEquals(selected.label, block.path("selection").path("label").asText())
        assertEquals(selected.proposedText, block.path("selection").path("proposed_text").asText())
        assertTrue(block.path("question_snapshot_sha256").asText().matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            capsule.snapshot.context.inputs
                .first { it.submissionVersionId == accepted.latest.id.value }
                .contentSha256,
            block.path("accepted_input_fingerprint").asText(),
        )
        assertEquals(capturedAt.toString(), block.path("captured_at").asText())
        assertEquals(
            block,
            mapper.readTree(mapper.writeValueAsBytes(block)),
            "Local context JSON readback is lossless, not production storage",
        )
        val expectedData = mapper.readTree(mapper.writeValueAsBytes(baselineData))
        matchingQuestion(expectedData).replace("selected_option_context", block)
        assertEquals(expectedData, actualData, "No native source, question linkage, owner, revision or answer may change")
        val expectedWire = mapper.readTree(baselineBytes) as ObjectNode
        expectedWire.put("input", mapper.writeValueAsString(expectedData))
        expectedWire.put("instructions", baselineWire.path("instructions").asText() + "\n\n" + appendix)
        assertEquals(expectedWire, actualWire, "Only selected non-evidence context and its frozen instructions may change")
        assertEquals(canonical, evidence.canonical())
        assertContentEquals(baselineBytes, baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        assertContentEquals(
            baselineBytes,
            C5EvaluationSelectedHttpTransport(mapper, gate, selectedChoiceContexts = C5SelectedChoiceContexts.freeze(capsule, emptyList()))
                .body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        val baselineGeminiBytes = baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash")
        val baselineGemini = mapper.readTree(baselineGeminiBytes)
        val expectedGemini = mapper.readTree(baselineGeminiBytes) as ObjectNode
        val neutralGemini =
            mapper.readTree(
                baselineGemini
                    .path("contents")
                    .get(0)
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText(),
            )
        val expectedNeutralGemini = mapper.readTree(mapper.writeValueAsBytes(neutralGemini)) as ObjectNode
        matchingQuestion(expectedNeutralGemini).replace("selected_option_context", block)
        expectedNeutralGemini.put("prompt", neutralGemini.path("prompt").asText() + "\n\n" + appendix)
        (
            expectedGemini
                .path("contents")
                .get(0)
                .path("parts")
                .get(0) as ObjectNode
        ).put("text", mapper.writeValueAsString(expectedNeutralGemini))
        val actualGemini = mapper.readTree(candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        assertEquals(expectedGemini, actualGemini, "Gemini may change only the same neutral selection context and appendix")
        assertEquals(baselineGemini.path("generationConfig"), actualGemini.path("generationConfig"))
        assertContentEquals(
            baselineGeminiBytes,
            C5EvaluationSelectedHttpTransport(mapper, gate, selectedChoiceContexts = C5SelectedChoiceContexts.freeze(capsule, emptyList()))
                .body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"),
        )
        val c4 =
            FinalAnalysisRouteChoice.DslContextModeV4(
                IrExecutionIdentity("schedule-dsl-response-4", "schedule-ir-product-5", ScheduleDslContextModeNativeFixture.policy.version),
                ScheduleDslContextModeNativeFixture.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(c4), FinalAnalysisRouteTestFixture.invocation()),
        )
        assertContentEquals(
            baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
            candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `HR15 selected context receipts can only be constructed from a private prospective capture token`() {
        val pending = C5SelectedChoiceContexts.Pending::class.java
        val bound = C5SelectedChoiceContexts.Bound::class.java
        for (type in listOf(pending, bound)) {
            val constructors = type.declaredConstructors.filterNot { it.isSynthetic }
            assertTrue(constructors.isNotEmpty())
            assertTrue(constructors.all { Modifier.isPrivate(it.modifiers) }, "Capture and receipt constructors must be private")
        }
        val factories =
            C5SelectedChoiceContexts.Bound.Companion.javaClass.declaredMethods.filter {
                !it.isSynthetic && it.returnType == bound
            }
        assertTrue(factories.isNotEmpty(), "The receipt factory must explicitly consume a captured token")
        for (factory in factories) {
            assertEquals(pending, factory.parameterTypes.firstOrNull(), "A receipt factory may not accept uncaptured raw source metadata")
        }
    }

    @Test
    fun `HR07 frozen candidate classes and strict reader are bound on the explicit test classpath`() {
        assertEquals(4, C5EvaluationCandidateProfile.verify().size)
        val parser = C5EvaluationCandidateProfile.parser(JsonMapper.builder().findAndAddModules().build())
        assertEquals("C5EvaluationStrictJsonBoundary", parser.javaClass.simpleName)
        assertEquals("schedule-dsl-response-5", parser.supportedResponseContractVersion)
    }

    @Test
    fun `HR07 candidate native schema and prompt equal the frozen P36 contract and preserve native inputs`() {
        val reference =
            mapper.readTree(
                requireNotNull(
                    javaClass.getResourceAsStream("/contracts/c5/frozen-public-producer-baseline.json"),
                ).use { it.readAllBytes() },
            )
        val capsule = ScheduleDslContextModeNativeFixture.capsule()
        val old = ScheduleDslContextModeProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER)
        val oldRoot = mapper.readTree(old)
        val candidate = C5EvaluationStructuralProviderContract(mapper)
        val request = mapper.readTree(candidate.requestBytes(capsule, MeetingMode.EITHER))
        assertEquals(reference.path("schema"), mapper.readTree(mapper.writeValueAsBytes(candidate.responseSchema())))
        assertEquals(reference.path("prompt"), request.path("prompt"))
        assertEquals("c5-direct-locator-prototype-1", request.path("producer_schema_revision").asText())
        assertEquals(oldRoot.path("inputs"), request.path("inputs"))
        assertEquals(oldRoot.path("context"), request.path("context"))
        assertEquals(oldRoot.path("questions"), request.path("questions"))
        assertEquals(
            mapper.readTree(old),
            mapper.readTree(
                ScheduleDslContextModeProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER),
            ),
        )
    }

    @Test
    fun `HR07 strict raw boundary continues rejecting duplicate trailing unknown oversized and invalid unicode JSON`() {
        val parser = C5EvaluationCandidateProfile.parser(mapper)
        val snapshot = ScheduleDslContextModeNativeFixture.snapshot()
        listOf(
            "{\"semantic_version\":\"x\",\"semantic_version\":\"x\"}",
            "{} {}",
            "{\"results\":[],\"unexpected\":1}",
            " ".repeat(262145),
            "\"\uD800\"",
        ).forEach { raw ->
            val failure =
                assertFailsWith<NaturalLanguageParserException> {
                    parser.parse(raw, snapshot, "schedule-dsl-response-5", "schedule-ir-product-6", "schedule-ast-1", "ko-time-terms-1")
                }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
        }
    }

    @Test
    fun `HR06 free origin checks and caller supplied booleans cannot arm a live execution`() {
        val failure = assertFailsWith<IllegalStateException> { C5EvaluationCandidateProfile.requireLiveBindings() }
        assertFalse(failure.message.isNullOrBlank())
    }

    @Test
    @EnabledIfSystemProperty(named = "meetme.free.actual-c5-replay", matches = "true")
    fun `HR09 private saved native parser replay preserves both successful and quarantined stored envelopes`() {
        // Runtime paths only; no private text, quote, provider output or failure message enters diagnostics.
        try {
            fun path(suffix: String): Path = Path.of(requireNotNull(System.getProperty("meetme.free.actual-c5-replay.$suffix")))

            fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

            fun nullableText(node: JsonNode): String? = if (node.isNull) null else node.asText()

            fun nullableInt(node: JsonNode): Int? {
                if (node.isNull) return null
                check(node.isIntegralNumber && node.canConvertToInt())
                return node.intValue()
            }

            data class Replay(
                val label: String,
                val request: Path,
                val response: Path,
                val database: Path,
                val expectedKept: Int,
                val expectedQuarantined: Int,
                val expectedProblemPrograms: Int,
            )

            val p36 = path("p36Dir").resolve("direct")
            val current = path("currentDir")
            val call = "call-b6c94886-53c6-498f-bf0f-7db70ab734af"
            val cases =
                listOf(
                    Replay(
                        "P36",
                        p36.resolve("final-OPENAI-1-request.json"),
                        p36.resolve("final-OPENAI-1-model-output.json"),
                        p36.resolve("schedule_ir_envelopes-db.json"),
                        13,
                        0,
                        0,
                    ),
                    Replay(
                        "CURRENT",
                        current.resolve("$call-request.json"),
                        current.resolve("$call-model-output.json"),
                        current.resolve("results.json"),
                        9,
                        4,
                        2,
                    ),
                )
            val parser = C5EvaluationStrictJsonBoundary(mapper)
            val reports = mutableListOf<Map<String, Any>>()
            for (case in cases) {
                val requestBytes = Files.readAllBytes(case.request)
                val responseBytes = Files.readAllBytes(case.response)
                val databaseBytes = Files.readAllBytes(case.database)
                val wire = mapper.readTree(requestBytes)
                val neutral = mapper.readTree(wire.path("input").asText())
                val database = mapper.readTree(databaseBytes)
                val records =
                    if (case.label == "P36") database else database.path("database").path("schedule_ir_envelopes")
                check(records.isArray && records.size() == 1)
                val encoded = records.get(0).path("envelope")
                val stored = if (encoded.isString) mapper.readTree(encoded.asText()) else encoded
                check(stored.isObject)
                val context = mapper.treeToValue(stored.path("context"), ScheduleIrContext::class.java)
                check(context.inputs.size == 4)
                val requestContext = neutral.path("context")
                check(requestContext.path("time_zone").asText() == context.timeZone)
                check(requestContext.path("search_start").asText() == context.searchStart.toString())
                check(requestContext.path("search_end").asText() == context.searchEnd.toString())
                check(requestContext.path("policy_version").asText() == context.policyVersion)
                val requestInputs = neutral.path("inputs")
                check(requestInputs.isArray && requestInputs.size() == context.inputs.size)
                val byInput = requestInputs.values().associateBy { UUID.fromString(it.path("input_ref").asText()) }
                check(byInput.keys == context.inputs.map { it.submissionVersionId }.toSet())
                val texts =
                    context.inputs.associate { input ->
                        val requested = byInput.getValue(input.submissionVersionId)
                        check(nullableInt(requested.path("submission_revision")) == input.submissionRevision)
                        check(nullableInt(requested.path("original_revision")) == input.originalRevision)
                        val sources = requested.path("sources")
                        check(sources.isArray && sources.size() == input.sources.size)
                        val bySource = sources.values().associateBy { it.path("source_id").asText() }
                        check(bySource.keys == input.sources.map { it.sourceId }.toSet())
                        input.submissionVersionId to
                            input.sources.associate { binding ->
                                val source = bySource.getValue(binding.sourceId)
                                val text = source.path("text")
                                check(text.isString)
                                val raw = text.asText()
                                check(source.path("kind").asText() == binding.kind.name)
                                check(nullableInt(source.path("revision")) == binding.revision)
                                check(nullableInt(source.path("code_point_length")) == binding.codePointLength)
                                check(raw.codePointCount(0, raw.length) == binding.codePointLength)
                                check(sha(raw.toByteArray(Charsets.UTF_8)) == binding.contentSha256)
                                check(source.path("reference_at").asText() == binding.referenceAt.toString())
                                check(nullableText(source.path("question_id")) == binding.questionId?.toString())
                                check(nullableInt(source.path("original_start_code_point")) == binding.originalStartCodePoint)
                                check(nullableInt(source.path("original_end_code_point")) == binding.originalEndCodePoint)
                                binding.sourceId to raw
                            }
                    }
                val snapshot = ScheduleEvidenceSnapshot(context, texts)
                val parsed =
                    parser.parse(
                        responseBytes.toString(Charsets.UTF_8),
                        snapshot,
                        "schedule-dsl-response-5",
                        "schedule-ir-product-6",
                        "schedule-ast-1",
                        "ko-time-terms-1",
                    )
                val reparsed = mapper.readTree(mapper.writeValueAsBytes(parsed)) as ObjectNode
                reparsed.replace("base", mapper.readTree(mapper.writeValueAsBytes(StoredContextModeEnvelope.from(parsed.base))))
                reparsed.replace(
                    "calculationProjection",
                    mapper.readTree(mapper.writeValueAsBytes(StoredDslDeclaration.from(parsed.calculationProjection))),
                )
                check(reparsed == stored)
                check(parsed.context == context && snapshot.context == context)
                val declaration = parsed.base.base.declaration
                val kept = declaration.claims.results.sumOf { it.conditions.size }
                val quarantined = declaration.claims.results.sumOf { it.quarantined.size }
                val problemPrograms = declaration.programs.count { it.problems.isNotEmpty() }
                check(kept == case.expectedKept && quarantined == case.expectedQuarantined)
                check(declaration.programs.size == 4 && problemPrograms == case.expectedProblemPrograms)
                check(declaration.programs.count { ScheduleDslProblem.REFERENCE in it.problems } == case.expectedProblemPrograms)
                check(sha(Files.readAllBytes(case.request)) == sha(requestBytes))
                check(sha(Files.readAllBytes(case.response)) == sha(responseBytes))
                check(sha(Files.readAllBytes(case.database)) == sha(databaseBytes))
                reports +=
                    mapOf(
                        "case" to case.label,
                        "request_sha256" to sha(requestBytes),
                        "response_sha256" to sha(responseBytes),
                        "database_evidence_sha256" to sha(databaseBytes),
                        "stored_envelope_sha256" to sha(mapper.writeValueAsBytes(stored)),
                        "kept" to kept,
                        "quarantined" to quarantined,
                        "problem_programs" to problemPrograms,
                        "whole_envelope_json_equal" to true,
                        "storage_codec" to "StoredContextModeEnvelope.from + StoredDslDeclaration.from",
                    )
            }
            val report =
                mapper.writeValueAsBytes(
                    mapOf("scope" to "NATIVE_PARSER_REPLAY_NOT_FRESH_JDBC_MODEL_OR_E2E_SUCCESS", "cases" to reports),
                )
            Files
                .newByteChannel(
                    path("outputPath"),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                ).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(report)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
        } catch (failure: Throwable) {
            val line =
                failure.stackTrace
                    .firstOrNull { it.fileName == "C5CandidateRuntimeBindingTest.kt" }
                    ?.lineNumber ?: -1
            throw AssertionError("ACTUAL_C5_REPLAY_FAILED_${failure.javaClass.simpleName}_LINE_$line")
        }
    }
}
