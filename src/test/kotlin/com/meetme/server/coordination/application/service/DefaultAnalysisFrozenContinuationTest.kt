package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.AnalyzeRevisionCommand
import com.meetme.server.coordination.application.port.output.InputRevisionRoundRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.InputRevisionRound
import com.meetme.server.coordination.domain.RevisionAnalysisOutcome
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.input.GetRoomUseCase
import com.meetme.server.meetingroom.application.port.input.RoomView
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.application.service.DefaultAnalysisClosureFixture
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue218 new closure defaults never rewrite a previous run's chosen generation or policy. */
class DefaultAnalysisFrozenContinuationTest {
    @BeforeEach fun activeTransaction() = TransactionSynchronizationManager.setActualTransactionActive(true)

    @AfterEach fun clearTransaction() = TransactionSynchronizationManager.clear()

    @ParameterizedTest
    @ValueSource(strings = ["legacy", "terms3", "structural1", "weekly5"])
    fun `DFC01 forced revisions inherit the exact source descriptor rather than current weekly flags`(kind: String) {
        val f = Fixture(kind)
        val original = f.f.frozen.toMap()
        val source = f.source
        val result = f.service.analyze(f.command(force = true))
        assertEquals(RevisionAnalysisOutcome.QUEUED, result.outcome)
        assertNotEquals(source.id.value, result.analysisId)
        val successor = assertNotNull(f.f.recordedRuns[CoordinationRunId(result.analysisId)])
        assertEquals(source.batch.submissionVersionIds, successor.batch.submissionVersionIds)
        assertEquals(source, f.f.recordedRuns[source.id])
        original.forEach { (run, route) -> assertEquals(route, f.f.frozen[run]) }
        if (kind == "legacy") {
            assertTrue(result.analysisId !in f.f.frozen)
        } else {
            val choice = assertNotNull(f.f.frozen[result.analysisId]).choice
            val prior = assertNotNull(f.f.frozen[source.id.value]).choice
            when (prior) {
                is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                    val next = choice as FinalAnalysisRouteChoice.DslApprovedTermsV3
                    assertEquals(prior.identity, next.identity)
                    assertEquals(prior.policy, next.policy)
                    assertEquals(prior.vocabularyVersion, next.vocabularyVersion)
                    assertNotEquals(prior.context.runId, next.context.runId)
                }
                is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                    val next = choice as FinalAnalysisRouteChoice.DslStructuralV5
                    assertEquals(prior.identity, next.identity)
                    assertEquals(prior.policy, next.policy)
                    assertEquals(prior.vocabularyVersion, next.vocabularyVersion)
                    assertNotEquals(prior.context.runId, next.context.runId)
                }
                else -> error("Unsupported synthetic source")
            }
        }
        assertEquals(
            CollectionClosureService.STRUCTURING_REQUESTED,
            f.f.events
                .single()
                .eventType,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["legacy", "structural1", "weekly5"])
    fun `DFC02 unchanged revision reuse does not create a run route event or reinterpret the completed result`(kind: String) {
        val f = Fixture(kind)
        val source = f.f.recordedRuns.toMap()
        val routes = f.f.frozen.toMap()
        val result = f.service.analyze(f.command(force = false))
        assertEquals(RevisionAnalysisOutcome.REUSED, result.outcome)
        assertEquals(f.source.id.value, result.analysisId)
        assertEquals(source, f.f.recordedRuns)
        assertEquals(routes, f.f.frozen)
        assertTrue(f.f.events.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["legacy", "structural1", "weekly5"])
    fun `DFC03 explicit retry reuses the same batch and frozen route without adopting new closure defaults`(kind: String) {
        val f = Fixture(kind)
        val delayed = CoordinationRun.queued(f.source.id, f.source.batch).startStructuring().delayAnalysis()
        f.f.record(delayed)
        f.f.currentRoom = f.f.currentRoom.transition(activeRevisionRoundId = null)
        val routes = f.f.frozen.toMap()
        val retry =
            RetryAnalysisService(
                f.f.rooms,
                f.f.runs,
                f.f.outbox,
                f.f.ids,
                f.getRoom,
                Clock.fixed(f.f.now, ZoneOffset.UTC),
                mock(ParticipantOwnershipResolver::class.java),
                f.f.routes,
            )
        retry.retry(f.f.room.inviteCode.value, null)
        val result = assertNotNull(f.f.recordedRuns[delayed.id])
        assertEquals(delayed.id, result.id)
        assertEquals(delayed.batch, result.batch)
        assertEquals(CoordinationStatus.QUEUED, result.status)
        assertEquals(routes, f.f.frozen)
        assertEquals(1, f.f.recordedRuns.size)
        assertEquals(
            CollectionClosureService.STRUCTURING_REQUESTED,
            f.f.events
                .single()
                .eventType,
        )
    }

    private class Fixture(
        kind: String,
    ) {
        val f = DefaultAnalysisClosureFixture()
        val source =
            CoordinationRun
                .queued(
                    CoordinationRunId(UUID(0, 900)),
                    SubmissionBatch(SubmissionBatchId(UUID(0, 901)), f.room.id, f.current.map { it.latest.id }, f.now.minusSeconds(1)),
                ).startMatching()
                .complete(CandidateQuality.PARTIAL, emptyList())
        private val round = InputRevisionRound(UUID(0, 902), f.room.id, 1, source.id.value, UUID(0, 903), 0, f.now.minusSeconds(1))
        private val rounds = mock(InputRevisionRoundRepository::class.java)
        val getRoom = mock(GetRoomUseCase::class.java)
        val service: InputRevisionService

        init {
            f.record(source)
            f.frozen.clear()
            if (kind != "legacy") {
                val policy =
                    when (kind) {
                        "terms3" -> ApprovedScheduleDslMatchingPolicy.contextual()
                        "structural1" -> ScheduleDslContextModePolicy.contextual()
                        else -> ScheduleDslStructuralMatchingPolicy.union()
                    }
                val context =
                    ScheduleEvidenceSnapshotFactory()
                        .create(
                            source,
                            f.current,
                            f.room.timeZone.value,
                            f.room.searchRange.startInclusive,
                            f.room.searchRange.endExclusive,
                            policy.version,
                        ).context
                val response =
                    when (kind) {
                        "terms3" -> "schedule-dsl-response-3"
                        "structural1" -> "schedule-dsl-evidence-response-1"
                        else -> "schedule-dsl-evidence-response-5"
                    }
                val semantic = if (kind == "terms3") "schedule-ir-product-4" else "schedule-ir-product-6"
                val identity = IrExecutionIdentity(response, semantic, policy.version)
                val choice =
                    if (kind ==
                        "terms3"
                    ) {
                        FinalAnalysisRouteChoice.DslApprovedTermsV3(identity, policy, context, f.room.mode, "ko-time-terms-1")
                    } else {
                        FinalAnalysisRouteChoice.DslStructuralV5(identity, policy, context, f.room.mode, "ko-time-terms-1")
                    }
                f.frozen[source.id.value] = FrozenFinalAnalysisRoute(source.id.value, source.batch.id.value, f.room.id.value, choice, f.now)
            }
            f.currentRoom =
                f.room
                    .close(ClosureReason.MANUAL, f.now.minusSeconds(1), 2)
                    .transition(activeRunId = source.id.value, activeRevisionRoundId = round.id, revisionGeneration = 1)
            `when`(rounds.findById(f.room.id, round.id)).thenReturn(round)
            `when`(getRoom.get(f.room.inviteCode.value, null)).thenReturn(mock(RoomView::class.java))
            service =
                InputRevisionService(
                    f.rooms,
                    f.runs,
                    rounds,
                    f.submissions,
                    f.outbox,
                    f.ids,
                    getRoom,
                    Clock.fixed(f.now, ZoneOffset.UTC),
                    mock(ParticipantOwnershipResolver::class.java),
                    f.selection,
                    f.routes,
                )
        }

        fun command(force: Boolean) = AnalyzeRevisionCommand(f.room.inviteCode.value, null, round.id, UUID(0, 904), force)
    }
}
