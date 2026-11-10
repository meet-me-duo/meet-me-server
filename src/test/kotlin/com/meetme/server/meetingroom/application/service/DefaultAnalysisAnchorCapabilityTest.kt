package com.meetme.server.meetingroom.application.service

import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.domain.ClosureReason
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import com.meetme.server.submission.domain.Submission
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Default eligibility only: unsupported historical V1 anchor meanings retain Legacy without rewriting native source. */
class DefaultAnalysisAnchorCapabilityTest {
    @BeforeEach fun activeTransaction() = TransactionSynchronizationManager.setActualTransactionActive(true)

    @AfterEach fun clearTransaction() = TransactionSynchronizationManager.clear()

    @Test
    fun `DACAP01 a different local day V1 accepted anchor keeps the full cohort on Legacy`() {
        val f = Fixture()
        val source = f.f.current.toList()
        `when`(f.prechecks.anchor(source[0].latest.id.value)).thenReturn(source[0].latest.createdAt.minusSeconds(86400))
        val closed = f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
        assertTrue(f.f.frozen.isEmpty())
        assertEquals(source, f.f.current)
        val run =
            f.f.recordedRuns.values
                .single()
        assertEquals(source.map { it.latest.id }.toSet(), run.batch.submissionVersionIds.toSet())
        assertEquals(CoordinationStatus.QUEUED, run.status)
        assertEquals(run.id.value, closed.activeRunId)
        assertEquals(
            CollectionClosureService.STRUCTURING_REQUESTED,
            f.f.events
                .single()
                .eventType,
        )
    }

    @Test
    fun `DACAP02 no accepted precheck anchor identifies supported direct natural language input`() {
        val f = Fixture()
        f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
        f.assertWeekly()
    }

    @Test
    fun `DACAP03 different instants on the same room local day retain V5 despite different UTC dates`() {
        val f = Fixture()
        val first = f.at(f.f.current[0], Instant.parse("2027-01-01T00:00:00Z"))
        f.f.current = listOf(first, f.f.current[1])
        // Both are January1 in Asia/Seoul, while their UTC dates differ.
        `when`(f.prechecks.anchor(first.latest.id.value)).thenReturn(Instant.parse("2026-12-31T23:00:00Z"))
        f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
        f.assertWeekly()
    }

    @Test
    fun `DACAP04 the same UTC date does not hide different local days at the room timezone boundary`() {
        val f = Fixture()
        // Default source is December31 23:59 UTC = January1 in Seoul; this anchor is still December31 in Seoul.
        `when`(
            f.prechecks.anchor(
                f.f.current[0]
                    .latest.id.value,
            ),
        ).thenReturn(Instant.parse("2026-12-31T14:59:00Z"))
        f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
        assertTrue(f.f.frozen.isEmpty())
    }

    @Test
    fun `DACAP05 V2 evidence authority bypasses historical V1 precheck anchor probing`() {
        val f = Fixture()
        f.f.current =
            f.f.current.map { source ->
                Submission.restore(
                    source.id,
                    source.roomId,
                    source.participantId,
                    source.latest.copy(
                        evidence =
                            InputEvidence(
                                1,
                                source.latest.createdAt.minusSeconds(86400),
                                interpretation = InputInterpretation.CONFIRMED,
                            ),
                    ),
                )
            }
        f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
        f.assertWeekly()
        verifyNoInteractions(f.prechecks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["LEGACY", "DSL_STRUCTURAL_V5"])
    fun `DACAP06 explicit selections keep their existing behavior without probing historical anchors`(selection: String) {
        val f = Fixture()
        `when`(
            f.prechecks.anchor(
                f.f.current[0]
                    .latest.id.value,
            ),
        ).thenReturn(Instant.parse("2026-12-30T00:00:00Z"))
        val choice = FinalAnalysisContractSelection.valueOf(selection)
        f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now, choice)
        if (choice == FinalAnalysisContractSelection.LEGACY) assertTrue(f.f.frozen.isEmpty()) else f.assertWeekly()
        verifyNoInteractions(f.prechecks)
    }

    @Test
    fun `DACAP07 an already closed Legacy room stays unchanged without any new capability probe`() {
        val f = Fixture()
        val closed = f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now, FinalAnalysisContractSelection.LEGACY)
        val runs = f.f.recordedRuns.toMap()
        val events = f.f.events.toList()
        val result = f.service.close(closed, ClosureReason.MANUAL, 2, f.f.now.plusSeconds(1))
        assertSame(closed, result)
        assertEquals(runs, f.f.recordedRuns)
        assertEquals(events, f.f.events)
        verifyNoInteractions(f.prechecks)
    }

    @ParameterizedTest
    @ValueSource(strings = ["manual", "mixed", "insufficient"])
    fun `DACAP08 input modes that cannot select default V5 do not probe precheck anchors`(mode: String) {
        val f = Fixture()
        f.f.current =
            when (mode) {
                "manual" -> listOf(f.f.submission(1, false), f.f.submission(2, false))
                "mixed" -> listOf(f.f.submission(1, true), f.f.submission(2, false))
                else -> f.f.current.take(1)
            }
        f.service.close(f.f.room, ClosureReason.MANUAL, f.f.current.size, f.f.now)
        assertTrue(f.f.frozen.isEmpty())
        verifyNoInteractions(f.prechecks)
    }

    @Test
    fun `DACAP09 a genuinely future source with a mismatched anchor is rejected rather than downgraded to Legacy`() {
        val f = Fixture()
        val future = f.at(f.f.current[0], f.f.now.plusSeconds(2))
        f.f.current = listOf(future, f.f.current[1])
        `when`(f.prechecks.anchor(future.latest.id.value)).thenReturn(Instant.parse("2026-12-30T00:00:00Z"))
        assertFailsWith<IllegalArgumentException> { f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now) }
    }

    @Test
    fun `DACAP10 anchor read infrastructure failures propagate instead of masquerading as absent anchors or Legacy fallback`() {
        val f = Fixture()
        val failure = DataAccessResourceFailureException("Synthetic anchor read outage")
        `when`(
            f.prechecks.anchor(
                f.f.current[0]
                    .latest.id.value,
            ),
        ).thenThrow(failure)
        val actual =
            assertFailsWith<DataAccessResourceFailureException> {
                f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
            }
        assertSame(failure, actual)
        assertTrue(f.f.events.isEmpty())
    }

    @Test
    fun `DACAP11 the historical default Legacy never probes anchors even when the repository is present`() {
        val f = Fixture(default = FinalAnalysisContractSelection.LEGACY)
        f.service.close(f.f.room, ClosureReason.MANUAL, 2, f.f.now)
        assertTrue(f.f.frozen.isEmpty())
        verifyNoInteractions(f.prechecks)
    }

    private class Fixture(
        default: FinalAnalysisContractSelection = FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
    ) {
        val f = DefaultAnalysisClosureFixture()
        val prechecks = mock(PrecheckRepository::class.java)
        val service =
            CollectionClosureService(
                f.rooms,
                f.submissions,
                f.runs,
                f.outbox,
                f.ids,
                f.selection,
                defaultAnalysisContract = default,
                prechecks = prechecks,
            )

        fun at(
            source: Submission,
            createdAt: Instant,
        ): Submission = Submission.restore(source.id, source.roomId, source.participantId, source.latest.copy(createdAt = createdAt))

        fun assertWeekly() {
            val choice =
                f.frozen.values
                    .single()
                    .choice as FinalAnalysisRouteChoice.DslStructuralV5
            assertEquals("schedule-dsl-evidence-response-5", choice.identity.responseContractVersion)
            assertEquals("schedule-structural-union-1", choice.policy.version)
        }
    }
}
