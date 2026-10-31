package com.meetme.server.shared.application.port.output

import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** EX-EVIDENCE-FENCE: authorization binds the answer and policy, not just unchanged raw text. */
class EvidenceDispatchFingerprintTest {
    @Test
    fun `legacy personal and final digests remain byte compatible without evidence`() {
        val raw = "오후 가능"
        assertEquals(framed(listOf(raw)), AiDispatchFingerprints.personalSource(raw))
        val input = AiDispatchInputSnapshot("source", raw, "ko-KR", LocalDate.parse("2026-11-02"))
        assertEquals(framed(listOf("final-source-v1", "source", raw)), AiDispatchFingerprints.batchSource(listOf(input)))
    }

    @Test
    fun `personal dispatch rejects evidence omission and any changed finalized answer`() {
        val evidence = evidence()
        val exact = AiDispatchFingerprints.personalSource(RAW, evidence)
        assertNotEquals(AiDispatchFingerprints.personalSource(RAW), exact)
        val answer = requireNotNull(evidence.supplement)
        val alterations =
            listOf(
                evidence.copy(supplement = answer.copy(answerText = "오전 7시")),
                evidence.copy(supplement = answer.copy(sourceExcerpt = "수요일")),
                evidence.copy(supplement = answer.copy(sourceStartCodePoint = 0)),
                evidence.copy(supplement = answer.copy(referenceAt = NOW.plusSeconds(1))),
                evidence.copy(supplement = answer.copy(choiceId = "B")),
                evidence.copy(referenceAt = NOW.plusSeconds(1)),
                evidence.copy(originalRevision = 2),
                evidence.copy(interpretation = InputInterpretation.CONFIRMED, unresolvedReason = null),
                evidence.copy(unresolvedReason = "OTHER_UNRESOLVED_REASON"),
                evidence.copy(protocol = "UNKNOWN_POLICY"),
            )
        alterations.forEach { assertNotEquals(exact, AiDispatchFingerprints.personalSource(RAW, it)) }
    }

    @Test
    fun `final frozen source includes version evidence independently from raw text and cohort order`() {
        val first = AiDispatchInputSnapshot("source-a", RAW, "ko-KR", LocalDate.parse("2026-11-02"), evidence())
        val second = AiDispatchInputSnapshot("source-b", "수요일 오후 가능", "ko-KR", LocalDate.parse("2026-11-02"))
        val exact = AiDispatchFingerprints.batchSource(listOf(first, second))
        assertNotEquals(exact, AiDispatchFingerprints.batchSource(listOf(first.copy(evidence = null), second)))
        assertNotEquals(exact, AiDispatchFingerprints.batchSource(listOf(second, first)))
        val changed = first.copy(evidence = evidence().copy(unresolvedReason = "QUESTION_BUDGET_EXHAUSTED"))
        assertNotEquals(exact, AiDispatchFingerprints.batchSource(listOf(changed, second)))
    }

    private fun evidence() =
        InputEvidence(
            originalRevision = 1,
            referenceAt = NOW,
            supplement =
                InputSupplement(
                    UUID.fromString("95a1b984-c1a4-4b65-a9af-e602e3c87b70"),
                    1,
                    "7시",
                    4,
                    "오전인가요 오후인가요?",
                    "오후 7시",
                    "A",
                    NOW.plusSeconds(10),
                    NOW,
                ),
            interpretation = InputInterpretation.UNRESOLVED,
            unresolvedReason = "MATERIAL_AMBIGUITY",
        )

    private fun framed(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val RAW = "수요일 7시 이후"
        private val NOW = Instant.parse("2026-11-02T00:00:00Z")
    }
}
