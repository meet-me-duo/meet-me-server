package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A bound on frozen transmitted bytes, independent of price/token authority and the historical capture profile. */
class FinalWireInputLimitTest {
    @ParameterizedTest
    @EnumSource(value = AiPhysicalOperation::class, names = ["FINAL_PRIMARY", "FINAL_FALLBACK"])
    fun `exact 256KiB final body remains admissible with its unchanged capture profile`(operation: AiPhysicalOperation) {
        val budget = DispatchBudget()
        val context = dispatchContext(final = true)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, DispatchClock())
        val body = ByteArray(262_144) { 'x'.code.toByte() }
        val profile = profile(operation)

        val prepared = PreparedAiHttpRequest.freeze(operation, 1, context.finalAttemptId, profile, body)
        gate.prepareGroup(context, listOf(prepared))

        assertContentEquals(body, prepared.copyBody())
        assertEquals(32 * 1024 * 1024, prepared.profile.captureLimitBytes, "Existing wire/profile identity remains unchanged")
        assertEquals(1, budget.reserves.size)
        assertTrue(budget.claims.isEmpty(), "Preparing bounded bytes does not grant or perform a physical dispatch")
    }

    @ParameterizedTest
    @EnumSource(value = AiPhysicalOperation::class, names = ["FINAL_PRIMARY", "FINAL_FALLBACK"])
    fun `256KiB plus one final body is rejected before any durable reserve`(operation: AiPhysicalOperation) {
        val budget = DispatchBudget()
        val context = dispatchContext(final = true)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, DispatchClock())

        assertFailsWith<AiHttpDispatchException> {
            val prepared =
                PreparedAiHttpRequest.freeze(operation, 1, context.finalAttemptId, profile(operation), ByteArray(262_145))
            gate.prepareGroup(context, listOf(prepared))
        }
        assertTrue(budget.reserves.isEmpty(), "Oversized final bytes must not consume even a zero-sized financial hold")
        assertTrue(budget.claims.isEmpty())
        assertTrue(budget.observations.isEmpty())
    }

    private fun profile(operation: AiPhysicalOperation) =
        physicalProfile(operation, "synthetic-fixed-model", URI("http://127.0.0.1:1/v1/synthetic"), 32 * 1024 * 1024)
}
