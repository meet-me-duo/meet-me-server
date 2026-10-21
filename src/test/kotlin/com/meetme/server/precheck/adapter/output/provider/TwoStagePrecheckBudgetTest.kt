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
import com.meetme.server.shared.adapter.output.budget.DefaultDenyAiCallBudget
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.application.port.output.AiCallBudgetRequest
import com.meetme.server.shared.application.port.output.AiCallBudgetReservation
import com.meetme.server.shared.application.port.output.AiCallObservation
import com.meetme.server.shared.application.port.output.AiCallOutcome
import com.meetme.server.shared.application.port.output.AiCallPurpose
import com.meetme.server.shared.application.port.output.AiCallQuote
import com.meetme.server.shared.application.port.output.AiCallStage
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TS06-10: fake quota proves the caller contract, not durable PG atomicity or a monetary ceiling. */
class TwoStagePrecheckBudgetTest {
    @Test
    fun `TS07 default budget grants neither reservation nor any outbound even with an available classifier`() {
        val deny = DefaultDenyAiCallBudget()
        val request = TwoStageFakeBudget.sampleRequest()
        assertNull(deny.reserve(request))
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("default deny") },
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(0, calls)
    }

    @ParameterizedTest
    @EnumSource(AiCallStage::class)
    fun `TS06 each external stage requires its own prior reservation`(deniedStage: AiCallStage) {
        val budget = TwoStageFakeBudget().apply { denyStage = deniedStage }
        var classifiers = 0
        var generators = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    classifiers++
                    assertEquals(1, budget.held(TwoStageFixtures.roomId))
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    generators++
                    error("denied generation")
                },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(if (deniedStage == AiCallStage.CLASSIFICATION) 0 else 1, classifiers)
        assertEquals(0, generators)
    }

    @Test
    fun `TS06 two stages reserve one classifier request plus a conservative two-call generation bundle`() {
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    assertEquals(1, budget.held(TwoStageFixtures.roomId))
                    TwoStageFixtures.review()
                },
                PreSubmissionCheckProvider {
                    assertEquals(3, budget.held(TwoStageFixtures.roomId))
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                budget,
            )
        provider.check(TwoStageFixtures.request())
        val first = budget.requests[0]
        val second = budget.requests[1]
        assertEquals(AiCallStage.CLASSIFICATION, first.stage)
        assertEquals(AiCallPurpose.PRECHECK_CLASSIFICATION, first.purpose)
        assertEquals(1, first.maximumPhysicalCalls)
        assertEquals(AiCallStage.GENERATION, second.stage)
        assertEquals(AiCallPurpose.PRECHECK_GENERATION, second.purpose)
        assertEquals(2, second.maximumPhysicalCalls)
        budget.requests.forEach {
            assertEquals(TwoStageFixtures.roomId, it.roomId)
            assertEquals(TwoStageFixtures.checkId, it.executionId)
            assertEquals(2, it.executionGeneration)
            assertTrue(it.sourceFingerprint.isNotBlank())
            assertTrue(it.contextFingerprint.isNotBlank())
            assertTrue(it.profileFingerprint.isNotBlank())
            assertFalse(it.sourceFingerprint.contains(TwoStageFixtures.request().rawText))
        }
        assertEquals(first.sourceFingerprint, second.sourceFingerprint)
        assertEquals(first.contextFingerprint, second.contextFingerprint)
        assertNotEquals(first.profileFingerprint, second.profileFingerprint)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "UNVERIFIED",
            "NO_EVIDENCE",
            "WRONG_PROFILE",
            "TOO_FEW_CALLS",
            "NO_DISPATCH",
            "WRONG_ROOM",
            "WRONG_GENERATION",
            "WRONG_SOURCE",
            "WRONG_CONTEXT",
        ],
    )
    fun `TS07 malformed or mismatched reservation is not dispatch permission`(fault: String) {
        val budget =
            TwoStageFakeBudget().apply {
                transform = { reservation ->
                    when (fault) {
                        "UNVERIFIED" -> reservation.copy(quote = reservation.quote.copy(verified = false))
                        "NO_EVIDENCE" -> reservation.copy(quote = reservation.quote.copy(evidenceFingerprint = ""))
                        "WRONG_PROFILE" -> reservation.copy(quote = reservation.quote.copy(profileFingerprint = "different"))
                        "TOO_FEW_CALLS" -> reservation.copy(quote = reservation.quote.copy(maximumPhysicalCalls = 0))
                        "NO_DISPATCH" -> reservation.copy(dispatchGranted = false)
                        "WRONG_ROOM" -> reservation.copy(request = reservation.request.copy(roomId = UUID.randomUUID()))
                        "WRONG_GENERATION" -> reservation.copy(request = reservation.request.copy(executionGeneration = 99))
                        "WRONG_SOURCE" -> reservation.copy(request = reservation.request.copy(sourceFingerprint = "different"))
                        else -> reservation.copy(request = reservation.request.copy(contextFingerprint = "different"))
                    }
                }
            }
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("invalid reservation") },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(0, calls)
    }

    @Test
    fun `TS07 unavailable budget fails closed before classification`() {
        val budget = TwoStageFakeBudget().apply { reserveFailure = true }
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("budget unavailable") },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(0, calls)
    }

    @Test
    fun `TS08 known token observations preserve output tokens and do not assert a known bill or refund`() {
        val budget = TwoStageFakeBudget()
        TwoStagePreSubmissionCheckProvider(
            AmbiguityClassificationPort { TwoStageFixtures.clear() },
            PreSubmissionCheckProvider { error("CLEAR") },
            budget,
        ).check(TwoStageFixtures.request())
        val observation = budget.observations.single().second
        assertEquals(AiCallOutcome.SUCCESS, observation.outcome)
        assertEquals(17L, observation.inputTokens)
        assertEquals(4L, observation.outputTokens)
        assertTrue(observation.usageComplete)
        assertFalse(observation.billedCostKnown)
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @Test
    fun `TS08 missing usage stays unknown rather than becoming zero`() {
        val budget = TwoStageFakeBudget()
        TwoStagePreSubmissionCheckProvider(
            AmbiguityClassificationPort { AmbiguityClassificationResult(AmbiguityAssessment.CLEAR, confidence = 0.99) },
            PreSubmissionCheckProvider { error("CLEAR") },
            budget,
        ).check(TwoStageFixtures.request())
        val observation = budget.observations.single().second
        assertNull(observation.inputTokens)
        assertNull(observation.outputTokens)
        assertFalse(observation.usageComplete)
        assertFalse(observation.billedCostKnown)
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @ParameterizedTest
    @ValueSource(strings = ["TIMEOUT", "CONNECTION_FAILED"])
    fun `TS08 dispatched failures record unknown cost and retain the entire held quota`(failure: String) {
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { throw AmbiguityClassificationException(failure) },
                PreSubmissionCheckProvider { error("failure cannot fallback") },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        val observation = budget.observations.single().second
        assertEquals(if (failure == "TIMEOUT") AiCallOutcome.TIMED_OUT else AiCallOutcome.FAILED, observation.outcome)
        assertNull(observation.inputTokens)
        assertNull(observation.outputTokens)
        assertFalse(observation.billedCostKnown)
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @Test
    fun `TS08 refusal is recorded without clearing its outstanding hold`() {
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    AmbiguityClassificationResult(AmbiguityAssessment.INDETERMINATE, AmbiguityClassificationReason.REFUSED)
                },
                PreSubmissionCheckProvider { error("refused") },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(
            AiCallOutcome.REFUSED,
            budget.observations
                .single()
                .second.outcome,
        )
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @Test
    fun `TS08 generator error retains count generation bundle hold and records both stages`() {
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.review() },
                PreSubmissionCheckProvider { throw PrecheckProviderException("CONNECTION_FAILED") },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(3, budget.held(TwoStageFixtures.roomId))
        assertEquals(2, budget.observations.size)
        assertEquals(
            AiCallOutcome.FAILED,
            budget.observations
                .last()
                .second.outcome,
        )
        assertFalse(
            budget.observations
                .last()
                .second.billedCostKnown,
        )
    }

    @Test
    fun `TS08 failed cost recording cannot return CLEAR or start another external stage`() {
        val budget = TwoStageFakeBudget().apply { recordFailure = true }
        var generators = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.review() },
                PreSubmissionCheckProvider {
                    generators++
                    PrecheckProviderResult(PrecheckVerdict.CLEAR)
                },
                budget,
            )
        assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) }
        assertEquals(0, generators)
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @Test
    fun `TS08 stage-specific token and model observations remain separate from the terminal result`() {
        val budget = TwoStageFakeBudget()
        val terminal =
            PrecheckProviderResult(
                PrecheckVerdict.CLEAR,
                inputTokens = 23,
                outputTokens = 100,
                provider = "SYNTHETIC_GENERATOR",
                model = "generator-profile",
            )
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.review().copy(outputTokens = 2000) },
                PreSubmissionCheckProvider { terminal },
                budget,
            )
        assertEquals(terminal, provider.check(TwoStageFixtures.request()))
        val first = budget.observations[0].second
        val second = budget.observations[1].second
        assertEquals("OPENAI_DECISIONS", first.provider)
        assertEquals("gpt-6-luna", first.model)
        assertEquals(2000L, first.outputTokens)
        assertEquals("SYNTHETIC_GENERATOR", second.provider)
        assertEquals("generator-profile", second.model)
        assertEquals(100L, second.outputTokens)
        assertFalse(first.billedCostKnown)
        assertFalse(second.billedCostKnown)
        assertFalse(second.usageComplete)
    }

    @Test
    fun `TS09 replaying an identical execution does not grant another dispatch`() {
        val budget = TwoStageFakeBudget()
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("CLEAR") },
                budget,
            )
        val input = TwoStageFixtures.request()
        provider.check(input)
        assertFailsWith<PrecheckProviderException> { provider.check(input) }
        assertEquals(1, calls)
        assertEquals(1, budget.held(input.roomId!!))
    }

    @Test
    fun `TS09 generations and retries consume one shared room quota without resetting it`() {
        val budget = TwoStageFakeBudget(limitPerRoom = 2)
        var calls = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort {
                    calls++
                    TwoStageFixtures.clear()
                },
                PreSubmissionCheckProvider { error("CLEAR") },
                budget,
            )
        val input = TwoStageFixtures.request()
        provider.check(input)
        provider.check(input.copy(executionGeneration = 3))
        assertFailsWith<PrecheckProviderException> { provider.check(input.copy(executionGeneration = 4)) }
        assertEquals(2, calls)
        assertEquals(2, budget.held(input.roomId!!))
        provider.check(input.copy(roomId = UUID.randomUUID()))
        assertEquals(3, calls)
    }

    @Test
    fun `TS09 source and context changes remain distinct fingerprints without new room quota`() {
        val budget = TwoStageFakeBudget()
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.clear() },
                PreSubmissionCheckProvider { error("CLEAR") },
                budget,
            )
        val input = TwoStageFixtures.request()
        provider.check(input)
        provider.check(input.copy(checkId = UUID.randomUUID(), rawText = input.rawText + " 단 저녁은 제외"))
        provider.check(input.copy(checkId = UUID.randomUUID(), referenceAt = input.referenceAt.plusSeconds(1)))
        val requests = budget.requests
        assertNotEquals(requests[0].sourceFingerprint, requests[1].sourceFingerprint)
        assertEquals(requests[0].contextFingerprint, requests[1].contextFingerprint)
        assertEquals(requests[0].sourceFingerprint, requests[2].sourceFingerprint)
        assertNotEquals(requests[0].contextFingerprint, requests[2].contextFingerprint)
        assertEquals(3, budget.held(input.roomId!!))
    }

    @Test
    fun `TS09 concurrent duplicate executions permit one external call under the fake atomic budget`() {
        val budget = TwoStageFakeBudget()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val provider =
                TwoStagePreSubmissionCheckProvider(
                    AmbiguityClassificationPort {
                        calls.incrementAndGet()
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        TwoStageFixtures.clear()
                    },
                    PreSubmissionCheckProvider { error("CLEAR") },
                    budget,
                )
            val first = executor.submit<Any> { provider.check(TwoStageFixtures.request()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Any> { assertFailsWith<PrecheckProviderException> { provider.check(TwoStageFixtures.request()) } }
            second.get(5, TimeUnit.SECONDS)
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals(1, calls.get())
            assertEquals(1, budget.held(TwoStageFixtures.roomId))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

/** Explicit in-memory synthetic oracle only. There is deliberately no refund API or production amount. */
internal class TwoStageFakeBudget(
    private val limitPerRoom: Int = 100,
) : AiCallBudgetPort {
    val requests = CopyOnWriteArrayList<AiCallBudgetRequest>()
    val observations = CopyOnWriteArrayList<Pair<AiCallBudgetReservation, AiCallObservation>>()
    private val holds = mutableMapOf<UUID, Int>()
    private val dispatched = mutableSetOf<List<Any>>()
    var denyStage: AiCallStage? = null
    var reserveFailure = false
    var recordFailure = false
    var onReserve: (AiCallBudgetRequest) -> Unit = {}
    var onRecord: (AiCallBudgetReservation) -> Unit = {}
    var transform: (AiCallBudgetReservation) -> AiCallBudgetReservation = { it }

    @Synchronized
    override fun reserve(request: AiCallBudgetRequest): AiCallBudgetReservation? {
        requests += request
        onReserve(request)
        if (reserveFailure) error("SYNTHETIC_BUDGET_UNAVAILABLE")
        val key = listOf(request.roomId, request.executionId, request.executionGeneration, request.stage)
        if (request.stage == denyStage ||
            key in dispatched ||
            held(request.roomId) + request.maximumPhysicalCalls > limitPerRoom
        ) {
            return null
        }
        dispatched += key
        holds[request.roomId] = held(request.roomId) + request.maximumPhysicalCalls
        return transform(
            AiCallBudgetReservation(
                UUID.randomUUID(),
                request,
                AiCallQuote(request.profileFingerprint, "synthetic-verified-proof-no-real-price", request.maximumPhysicalCalls, true),
                true,
            ),
        )
    }

    @Synchronized
    override fun record(
        reservation: AiCallBudgetReservation,
        observation: AiCallObservation,
    ) {
        onRecord(reservation)
        if (recordFailure) error("SYNTHETIC_RECORD_UNAVAILABLE")
        observations += reservation to observation
    }

    @Synchronized
    fun held(roomId: UUID) = holds[roomId] ?: 0

    companion object {
        fun sampleRequest() =
            AiCallBudgetRequest(
                TwoStageFixtures.roomId,
                TwoStageFixtures.checkId,
                2,
                AiCallStage.CLASSIFICATION,
                AiCallPurpose.PRECHECK_CLASSIFICATION,
                "synthetic-source-hash",
                "synthetic-context-hash",
                "synthetic-classifier-profile",
                1,
            )
    }
}
