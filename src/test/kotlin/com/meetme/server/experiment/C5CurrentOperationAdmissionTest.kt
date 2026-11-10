package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Synthetic original grants and admission counters only; no SDK/HTTP/DB or completed-run recovery is exercised. */
class C5CurrentOperationAdmissionTest {
    @TempDir lateinit var directory: Path

    private val room = UUID(0, 810)
    private val now = Instant.parse("2027-02-01T00:00:00Z")
    private val deadline = now.plusSeconds(15)

    @Test
    fun `COA01 an original-authorized child of the same final operation can proceed while driver checkpoint remains strict`() {
        val signals = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { signals.toList() }
        val original = mock(JdbcAiBudgetExecutionFence::class.java)
        val first = request(AiPhysicalOperation.FINAL_PRIMARY, 1)
        val second = request(AiPhysicalOperation.FINAL_PRIMARY, 2)
        `when`(original.authorize(first, now)).thenReturn(deadline)
        `when`(original.authorize(second, now)).thenReturn(deadline)
        val journal = C5HttpWireJournal(directory.resolve("same-final"))
        journal.arm(room)
        var admissions = 0
        val fixtures =
            ExperimentalLiveHttpEvaluationTest.LiveFixtures {
                admissions++
                true
            }
        val guard = fixtures.boundedActualDispatch(original, LiveEvaluationAdmission(safety), journal)

        assertSame(deadline, guard.authorize(first, now))
        assertEquals(1, admissions)
        signals += LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")
        assertSame(deadline, guard.authorize(second, now))
        assertEquals(2, admissions)
        verify(original).authorize(first, now)
        verify(original).authorize(second, now)
        val stopped = assertFailsWith<LiveEvaluationTechnicalStop> { safety.checkpoint() }
        assertEquals("SERVER", stopped.barrier.failureCode)
        assertEquals(0, (journal.snapshot().getValue("physicalCount") as Number).toInt())
    }

    @Test
    fun `COA02 precheck keeps the existing technical checkpoint and never reaches the extra admission callback`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("PRECHECK_CLASSIFICATION", parserFailureCode = "SERVER")) }
        val original = mock(JdbcAiBudgetExecutionFence::class.java)
        val request = request(AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1)
        `when`(original.authorize(request, now)).thenReturn(deadline)
        val journal = C5HttpWireJournal(directory.resolve("precheck-strict"))
        journal.arm(room)
        var admissions = 0
        val guard =
            ExperimentalLiveHttpEvaluationTest
                .LiveFixtures {
                    admissions++
                    true
                }.boundedActualDispatch(original, LiveEvaluationAdmission(safety), journal)

        val stopped = assertFailsWith<LiveEvaluationTechnicalStop> { guard.authorize(request, now) }
        assertEquals("SERVER", stopped.barrier.failureCode)
        assertEquals(0, admissions)
        verify(original).authorize(request, now)
        assertEquals(0, (journal.snapshot().getValue("physicalCount") as Number).toInt())
    }

    @Test
    fun `COA03 a missing original final grant never registers journal source or calls extra admission`() {
        val safety = LiveEvaluationSafety { error("No source grant exists to inspect") }
        val original = mock(JdbcAiBudgetExecutionFence::class.java)
        val request = request(AiPhysicalOperation.FINAL_PRIMARY, 1)
        `when`(original.authorize(request, now)).thenReturn(null)
        val journal = spy(C5HttpWireJournal(directory.resolve("missing-source")))
        journal.arm(room)
        var admissions = 0
        val guard =
            ExperimentalLiveHttpEvaluationTest
                .LiveFixtures {
                    admissions++
                    true
                }.boundedActualDispatch(original, LiveEvaluationAdmission(safety), journal)
        clearInvocations(journal)

        assertNull(guard.authorize(request, now))
        assertEquals(0, admissions)
        verify(original).authorize(request, now)
        verifyNoInteractions(journal)
        assertEquals(0, (journal.snapshot().getValue("physicalCount") as Number).toInt())
    }

    private fun request(
        operation: AiPhysicalOperation,
        ordinal: Int,
    ): AiBudgetReserveRequest {
        val final = operation == AiPhysicalOperation.FINAL_PRIMARY
        return AiBudgetReserveRequest(
            AiBudgetExecution(
                room,
                if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                UUID(0, if (final) 811 else 812),
                1,
                UUID(0, 813),
                0,
                "c".repeat(64),
                "d".repeat(64),
            ),
            listOf(
                AiPhysicalCallSpec(
                    operation,
                    ordinal,
                    ordinal.toString(16).padStart(64, '0'),
                    "b".repeat(64),
                    if (final) UUID(0, 820 + ordinal.toLong()) else null,
                ),
            ),
        )
    }
}
