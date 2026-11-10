package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_REQUEST_CONTRACT
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
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

class ScheduleDslContextProviderContractTest {
    private val f = ScheduleDslQuestionContextFixture
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    private fun capsule() = ScheduleDslProviderInputCapsule.create(f.snapshot(), f.native())

    private fun request() = ScheduleDslContextProviderContract(mapper).requestBytes(capsule())

    @Test
    fun `CTXP01 explicit version2 sends readable question linkage without promoting metadata into evidence`() {
        val root = mapper.readTree(request())
        assertEquals(SCHEDULE_DSL_CONTEXT_REQUEST_CONTRACT, root.path("request_contract_version").asString())
        assertEquals(SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT, root.path("response_contract_version").asString())
        val first = root.path("inputs").single { it.path("input_ref").asString() == f.ir.first.toString() }
        val contexts = first.path("clarification_contexts")
        val context = contexts.single()
        val expected =
            mapOf(
                "question_id" to f.questionId.toString(),
                "answer_source_id" to "supplement:${f.questionId}",
                "source_revision" to 3,
                "original_start_code_point" to 0,
                "original_end_code_point" to 1,
                "prompt" to f.PROMPT,
                "choice_id" to f.CHOICE,
                "answered_at" to f.answeredAt.toString(),
                "answer_reference_at" to f.ir.reference.toString(),
                "choice_definitions_available" to false,
            )
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(expected)), context)
        val old =
            mapper.readTree(
                ScheduleDslProviderContract(
                    mapper,
                ).requestBytes(f.snapshot(), SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION),
            )
        val oldFirst = old.path("inputs").single { it.path("input_ref").asString() == f.ir.first.toString() }
        assertEquals(oldFirst.path("sources"), first.path("sources"))
        assertEquals(old.path("schema"), root.path("schema"))
        val prompt = requireNotNull(root.path("prompt").asString())
        assertTrue(prompt.isNotBlank())
        assertFalse(
            prompt == old.path("prompt").asString(),
            "New instructions must distinguish question context from assertion evidence and unavailable choices",
        )
        val text = request().toString(Charsets.UTF_8)
        assertFalse(text.contains(f.ir.room.toString()))
        assertFalse(text.contains(f.ir.firstParticipant.toString()))
        assertFalse(text.contains(f.ir.secondParticipant.toString()))
    }

    @Test
    fun `CTXP02 input without a stored question carries an explicit empty clarification context list`() {
        val input = f.input1()
        val plain = input.copy(inputs = input.inputs.map { it.copy(evidence = it.evidence?.copy(supplement = null)) })
        val snapshot = ScheduleDslOfflinePipeline.snapshot(plain)
        val capsule = ScheduleDslProviderInputCapsule.create(snapshot, f.native(plain))
        assertTrue(capsule.questions.isEmpty())
        val root = mapper.readTree(ScheduleDslContextProviderContract(mapper).requestBytes(capsule))
        for (entry in root.path("inputs")) {
            assertTrue(entry.has("clarification_contexts"))
            assertTrue(entry.path("clarification_contexts").isArray)
            assertEquals(0, entry.path("clarification_contexts").size())
        }
    }

    @Test
    fun `CTXP03 original response1 request bytes remain unchanged after explicit context rendering`() {
        val legacy = ScheduleDslProviderContract(mapper)
        val before = legacy.requestBytes(f.snapshot(), SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)
        request()
        val after = legacy.requestBytes(f.snapshot(), SCHEDULE_DSL_RESPONSE_CONTRACT, SCHEDULE_DSL_SEMANTIC_VERSION, SCHEDULE_AST_VERSION)
        assertContentEquals(before, after)
        val old = mapper.readTree(after)
        assertFalse(old.has("request_contract_version"))
        for (entry in old.path("inputs")) assertFalse(entry.has("clarification_contexts"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["response", "semantic", "ast"])
    fun `CTXP04 strict context parser rejects mismatched selected identities without old decoder fallback`(field: String) {
        val parser = StrictScheduleDslContextResponseParser(mapper)
        assertEquals(SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT, parser.supportedResponseContractVersion)
        assertFailsWith<RuntimeException> {
            parser.parse(
                f.wire.json(),
                f.snapshot(),
                if (field ==
                    "response"
                ) {
                    SCHEDULE_DSL_RESPONSE_CONTRACT
                } else {
                    SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
                },
                if (field ==
                    "semantic"
                ) {
                    "schedule-ir-product-99"
                } else {
                    SCHEDULE_DSL_SEMANTIC_VERSION
                },
                if (field ==
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
    fun `CTXP05 explicit response2 uses unchanged output schema and actual native context with no metadata condition`() {
        val parsed =
            StrictScheduleDslContextResponseParser(
                mapper,
            ).parse(
                f.wire.json(),
                f.snapshot(),
                SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT,
                SCHEDULE_DSL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
            )
        assertEquals(f.snapshot().context, parsed.context)
        assertEquals(
            f.wire.claims.map(f.ir::verified),
            parsed.claims.results
                .first()
                .conditions,
        )
        assertTrue(
            parsed.claims.results
                .first()
                .quarantined
                .isEmpty(),
        )
        assertEquals(
            f.wire.program.nodes
                .map { it.operator },
            parsed.programs
                .first()
                .program!!
                .nodes
                .map { it.operator },
        )
    }

    @Test
    fun `CTXP06 Gemini context wire wraps the exact version2 neutral payload with existing generation config`() {
        val capsule = capsule()
        val bytes = ScheduleDslGeminiWire.requestContextBytes(mapper, capsule, "gemini-3.8-flash")
        val root = mapper.readTree(bytes)
        val content = root.path("contents").single()
        assertEquals("user", content.path("role").asString())
        assertContentEquals(
            ScheduleDslContextProviderContract(mapper).requestBytes(capsule),
            requireNotNull(
                content
                    .path("parts")
                    .single()
                    .path("text")
                    .asString(),
            ).toByteArray(Charsets.UTF_8),
        )
        val old = mapper.readTree(ScheduleDslGeminiWire.requestBytes(mapper, f.snapshot(), "gemini-3.8-flash"))
        assertEquals(old.path("generationConfig"), root.path("generationConfig"))
    }
}
