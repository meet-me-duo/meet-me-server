package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository
import com.meetme.server.shared.application.port.output.AiBudgetCoverageEpochQueryPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DB19/20: real persisted coverage, not the migration timestamp or caller's epoch hint. */
class Issue123BudgetCoveragePostgresTest : DurableBudgetPostgresFixture() {
    private fun coverage(): AiBudgetCoverageEpochQueryPort = JdbcDurableAiBudgetRepository(jdbc)

    @Test
    fun `DB20 current tracked epoch is returned only for an execution covered from its start`() {
        val room = ready()
        val expected = assertNotNull(fence.epochs[room])
        assertEquals(expected, assertDoesNotThrow { coverage().trackedEpoch(room, NOW) })
        assertNull(assertDoesNotThrow { coverage().trackedEpoch(room, NOW.minusMillis(1)) })
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNCONFIGURED", "HISTORICAL_UNKNOWN", "UNTRACKED_EPOCH", "FROZEN"])
    fun `DB19 all untracked or frozen states deny coverage without resetting history`(state: String) {
        val room = ready()
        jdbc.update("UPDATE ai_room_budgets SET state=? WHERE room_id=?", state, room)
        assertNull(assertDoesNotThrow { coverage().trackedEpoch(room, NOW) })
    }

    @Test
    fun `DB20 stale fence epoch cannot reserve against a newly changed persisted coverage epoch`() {
        val room = ready()
        val original = assertNotNull(fence.epochs[room])
        val changed = UUID.randomUUID()
        jdbc.update("UPDATE ai_room_budgets SET epoch_id=? WHERE room_id=?", changed, room)
        val request = request(room)
        authorize(request, 30)
        assertEquals(original, fence.epochs[room])
        assertNull(assertDoesNotThrow { adapter().reserve(request) })
        assertEquals(BigInteger.ZERO, snapshot(room).held)
        assertEquals(changed, snapshot(room).epochId)
    }

    @Test
    fun `DB20 absent room is never initialized by the coverage lookup`() {
        val room = UUID.randomUUID()
        assertNull(assertDoesNotThrow { coverage().trackedEpoch(room, NOW) })
        assertNull(assertDoesNotThrow { adapter().snapshot(room) })
    }
}
