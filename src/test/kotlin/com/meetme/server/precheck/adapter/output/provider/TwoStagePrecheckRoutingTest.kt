package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckChoice
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Independent TS01-03/05/12 oracle. Provider stages are explicit synthetic functions, not semantic regexes. */
class TwoStagePrecheckRoutingTest {
    @Test
    fun `TS02 normalized sufficiently confident CLEAR avoids the generator entirely`() {
        var classifierCalls = 0
        var generatorCalls = 0
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classifierCalls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider {
                    generatorCalls++
                    error("CLEAR must not reach generator")
                },
                budget,
            )
        val result = provider.check(TwoStageFixtures.request())
        assertEquals(PrecheckVerdict.CLEAR, result.verdict)
        assertNull(result.prompt)
        assertNull(result.sourceExcerpt)
        assertTrue(result.choices.isEmpty())
        assertEquals(17L, result.inputTokens)
        assertNull(result.outputTokens)
        assertEquals("OPENAI_DECISIONS", result.provider)
        assertEquals("gpt-6-luna", result.model)
        assertEquals(1, classifierCalls)
        assertEquals(0, generatorCalls)
        assertEquals(1, budget.requests.size)
    }

    @ParameterizedTest
    @EnumSource(PrecheckVerdict::class)
    fun `TS03 suspect classification delegates exactly once and preserves each legal generator result`(verdict: PrecheckVerdict) {
        val expected = TwoStageFixtures.generated(verdict)
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.review() },
                PreSubmissionCheckProvider {
                    calls++
                    expected
                },
                TwoStageFakeBudget(),
            )
        assertEquals(expected, provider.check(TwoStageFixtures.request()))
        assertEquals(1, calls)
    }

    @ParameterizedTest
    @EnumSource(AmbiguityClassificationReason::class, names = ["MODEL_UNCERTAIN", "LOW_CONFIDENCE"])
    fun `TS02 normal abstention may be reclassified CLEAR by the generator`(reason: AmbiguityClassificationReason) {
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { AmbiguityClassificationResult(AmbiguityAssessment.INDETERMINATE, reason, confidence = 0.5) },
                PreSubmissionCheckProvider {
                    calls++
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                TwoStageFakeBudget(),
            )
        assertEquals(PrecheckVerdict.CLEAR, provider.check(TwoStageFixtures.request()).verdict)
        assertEquals(1, calls)
    }

    @Test
    fun `TS02 refusal is a failure and never automatically requests generation`() {
        var generatorCalls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    AmbiguityClassificationResult(AmbiguityAssessment.INDETERMINATE, AmbiguityClassificationReason.REFUSED)
                },
                PreSubmissionCheckProvider {
                    generatorCalls++
                    error("refusal cannot fallback")
                },
                TwoStageFakeBudget(),
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(0, generatorCalls)
    }

    @ParameterizedTest
    @ValueSource(strings = ["CONFIGURATION", "TIMEOUT", "INVALID_RESPONSE", "RATE_LIMITED", "CONNECTION_FAILED", "SERVER"])
    fun `TS02 classifier API or protocol failure has no automatic second stage`(failure: String) {
        var generatorCalls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { throw AmbiguityClassificationException(failure) },
                PreSubmissionCheckProvider {
                    generatorCalls++
                    error("API failure cannot fallback")
                },
                TwoStageFakeBudget(),
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(0, generatorCalls)
    }

    @Test
    fun `TS03 exact raw text negation exceptions context and interpretation anchor reach both stages`() {
        val input = TwoStageFixtures.request().copy(rawText = "  주말 아무 때나, 단 토요일 저녁은 안 돼요. 민수가 정한 곳이면 가능해요.  ")
        var classified: AmbiguityClassificationRequest? = null
        var generated: PrecheckProviderRequest? = null
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classified = it
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    generated = it
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                TwoStageFakeBudget(),
            )
        provider.check(input)
        val first = requireNotNull(classified)
        assertEquals(input.rawText, first.rawText)
        assertEquals(input.referenceAt, first.referenceAt)
        assertEquals(input.timeZone, first.timeZone)
        assertEquals(input.locale, first.locale)
        assertEquals(input.searchStartDate, first.searchStartDate)
        assertEquals(input.searchEndDate, first.searchEndDate)
        assertEquals(input.copy(timeout = requireNotNull(generated).timeout), generated)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "CLEAR_REASON",
            "REVIEW_REASON",
            "CLEAR_NO_CONF",
            "REVIEW_NO_CONF",
            "INDET_NO_REASON",
            "INDET_NO_CONF",
            "INDET_NAN",
            "INDET_OVER_ONE",
            "INDET_NEGATIVE",
            "NAN",
            "INFINITY",
            "NEGATIVE",
            "OVER_ONE",
        ],
    )
    fun `TS02 malformed normalized port result fails without CLEAR or generation`(fault: String) {
        val invalid =
            when (fault) {
                "CLEAR_REASON" -> TwoStageFixtures.clear().copy(reason = AmbiguityClassificationReason.LOW_CONFIDENCE)
                "REVIEW_REASON" -> TwoStageFixtures.review().copy(reason = AmbiguityClassificationReason.MODEL_UNCERTAIN)
                "CLEAR_NO_CONF" -> TwoStageFixtures.clear().copy(confidence = null)
                "REVIEW_NO_CONF" -> TwoStageFixtures.review().copy(confidence = null)
                "INDET_NO_REASON" -> TwoStageFixtures.clear().copy(assessment = AmbiguityAssessment.INDETERMINATE)
                "INDET_NO_CONF" ->
                    AmbiguityClassificationResult(
                        AmbiguityAssessment.INDETERMINATE,
                        AmbiguityClassificationReason.LOW_CONFIDENCE,
                    )
                "INDET_NAN" ->
                    AmbiguityClassificationResult(
                        AmbiguityAssessment.INDETERMINATE,
                        AmbiguityClassificationReason.MODEL_UNCERTAIN,
                        Double.NaN,
                    )
                "INDET_OVER_ONE" ->
                    AmbiguityClassificationResult(
                        AmbiguityAssessment.INDETERMINATE,
                        AmbiguityClassificationReason.LOW_CONFIDENCE,
                        1.1,
                    )
                "INDET_NEGATIVE" ->
                    AmbiguityClassificationResult(
                        AmbiguityAssessment.INDETERMINATE,
                        AmbiguityClassificationReason.MODEL_UNCERTAIN,
                        -0.1,
                    )
                "NAN" -> TwoStageFixtures.clear().copy(confidence = Double.NaN)
                "INFINITY" -> TwoStageFixtures.review().copy(confidence = Double.POSITIVE_INFINITY)
                "NEGATIVE" -> TwoStageFixtures.clear().copy(confidence = -0.1)
                else -> TwoStageFixtures.review().copy(confidence = 1.1)
            }
        var generators = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { invalid },
                PreSubmissionCheckProvider {
                    generators++
                    error("invalid normalized classification")
                },
                TwoStageFakeBudget(),
            )
        assertEquals(
            "INVALID_RESPONSE",
            assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }.failureCode,
        )
        assertEquals(0, generators)
    }

    @Test
    fun `TS05 missing scope or invalid generation is rejected before any reservation or outbound`() {
        val budget = TwoStageFakeBudget()
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("invalid identity") },
                budget,
            )
        val input = TwoStageFixtures.request()
        listOf(input.copy(roomId = null), input.copy(executionGeneration = null), input.copy(executionGeneration = -1)).forEach {
            assertFailsWith<PrecheckProviderException> { provider.check(it) }
        }
        assertEquals(0, calls)
        assertTrue(budget.requests.isEmpty())
    }
}

internal object TwoStageFixtures {
    val roomId: UUID = UUID.fromString("00000000-0000-0000-0000-000000107301")
    val checkId: UUID = UUID.fromString("00000000-0000-0000-0000-000000107302")
    val anchor: Instant = Instant.parse("2026-10-18T03:00:00Z")

    fun request() =
        PrecheckProviderRequest(
            checkId,
            "다음 주 토요일 오전 아니면 오후에 만날까요",
            anchor,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 18),
            LocalDate.of(2026, 11, 1),
            Duration.ofSeconds(15),
            roomId = roomId,
            executionGeneration = 2,
        )

    fun clear() =
        AmbiguityClassificationResult(
            AmbiguityAssessment.CLEAR,
            confidence = 0.99,
            inputTokens = 17,
            outputTokens = 4,
            usageComplete = true,
        )

    fun review() = clear().copy(assessment = AmbiguityAssessment.NEEDS_REVIEW)

    fun generated(verdict: PrecheckVerdict) =
        if (verdict == PrecheckVerdict.NEEDS_CHOICE) {
            PrecheckProviderResult(
                verdict,
                "오전과 오후 중 어느 쪽인가요?",
                "오전 아니면 오후",
                listOf(
                    PrecheckChoice("AM", "오전", "다음 주 토요일 오전에 만날까요"),
                    PrecheckChoice("PM", "오후", "다음 주 토요일 오후에 만날까요"),
                ),
                23,
                8,
                180,
                "SYNTHETIC_GENERATOR",
                "synthetic-profile",
            )
        } else {
            PrecheckProviderResult(
                verdict,
                inputTokens = 23,
                outputTokens = 8,
                provider = "SYNTHETIC_GENERATOR",
                model = "synthetic-profile",
            )
        }
}
