package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB01/10/14: fresh quote validity and preservation of representable known billing evidence. */
class Issue123BudgetAccountingDefensePostgresTest : DurableBudgetPostgresFixture() {
    @Test
    fun `DB10 quote expiring during actual global profile lock wait cannot create a new hold`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        fence.deadline = NOW.plusSeconds(120)
        val connection = dataSource.connection
        val pool = Executors.newSingleThreadExecutor()
        try {
            connection.autoCommit = false
            connection.createStatement().use { it.execute("LOCK TABLE ai_budget_profiles IN ACCESS EXCLUSIVE MODE") }
            val pending = pool.submit(Callable { assertDoesNotThrow { adapter().reserve(request) } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (System.nanoTime() < deadline) {
                waiting =
                    jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query ILIKE '%ai_budget_profiles%')",
                        Boolean::class.java,
                    ) ==
                    true
                if (waiting || pending.isDone)break
                Thread.sleep(20)
            }
            assertTrue(waiting, "The reserve must reach a real global profile lock before synthetic quote expiry")
            clock.advance(Duration.ofSeconds(61))
            connection.commit()
            assertNull(
                assertDoesNotThrow { pending.get(10, TimeUnit.SECONDS) },
                "Execution is still valid but the immutable quote has expired",
            )
        } finally {
            connection.rollback()
            connection.close()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id=?", Int::class.java, room))
    }

    @Test
    fun `DB14 representable complete bill remains known in accounting event even if settled total overflows`() {
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
        bill(firstCall, priorSettled, "defense-near-max")
        assertTrue(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(firstCall.reservationId, firstPermit.dispatchToken, "defense-near-max"))
            },
        )
        bill(secondCall, 20, "defense-known20")
        assertFalse(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(secondCall.reservationId, secondPermit.dispatchToken, "defense-known20"))
            },
        )
        val events =
            jdbc.queryForList(
                "SELECT bill_amount,failure,evidence_fingerprint FROM ai_call_observations WHERE reservation_id=? AND kind='BILL'",
                secondCall.reservationId,
            )
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(
            BigInteger.valueOf(20),
            assertNotNull(event["bill_amount"] as? BigDecimal).toBigIntegerExact(),
            "A known representable charge is not unknown merely because the aggregate overflows",
        )
        assertEquals("ACCOUNTING", event["failure"])
        assertEquals(hash("bill:defense-known20"), event["evidence_fingerprint"])
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        assertEquals(priorSettled, snapshot(room).settled)
        assertEquals(bi(1), snapshot(room).held)
    }

    @Test
    fun `DB14 an individually unrepresentable bill records unknown amount and accounting evidence without clipping`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        bill(call, BigInteger.TEN.pow(38), "defense-unrepresentable")
        assertFalse(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "defense-unrepresentable"))
            },
        )
        val events =
            jdbc.queryForList(
                "SELECT bill_amount,failure,evidence_fingerprint FROM ai_call_observations WHERE reservation_id=? AND kind='BILL'",
                call.reservationId,
            )
        assertEquals(1, events.size)
        assertNull(events.single()["bill_amount"])
        assertEquals("ACCOUNTING", events.single()["failure"])
        assertEquals(hash("bill:defense-unrepresentable"), events.single()["evidence_fingerprint"])
        assertEquals(AiRoomBudgetState.FROZEN, snapshot(room).state)
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
    }
}
