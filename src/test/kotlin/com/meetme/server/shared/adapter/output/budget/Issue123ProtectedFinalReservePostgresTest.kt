package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR055 + agreed policy-derived protection of one future 4+1 invocation; no runtime amount decision. */
class Issue123ProtectedFinalReservePostgresTest : DurableBudgetPostgresFixture() {
    @Test
    fun `PF05 personal exposure plus protected final reserve must stay strictly below cap`() {
        val room = ready()
        val equal = request(room)
        authorize(equal, 53)
        finalReserve(equal, bi(47))
        assertNull(assertDoesNotThrow { adapter().reserve(equal) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        val below = request(room)
        authorize(below, 52)
        finalReserve(below, bi(47))
        reserve(below)
        assertEquals(bi(52), snapshot(room).held, "Protection is a virtual admission floor, not another physical hold")
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
    }

    @Test
    fun `PF06 already settled charges are included before protecting the future final invocation`() {
        val room = ready()
        val paid = request(room)
        authorize(paid, 20)
        val call = reserve(paid).calls.single()
        val permit = claim(paid, call)
        bill(call, 20, "synthetic-paid20")
        assertTrue(adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "synthetic-paid20")))
        val equal = request(room)
        authorize(equal, 33)
        finalReserve(equal, bi(47))
        assertNull(adapter().reserve(equal))
        assertEquals(bi(20), snapshot(room).settled)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "POLICY", "UNIT", "EXPIRED", "NEGATIVE", "OVERFLOW"])
    fun `PF07 missing or invalid final protection denies before writing a personal hold`(mutation: String) {
        val room = ready()
        val request = request(room)
        authorize(request, 1)
        finalReserve(request, bi(47))
        alterFinalReserve(request.calls.single()) {
            when (mutation) {
                "MISSING" -> null
                "POLICY" -> it.copy(policyFingerprint = hash("foreign-policy"))
                "UNIT" -> it.copy(unit = it.unit.copy(scale = 1))
                "EXPIRED" -> it.copy(validUntil = NOW)
                "NEGATIVE" -> it.copy(amount = bi(-1))
                "OVERFLOW" -> it.copy(amount = BigInteger.TEN.pow(38))
                else -> error("Unknown synthetic mutation")
            }
        }
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, room))
    }

    @Test
    fun `PF08 a two-child personal group pays its floor once and rejects differing protection evidence atomically`() {
        val room = ready()
        val first = request(room, AiPhysicalOperation.PRECHECK_COUNT)
        val second = request(room, AiPhysicalOperation.PRECHECK_GENERATION).calls.single()
        val group = first.copy(calls = first.calls + second)
        authorize(group, 26)
        finalReserve(group, bi(47))
        alterFinalReserve(second) { it.copy(amount = bi(46)) }
        assertNull(adapter().reserve(group), "Two new physical requests must agree on the same policy-derived protection")
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        finalReserve(group, bi(47))
        val reservation = reserve(group)
        assertEquals(2, reservation.calls.size)
        assertEquals(bi(52), snapshot(room).held, "26+26+47=99; the floor must not be applied once per child")
    }

    @Test
    fun `PF09 replay preserves IDs and exposure without requiring another floor or granting another dispatch`() {
        val room = ready()
        val request = request(room)
        authorize(request, 52)
        finalReserve(request, bi(47))
        val first = reserve(request)
        val lookupsBeforeReplay = finalReserveLookups()
        alterFinalReserve(request.calls.single()) { null }
        assertEquals(first, reserve(request), "No new hold means no new protection debit or proof requirement")
        assertEquals(lookupsBeforeReplay, finalReserveLookups(), "A complete replay must not resolve another policy proof")
        assertEquals(bi(52), snapshot(room).held)
        val call = first.calls.single()
        claim(request, call)
        assertNull(adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)))
    }

    @Test
    fun `PF09 a new zero-amount physical call still needs valid final protection`() {
        val room = ready()
        val request = request(room)
        authorize(request, 0)
        alterFinalReserve(request.calls.single()) { null }
        assertNull(adapter().reserve(request), "A zero quote is a new physical call, not an existing reservation replay")
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, room))
        finalReserve(request, bi(47))
        reserve(request)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, room))
    }

    @ParameterizedTest
    @ValueSource(strings = ["FINAL_PRIMARY", "FINAL_FALLBACK"])
    fun `PF10 final physical reservations spend existing headroom without protecting it twice`(operation: String) {
        val room = ready()
        val personal = request(room)
        authorize(personal, 52)
        finalReserve(personal, bi(47))
        reserve(personal)
        val final = request(room, AiPhysicalOperation.valueOf(operation))
        authorize(final, 47)
        alterFinalReserve(final.calls.single()) { null }
        reserve(final)
        assertEquals(bi(99), snapshot(room).held)
    }

    @Test
    fun `PF11 concurrent personal requests serialize the protected remainder rather than each spending it`() {
        val room = ready()
        val requests = List(2) { request(room) }
        requests.forEach {
            authorize(it, 30)
            finalReserve(it, bi(47))
        }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pending =
                requests.map { request ->
                    pool.submit(
                        Callable {
                            barrier.await(5, TimeUnit.SECONDS)
                            adapter().reserve(request)
                        },
                    )
                }
            val results = pending.map { assertDoesNotThrow { it.get(10, TimeUnit.SECONDS) } }
            assertEquals(1, results.count { it != null }, "Only one 30-unit request fits alongside the protected47 below cap100")
            assertEquals(bi(30), snapshot(room).held)
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, room))
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
