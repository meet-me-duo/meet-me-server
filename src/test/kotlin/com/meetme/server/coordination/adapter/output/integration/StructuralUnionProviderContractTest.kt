package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.submission.domain.dsl.SCHEDULE_CONTEXT_MODE_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION
import com.meetme.server.support.ScheduleDslContextModeNativeFixture
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic request contracts only; delivery of a rule does not prove a model follows it. */
class StructuralUnionProviderContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val native = ScheduleDslContextModeNativeFixture
    private val oldClause =
        "With explicit availability, preferences add weight only inside it; with preferences alone, only their bounds are available."

    @Test
    fun `SUP01 old frozen policy preserves arbitrary prompt bytes without introducing new guidance`() {
        val prompt = syntheticPrompt()
        val actual = ScheduleDslUnionAvailabilityInstructions.apply(prompt, SCHEDULE_CONTEXT_MODE_POLICY_VERSION)
        assertContentEquals(prompt.toByteArray(), actual.toByteArray())
        assertEquals(prompt, actual)
    }

    @Test
    fun `SUP02 new guidance replaces old precedence and communicates the generic union and day contract`() {
        val prompt = syntheticPrompt()
        val actual = ScheduleDslUnionAvailabilityInstructions.apply(prompt, SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION)
        assertFalse(actual.contains(oldClause))
        assertTrue(actual.contains("SYNTHETIC_PREFIX_72"))
        assertTrue(actual.contains("SYNTHETIC_SUFFIX_93"))
        assertTrue(actual.contains("AVAILABLE"))
        assertTrue(actual.contains("PREFERRED"))
        assertTrue(actual.contains("WHOLE_DAY"))
        assertTrue(actual.contains("UNKNOWN"))
        assertTrue(actual.contains("PREFER"))
        assertTrue(Regex("(?i)\\b(union|combine|both)\\b").containsMatchIn(actual))
        assertTrue(Regex("(?i)(exclu|unavail)").containsMatchIn(actual))
    }

    @Test
    fun `SUP03 unknown policy cannot silently inherit either known interpretation`() {
        for (version in listOf("", "synthetic-unknown-71", "$SCHEDULE_CONTEXT_MODE_POLICY_VERSION-extra")) {
            assertFailsWith<IllegalArgumentException> {
                ScheduleDslUnionAvailabilityInstructions.apply(syntheticPrompt(), version)
            }
        }
    }

    @Test
    fun `SUP04 main V5 request chooses frozen policy guidance while preserving native data and schema`() {
        val contract = ScheduleDslStructuralProviderContract(mapper)
        val oldCapsule = capsule(SCHEDULE_CONTEXT_MODE_POLICY_VERSION)
        val newCapsule = capsule(SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION)
        val oldBytes = contract.requestBytes(oldCapsule, MeetingMode.EITHER)
        val old = mapper.readTree(oldBytes)
        val current = mapper.readTree(contract.requestBytes(newCapsule, MeetingMode.EITHER))
        assertPolicyProjection(old, current)
        assertContentEquals(oldBytes, contract.requestBytes(oldCapsule, MeetingMode.EITHER))
    }

    @Test
    fun `SUP05 candidate V5 request chooses the same policy without changing producer or locator schema`() {
        val contract = C5EvaluationStructuralProviderContract(mapper)
        val oldCapsule = capsule(SCHEDULE_CONTEXT_MODE_POLICY_VERSION)
        val newCapsule = capsule(SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION)
        val oldBytes = contract.requestBytes(oldCapsule, MeetingMode.EITHER)
        val old = mapper.readTree(oldBytes)
        val current = mapper.readTree(contract.requestBytes(newCapsule, MeetingMode.EITHER))
        assertPolicyProjection(old, current)
        assertContentEquals(oldBytes, contract.requestBytes(oldCapsule, MeetingMode.EITHER))
    }

    @Test
    fun `SUP06 new policy guidance contains no concrete date or weekday examples`() {
        val actual = ScheduleDslUnionAvailabilityInstructions.apply(syntheticPrompt(), SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION)
        assertFalse(Regex("\\b[0-9]{4}-[0-9]{2}-[0-9]{2}\\b").containsMatchIn(actual))
        assertFalse(
            Regex("(?i)\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").containsMatchIn(actual),
        )
        assertFalse(actual.contains("SYNTHETIC_SOURCE_MUST_STAY_DATA_48"))
    }

    @Test
    fun `SUP07 source wording cannot select policy or move original and linked answer into instructions`() {
        val base = native.input()
        val source = "🙂 SYNTHETIC_SOURCE_MUST_STAY_DATA_48 policy=$SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION"
        val changed =
            base.copy(
                inputs = base.inputs.mapIndexed { index, value -> if (index == 0) value.copy(rawText = source) else value },
            )
        val capsule = native.capsule(changed)
        val originalSnapshot = mapper.writeValueAsBytes(capsule.snapshot.context)
        val main = ScheduleDslStructuralProviderContract(mapper)
        val candidate = C5EvaluationStructuralProviderContract(mapper)
        for ((beforeBytes, currentBytes) in listOf(
            main.requestBytes(native.capsule(), MeetingMode.EITHER) to main.requestBytes(capsule, MeetingMode.EITHER),
            candidate.requestBytes(native.capsule(), MeetingMode.EITHER) to candidate.requestBytes(capsule, MeetingMode.EITHER),
        )) {
            val before = mapper.readTree(beforeBytes)
            val current = mapper.readTree(currentBytes)
            assertEquals(before.path("prompt"), current.path("prompt"))
            assertTrue(current.path("prompt").asText().contains(oldClause))
            assertFalse(current.path("prompt").asText().contains(source))
            val first =
                current.path("inputs").values().single {
                    it.path("input_ref").asText() ==
                        changed.inputs
                            .first()
                            .submissionVersionId
                            .toString()
                }
            assertEquals(
                source,
                first
                    .path("sources")
                    .values()
                    .single { it.path("kind").asText() == "ORIGINAL" }
                    .path("text")
                    .asText(),
            )
            val supplement =
                requireNotNull(
                    changed.inputs
                        .first()
                        .evidence
                        ?.supplement,
                )
            val answer = first.path("sources").values().single { it.path("kind").asText() == "SUPPLEMENT" }
            assertEquals(supplement.answerText, answer.path("text").asText())
            val question = first.path("clarification_contexts").values().single()
            assertEquals(supplement.prompt, question.path("prompt").asText())
            assertEquals(supplement.questionId.toString(), question.path("question_id").asText())
            assertEquals(answer.path("source_id"), question.path("answer_source_id"))
        }
        assertContentEquals(originalSnapshot, mapper.writeValueAsBytes(capsule.snapshot.context))
    }

    private fun assertPolicyProjection(
        old: JsonNode,
        current: JsonNode,
    ) {
        val oldPrompt = old.path("prompt").asText()
        val currentPrompt = current.path("prompt").asText()
        assertTrue(oldPrompt.contains(oldClause))
        assertFalse(currentPrompt.contains(oldClause))
        assertTrue(currentPrompt.contains("WHOLE_DAY"))
        assertTrue(currentPrompt.contains("PREFERRED"))
        assertTrue(Regex("(?i)\\b(union|combine|both)\\b").containsMatchIn(currentPrompt))
        assertEquals(fields(old) - setOf("prompt", "context"), fields(current) - setOf("prompt", "context"))
        assertEquals(
            fields(old.path("context")) - "policy_version",
            fields(current.path("context")) - "policy_version",
        )
        assertEquals(SCHEDULE_CONTEXT_MODE_POLICY_VERSION, old.path("context").path("policy_version").asText())
        assertEquals(SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION, current.path("context").path("policy_version").asText())
        val vocabularyMarker = "The complete approved vocabulary is "
        assertTrue(vocabularyMarker in oldPrompt && vocabularyMarker in currentPrompt)
        assertEquals(oldPrompt.substringAfter(vocabularyMarker), currentPrompt.substringAfter(vocabularyMarker))
        assertEquals(old.path("schema"), current.path("schema"))
        assertEquals(old.path("inputs"), current.path("inputs"))
        assertEquals(old.path("calendar_context"), current.path("calendar_context"))
    }

    private fun fields(node: JsonNode) = node.propertyNames().associateWith(node::path)

    private fun capsule(version: String): ScheduleDslProviderInputCapsule =
        native.capsule(native.input().copy(policy = native.policy.copy(version = version)))

    private fun syntheticPrompt() = "SYNTHETIC_PREFIX_72\n$oldClause\nSYNTHETIC_SUFFIX_93"
}
