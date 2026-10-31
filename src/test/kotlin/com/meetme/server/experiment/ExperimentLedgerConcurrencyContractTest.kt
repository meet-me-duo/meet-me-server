package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmission
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same durable file, independent ledger objects and competing worker threads. No SDK or HTTP executes. */
class ExperimentLedgerConcurrencyContractTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `two ledger instances competing for the final reservation cannot exceed ten`() {
        val path = directory.resolve("shared-ledger.json")

        fun open() = ExperimentCallLedger(path, UUID(0, 501), "a".repeat(64), "b".repeat(64), "c".repeat(64))

        fun admission(n: Int) = AiHttpExperimentAdmission(n.toString(16).padStart(64, '0'), AiPhysicalOperation.FINAL_PRIMARY)
        val room = UUID(0, 601)
        val execution = UUID(0, 701)
        val initial = open()
        initial.beginConnectivityAttempt()
        initial.bindRoom(UUID(0, 600))
        initial.reserveForExecution(UUID(0, 600), UUID(0, 700), listOf(admission(1)))
        val connected =
            AiHttpExperimentCall(UUID(0, 801), UUID(0, 700), UUID(0, 901), AiPhysicalOperation.FINAL_PRIMARY, 1, admission(1).opaqueId)
        initial.callStarted(connected)
        initial.beforeHeaders(connected)
        initial.headersEmitted(connected)
        initial.callFinished(connected, 200, null)
        initial.finishConnectivityAttempt(true)
        initial.beginE2E()
        initial.bindRoom(room)
        initial.reserveForExecution(room, execution, (2..9).map(::admission))
        assertEquals(9, initial.snapshot().admittedCalls)
        val instances = listOf(open(), open())
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val accepted =
            try {
                val attempts =
                    instances.mapIndexed { index, ledger ->
                        workers.submit<Boolean> {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            try {
                                ledger.reserveForExecution(room, execution, listOf(admission(10 + index)))
                                true
                            } catch (_: ExperimentCallStop) {
                                false
                            }
                        }
                    }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                attempts.count { it.get(5, TimeUnit.SECONDS) }
            } finally {
                start.countDown()
                workers.shutdownNow()
                workers.awaitTermination(5, TimeUnit.SECONDS)
            }
        val durable = open().snapshot()
        assertTrue(accepted <= 1, "One remaining physical reservation cannot produce two grants")
        assertTrue(durable.admittedCalls in 9..10)
        if (accepted == 1) assertEquals(10, durable.admittedCalls)
        assertEquals(durable, open().snapshot(), "Reopening cannot reset or lose the concurrent durable result")
    }
}
