package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.application.port.output.AiCallStage
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** TS04/08: a deterministic monotonic clock, never a real fifteen-second sleep. */
class TwoStagePrecheckDeadlineTest {
    @Test
    fun `TS04 classification and generation share one fifteen second ceiling`() {
        val clock = AtomicLong(100)
        var first = Duration.ZERO
        var second = Duration.ZERO
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    first = it.timeout
                    clock.addAndGet(Duration.ofSeconds(6).toNanos())
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    second = it.timeout
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                budget,
                LongSupplier(clock::get),
            )
        assertEquals(PrecheckVerdict.CLEAR, provider.check(TwoStageFixtures.request().copy(timeout = Duration.ofSeconds(30))).verdict)
        assertTrue(first > Duration.ZERO && first <= Duration.ofSeconds(15))
        assertTrue(second > Duration.ZERO && second <= Duration.ofSeconds(9))
    }

    @Test
    fun `TS04 a smaller caller deadline is not enlarged for either stage`() {
        val clock = AtomicLong()
        var first = Duration.ZERO
        var second = Duration.ZERO
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    first = it.timeout
                    clock.addAndGet(Duration.ofMillis(1200).toNanos())
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    second = it.timeout
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                TwoStageFakeBudget(),
                LongSupplier(clock::get),
            )
        provider.check(TwoStageFixtures.request().copy(timeout = Duration.ofSeconds(2)))
        assertTrue(first > Duration.ZERO && first <= Duration.ofSeconds(2))
        assertTrue(second > Duration.ZERO && second <= Duration.ofMillis(800))
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `TS04 nonpositive timeout denies before budget or outbound`(millis: Long) {
        val budget = TwoStageFakeBudget()
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("nonpositive timeout") },
                budget,
            )
        assertFailsWith<PrecheckProviderException> {
            provider.check(TwoStageFixtures.request().copy(timeout = Duration.ofMillis(millis)))
        }
        assertEquals(0, calls)
        assertTrue(budget.requests.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["A_RESERVE", "A_CALL", "A_RECORD", "B_RESERVE", "B_CALL", "B_RECORD"])
    fun `TS04 all reserve dispatch and cost recording latency uses the same deadline and cannot return late CLEAR`(phase: String) {
        val clock = AtomicLong()

        fun expire() {
            clock.set(Duration.ofSeconds(15).toNanos())
        }
        val budget =
            TwoStageFakeBudget().apply {
                onReserve =
                    {
                        if (phase == "A_RESERVE" &&
                            it.stage == AiCallStage.CLASSIFICATION ||
                            phase == "B_RESERVE" &&
                            it.stage == AiCallStage.GENERATION
                        ) {
                            expire()
                        }
                    }
                onRecord =
                    {
                        if (phase == "A_RECORD" &&
                            it.request.stage == AiCallStage.CLASSIFICATION ||
                            phase == "B_RECORD" &&
                            it.request.stage == AiCallStage.GENERATION
                        ) {
                            expire()
                        }
                    }
            }
        var classifiers = 0
        var generators = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classifiers++
                    if (phase == "A_CALL") expire()
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    generators++
                    if (phase == "B_CALL") expire()
                    PrecheckProviderResult(PrecheckVerdict.CLEAR, inputTokens = 9, outputTokens = 2)
                },
                budget,
                LongSupplier(clock::get),
            )
        assertEquals("TIMEOUT", assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }.failureCode)
        assertEquals(if (phase == "A_RESERVE") 0 else 1, classifiers)
        assertEquals(if (phase in setOf("B_CALL", "B_RECORD")) 1 else 0, generators)
        if (classifiers > 0) {
            assertTrue(budget.observations.any { it.first.request.stage == AiCallStage.CLASSIFICATION })
            assertEquals(
                17L,
                budget.observations
                    .first()
                    .second.inputTokens,
            )
        }
        if (generators > 0) assertTrue(budget.observations.any { it.first.request.stage == AiCallStage.GENERATION })
    }

    @Test
    fun `TS04 a late first-stage CLEAR records incurred usage but cannot be accepted`() {
        val clock = AtomicLong()
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    clock.set(Duration.ofSeconds(16).toNanos())
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("late CLEAR") },
                budget,
                LongSupplier(clock::get),
            )
        assertEquals("TIMEOUT", assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }.failureCode)
        assertEquals(
            17L,
            budget.observations
                .single()
                .second.inputTokens,
        )
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }
}
