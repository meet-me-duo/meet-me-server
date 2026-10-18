package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.test.assertNull

/** DB01: caller hashes and synthetic evidence-looking strings never configure production authority. */
class DurableAiBudgetDefaultAuthorityTest {
    @ParameterizedTest
    @EnumSource(AiPhysicalOperation::class)
    fun `DB01 production authority never invents a quote for any physical operation`(operation: AiPhysicalOperation) {
        val execution =
            AiBudgetExecution(
                UUID.randomUUID(),
                if (operation.name.startsWith("FINAL")) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                "1".repeat(64),
                "2".repeat(64),
            )
        val request =
            AiBudgetQuoteRequest(
                execution,
                AiPhysicalCallSpec(
                    operation,
                    1,
                    "3".repeat(64),
                    "4".repeat(64),
                    if (operation.name.startsWith("FINAL")) UUID.randomUUID() else null,
                ),
                "5".repeat(64),
            )

        val quote = assertDoesNotThrow { DenyAiBudgetAuthorityRegistry().quote(request) }

        assertNull(quote, "No approved live monetary policy/profile is configured")
    }

    @Test
    fun `DB01 production authority never upgrades a caller evidence reference into a complete bill`() {
        val bill =
            assertDoesNotThrow {
                DenyAiBudgetAuthorityRegistry().completeBill(
                    AiBudgetBillRequest(UUID.randomUUID(), "1".repeat(64), "synthetic-looking-evidence"),
                )
            }

        assertNull(bill, "Usage completeness and a supplied reference do not prove actual billing")
    }
}
