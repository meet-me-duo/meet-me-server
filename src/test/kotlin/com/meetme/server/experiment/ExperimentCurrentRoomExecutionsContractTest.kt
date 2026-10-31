package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmissionIds
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** One approved room has multiple real execution scopes; no SDK, provider or network runs in this test. */
class ExperimentCurrentRoomExecutionsContractTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `three personal executions answer recheck and final share the current room budget without reviving an old room`() {
        val ledger = ExperimentCallLedger(directory.resolve("ledger.json"), UUID(0, 10), "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val oldRoom = UUID(0, 20)
        val currentRoom = UUID(0, 21)
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(oldRoom)
        val diagnostic = AiHttpExperimentAdmission("d".repeat(64), AiPhysicalOperation.FINAL_PRIMARY)
        ledger.reserveForExecution(oldRoom, UUID(0, 30), listOf(diagnostic))
        emit(ledger, AiHttpExperimentCall(UUID(0, 40), UUID(0, 30), UUID(0, 50), diagnostic.operation, 1, diagnostic.opaqueId))
        ledger.finishConnectivityAttempt(true)
        ledger.beginE2E()
        ledger.bindRoom(currentRoom)
        val admission = ExperimentCounterAdmission(ledger)
        val grant = Instant.parse("2026-11-02T03:00:15Z")
        var sourceGrants = 0
        repeat(5) { index ->
            val request = request(currentRoom, UUID(0, 100L + index), final = index == 4)
            assertSame(
                grant,
                admission.authorize({
                    sourceGrants++
                    grant
                }, request),
            )
            val spec = request.calls.single()
            val identity = AiHttpExperimentAdmissionIds.of(request, spec)
            emit(
                ledger,
                AiHttpExperimentCall(
                    UUID(0, 200L + index),
                    request.execution.executionId,
                    spec.attemptId,
                    spec.operation,
                    spec.ordinal,
                    identity.opaqueId,
                ),
            )
        }
        assertEquals(5, sourceGrants)
        assertEquals(6, ledger.snapshot().admittedCalls)
        assertEquals(6, ledger.snapshot().headersEmitted)
        assertEquals(1, ledger.snapshot().e2eRooms)
        assertEquals(
            0,
            ledger
                .snapshot()
                .errors.values
                .sum(),
        )
        var staleSdkCalls = 0
        assertFailsWith<ExperimentCallStop> {
            admission.authorize({ grant }, request(oldRoom, UUID(0, 30), final = true))
            staleSdkCalls++
        }
        assertEquals(0, staleSdkCalls)
        assertEquals(6, ledger.snapshot().headersEmitted)
    }

    private fun emit(
        ledger: ExperimentCallLedger,
        call: AiHttpExperimentCall,
    ) {
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
        ledger.callFinished(call, 200, null)
    }

    private fun request(
        room: UUID,
        execution: UUID,
        final: Boolean,
    ) = AiBudgetReserveRequest(
        AiBudgetExecution(
            room,
            if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
            execution,
            1,
            UUID(0, 80),
            0,
            "a".repeat(64),
            "b".repeat(64),
        ),
        listOf(
            AiPhysicalCallSpec(
                if (final) AiPhysicalOperation.FINAL_PRIMARY else AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                1,
                "c".repeat(64),
                "d".repeat(64),
                if (final) UUID(0, 90) else null,
            ),
        ),
    )
}
