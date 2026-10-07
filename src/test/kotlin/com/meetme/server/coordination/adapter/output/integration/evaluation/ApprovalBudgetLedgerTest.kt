package com.meetme.server.coordination.adapter.output.integration.evaluation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Independent offline checks of the evaluation-only approval boundary. No provider client or credentials.
class ApprovalBudgetLedgerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `verified usage survives another output run and duplicate identities stay consumed`() {
        open().use {
            it.reserve("case-1", "prompt-1", 100, 100_000, 197_880_000)
            it.complete(10_000, 10, 20)
        }
        open().use {
            assertEquals(1L, it.amount("provider_calls"))
            assertEquals(10_000L, it.amount("accounted_nano_usd"))
            assertEquals(10_000L, it.amount("verified_usage_nano_usd"))
            assertEquals(10L, it.amount("total_input_tokens"))
            assertEquals(20L, it.amount("total_billed_output_including_thinking_tokens"))
            assertTrue(it.hasDuplicate("case-1", "prompt-1"))
            assertFalse(it.hasDuplicate("case-1", "another-prompt"))
            assertFailsWith<IllegalArgumentException> { it.reserve("case-1", "prompt-1", 100, 100_000, 197_880_000) }
            assertEquals(1L, it.amount("provider_calls"))
        }
    }

    @Test
    fun `interrupted reservation retains full cost and prevents another call`() {
        open().use { it.reserve("case-1", "prompt-1", 100, 100_000, 197_880_000) }
        open().use {
            assertTrue(it.hasPending())
            assertEquals(197_880_000L, it.amount("accounted_nano_usd"))
            assertEquals(0L, it.amount("verified_usage_nano_usd"))
            assertFailsWith<IllegalArgumentException> { it.reserve("case-2", "prompt-2", 100, 100_000, 197_880_000) }
            assertEquals(1L, it.amount("provider_calls"))
        }
    }

    @Test
    fun `shared money limit cannot reset between runs`() {
        open().use {
            it.reserve("case-1", "prompt-1", 100, 100_000, 200_000_000)
            it.complete(200_000_000, 10, 20)
        }
        open().use {
            assertFailsWith<IllegalArgumentException> { it.reserve("case-2", "prompt-2", 100, 100_000, 100_000_001) }
            assertEquals(200_000_000L, it.amount("accounted_nano_usd"))
            assertEquals(1L, it.amount("provider_calls"))
        }
    }

    @Test
    fun `twenty five call limit persists independently of low actual cost`() {
        open().use { ledger ->
            repeat(25) { index ->
                ledger.reserve("case-$index", "prompt-$index", 100, 100_000, 1)
                ledger.complete(1, 1, 1)
            }
        }
        open().use {
            assertEquals(25L, it.amount("provider_calls"))
            assertFailsWith<IllegalArgumentException> { it.reserve("case-26", "prompt-26", 100, 100_000, 1) }
        }
    }

    @Test
    fun `another owner cannot lock the same approval until the first owner releases it`() {
        open().use {
            assertFailsWith<OverlappingFileLockException> { open().close() }
        }
        open().use { assertEquals(0L, it.amount("provider_calls")) }
    }

    @Test
    fun `changing approval policy cannot reset an existing reservation`() {
        open().use { it.reserve("case-1", "prompt-1", 100, 100_000, 197_880_000) }
        assertFailsWith<IllegalArgumentException> {
            ApprovalBudgetLedger.open("same-approval", directory.toString(), "different-policy").close()
        }
        open().use {
            assertTrue(it.hasPending())
            assertEquals(1L, it.amount("provider_calls"))
            assertEquals(197_880_000L, it.amount("accounted_nano_usd"))
        }
    }

    private fun open() = ApprovalBudgetLedger.open("same-approval", directory.toString(), "fixed-policy")
}
