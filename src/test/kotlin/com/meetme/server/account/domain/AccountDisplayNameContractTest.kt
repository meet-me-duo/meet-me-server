package com.meetme.server.account.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Issue228: account display names are user-editable labels, not identity or authority. */
class AccountDisplayNameContractTest {
    @Test
    fun `DN01 trims surrounding spaces and preserves inner spaces and Unicode`() {
        assertEquals("별 빛 😀", AccountDisplayName.of("  별 빛 😀  ").value)
        assertEquals("A", AccountDisplayName.of("A").value)
        assertEquals("같은 이름", AccountDisplayName.of("같은 이름").value)
        assertEquals("👩‍💻", AccountDisplayName.of("👩‍💻").value)
    }

    @Test
    fun `DN02 limits count Unicode code points rather than UTF16 code units`() {
        listOf("가".repeat(50), "😀".repeat(50), "가😀".repeat(25)).forEach {
            assertEquals(it, AccountDisplayName.of(it).value)
        }
        listOf("가".repeat(51), "😀".repeat(51), "가😀".repeat(25) + "A").forEach {
            assertFailsWith<IllegalArgumentException> { AccountDisplayName.of(it) }
        }
    }

    @Test
    fun `DN03 rejects empty and whitespace-only names`() {
        listOf("", " ", "    ", "\u2003\u2003").forEach {
            assertFailsWith<IllegalArgumentException> { AccountDisplayName.of(it) }
        }
    }

    @Test
    fun `DN04 rejects control characters anywhere including surrounding input`() {
        listOf("A\u0000B", "A\nB", "A\rB", "A\tB", "A\u007fB", "A\u0085B", "\nA", "A\n").forEach {
            assertFailsWith<IllegalArgumentException> { AccountDisplayName.of(it) }
        }
    }
}
