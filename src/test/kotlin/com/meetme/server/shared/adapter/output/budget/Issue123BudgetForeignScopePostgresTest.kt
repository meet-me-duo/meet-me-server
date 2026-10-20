package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiCurrentBudgetExecution
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import com.meetme.server.shared.application.service.DurableAiBudgetService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DB07: a valid room claim must never wait for a reservation belonging to another room. */
class Issue123BudgetForeignScopePostgresTest : DurableBudgetPostgresFixture() {
    @Test
    fun `DB07 foreign reservation row lock cannot block a valid own-room claim denial`() {
        val foreignRoom = ready()
        val ownRoom = ready()
        val foreignRequest = request(foreignRoom)
        val ownRequest = request(ownRoom)
        authorize(foreignRequest, 30)
        authorize(ownRequest, 30)
        val maximumByExecution = mutableMapOf(foreignRequest.execution.executionId to bi(30), ownRequest.execution.executionId to bi(30))
        val syntheticAuthority =
            object : AiBudgetAuthorityRegistryPort {
                override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote? {
                    val maximum = maximumByExecution[request.execution.executionId] ?: return null
                    return AiVerifiedBudgetQuote(
                        hash("quote:${request.execution.executionId}:${request.call}:$maximum"),
                        request.call.profileFingerprint,
                        request.call.wholePayloadFingerprint,
                        hash("synthetic-policy"),
                        hash("synthetic-evidence"),
                        maximum,
                        AiBudgetUnit("TEST_SUBUNIT", 0),
                        NOW.plusSeconds(60),
                    )
                }

                override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null

                // Explicit synthetic zero floor preserves this fixture's unrelated existing contract.
                override fun protectedFinalReserve(request: AiBudgetQuoteRequest): AiVerifiedFinalReserve =
                    AiVerifiedFinalReserve(request.policyFingerprint, BigInteger.ZERO, AiBudgetUnit("TEST_SUBUNIT", 0), NOW.plusSeconds(60))
            }
        val foreignCall = reserve(foreignRequest).calls.single()
        val ownCall = reserve(ownRequest).calls.single()
        val enteredOwnFence = CountDownLatch(1)
        val observedFence = observeFence(enteredOwnFence)
        val coordinator =
            DurableAiBudgetService(
                JdbcDurableAiBudgetRepository(jdbc),
                clock,
                observedFence,
                syntheticAuthority,
            )
        // Positive control uses the same explicitly vetted synthetic quote; absent authority correctly remains DENY.
        assertNotNull(
            assertDoesNotThrow {
                transaction.execute {
                    coordinator.claim(
                        AiBudgetDispatchRequest(ownCall.reservationId, ownRequest.execution, ownCall.call),
                    )
                }
            },
        )
        val attackRequest = request(ownRoom)
        authorize(attackRequest, 1)
        maximumByExecution[attackRequest.execution.executionId] = bi(1)
        reserve(attackRequest)
        val ownFenceReached = CountDownLatch(1)
        val attackCoordinator =
            DurableAiBudgetService(
                JdbcDurableAiBudgetRepository(jdbc),
                clock,
                observeFence(ownFenceReached),
                syntheticAuthority,
            )
        val connection = dataSource.connection
        val pool = Executors.newSingleThreadExecutor()
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM ai_call_reservations WHERE id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, foreignCall.reservationId)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            val pending =
                pool.submit(
                    Callable {
                        transaction.execute {
                            attackCoordinator.claim(
                                AiBudgetDispatchRequest(foreignCall.reservationId, attackRequest.execution, attackRequest.calls.single()),
                            )
                        }
                    },
                )
            assertTrue(
                ownFenceReached.await(5, TimeUnit.SECONDS) || pending.isDone,
                "The own-room fence must be reached, or the foreign scope must already have been denied",
            )
            assertNull(
                assertDoesNotThrow {
                    pending.get(2, TimeUnit.SECONDS)
                },
                "A foreign ID must deny while its row remains locked by an independent PG connection",
            )
        } finally {
            connection.rollback()
            connection.close()
            pool.shutdown()
            assertTrue(
                pool.awaitTermination(5, TimeUnit.SECONDS),
                "Releasing the synthetic peer lock must cleanly finish the pending transaction",
            )
        }
        assertNotNull(
            assertDoesNotThrow {
                adapter().claim(AiBudgetDispatchRequest(foreignCall.reservationId, foreignRequest.execution, foreignCall.call))
            },
            "Denied foreign access must leave the real owner's dispatch unconsumed",
        )
    }

    private fun observeFence(reached: CountDownLatch): AiBudgetExecutionFencePort =
        object : AiBudgetExecutionFencePort {
            override fun lockAndValidate(
                request: AiBudgetReserveRequest,
                at: Instant,
            ): AiCurrentBudgetExecution? = fence.lockAndValidate(request, at).also { if (it != null) reached.countDown() }
        }
}
