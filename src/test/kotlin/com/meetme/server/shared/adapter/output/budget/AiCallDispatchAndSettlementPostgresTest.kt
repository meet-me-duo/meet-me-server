package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiCallDispatchAndSettlementPostgresTest : DurableBudgetPostgresFixture() {
    @Test
    fun `DB08 reserved hold survives coordinator replacement and clock expiry without resending`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val first = reserve(request)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertNull(
            assertDoesNotThrow {
                adapter().claim(AiBudgetDispatchRequest(first.calls.single().reservationId, request.execution, first.calls.single().call))
            },
        )
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB09 committed intent is never reclaimed after its execution expires`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        claim(request, call)
        clock.advance(Duration.ofMinutes(5))
        assertNull(assertDoesNotThrow { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)) })
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB10 stale owner cannot consume a still-reserved physical call`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        fence.revoke(request.execution)
        assertNull(assertDoesNotThrow { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)) })
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB11 timeout observation retains full hold and cannot mint another physical dispatch`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        val event =
            AiBudgetObservation(
                call.reservationId,
                permit.dispatchToken,
                UUID.randomUUID(),
                AiBudgetOutcome.TIMED_OUT,
                failure = AiBudgetFailure.TIMEOUT,
            )
        assertTrue(assertDoesNotThrow { adapter().observe(event) })
        assertTrue(assertDoesNotThrow { adapter().observe(event) })
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertNull(assertDoesNotThrow { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)) })
    }

    @Test
    fun `DB13 partial known usage and complete usage both remain observations rather than money`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        val event =
            AiBudgetObservation(
                call.reservationId,
                permit.dispatchToken,
                UUID.randomUUID(),
                AiBudgetOutcome.SUCCEEDED,
                AiBudgetUsage(10, null, null, null, false),
            )
        assertTrue(assertDoesNotThrow { adapter().observe(event) })
        val complete = event.copy(eventId = UUID.randomUUID(), usage = AiBudgetUsage(10, 0, 0, 0, true))
        assertTrue(assertDoesNotThrow { adapter().observe(complete) })
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertFalse(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "not-a-trusted-bill"))
            },
        )
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB13 conflicting duplicate observation is rejected and never reduces uncertainty`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        val event = AiBudgetObservation(call.reservationId, permit.dispatchToken, UUID.randomUUID(), AiBudgetOutcome.UNKNOWN)
        assertTrue(assertDoesNotThrow { adapter().observe(event) })
        assertFalse(assertDoesNotThrow { adapter().observe(event.copy(usage = AiBudgetUsage(inputTokens = 99))) })
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB14 trusted complete bill settles once and duplicate evidence releases no extra capacity`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        bill(call, 12, "bill12")
        val settlement = AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "bill12")
        assertTrue(assertDoesNotThrow { adapter().settle(settlement) })
        assertTrue(assertDoesNotThrow { adapter().settle(settlement) })
        assertEquals(bi(12), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    @Test
    fun `DB14 quote overrun is recorded in full and freezes future room admission`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        bill(call, 120, "overrun120")
        assertTrue(
            assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "overrun120")) },
        )
        assertEquals(bi(120), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        val next = request(room)
        authorize(next, 1)
        assertNull(assertDoesNotThrow { adapter().reserve(next) })
    }

    @Test
    fun `DB14 conflicting complete bill never overwrites settled proof with a smaller charge`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        bill(call, 12, "first12")
        assertTrue(assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "first12")) })
        bill(call, 1, "conflict1")
        assertFalse(
            assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "conflict1")) },
        )
        assertEquals(bi(12), snapshot(room).settled)
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
    }

    @Test
    fun `DB16 authentic late usage and settlement attach to old call without current execution authority`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        fence.revoke(request.execution)
        clock.advance(Duration.ofMinutes(5))
        assertTrue(
            assertDoesNotThrow {
                adapter().observe(
                    AiBudgetObservation(
                        call.reservationId,
                        permit.dispatchToken,
                        UUID.randomUUID(),
                        AiBudgetOutcome.SUCCEEDED,
                        AiBudgetUsage(inputTokens = 42),
                    ),
                )
            },
        )
        bill(call, 12, "late12")
        assertTrue(assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "late12")) })
        assertEquals(bi(12), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertNull(assertDoesNotThrow { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)) })
    }

    @Test
    fun `DB16 forged dispatch nonce cannot add observations or settle another reservation`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        claim(request, call)
        val forged = UUID.randomUUID()
        assertFalse(
            assertDoesNotThrow {
                adapter().observe(AiBudgetObservation(call.reservationId, forged, UUID.randomUUID(), AiBudgetOutcome.SUCCEEDED))
            },
        )
        bill(call, 12, "validBillWrongNonce")
        assertFalse(assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(call.reservationId, forged, "validBillWrongNonce")) })
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
    }

    @Test
    fun `DB14 unsupported complete bill magnitude freezes before SQL overflow and retains full unknown hold`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        bill(call, BigInteger.TEN.pow(38), "unsupported-bill")
        assertFalse(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "unsupported-bill"))
            },
        )
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertNull(assertDoesNotThrow { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)) })
    }

    @Test
    fun `DB14 representable bill sum overflow retains prior settled and the unresolved second hold`() {
        val maximum = BigInteger.TEN.pow(38).subtract(BigInteger.ONE)
        val room = ready(maximum)
        val first = request(room)
        val second = request(room)
        authorize(first, 1)
        authorize(second, 1)
        val firstCall = reserve(first).calls.single()
        val secondCall = reserve(second).calls.single()
        val firstPermit = claim(first, firstCall)
        val secondPermit = claim(second, secondCall)
        val priorSettled = maximum.subtract(bi(10))
        bill(firstCall, priorSettled, "near-max")
        assertTrue(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(firstCall.reservationId, firstPermit.dispatchToken, "near-max"))
            },
        )
        bill(secondCall, 20, "sum-overflow")
        assertFalse(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(secondCall.reservationId, secondPermit.dispatchToken, "sum-overflow"))
            },
        )
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        assertEquals(priorSettled, snapshot(room).settled)
        assertEquals(bi(1), snapshot(room).held)
    }
}
