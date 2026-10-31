package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputSupplement
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** EX-MODEL: public wire/action policy. V2 label routing follows the user decision of 2026-10-30. */
class EvidencePrecheckContractTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = PrecheckProviderContract(mapper)
    private val request =
        TwoStageFixtures.request().copy(
            rawText = "목요일 7시부터 9시까지 가능",
            evidence = InputEvidence(1, TwoStageFixtures.anchor),
        )

    @Test
    fun `EX-LABEL V2 clear semantic evidence is not overridden by legacy confidence normalization`() {
        var generations = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    TwoStageFixtures.clear().copy(
                        assessment = AmbiguityAssessment.INDETERMINATE,
                        semanticAssessment = AmbiguityAssessment.CLEAR,
                        reason = AmbiguityClassificationReason.LOW_CONFIDENCE,
                    )
                },
                PreSubmissionCheckProvider {
                    generations++
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                TwoStageFakeBudget(),
            )
        assertEquals(PrecheckVerdict.CLEAR, provider.check(request).verdict)
        assertEquals(0, generations)
    }

    @Test
    fun `EX-ONE-QUESTION answer recheck carries same original and never generates another question`() {
        val supplement =
            InputSupplement(
                UUID.randomUUID(),
                1,
                "7시부터 9시",
                4,
                "오전 또는 오후인가요?",
                "오후 7시부터 9시",
                "B",
                TwoStageFixtures.anchor,
                TwoStageFixtures.anchor,
            )
        val answered = request.copy(evidence = requireNotNull(request.evidence).copy(supplement = supplement))
        var generations = 0
        var received: com.meetme.server.shared.domain.InputEvidence? = null
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    received = it.evidence
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    generations++
                    PrecheckProviderResult(PrecheckVerdict.NEEDS_EDIT)
                },
                TwoStageFakeBudget(),
            )
        assertEquals(PrecheckVerdict.UNRESOLVED, provider.check(answered).verdict)
        assertEquals(answered.evidence, received)
        assertEquals(0, generations)
    }

    @Test
    fun `EX-TEXT-ONLY V2 permits one grounded question without unsafe A B alternatives`() {
        val response = envelope("""{"verdict":"NEEDS_CHOICE","prompt":"가능한 시간대를 알려주세요","sourceExcerpt":"7시부터 9시","choices":[]}""")
        val result = contract.parseLuna(response, request)
        assertEquals(PrecheckVerdict.NEEDS_CHOICE, result.verdict)
        assertTrue(result.choices.isEmpty())
        assertEquals("7시부터 9시", result.sourceExcerpt)
        assertFailsWith<PrecheckProviderException> { contract.parseLuna(response, request.copy(evidence = null)) }
    }

    @Test
    fun `EX-GROUNDING repeated source excerpt is rejected because scoped supplement would be ambiguous`() {
        val repeated = request.copy(rawText = "7시부터 9시 또는 7시부터 9시")
        val response = envelope("""{"verdict":"NEEDS_CHOICE","prompt":"어느 쪽인가요?","sourceExcerpt":"7시부터 9시","choices":[]}""")
        assertFailsWith<PrecheckProviderException> { contract.parseLuna(response, repeated) }
    }

    @Test
    fun `EX-STRICT V2 response has all four fields and no generic needs edit branch`() {
        val missing = envelope("""{"verdict":"CLEAR"}""")
        val needsEdit = envelope("""{"verdict":"NEEDS_EDIT","prompt":null,"sourceExcerpt":null,"choices":[]}""")
        assertFailsWith<PrecheckProviderException> { contract.parseLuna(missing, request) }
        assertFailsWith<PrecheckProviderException> { contract.parseLuna(needsEdit, request) }
    }

    private fun envelope(payload: String): ByteArray =
        mapper.writeValueAsBytes(
            mapOf(
                "status" to "completed",
                "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload)))),
            ),
        )
}
