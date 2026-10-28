package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiBudgetDispatchRequest
import com.meetme.server.shared.application.port.output.AiBudgetObservation
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** OA02: no fake monetary receipt, quote, epoch, or permit is supplied by these public gate tests. */
class Issue144ProductionRuntimeGateTest {
    private val body = "{\"synthetic\":\"original\"}".toByteArray()

    @Test
    fun `OA02 nonmonetary exchange rechecks guard and permits only one application call`() {
        val budget = ForbiddenAccounting()
        val checked = AtomicInteger()
        val guard =
            AiRuntimeDispatchGuardPort { _, _ ->
                checked.incrementAndGet()
                dispatchNow.plusSeconds(10)
            }
        DispatchEndpoint().use { server ->
            val gate = runtimeGate(budget, guard)
            val prepared = prepared(server)
            val group = gate.prepareGroup(dispatchContext(), listOf(prepared))
            val exchange = gate.forPreparedCall(OkHttpClient(), group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
            exchange.client
                .newCall(request(server))
                .execute()
                .use { assertEquals(200, it.code) }
            assertTrue(exchange.observe(UUID.randomUUID(), AiBudgetOutcome.SUCCEEDED))
            assertFails {
                exchange.client
                    .newCall(request(server))
                    .execute()
                    .use { }
            }
            assertFails { gate.forPreparedCall(OkHttpClient(), group, AiPhysicalOperation.PRECHECK_CLASSIFICATION) }
            assertEquals(1, server.requests.size)
            assertTrue(checked.get() >= 2, "Both application and network boundaries must recheck authority")
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `OA02 revocation after application authorization blocks the network exchange`() {
        val revoked = AtomicBoolean()
        val checked = AtomicInteger()
        val budget = ForbiddenAccounting()
        val guard =
            AiRuntimeDispatchGuardPort { _, _ ->
                checked.incrementAndGet()
                if (revoked.get()) null else dispatchNow.plusSeconds(10)
            }
        DispatchEndpoint().use { server ->
            val base =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        revoked.set(true)
                        chain.proceed(chain.request())
                    }.build()
            assertFails {
                val gate = runtimeGate(budget, guard)
                val group = gate.prepareGroup(dispatchContext(), listOf(prepared(server)))
                gate
                    .forPreparedCall(base, group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                    .client
                    .newCall(request(server))
                    .execute()
                    .use { }
            }
            assertTrue(checked.get() >= 2)
            assertEquals(0, server.requests.size)
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `OA02 time spent in runtime guard cannot revive an expired returned deadline`() {
        val clock = DispatchClock()
        val budget = ForbiddenAccounting()
        val guard =
            AiRuntimeDispatchGuardPort { _, _ ->
                clock.current = dispatchNow.plusSeconds(5)
                clock.current
            }
        DispatchEndpoint().use { server ->
            assertFails {
                val gate = runtimeGate(budget, guard, clock)
                val group = gate.prepareGroup(dispatchContext(), listOf(prepared(server)))
                gate
                    .forPreparedCall(OkHttpClient(), group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                    .client
                    .newCall(request(server))
                    .execute()
                    .use { }
            }
            assertEquals(0, server.requests.size)
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `OA02 503 with RetryAfter zero cannot create an implicit second request`() {
        val budget = ForbiddenAccounting()
        DispatchEndpoint({ _, _ -> 503 to "{}" }, retryAfter = true).use { server ->
            val gate = runtimeGate(budget, AiRuntimeDispatchGuardPort { _, _ -> dispatchNow.plusSeconds(10) })
            val group = gate.prepareGroup(dispatchContext(), listOf(prepared(server)))
            val exchange = gate.forPreparedCall(OkHttpClient(), group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
            val failure =
                assertThrows<AiHttpProviderStatusException> {
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .use { }
                }
            assertEquals(503, failure.status)
            assertEquals(0L, failure.retryAfterMillis)
            assertEquals(1, server.requests.size)
            assertEquals(0, budget.calls.get())
        }
    }

    @Test
    fun `OA02 exact frozen body endpoint and method remain mandatory without monetary enforcement`() {
        for (fault in listOf("BODY", "NETWORK_BODY", "METHOD", "ENDPOINT")) {
            val budget = ForbiddenAccounting()
            DispatchEndpoint().use { server ->
                val gate = runtimeGate(budget, AiRuntimeDispatchGuardPort { _, _ -> dispatchNow.plusSeconds(10) })
                val group = gate.prepareGroup(dispatchContext(), listOf(prepared(server)))
                val base =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            val changed =
                                if (fault == "NETWORK_BODY") {
                                    chain
                                        .request()
                                        .newBuilder()
                                        .post("{\"synthetic\":\"tampered\"}".toRequestBody())
                                        .build()
                                } else {
                                    chain.request()
                                }
                            chain.proceed(changed)
                        }.build()
                val altered =
                    request(server)
                        .newBuilder()
                        .apply {
                            if (fault == "BODY") post("{\"synthetic\":\"tampered\"}".toRequestBody())
                            if (fault == "METHOD") put(body.toRequestBody())
                            if (fault == "ENDPOINT") url(server.uri("/different").toString())
                        }.build()
                assertFails {
                    gate
                        .forPreparedCall(base, group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                        .client
                        .newCall(altered)
                        .execute()
                        .use { }
                }
                assertEquals(0, server.requests.size, fault)
                assertEquals(0, budget.calls.get())
            }
        }
    }

    @Test
    fun `OA02 missing runtime authority denies rather than falling through to unmanaged HTTP`() {
        val budget = ForbiddenAccounting()
        DispatchEndpoint().use { server ->
            assertFails {
                val gate = runtimeGate(budget, null)
                val group = gate.prepareGroup(dispatchContext(), listOf(prepared(server)))
                gate
                    .forPreparedCall(OkHttpClient(), group, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                    .client
                    .newCall(request(server))
                    .execute()
                    .use { }
            }
            assertEquals(0, server.requests.size)
            assertEquals(0, budget.calls.get())
        }
    }

    private fun runtimeGate(
        budget: ForbiddenAccounting,
        guard: AiRuntimeDispatchGuardPort?,
        clock: DispatchClock = DispatchClock(),
    ) = AiHttpDispatchGate(AiDispatchGateProperties(true), budget, clock, runtimeGuard = guard, enforceBudget = false)

    private fun prepared(server: DispatchEndpoint) =
        PreparedAiHttpRequest.freeze(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION,
            1,
            null,
            AiPhysicalWireProfile(dispatchHash("synthetic-profile".toByteArray()), "POST", server.uri(), 16384),
            body,
        )

    private fun request(server: DispatchEndpoint): Request =
        Request
            .Builder()
            .url(server.uri().toString())
            .post(body.toRequestBody())
            .build()

    private class ForbiddenAccounting : DurableAiBudgetPort {
        val calls = AtomicInteger()

        private fun forbidden(): Nothing {
            calls.incrementAndGet()
            error("OA02 monetary port must remain unused")
        }

        override fun reserve(request: AiBudgetReserveRequest): Nothing = forbidden()

        override fun claim(request: AiBudgetDispatchRequest): Nothing = forbidden()

        override fun observe(observation: AiBudgetObservation): Nothing = forbidden()

        override fun settle(request: AiBudgetSettlementRequest): Nothing = forbidden()

        override fun snapshot(roomId: UUID): Nothing = forbidden()
    }
}
