package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.application.service.ClaimedPrecheck
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import com.meetme.server.precheck.application.service.PrecheckProcessingService
import com.meetme.server.precheck.domain.PrecheckCandidate
import com.meetme.server.precheck.domain.PrecheckExecution
import com.meetme.server.precheck.domain.PrecheckStatus
import com.meetme.server.shared.application.port.output.AiCallOutcome
import com.meetme.server.shared.application.port.output.AiCallStage
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TS08 clarified: classify usage belongs to its observation, never the legacy generation output-cap field. */
class TwoStagePrecheckTerminalUsageWorkerTest {
    @ParameterizedTest
    @ValueSource(longs = [4, 2049])
    fun `TS08 classifier CLEAR completes through the actual worker while known usage stays in its budget record`(output: Long) {
        val budget = TwoStageFakeBudget()
        var generators = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.clear().copy(outputTokens = output) },
                PreSubmissionCheckProvider {
                    generators++
                    error("classifier CLEAR must not generate")
                },
                budget,
            )
        val expected =
            PrecheckProviderResult(
                PrecheckVerdict.CLEAR,
                inputTokens = 17,
                outputTokens = null,
                provider = "OPENAI_DECISIONS",
                model = "gpt-6-luna",
            )
        val harness = harness(provider, expected)
        assertTrue(harness.worker.runOne())
        verify(harness.processing).publish(harness.claim, expected, null)
        assertEquals(0, generators)
        val (reservation, observation) = budget.observations.single()
        assertEquals(AiCallStage.CLASSIFICATION, reservation.request.stage)
        assertEquals(AiCallOutcome.SUCCESS, observation.outcome)
        assertEquals(output, observation.outputTokens)
        assertEquals(17L, observation.inputTokens)
        assertTrue(observation.usageComplete)
        assertFalse(observation.billedCostKnown)
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @Test
    fun `TS08 negative classifier usage is invalid before legacy N A mapping and cannot become CLEAR`() {
        val budget = TwoStageFakeBudget()
        var generators = 0
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.clear().copy(outputTokens = -1) },
                PreSubmissionCheckProvider {
                    generators++
                    error("invalid classifier usage")
                },
                budget,
            )
        val harness = harness(provider, null)
        assertTrue(harness.worker.runOne())
        verify(harness.processing).publish(harness.claim, null, "INVALID_RESPONSE")
        assertEquals(0, generators)
        assertEquals(
            AiCallOutcome.FAILED,
            budget.observations
                .single()
                .second.outcome,
        )
        assertFalse(
            budget.observations
                .single()
                .second.billedCostKnown,
        )
        assertEquals(1, budget.held(TwoStageFixtures.roomId))
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 2048, 2049])
    fun `TS08 generator output remains unmodified and subject to the existing worker generation cap`(output: Long) {
        val budget = TwoStageFakeBudget()
        var generators = 0
        val terminal =
            PrecheckProviderResult(
                PrecheckVerdict.CLEAR,
                inputTokens = 23,
                outputTokens = output,
                provider = "SYNTHETIC_GENERATOR",
                model = "generator-profile",
            )
        val provider =
            TwoStagePreSubmissionCheckProvider(
                AmbiguityClassificationPort { TwoStageFixtures.review().copy(outputTokens = 2049) },
                PreSubmissionCheckProvider {
                    generators++
                    terminal
                },
                budget,
            )
        val allowed = output == 2048L
        val harness = harness(provider, if (allowed) terminal else null)
        assertTrue(harness.worker.runOne())
        verify(harness.processing).publish(harness.claim, if (allowed) terminal else null, if (allowed) null else "INVALID_RESPONSE")
        assertEquals(1, generators)
        assertEquals(2, budget.observations.size)
        assertEquals(2049L, budget.observations[0].second.outputTokens)
        assertEquals(
            AiCallStage.GENERATION,
            budget.observations[1]
                .first.request.stage,
        )
        assertEquals(output, budget.observations[1].second.outputTokens)
        assertFalse(budget.observations[1].second.usageComplete)
        assertFalse(budget.observations[1].second.billedCostKnown)
        assertEquals(3, budget.held(TwoStageFixtures.roomId))
    }

    private fun harness(
        provider: PreSubmissionCheckProvider,
        expected: PrecheckProviderResult?,
    ): Harness {
        val repository = mock(PrecheckRepository::class.java)
        val processing = mock(PrecheckProcessingService::class.java)
        val input = TwoStageFixtures.request()
        val room =
            MeetingRoom.create(
                MeetingRoomId(TwoStageFixtures.roomId),
                InviteCode.fromEntropy(ByteArray(16) { it.toByte() }),
                "합성 stage usage",
                MeetingMode.EITHER,
                MeetingTimeZone.of("Asia/Seoul"),
                SearchDateRange.defaultFrom(TwoStageFixtures.anchor, MeetingTimeZone.of("Asia/Seoul")),
                ClosurePolicy.of(manualOnly = true),
                TwoStageFixtures.anchor,
            )
        val candidate =
            PrecheckCandidate(
                UUID.randomUUID(),
                room.id.value,
                UUID.randomUUID(),
                3,
                input.rawText,
                input.locale,
                input.referenceAt,
                input.referenceAt,
                "synthetic-owner-binding",
            )
        val check =
            PrecheckExecution(
                input.checkId,
                candidate,
                PrecheckStatus.CHECKING,
                4,
                2,
                0,
                TwoStageFixtures.anchor.plusSeconds(30),
                claimedAt = TwoStageFixtures.anchor,
            )
        val claim = ClaimedPrecheck(check, room)
        `when`(repository.unfinishedCheckIds(128)).thenReturn(emptyList())
        `when`(repository.queuedCheckIds(128)).thenReturn(listOf(check.id))
        `when`(processing.claim(check.id)).thenReturn(claim)
        if (expected != null) `when`(processing.publish(claim, expected, null)).thenReturn(PrecheckStatus.CLEAR)
        `when`(processing.publish(claim, null, "INVALID_RESPONSE")).thenReturn(PrecheckStatus.FAILED)
        return Harness(
            PreSubmissionCheckWorker(repository, processing, provider, Clock.fixed(TwoStageFixtures.anchor, ZoneOffset.UTC)),
            processing,
            claim,
        )
    }

    private data class Harness(
        val worker: PreSubmissionCheckWorker,
        val processing: PrecheckProcessingService,
        val claim: ClaimedPrecheck,
    )
}
