package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** A transport callback is provisional until the typed producer completes causal normalization. No SDK or HTTP. */
class ExperimentPendingFailurePhaseContractTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `settled wire failure without typed finalization cannot open another diagnostic phase`() {
        val ledger = ExperimentCallLedger(directory.resolve("ledger.json"), UUID(0, 1), "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val room = UUID(0, 2)
        val execution = UUID(0, 3)
        val admission = AiHttpExperimentAdmission("d".repeat(64), AiPhysicalOperation.FINAL_PRIMARY)
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(room)
        ledger.reserveForExecution(room, execution, listOf(admission))
        val call = AiHttpExperimentCall(UUID(0, 4), execution, UUID(0, 5), admission.operation, 1, admission.opaqueId)
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
        ledger.callFinished(call, null, AiHttpExperimentTransportFailure.NETWORK)
        var secondPhaseEntered = false

        val transition =
            runCatching {
                ledger.finishConnectivityAttempt(false)
                ledger.beginConnectivityAttempt()
                secondPhaseEntered = true
            }

        assertFalse(secondPhaseEntered, "A pending normalization must stop before opening a new phase")
        assertIs<ExperimentCallStop>(transition.exceptionOrNull())
        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertFalse(ledger.snapshot().observationCoverageKnown)
    }
}
