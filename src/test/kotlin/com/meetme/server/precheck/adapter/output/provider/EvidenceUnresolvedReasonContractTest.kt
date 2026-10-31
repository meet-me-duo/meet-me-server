package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** EX-RECEIPT: V2 labels route under the 2026-10-30 decision; after-answer uncertainty remains diagnosable. */
class EvidenceUnresolvedReasonContractTest {
    @Test
    fun `EX-LABEL initial low confidence receives Luna verification instead of a silent unresolved receipt`() {
        val low = uncertain(AmbiguityClassificationReason.LOW_CONFIDENCE)
        val result = routed(low)

        assertEquals(PrecheckVerdict.CLEAR, result.verdict)
        assertEquals(null, result.unresolvedReason)
    }

    @Test
    fun `EX-REASON model uncertainty after one answer retains its own normalized reason`() {
        val result = routed(uncertain(AmbiguityClassificationReason.MODEL_UNCERTAIN), answered = true)

        assertEquals(PrecheckVerdict.UNRESOLVED, result.verdict)
        assertEquals("CLASSIFIER_MODEL_UNCERTAIN", result.unresolvedReason)
    }

    @Test
    fun `EX-REASON review after one answer is unresolved without a second generation`() {
        val result = routed(TwoStageFixtures.review(), answered = true)

        assertEquals(PrecheckVerdict.UNRESOLVED, result.verdict)
        assertEquals("AFTER_ANSWER_REVIEW", result.unresolvedReason)
    }

    @Test
    fun `EX-REASON refusal and timeout remain technical exceptions instead of semantic receipts`() {
        val refused =
            assertFailsWith<PrecheckProviderException> {
                routed(uncertain(AmbiguityClassificationReason.REFUSED))
            }
        assertEquals("REFUSED", refused.failureCode)
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { throw AmbiguityClassificationException("TIMEOUT") },
                PreSubmissionCheckProvider { error("A technical classification failure cannot generate a question") },
                TwoStageFakeBudget(),
            )
        val timeout = assertFailsWith<PrecheckProviderException> { provider.check(request(false)) }
        assertEquals("TIMEOUT", timeout.failureCode)
    }

    private fun uncertain(reason: AmbiguityClassificationReason): AmbiguityClassificationResult =
        TwoStageFixtures.clear().copy(
            assessment = AmbiguityAssessment.INDETERMINATE,
            reason = reason,
            semanticAssessment = AmbiguityAssessment.INDETERMINATE,
        )

    private fun routed(
        result: AmbiguityClassificationResult,
        answered: Boolean = false,
    ): PrecheckProviderResult {
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { result },
                PreSubmissionCheckProvider {
                    generations++
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                TwoStageFakeBudget(),
            )
        val routed = provider.check(request(answered))
        assertEquals(if (answered || result.assessment == AmbiguityAssessment.CLEAR) 0 else 1, generations)
        return routed
    }

    private fun request(answered: Boolean) =
        TwoStageFixtures.request().copy(
            rawText = "목요일 7시 이후 가능",
            evidence =
                InputEvidence(
                    1,
                    TwoStageFixtures.anchor,
                    supplement =
                        if (answered) {
                            InputSupplement(
                                UUID.randomUUID(),
                                1,
                                "7시",
                                4,
                                "오전인가요 오후인가요?",
                                "오후",
                                "B",
                                TwoStageFixtures.anchor.plusSeconds(1),
                                TwoStageFixtures.anchor,
                            )
                        } else {
                            null
                        },
                ),
        )
}
