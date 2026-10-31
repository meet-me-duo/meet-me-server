package com.meetme.server.shared.domain

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Latest experimental contract: immutable original plus one scoped answer, never a replacement. */
class InputEvidenceContractTest {
    private val reference = Instant.parse("2026-10-29T03:00:00Z")
    private val answered = reference.plusSeconds(60)
    private val questionId = UUID.fromString("a73973e4-7302-4eb5-b13b-808f360ee9bd")
    private val original = "🙂 목요일 7시 이후 가능. 금요일은 불가."
    private val excerpt = "7시"
    private val sourceStart = original.codePointCount(0, original.indexOf(excerpt))

    @Test
    fun `EX-ORIGINAL Unicode source coordinates are codepoints and the original is preserved`() {
        val evidence = evidence()

        evidence.validateAgainst(original)

        assertEquals("🙂 목요일 7시 이후 가능. 금요일은 불가.", original)
        assertEquals("오후입니다", evidence.supplement?.answerText)
        assertEquals(reference, evidence.referenceAt)
        assertEquals(reference, evidence.supplement?.referenceAt)
    }

    @Test
    fun `EX-SOURCE a guessed source excerpt cannot be accepted as original evidence`() {
        assertFailsWith<IllegalArgumentException> {
            evidence(supplement().copy(sourceExcerpt = "9시")).validateAgainst(original)
        }
    }

    @Test
    fun `EX-SOURCE UTF16 offset cannot substitute for a codepoint coordinate`() {
        val utf16Offset = original.indexOf(excerpt)
        assertNotEquals(sourceStart, utf16Offset)

        assertFailsWith<IllegalArgumentException> {
            evidence(supplement().copy(sourceStartCodePoint = utf16Offset)).validateAgainst(original)
        }
    }

    @Test
    fun `EX-SOURCE repeated excerpts are scoped to the recorded occurrence`() {
        val repeated = "7시가 아니라 금요일 7시 가능"
        val second = repeated.codePointCount(0, repeated.lastIndexOf("7시"))
        val evidence = evidence(supplement().copy(sourceStartCodePoint = second))

        evidence.validateAgainst(repeated)

        assertFailsWith<IllegalArgumentException> {
            evidence.copy(supplement = evidence.supplement!!.copy(sourceStartCodePoint = second - 1)).validateAgainst(repeated)
        }
    }

    @Test
    fun `EX-SOURCE negative or past-end source positions fail without repairing the source`() {
        for (position in listOf(-1, original.codePointCount(0, original.length), Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                evidence(supplement().copy(sourceStartCodePoint = position)).validateAgainst(original)
            }
        }
    }

    @Test
    fun `EX-REVISION an answer cannot attach to a different original revision`() {
        assertFailsWith<IllegalArgumentException> {
            evidence(supplement().copy(sourceRevision = 2)).validateAgainst(original)
        }
    }

    @Test
    fun `EX-ORIGINAL an answer cannot move the original relative-date reference`() {
        assertFailsWith<IllegalArgumentException> {
            evidence(supplement().copy(referenceAt = reference.plusSeconds(86400))).validateAgainst(original)
        }
    }

    @Test
    fun `EX-ORIGINAL direct answer records its answer-time reference without replacing original reference`() {
        val direct = evidence(supplement().copy(choiceId = null, referenceAt = answered))

        direct.validateAgainst(original)

        assertEquals(reference, direct.referenceAt)
        assertEquals(answered, direct.supplement?.referenceAt)
    }

    @Test
    fun `EX-ORIGINAL original revision must be positive`() {
        for (revision in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> {
                InputEvidence(revision, reference).validateAgainst(original)
            }
        }
    }

    @Test
    fun `EX-RECEIPT unresolved status carries a bounded reason while other states carry none`() {
        val pending = InputEvidence(1, reference)
        for (reason in listOf(null, "", " ", "x".repeat(65))) {
            assertFailsWith<IllegalArgumentException> {
                pending.copy(interpretation = InputInterpretation.UNRESOLVED, unresolvedReason = reason).validateAgainst(original)
            }
        }
        for (state in listOf(InputInterpretation.PENDING, InputInterpretation.CONFIRMED)) {
            assertFailsWith<IllegalArgumentException> {
                pending.copy(interpretation = state, unresolvedReason = "UNRESOLVED").validateAgainst(original)
            }
        }
        pending.copy(interpretation = InputInterpretation.UNRESOLVED, unresolvedReason = "TIME_UNRESOLVED").validateAgainst(original)
    }

    @Test
    fun `EX-RECEIPT no answer is a valid pending original and not an invented answer`() {
        val pending = InputEvidence(1, reference)

        pending.validateAgainst(original)

        assertNull(pending.supplement)
        assertEquals(InputInterpretation.PENDING, pending.interpretation)
    }

    @Test
    fun `EX-EVIDENCE-LIMIT counts original and answer Unicode codepoints exactly once`() {
        val raw = "🙂7시"
        val evidence = evidence(supplement().copy(sourceStartCodePoint = 1, answerText = "오후🙂"))

        evidence.validateAgainst(raw)

        assertEquals(6, evidence.textCodePoints(raw))
        assertEquals(3, InputEvidence(1, reference).textCodePoints(raw))
    }

    @Test
    fun `EX-EVIDENCE-LIMIT combined original and answer retain the existing 500 codepoint cap`() {
        val raw = "7시" + "🙂".repeat(495)
        val atLimit = evidence(supplement().copy(sourceStartCodePoint = 0, answerText = "오후🙂"))

        atLimit.validateAgainst(raw)
        assertEquals(500, atLimit.textCodePoints(raw))

        assertFailsWith<IllegalArgumentException> {
            atLimit.copy(supplement = atLimit.supplement!!.copy(answerText = "오후🙂🙂")).validateAgainst(raw)
        }
        assertFailsWith<IllegalArgumentException> {
            InputEvidence(1, reference).validateAgainst("🙂".repeat(501))
        }
    }

    @Test
    fun `EX-EVIDENCE-FENCE canonical source changes when the answer or source scope changes`() {
        val baseline = evidence()
        val alternatives =
            listOf(
                baseline.copy(originalRevision = 2),
                baseline.copy(referenceAt = reference.plusSeconds(1)),
                baseline.copy(interpretation = InputInterpretation.UNRESOLVED, unresolvedReason = "아직 시간 불명확"),
                baseline.copy(supplement = supplement().copy(answerText = "오전입니다")),
                baseline.copy(supplement = supplement().copy(questionId = UUID.randomUUID())),
                baseline.copy(supplement = supplement().copy(sourceRevision = 2)),
                baseline.copy(supplement = supplement().copy(sourceStartCodePoint = sourceStart + 1)),
                baseline.copy(supplement = supplement().copy(choiceId = "B")),
                baseline.copy(supplement = supplement().copy(referenceAt = reference.plusSeconds(1))),
            )

        for (alternative in alternatives) {
            assertNotEquals(baseline.canonical(), alternative.canonical())
        }
        assertEquals(baseline.canonical(), baseline.copy().canonical())
    }

    @Test
    fun `EX-EVIDENCE-FENCE adjacent text fields cannot collide by moving a delimiter`() {
        val first = evidence(supplement().copy(prompt = "몇 시|오전", answerText = "오후"))
        val second = evidence(supplement().copy(prompt = "몇 시", answerText = "오전|오후"))
        val nullChoice = evidence(supplement().copy(choiceId = null))
        val textChoice = evidence(supplement().copy(choiceId = "null"))

        assertNotEquals(first.canonical(), second.canonical())
        assertNotEquals(nullChoice.canonical(), textChoice.canonical())
    }

    private fun evidence(answer: InputSupplement = supplement()) =
        InputEvidence(
            originalRevision = 1,
            referenceAt = reference,
            supplement = answer,
            interpretation = InputInterpretation.CONFIRMED,
        )

    private fun supplement() =
        InputSupplement(
            questionId = questionId,
            sourceRevision = 1,
            sourceExcerpt = excerpt,
            sourceStartCodePoint = sourceStart,
            prompt = "7시는 오전인가요, 오후인가요?",
            answerText = "오후입니다",
            choiceId = "A",
            answeredAt = answered,
            referenceAt = reference,
        )
}
