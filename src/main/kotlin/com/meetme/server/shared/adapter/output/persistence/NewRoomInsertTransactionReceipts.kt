package com.meetme.server.shared.adapter.output.persistence

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/** Adapter-local evidence issued only after a successful repository INSERT, never by an application caller. */
internal object NewRoomInsertTransactionReceipts {
    fun recordSuccessfulInsert(roomId: UUID) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            return
        }
        TransactionSynchronizationManager.registerSynchronization(InsertReceipt(roomId))
    }

    fun consume(roomId: UUID): Boolean {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            return false
        }
        // Spring suspends/resumes this list with its owning transaction; it is not shared across threads.
        val receipt =
            TransactionSynchronizationManager
                .getSynchronizations()
                .filterIsInstance<InsertReceipt>()
                .singleOrNull { it.roomId == roomId && !it.consumed }
                ?: return false
        receipt.consumed = true
        return true
    }

    private class InsertReceipt(
        val roomId: UUID,
        var consumed: Boolean = false,
    ) : TransactionSynchronization {
        private val laterSavepoints = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        override fun savepoint(savepoint: Any) {
            laterSavepoints.add(savepoint)
        }

        override fun savepointRollback(savepoint: Any) {
            // Only receipts that already existed when this exact savepoint was created survive its rollback.
            if (savepoint !in laterSavepoints) consumed = true
        }

        override fun afterCompletion(status: Int) {
            consumed = true
            laterSavepoints.clear()
        }
    }
}
