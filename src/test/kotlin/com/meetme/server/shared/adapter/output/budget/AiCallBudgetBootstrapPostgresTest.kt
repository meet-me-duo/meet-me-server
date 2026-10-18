package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.meetingroom.adapter.output.persistence.RoomDataRetentionRepository
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiRoomBudgetState
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiCallBudgetBootstrapPostgresTest : DurableBudgetPostgresFixture() {
    @Test
    fun `DB19 migration of a real V16 room never invents tracked zero-spend authority`() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .clean()
        Flyway
            .configure()
            .dataSource(dataSource)
            .target("16")
            .load()
            .migrate()
        val historical = room()
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        val budget = snapshot(historical)
        assertEquals(AiRoomBudgetState.HISTORICAL_UNKNOWN, budget.state)
        assertNull(budget.cap)
        assertNull(budget.epochId)
        val request = request(historical)
        authorize(request, 1)
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(AiRoomBudgetState.HISTORICAL_UNKNOWN, snapshot(historical).state)
    }

    @Test
    fun `DB20 V17 room insert creates an unconfigured budget in the same transaction`() {
        val room = room()
        val budget = snapshot(room)
        assertEquals(AiRoomBudgetState.UNCONFIGURED, budget.state)
        assertNull(budget.cap)
        assertNull(budget.epochId)
        assertEquals(BigInteger.ZERO, budget.held)
        val request = request(room)
        authorize(request, 1)
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
    }

    @Test
    fun `DB20 budget initialization failure rolls back room creation rather than leaving lazy work`() {
        jdbc.execute(
            "CREATE FUNCTION issue123_fail_budget_insert() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic room budget fault''; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER issue123_budget_insert_fault BEFORE INSERT ON ai_room_budgets FOR EACH ROW EXECUTE FUNCTION issue123_fail_budget_insert()",
        )
        try {
            assertThrows<RuntimeException> { room() }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_rooms", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_room_budgets", Int::class.java))
        } finally {
            jdbc.execute("DROP TRIGGER issue123_budget_insert_fault ON ai_room_budgets")
            jdbc.execute("DROP FUNCTION issue123_fail_budget_insert()")
        }
    }

    @Test
    fun `DB20 a room whose budget row is missing is never lazily recreated by reserve`() {
        val room = ready()
        jdbc.update("DELETE FROM ai_room_budgets WHERE room_id = ?", room)
        val request = request(room)
        authorize(request, 30)
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertNull(assertDoesNotThrow { adapter().snapshot(room) })
    }

    @Test
    fun `DB19 untracked epoch remains denied across a new execution and adapter restart`() {
        val room = ready()
        jdbc.update("UPDATE ai_room_budgets SET state = 'UNTRACKED_EPOCH' WHERE room_id = ?", room)
        val first = request(room)
        authorize(first, 1)
        assertNull(assertDoesNotThrow { adapter().reserve(first) })
        val next = request(room)
        authorize(next, 1)
        assertNull(assertDoesNotThrow { adapter().reserve(next) })
        assertEquals(AiRoomBudgetState.UNTRACKED_EPOCH, snapshot(room).state)
    }

    @Test
    fun `DB21 closing a live room and expiring its execution retain unknown full hold`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        assertTrue(
            assertDoesNotThrow {
                adapter().observe(AiBudgetObservation(call.reservationId, permit.dispatchToken, UUID.randomUUID(), AiBudgetOutcome.UNKNOWN))
            },
        )
        jdbc.update(
            "UPDATE meeting_rooms SET collection_status = 'CLOSED', closure_reason = 'MANUAL', closed_at = ? WHERE id = ?",
            NOW.atOffset(java.time.ZoneOffset.UTC),
            room,
        )
        clock.advance(Duration.ofDays(1))
        assertEquals(bi(30), snapshot(room).held)
        assertEquals(BigInteger.ZERO, snapshot(room).settled)
    }

    @Test
    fun `DB21 authorized thirty-day deletion cascades unknown ledger and late events cannot resurrect scope`() {
        val room = ready()
        val request = request(room)
        authorize(request, 30)
        val call = reserve(request).calls.single()
        val permit = claim(request, call)
        val observation = AiBudgetObservation(call.reservationId, permit.dispatchToken, UUID.randomUUID(), AiBudgetOutcome.UNKNOWN)
        assertTrue(assertDoesNotThrow { adapter().observe(observation) })
        val now = NOW.plus(Duration.ofDays(31))
        val retention = RoomDataRetentionRepository(jdbc)
        assertEquals(1, transaction.execute { retention.deleteExpiredRooms(now.minus(Duration.ofDays(30)), 100, now) })
        assertNull(assertDoesNotThrow { adapter().snapshot(room) })
        assertFalse(assertDoesNotThrow { adapter().observe(observation.copy(eventId = UUID.randomUUID())) })
        bill(call, 12, "deleted-room-bill")
        assertFalse(
            assertDoesNotThrow {
                adapter().settle(AiBudgetSettlementRequest(call.reservationId, permit.dispatchToken, "deleted-room-bill"))
            },
        )
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_reservations WHERE room_id = ?", Int::class.java, room))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_observations", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM meeting_rooms", Int::class.java))
    }
}
