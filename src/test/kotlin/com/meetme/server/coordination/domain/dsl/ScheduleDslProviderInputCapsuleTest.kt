package com.meetme.server.coordination.domain.dsl

import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ScheduleDslProviderInputCapsuleTest {
    private val f = ScheduleDslQuestionContextFixture

    @Test
    fun `CTX01 capsule carries actual prompt choice times IDs and Unicode source linkage without creating evidence`() {
        val snapshot = f.snapshot()
        val capsule = ScheduleDslProviderInputCapsule.create(snapshot, f.native())
        assertEquals(snapshot.context, capsule.snapshot.context)
        val question = capsule.questions.single()
        assertEquals(f.ir.first, question.submissionVersionId)
        assertEquals(f.questionId, question.questionId)
        assertEquals("supplement:${f.questionId}", question.answerSourceId)
        assertEquals(3, question.sourceRevision)
        assertEquals(0, question.originalStartCodePoint)
        assertEquals(1, question.originalEndCodePoint)
        assertEquals(f.PROMPT, question.prompt)
        assertEquals(f.CHOICE, question.choiceId)
        assertEquals(f.answeredAt, question.answeredAt)
        assertEquals(f.ir.reference, question.answerReferenceAt)
        assertFalse(question.choiceDefinitionsAvailable)
        assertEquals(
            listOf("original", "supplement:${f.questionId}"),
            capsule.snapshot.context.inputs
                .single {
                    it.submissionVersionId ==
                        f.ir.first
                }.sources
                .map { it.sourceId },
        )
        assertEquals(f.ir.FIRST_TEXT, capsule.snapshot.sourceText(f.ir.first, "original"))
        assertEquals(f.answer.answerText, capsule.snapshot.sourceText(f.ir.first, question.answerSourceId))
    }

    @Test
    fun `CTX02 mutable native collection changes cannot alter a created capsule and ordering is deterministic`() {
        val submissions = f.native().reversed().toMutableList()
        val capsule = ScheduleDslProviderInputCapsule.create(f.snapshot(), submissions)
        submissions.clear()
        assertEquals(2, capsule.snapshot.context.inputs.size)
        assertEquals(1, capsule.questions.size)
        assertEquals(ScheduleDslProviderInputCapsule.create(f.snapshot(), f.native()).questions, capsule.questions)
    }

    @ParameterizedTest
    @ValueSource(strings = ["prompt", "choice", "answered_at", "raw_text", "participant", "room", "foreign_version"])
    fun `CTX03 forged native context cannot be attached to an earlier trusted snapshot`(field: String) {
        val input = f.input1()
        val first = input.inputs.first()
        val evidence = requireNotNull(first.evidence)
        val altered =
            when (field) {
                "prompt" -> first.copy(evidence = evidence.copy(supplement = f.answer.copy(prompt = "Synthetic changed question")))
                "choice" -> first.copy(evidence = evidence.copy(supplement = f.answer.copy(choiceId = "synthetic-different-choice")))
                "answered_at" -> first.copy(evidence = evidence.copy(supplement = f.answer.copy(answeredAt = f.answeredAt.plusSeconds(1))))
                "raw_text" -> first.copy(rawText = first.rawText + " changed")
                "participant" -> first.copy(participantId = UUID(0, 991))
                else -> first.copy(submissionVersionId = UUID(0, 992))
            }
        val forged = if (field == "room") input.copy(roomId = UUID(0, 993)) else input.copy(inputs = listOf(altered, input.inputs.last()))
        assertFailsWith<RuntimeException> { ScheduleDslProviderInputCapsule.create(f.snapshot(), f.native(forged)) }
    }

    @Test
    fun `CTX04 free answer retains null choice and its own answer reference time without inventing UI choices`() {
        val original = f.input1()
        val first = original.inputs.first()
        val evidence = requireNotNull(first.evidence)
        val freeAnswer = f.answer.copy(choiceId = null, referenceAt = f.answeredAt)
        val input = original.copy(inputs = listOf(first.copy(evidence = evidence.copy(supplement = freeAnswer)), original.inputs.last()))
        val snapshot =
            com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
                .snapshot(input)
        val capsule = ScheduleDslProviderInputCapsule.create(snapshot, f.native(input))
        val context = capsule.questions.single()
        assertEquals(null, context.choiceId)
        assertEquals(f.answeredAt, context.answerReferenceAt)
        assertEquals(f.answeredAt, context.answeredAt)
        assertFalse(context.choiceDefinitionsAvailable)
        assertEquals(snapshot.context, capsule.snapshot.context)
    }
}
