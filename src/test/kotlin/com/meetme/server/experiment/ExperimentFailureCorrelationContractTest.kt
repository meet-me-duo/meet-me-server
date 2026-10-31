package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Explicit execution/operation/attempt/ordinal correlation; no latest-call guesses or provider bodies. */
class ExperimentFailureCorrelationContractTest {
    @TempDir lateinit var directory: Path
    private val room = UUID(0, 21)
    private val execution = UUID(0, 31)

    @Test
    fun `overall router failure finalizes only the failed generation and preserves successful Jev classification`() {
        val ledger = connected()
        val classification = admission(2, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
        ledger.reserveForExecution(room, execution, listOf(classification))
        val jev = call(2, classification)
        start(ledger, jev)
        ledger.callFinished(jev, 200, null)
        val generation = admission(3, AiPhysicalOperation.PRECHECK_GENERATION)
        ledger.reserveForExecution(room, execution, listOf(generation))
        val luna = call(3, generation)
        start(ledger, luna)
        ledger.callFinished(luna, 503, AiHttpExperimentTransportFailure.NETWORK)

        repeat(2) {
            ledger.providerFailureForExecution(execution, AiPhysicalOperation.PRECHECK_GENERATION, null, 1, "SERVER")
        }

        assertNull(ledger.failureForCall(jev.callId), "A successful classifier is not the failed router's physical target")
        assertEquals(ExperimentTechnicalCode.SERVER, ledger.failureForCall(luna.callId))
        assertEquals(1, ledger.snapshot().errors[ExperimentTechnicalCode.SERVER])
        assertEquals(0, ledger.snapshot().errors[ExperimentTechnicalCode.NETWORK] ?: 0)
        assertEquals(
            1,
            ledger
                .snapshot()
                .errors.values
                .sum(),
        )
        ledger.checkpoint()
    }

    @ParameterizedTest
    @ValueSource(strings = ["operation", "attempt", "ordinal", "ambiguous"])
    fun `zero or multiple exact physical matches stop unknown without inventing a failed call`(mismatch: String) {
        val ledger = connected()
        val first = admission(2, AiPhysicalOperation.PRECHECK_GENERATION)
        val second = admission(3, AiPhysicalOperation.PRECHECK_GENERATION)
        ledger.reserveForExecution(room, execution, if (mismatch == "ambiguous") listOf(first, second) else listOf(first))
        val one = call(2, first)
        val two = call(3, second)
        start(ledger, one)
        if (mismatch == "ambiguous") start(ledger, two)
        ledger.callFinished(one, 503, AiHttpExperimentTransportFailure.NETWORK)
        if (mismatch == "ambiguous") ledger.callFinished(two, null, AiHttpExperimentTransportFailure.TIMEOUT)
        runCatching {
            ledger.providerFailureForExecution(
                execution,
                if (mismatch == "operation") AiPhysicalOperation.PRECHECK_CLASSIFICATION else AiPhysicalOperation.PRECHECK_GENERATION,
                if (mismatch == "attempt") UUID(0, 999) else null,
                if (mismatch == "ordinal") 2 else 1,
                "SERVER",
            )
        }

        assertFailsWith<ExperimentCallStop> { ledger.checkpoint() }
        assertEquals(ExperimentStopReason.OBSERVATION_UNAVAILABLE, ledger.snapshot().stopReason)
        assertNull(ledger.failureForCall(one.callId))
        if (mismatch == "ambiguous") assertNull(ledger.failureForCall(two.callId))
    }

    private fun connected(): ExperimentCallLedger {
        val ledger = ExperimentCallLedger(directory.resolve("ledger.json"), UUID(0, 1), "a".repeat(64), "b".repeat(64), "c".repeat(64))
        val diagnosticRoom = UUID(0, 20)
        val diagnosticExecution = UUID(0, 30)
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(diagnosticRoom)
        val admission = admission(1, AiPhysicalOperation.FINAL_PRIMARY)
        ledger.reserveForExecution(diagnosticRoom, diagnosticExecution, listOf(admission))
        val call = call(1, admission).copy(executionId = diagnosticExecution, attemptId = UUID(0, 40))
        start(ledger, call)
        ledger.callFinished(call, 200, null)
        ledger.finishConnectivityAttempt(true)
        ledger.beginE2E()
        ledger.bindRoom(room)
        return ledger
    }

    private fun admission(
        number: Int,
        operation: AiPhysicalOperation,
    ) = AiHttpExperimentAdmission(number.toString(16).padStart(64, '0'), operation)

    private fun call(
        number: Int,
        admission: AiHttpExperimentAdmission,
    ) = AiHttpExperimentCall(UUID(0, number.toLong()), execution, null, admission.operation, 1, admission.opaqueId)

    private fun start(
        ledger: ExperimentCallLedger,
        call: AiHttpExperimentCall,
    ) {
        ledger.callStarted(call)
        ledger.beforeHeaders(call)
        ledger.headersEmitted(call)
    }
}
