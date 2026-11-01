package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Test-only programmable session; no provider request or paid launch entrypoint of its own. */
internal class ActivationElevenHarness(
    approval: ActivationElevenApproval,
    claimsDirectory: Path,
    ledgerPath: Path,
    private val verifySnapshot: () -> Boolean,
) {
    private val budget = ActivationElevenBudget.open(approval, claimsDirectory, ledgerPath)
    private val active = AtomicReference<Stage?>()
    private val installed = AtomicReference<AiHttpDispatchGate?>()

    fun sourceFirst(delegate: AiRuntimeDispatchGuardPort): AiRuntimeDispatchGuardPort {
        val source = budget.sourceFirst(delegate)
        return AiRuntimeDispatchGuardPort { request, at ->
            try {
                val stage = boundStage()
                if (request.calls.size != 1 ||
                    request.calls.single().operation != stage.operation
                ) {
                    reject(ActivationElevenStop.INVALID_METADATA)
                }
                source.authorize(request, at)
            } catch (_: Throwable) {
                reject(ActivationElevenStop.INVALID_METADATA)
            }
        }
    }

    fun install(gate: AiHttpDispatchGate) {
        try {
            if (!gate.properties.enabled || !installed.compareAndSet(null, gate)) reject(ActivationElevenStop.INVALID_METADATA)
            gate.installExperimentWireHook(
                object : AiHttpExperimentWireHook {
                    override fun callStarted(call: AiHttpExperimentCall) {
                        boundCall(call)
                        budget.callStarted(call)
                    }

                    override fun beforeHeaders(call: AiHttpExperimentCall) {
                        boundCall(call)
                        budget.beforeHeaders(call)
                    }

                    override fun headersEmitted(call: AiHttpExperimentCall) {
                        boundCall(call)
                        budget.headersEmitted(call)
                    }

                    override fun callFinished(
                        call: AiHttpExperimentCall,
                        httpStatus: Int?,
                        failure: AiHttpExperimentTransportFailure?,
                    ) {
                        // Retain late observations and consumed/unknown attempts after a global stop.
                        budget.callFinished(call, httpStatus, failure)
                    }
                },
            )
        } catch (_: Throwable) {
            reject(ActivationElevenStop.COVERAGE_UNKNOWN)
        }
    }

    fun <T> execute(
        operation: AiPhysicalOperation,
        action: () -> T,
        validate: (T) -> ActivationElevenValidation,
    ): T {
        checkpoint()
        if (installed.get() == null) reject(ActivationElevenStop.INVALID_METADATA)
        val stage = Stage(operation)
        if (!active.compareAndSet(null, stage)) reject(ActivationElevenStop.COVERAGE_UNKNOWN)
        try {
            boundStage()
            val before = records().map { it.getValue("callId") }.toSet()
            val result = action()
            checkpoint()
            val added = records().filter { it["callId"] !in before }
            if (added.size != 1) reject(ActivationElevenStop.COVERAGE_UNKNOWN)
            val record = added.single()
            if (record["operation"] != operation.name ||
                record["headersEmitted"] != true ||
                record["finished"] != true ||
                record["validated"] != false
            ) {
                reject(ActivationElevenStop.COVERAGE_UNKNOWN)
            }
            val verdict = validate(result)
            budget.validateFinished(UUID.fromString(record["callId"] as String), verdict)
            checkpoint()
            return result
        } catch (_: Throwable) {
            // Do not expose arbitrary provider/body/header/user/verifier error text or cause.
            reject(ActivationElevenStop.COVERAGE_UNKNOWN)
        } finally {
            active.compareAndSet(stage, null)
        }
    }

    fun checkpoint() = budget.checkpoint()

    fun assertComplete() {
        if (active.get() != null) reject(ActivationElevenStop.COVERAGE_UNKNOWN)
        budget.assertComplete()
    }

    fun snapshot(): Map<String, Any?> = budget.snapshot()

    private fun boundCall(call: AiHttpExperimentCall) {
        if (boundStage().operation != call.operation) reject(ActivationElevenStop.INVALID_METADATA)
    }

    private fun boundStage(): Stage {
        checkpoint()
        val stage = active.get() ?: reject(ActivationElevenStop.INVALID_METADATA)
        val verified =
            try {
                verifySnapshot()
            } catch (_: Throwable) {
                false
            }
        if (!verified) reject(ActivationElevenStop.INVALID_METADATA)
        return stage
    }

    @Suppress("UNCHECKED_CAST")
    private fun records(): List<Map<String, Any?>> = budget.snapshot().getValue("calls") as List<Map<String, Any?>>

    private fun reject(reason: ActivationElevenStop): Nothing {
        budget.stop(reason)
        throw AiHttpDispatchException()
    }

    private data class Stage(
        val operation: AiPhysicalOperation,
        val id: UUID = UUID.randomUUID(),
    )
}
