package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic fixed-point TEST_SUBUNIT amounts are accounting examples, never a live budget decision. */
class PostgresAiCallBudgetContractTest : DurableBudgetPostgresFixture() {
    @Test
    fun `DB01 absent room is denied without lazy financial bootstrap`() {
        val request = request(UUID.randomUUID())
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertNull(assertDoesNotThrow { adapter().snapshot(request.execution.roomId) })
    }

    @Test
    fun `DB01 an existing room without vetted policy cannot reserve`() {
        val room = room()
        val request = request(room)
        fence.allow(request.execution)
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
    }

    @Test
    fun `DB01 ready synthetic coverage without an authority quote remains denied`() {
        val room = ready()
        val request = request(room)
        fence.allow(request.execution)
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    @ParameterizedTest
    @ValueSource(strings = ["EXPIRED", "PROFILE", "PAYLOAD", "POLICY", "UNIT", "NEGATIVE", "EMPTY_EVIDENCE"])
    fun `DB01 immutable quote mismatch fails before any money hold`(mismatch: String) {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        alterQuote(request.calls.single()) { quote ->
            when (mismatch) {
                "EXPIRED" -> quote.copy(validUntil = NOW)
                "PROFILE" -> quote.copy(profileFingerprint = hash("wrong-profile"))
                "PAYLOAD" -> quote.copy(wholePayloadFingerprint = hash("wrong-payload"))
                "POLICY" -> quote.copy(policyFingerprint = hash("wrong-policy"))
                "UNIT" -> quote.copy(unit = quote.unit.copy(code = "OTHER_TEST_UNIT"))
                "NEGATIVE" -> quote.copy(maximumAmount = bi(-1))
                "EMPTY_EVIDENCE" -> quote.copy(evidenceFingerprint = "")
                else -> error("Unknown synthetic mutation")
            }
        }
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
    }

    @Test
    fun `DB02 identical reservation replay has one immutable hold and no dispatch permission`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val first = reserve(request)
        val replay = reserve(request)
        assertEquals(first, replay)
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
        assertEquals(1, first.calls.size)
    }

    @Test
    fun `DB02 changed payload for the same physical identity cannot reprice or replace its reservation`() {
        val room = ready()
        val original = request(room)
        authorize(original, 30)
        val reservation = reserve(original)
        val changed = original.copy(calls = listOf(original.calls.single().copy(wholePayloadFingerprint = hash("changed"))))
        authorize(changed, 1)
        assertNull(assertDoesNotThrow { adapter().reserve(changed) })
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(reservation, reserve(original))
    }

    @Test
    fun `DB03 strict exposure below cap admits 99 and rejects equality 100`() {
        val room = ready()
        val settled = request(room)
        authorize(settled, 40)
        val paid = reserve(settled).calls.single()
        val permit = claim(settled, paid)
        bill(paid, 40, "settled40")
        assertTrue(
            assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(paid.reservationId, permit.dispatchToken, "settled40")) },
        )
        val held = request(room)
        authorize(held, 30)
        reserve(held)
        val equal = request(room)
        authorize(equal, 30)
        assertNull(assertDoesNotThrow { adapter().reserve(equal) })
        val below = request(room)
        authorize(below, 29)
        reserve(below)
        assertEquals(bi(40), snapshot(room).settled)
        assertEquals(bi(59), snapshot(room).held)
    }

    @Test
    fun `DB03 very large exact integer quotes never wrap into available capacity`() {
        val room = ready()
        val request = request(room)
        authorize(request, BigInteger.TEN.pow(100))
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    @Test
    fun `DB04 independent PG clients race precheck and final for one remaining hold`() {
        val room = ready()
        val precheck = request(room)
        val final = request(room, AiPhysicalOperation.FINAL_PRIMARY)
        authorize(precheck, 60)
        authorize(final, 60)
        val results = race({ adapter().reserve(precheck) }, { adapter().reserve(final) })
        assertEquals(1, results.count { it != null })
        assertEquals(bi(60), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
    }

    @Test
    fun `DB05 concurrent identity replay across independent clients creates only one hold`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val results = race({ adapter().reserve(request) }, { adapter().reserve(request) })
        assertTrue(results.all { it != null })
        assertEquals(results[0], results[1])
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB05 a consumed dispatch claim is not renewed by reservation replay or a new coordinator`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val dispatch = AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)
        val results = race({ adapter().claim(dispatch) }, { adapter().claim(dispatch) })
        assertEquals(1, results.count { it != null })
        assertEquals(call, reserve(request).calls.single())
        assertNull(assertDoesNotThrow { adapter().claim(dispatch) })
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB12 count and generation reserve atomically with independent child quotes`() {
        val room = ready()
        val count = request(room, AiPhysicalOperation.PRECHECK_COUNT)
        val genCall =
            count.calls.single().copy(
                operation = AiPhysicalOperation.PRECHECK_GENERATION,
                wholePayloadFingerprint = hash("generation"),
            )
        val group = count.copy(calls = count.calls + genCall)
        authorize(group.copy(calls = listOf(group.calls[0])), 7)
        authorize(group.copy(calls = listOf(group.calls[1])), 50)
        val reservation = reserve(group)
        assertEquals(2, reservation.calls.size)
        assertEquals(bi(57), snapshot(room).held)
        val countChild = reservation.calls.single { it.call.operation == AiPhysicalOperation.PRECHECK_COUNT }
        val permit = claim(group, countChild)
        bill(countChild, 3, "count3")
        assertTrue(
            assertDoesNotThrow { adapter().settle(AiBudgetSettlementRequest(countChild.reservationId, permit.dispatchToken, "count3")) },
        )
        assertEquals(bi(3), snapshot(room).settled)
        assertEquals(bi(50), snapshot(room).held)
    }

    @Test
    fun `DB12 rejected bundle leaves no affordable first-child partial reservation`() {
        val room = ready()
        val count = request(room, AiPhysicalOperation.PRECHECK_COUNT)
        val gen = count.calls.single().copy(operation = AiPhysicalOperation.PRECHECK_GENERATION, wholePayloadFingerprint = hash("gen"))
        val group = count.copy(calls = count.calls + gen)
        authorize(count, 7)
        authorize(group.copy(calls = listOf(gen)), 93)
        assertNull(assertDoesNotThrow { adapter().reserve(group) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    @Test
    fun `DB18 another invocation keeps the original room exposure while a second room is independent`() {
        val firstRoom = ready()
        val secondRoom = ready()
        val first = request(firstRoom)
        authorize(first, 60)
        reserve(first)
        val nextInvocation = request(firstRoom, AiPhysicalOperation.FINAL_FALLBACK)
        authorize(nextInvocation, 60)
        assertNull(assertDoesNotThrow { adapter().reserve(nextInvocation) })
        val otherRoom = request(secondRoom)
        authorize(otherRoom, 60)
        reserve(otherRoom)
        assertEquals(bi(60), snapshot(firstRoom).held)
        assertEquals(bi(60), snapshot(secondRoom).held)
    }

    @Test
    fun `DB22 changed profile or revoked quote cannot produce a dispatch permit`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val changed = call.call.copy(profileFingerprint = hash("other-profile"))
        assertNull(assertDoesNotThrow { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, changed)) })
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB06 physical insert failure rolls back every hold and reservation after reaching the fault`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        jdbc.execute("CREATE SEQUENCE issue123_fault_reached")
        jdbc.execute(
            "CREATE FUNCTION issue123_fail_reservation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM nextval(''issue123_fault_reached''); RAISE EXCEPTION ''synthetic reservation fault''; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER issue123_reservation_fault BEFORE INSERT ON ai_call_reservations FOR EACH ROW EXECUTE FUNCTION issue123_fail_reservation()",
        )
        try {
            val attempt = runCatching { adapter().reserve(request) }
            assertTrue(attempt.isFailure || attempt.getOrNull() == null)
            assertEquals(
                true,
                jdbc.queryForObject("SELECT is_called FROM issue123_fault_reached", Boolean::class.java),
                "A real PG insert fault must actually be reached",
            )
            assertEquals(BigInteger.ZERO, snapshot(room).held)
            assertEquals(BigInteger.ZERO, snapshot(room).settled)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id = ?", Int::class.java, room))
        } finally {
            jdbc.execute("DROP TRIGGER issue123_reservation_fault ON ai_call_reservations")
            jdbc.execute("DROP FUNCTION issue123_fail_reservation()")
            jdbc.execute("DROP SEQUENCE issue123_fault_reached")
        }
        reserve(request)
        assertEquals(bi(30), snapshot(room).held)
    }

    @Test
    fun `DB10 reserve uses fresh effective expiry after waiting for the last budget lock`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val result = afterBudgetLockWait(room) { adapter().reserve(request) }
        assertNull(result)
        assertEquals(BigInteger.ZERO, snapshot(room).held)
    }

    @Test
    fun `DB10 dispatch claim cannot publish permission after auth binding or claim deadline expires during budget wait`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val result =
            afterBudgetLockWait(room) { adapter().claim(AiBudgetDispatchRequest(call.reservationId, request.execution, call.call)) }
        assertNull(result)
        assertEquals(bi(30), snapshot(room).held)
    }

    private fun <T> afterBudgetLockWait(
        room: UUID,
        operation: () -> T,
    ): T {
        val connection = dataSource.connection
        val pool = Executors.newSingleThreadExecutor()
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT room_id FROM ai_room_budgets WHERE room_id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, room)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            val pending = pool.submit(Callable { assertDoesNotThrow { operation() } })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (System.nanoTime() < deadline) {
                waiting =
                    jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query ILIKE '%ai_room_budgets%')",
                        Boolean::class.java,
                    ) ==
                    true
                if (waiting || pending.isDone) break
                Thread.sleep(20)
            }
            assertTrue(waiting, "The operation must reach the actual PG budget lock before synthetic expiry")
            clock.advance(java.time.Duration.ofSeconds(20))
            connection.commit()
            return assertDoesNotThrow { pending.get(10, TimeUnit.SECONDS) }
        } finally {
            connection.rollback()
            connection.close()
            pool.shutdownNow()
        }
    }

    private fun <T> race(
        first: () -> T,
        second: () -> T,
    ): List<T> {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second)
                .map { operation ->
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            assertDoesNotThrow { operation() }
                        },
                    )
                }.map { assertDoesNotThrow { it.get(20, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
    }
}
