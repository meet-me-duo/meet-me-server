package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Test-harness controls: no production gate methods, no proof of production correctness. */
class Issue123DispatchFixtureControlTest {
    @Test
    fun `fixture final raw locale agrees with independently framed immutable context`() {
        val fixture = Issue123ProviderDispatchContractTest()
        for (route in listOf(
            Issue123ProviderDispatchContractTest.Route.FINAL_PRIMARY,
            Issue123ProviderDispatchContractTest.Route.FINAL_FALLBACK,
        )) {
            val context = fixture.context(route)
            val batch = fixture.batch(context)
            val input = batch.inputs.single()
            assertEquals("ko-KR", input.locale.toLanguageTag())
            assertEquals(dispatchFramedHash("final-source-v1", input.inputRef, input.rawText), context.execution.sourceFingerprint)
            assertEquals(
                dispatchFramedHash(
                    "final-context-v1",
                    batch.timeZone.id,
                    batch.searchStartDate.toString(),
                    batch.searchEndDate.toString(),
                    input.inputRef,
                    input.locale.toLanguageTag(),
                    input.referenceDate.toString(),
                ),
                context.execution.contextFingerprint,
            )
        }
    }

    @Test
    fun `fixture A count and generation use distinct durable IDs while replay is stable and claim is one shot`() {
        val budget = DispatchBudget()
        val execution = dispatchContext().execution

        fun call(operation: AiPhysicalOperation) = AiPhysicalCallSpec(operation, 1, "synthetic-payload", "synthetic-profile")
        val aRequest = AiBudgetReserveRequest(execution, listOf(call(AiPhysicalOperation.PRECHECK_CLASSIFICATION)))
        val bRequest =
            AiBudgetReserveRequest(
                execution,
                listOf(call(AiPhysicalOperation.PRECHECK_COUNT), call(AiPhysicalOperation.PRECHECK_GENERATION)),
            )
        val firstA = assertNotNull(budget.reserve(aRequest))
        val firstB = assertNotNull(budget.reserve(bRequest))
        assertEquals(3, (firstA.calls + firstB.calls).map { it.reservationId }.toSet().size)
        assertEquals(firstA, budget.reserve(aRequest))
        assertEquals(firstB, budget.reserve(bRequest))
        for (child in firstA.calls + firstB.calls) {
            val request = AiBudgetDispatchRequest(child.reservationId, execution, child.call)
            assertNotNull(budget.claim(request))
            assertNull(budget.claim(request))
        }
    }
}
