package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One-shot limits and original source-first ordering, with zero provider traffic. */
class LunaFallbackOnceGuardContractTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `original source denial returns before experiment acceptance`() {
        val source = mock(JdbcAiBudgetExecutionFence::class.java)
        val request = mock(AiBudgetReserveRequest::class.java)
        val at = Instant.parse("2026-10-30T05:00:00Z")
        assertNull(LunaFallbackOnceApplication().onceSource(source, LunaFallbackOnceSettings.freeForTest(temporary)).authorize(request, at))
        verify(source).authorize(request, at)
    }

    @Test
    fun `same immutable fallback identity rechecks but different attempt and primary are refused`() {
        val f = LunaFallbackOnceSettings.freeForTest(temporary)
        val execution = mock(AiBudgetExecution::class.java)
        val call = AiPhysicalCallSpec(AiPhysicalOperation.FINAL_FALLBACK, 1, "a".repeat(64), "b".repeat(64), UUID(0, 1))
        val request = AiBudgetReserveRequest(execution, listOf(call))
        assertTrue(f.source(request))
        assertTrue(f.source(request.copy(calls = listOf(call.copy()))))
        assertFalse(f.source(request.copy(calls = listOf(call.copy(attemptId = UUID(0, 2))))))
        assertFalse(f.source(request.copy(calls = listOf(call.copy(operation = AiPhysicalOperation.FINAL_PRIMARY)))))
        assertEquals(0, f.wire.admissions)
    }

    @Test
    fun `header limiter permits one bound fallback and refuses second header and other operations`() {
        val f = LunaFallbackOnceSettings.freeForTest(temporary)
        val call = AiHttpExperimentCall(UUID(0, 1), UUID(0, 2), UUID(0, 3), AiPhysicalOperation.FINAL_FALLBACK, 1, "bound-admission")
        assertFailsWith<Exception> { f.hook.beforeHeaders(call.copy(operation = AiPhysicalOperation.FINAL_PRIMARY)) }
        assertFailsWith<Exception> { f.hook.beforeHeaders(call.copy(admissionId = null)) }
        assertEquals(0, f.wire.admissions)
        f.hook.beforeHeaders(call)
        assertEquals(1, f.wire.admissions)
        assertFailsWith<Exception> { f.hook.beforeHeaders(call) }
        assertFailsWith<Exception> { f.hook.beforeHeaders(call.copy(callId = UUID(0, 9))) }
        assertEquals(1, f.events.count { it["event"] == "HEADERS_START" })
        assertEquals(1, StandaloneGeminiOnceDiagnostic.JSON.readTree(temporary.resolve("wire-ledger.json").toFile())["admissions"].asInt())
    }
}
