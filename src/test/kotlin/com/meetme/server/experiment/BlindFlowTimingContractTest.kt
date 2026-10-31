package com.meetme.server.experiment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class BlindFlowTimingContractTest {
    @Test
    fun `failure retains caller timing without replacing exception or exposing its message`() {
        val id = UUID.randomUUID()
        val failure = IllegalStateException("synthetic body must remain private")
        val caught =
            assertThrows(IllegalStateException::class.java) {
                BlindFlowApplication.finalTiming(Duration.ofSeconds(27), id) { throw failure }
            }
        assertSame(failure, caught)
        val event = BlindFlowApplication.events.single { it["executionId"] == id }
        assertEquals("FAILED", event["outcome"])
        assertEquals(Duration.ofSeconds(27).toNanos(), event["callerBudgetNanos"])
        assertTrue(event["elapsedNanos"] as Long >= 0)
        assertEquals(setOf("stage", "executionId", "elapsedNanos", "callerBudgetNanos", "outcome"), event.keys)
    }

    @Test
    fun `success retains result and records caller remaining budget`() {
        val id = UUID.randomUUID()
        val result = Any()
        assertSame(result, BlindFlowApplication.finalTiming(Duration.ofMillis(800), id) { result })
        val event = BlindFlowApplication.events.single { it["executionId"] == id }
        assertEquals("SUCCEEDED", event["outcome"])
        assertEquals(800_000_000L, event["callerBudgetNanos"])
    }
}
