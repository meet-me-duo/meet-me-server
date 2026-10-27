package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AD/BD/ID/TM wire-level oracles. Fake ledger is deliberately not a PG proof. */
class Issue123AiHttpDispatchGateTest {
    private val bytes = "{\"model\":\"synthetic\",\"schema\":{\"whole\":true},\"input\":\"original\"}".toByteArray()

    @Test
    fun `BD02 prepared wire freezes defensive copies and whole body digest`() {
        val original = bytes.copyOf()
        val profile = AiPhysicalWireProfile("profile-1", "POST", java.net.URI("http://127.0.0.1:12345/v1/test"), 16384)
        val frozen = PreparedAiHttpRequest.freeze(AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1, null, profile, original)
        original.fill(0)
        val returned = frozen.copyBody()
        returned.fill(1)
        assertContentEquals(bytes, frozen.copyBody())
        assertEquals(dispatchHash(bytes), frozen.wholePayloadFingerprint)
        assertEquals(profile, frozen.profile)
    }

    @Test
    fun `AD01 OFF preserves successful legacy call with no context or accounting`() {
        val budget = DispatchBudget().apply { reserveFailure = true }
        DispatchEndpoint().use { server ->
            val exchange = gate(budget, false).forLazySingleCall(OkHttpClient(), null, AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            exchange.client
                .newCall(request(server))
                .execute()
                .use { assertEquals(200, it.code) }
            assertEquals(1, server.requests.size)
            assertTrue(budget.reserves.isEmpty())
            assertTrue(budget.claims.isEmpty())
        }
    }

    @Test
    fun `AD04 lazy SDK style call reserves and claims exact whole serialized body before one wire request`() {
        val budget = DispatchBudget()
        val context = dispatchContext(final = true)
        DispatchEndpoint().use { server ->
            val exchange = gate(budget).forLazySingleCall(OkHttpClient(), context, AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            exchange.client
                .newCall(request(server))
                .execute()
                .use { assertEquals(200, it.code) }
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.reserves.size)
            assertEquals(context.execution, budget.reserves.single().execution)
            val call =
                budget.reserves
                    .single()
                    .calls
                    .single()
            assertEquals(AiPhysicalOperation.FINAL_PRIMARY, call.operation)
            assertEquals(context.finalAttemptId, call.attemptId)
            assertEquals(dispatchHash(server.requests.single().body), call.wholePayloadFingerprint)
            assertEquals(1, budget.claims.size)
            assertEquals(call, budget.claims.single().call)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["missing-context", "reserve-denied", "reserve-failure", "claim-denied", "expired-permit"])
    fun `AD02 denial occurs before connection and physical request`(reason: String) {
        val budget =
            DispatchBudget().apply {
                denyReserve = reason == "reserve-denied"
                reserveFailure = reason == "reserve-failure"
                denyClaim = reason == "claim-denied"
                if (reason == "expired-permit") permitDeadline = dispatchNow.minusSeconds(1)
            }
        val connections = AtomicInteger()
        DispatchEndpoint().use { server ->
            val base =
                OkHttpClient
                    .Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun connectStart(
                                call: Call,
                                inetSocketAddress: InetSocketAddress,
                                proxy: Proxy,
                            ) {
                                connections.incrementAndGet()
                            }
                        },
                    ).build()
            val result =
                runCatching {
                    val exchange =
                        gate(budget).forLazySingleCall(
                            base,
                            if (reason ==
                                "missing-context"
                            ) {
                                null
                            } else {
                                dispatchContext(true)
                            },
                            AiPhysicalOperation.FINAL_PRIMARY,
                            profile(server),
                        )
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .use { it.body?.string() }
                }
            assertTrue(result.isFailure)
            assertEquals(0, connections.get(), "No quote/claim means no connection, not merely no response")
            assertTrue(server.requests.isEmpty())
            assertEquals(if (reason == "missing-context") 0 else 1, budget.reserves.size)
            assertEquals(if (reason in setOf("claim-denied", "expired-permit")) 1 else 0, budget.claims.size)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["execution", "missing-child", "extra-child", "duplicate-child", "payload", "profile"])
    fun `ID02 malformed reservation cannot grant a network request`(mutation: String) {
        val budget =
            DispatchBudget().apply {
                mutateReservation = { reservation ->
                    val child = reservation.calls.single()
                    when (mutation) {
                        "execution" -> reservation.copy(execution = reservation.execution.copy(roomId = UUID(9, 9)))
                        "missing-child" -> reservation.copy(calls = emptyList())
                        "extra-child", "duplicate-child" ->
                            reservation.copy(
                                calls =
                                    reservation.calls + child.copy(reservationId = UUID(8, 8)),
                            )
                        "payload" ->
                            reservation.copy(
                                calls = listOf(child.copy(call = child.call.copy(wholePayloadFingerprint = "different"))),
                            )
                        else -> reservation.copy(calls = listOf(child.copy(call = child.call.copy(profileFingerprint = "different"))))
                    }
                }
            }
        DispatchEndpoint().use { server ->
            assertTrue(
                runCatching {
                    val exchange =
                        gate(
                            budget,
                        ).forLazySingleCall(OkHttpClient(), dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(1, budget.reserves.size)
            assertTrue(budget.claims.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `ID07 same Call 503 Retry After zero cannot send a second request`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 503 to "{}" }, retryAfter = true).use { server ->
            val exchange =
                gate(
                    budget,
                ).forLazySingleCall(OkHttpClient(), dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            runCatching {
                exchange.client
                    .newCall(request(server))
                    .execute()
                    .close()
            }
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.reserves.size)
            assertEquals(1, budget.claims.size)
        }
    }

    @Test
    fun `ID07 auth followup is not a second granted physical request`() {
        val budget = DispatchBudget()
        DispatchEndpoint(response = { _, _ -> 401 to "{}" }).use { server ->
            val base =
                OkHttpClient
                    .Builder()
                    .authenticator {
                        _,
                        response,
                        ->
                        response.request
                            .newBuilder()
                            .header("Authorization", "synthetic-retry")
                            .build()
                    }.build()
            val exchange = gate(budget).forLazySingleCall(base, dispatchContext(true), AiPhysicalOperation.FINAL_FALLBACK, profile(server))
            runCatching {
                exchange.client
                    .newCall(request(server))
                    .execute()
                    .close()
            }
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.reserves.size)
            assertEquals(1, budget.claims.size)
        }
    }

    @Test
    fun `ID01 second SDK Call cannot mint another reservation or connect`() {
        val budget = DispatchBudget()
        val connections = AtomicInteger()
        DispatchEndpoint().use { server ->
            val base =
                OkHttpClient
                    .Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun connectStart(
                                call: Call,
                                inetSocketAddress: InetSocketAddress,
                                proxy: Proxy,
                            ) {
                                connections.incrementAndGet()
                            }
                        },
                    ).build()
            val exchange = gate(budget).forLazySingleCall(base, dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            exchange.client
                .newCall(request(server))
                .execute()
                .close()
            val before = connections.get()
            assertTrue(
                runCatching {
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(before, connections.get())
            assertEquals(1, server.requests.size)
            assertEquals(1, budget.reserves.size)
            assertEquals(1, budget.claims.size)
        }
    }

    @Test
    fun `BD10 lazy freeze sends exactly snapshotted bytes even when writeTo changes on a second call`() {
        val budget = DispatchBudget()
        val writes = AtomicInteger()
        val changing =
            object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()

                override fun writeTo(sink: BufferedSink) {
                    sink.write(
                        if (writes.incrementAndGet() ==
                            1
                        ) {
                            bytes
                        } else {
                            "{\"mutated\":true}".toByteArray()
                        },
                    )
                }
            }
        DispatchEndpoint().use { server ->
            val exchange =
                gate(
                    budget,
                ).forLazySingleCall(OkHttpClient(), dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            exchange.client
                .newCall(request(server).newBuilder().post(changing).build())
                .execute()
                .close()
            assertEquals(1, server.requests.size)
            assertContentEquals(bytes, server.requests.single().body)
            assertEquals(
                dispatchHash(server.requests.single().body),
                budget.reserves
                    .single()
                    .calls
                    .single()
                    .wholePayloadFingerprint,
            )
            assertEquals(1, writes.get(), "Read once and send immutable snapshot; never quote A then send B")
        }
    }

    @Test
    fun `BD10 downstream network body mutation is denied before request bytes`() {
        val budget = DispatchBudget()
        DispatchEndpoint().use { server ->
            val base =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .post("{\"mutated\":true}".toRequestBody("application/json".toMediaType()))
                                .build(),
                        )
                    }.build()
            val exchange = gate(budget).forLazySingleCall(base, dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            assertTrue(
                runCatching {
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(1, budget.claims.size)
            assertTrue(server.requests.isEmpty())
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["reserve", "claim"])
    fun `TM01 elapsed accounting time does not renew original deadline`(where: String) {
        val start = System.nanoTime()
        val nanos = AtomicLong(start)
        val context = dispatchContext(true, start).copy(deadlineNanos = start + 1_000_000_000)
        val budget =
            DispatchBudget().apply {
                if (where == "reserve") onReserve = { nanos.addAndGet(2_000_000_000) } else onClaim = { nanos.addAndGet(2_000_000_000) }
            }
        DispatchEndpoint().use { server ->
            assertTrue(
                runCatching {
                    val exchange =
                        gate(
                            budget,
                            nanos = nanos,
                        ).forLazySingleCall(OkHttpClient(), context, AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(1, budget.reserves.size)
            assertEquals(if (where == "reserve") 0 else 1, budget.claims.size)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `AD04 count and generation share one group but claim distinct physical children only once`() {
        val budget = DispatchBudget()
        DispatchEndpoint().use { server ->
            val dispatch = gate(budget)
            val count = PreparedAiHttpRequest.freeze(AiPhysicalOperation.PRECHECK_COUNT, 1, null, profile(server), bytes)
            val generation = PreparedAiHttpRequest.freeze(AiPhysicalOperation.PRECHECK_GENERATION, 1, null, profile(server), bytes)
            val group = dispatch.prepareGroup(dispatchContext(), listOf(count, generation))
            for (operation in listOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)) {
                val exchange = dispatch.forPreparedCall(OkHttpClient(), group, operation)
                exchange.client
                    .newCall(request(server))
                    .execute()
                    .close()
            }
            assertEquals(1, budget.reserves.size)
            assertEquals(
                2,
                budget.reserves
                    .single()
                    .calls.size,
            )
            assertEquals(
                2,
                budget.claims
                    .map { it.reservationId }
                    .toSet()
                    .size,
            )
            assertEquals(2, server.requests.size)
            assertTrue(
                runCatching {
                    dispatch
                        .forPreparedCall(
                            OkHttpClient(),
                            group,
                            AiPhysicalOperation.PRECHECK_GENERATION,
                        ).client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(2, server.requests.size)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["method", "endpoint", "body-cap", "one-shot"])
    fun `BD10 invalid actual wire is denied before reservation`(case: String) {
        val budget = DispatchBudget()
        DispatchEndpoint().use { server ->
            val profile = profile(server).copy(captureLimitBytes = if (case == "body-cap") 1 else 16384)
            var request = request(server)
            if (case == "method") request = request.newBuilder().put(bytes.toRequestBody("application/json".toMediaType())).build()
            if (case == "endpoint") request = request.newBuilder().url(server.uri("/different").toString()).build()
            if (case == "one-shot") {
                request =
                    request
                        .newBuilder()
                        .post(
                            object : RequestBody() {
                                override fun contentType() = "application/json".toMediaType()

                                override fun isOneShot() = true

                                override fun writeTo(sink: BufferedSink) {
                                    sink.write(bytes)
                                }
                            },
                        ).build()
            }
            val exchange = gate(budget).forLazySingleCall(OkHttpClient(), dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile)
            assertTrue(
                runCatching {
                    exchange.client
                        .newCall(request)
                        .execute()
                        .close()
                }.isFailure,
            )
            assertTrue(budget.reserves.isEmpty())
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `TM02 final durable wall deadline is checked again after claim lock wait`() {
        val clock = DispatchClock()
        val budget =
            DispatchBudget().apply {
                permitDeadline = dispatchNow.plusSeconds(1)
                onClaim = { clock.current = dispatchNow.plusSeconds(2) }
            }
        DispatchEndpoint().use { server ->
            assertTrue(
                runCatching {
                    val exchange =
                        gate(
                            budget,
                            clock = clock,
                        ).forLazySingleCall(OkHttpClient(), dispatchContext(true), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(1, budget.claims.size)
            assertTrue(server.requests.isEmpty())
        }
    }

    private fun profile(server: DispatchEndpoint) = AiPhysicalWireProfile("synthetic-profile-whole", "POST", server.uri(), 32 * 1024 * 1024)

    private fun request(server: DispatchEndpoint) =
        Request
            .Builder()
            .url(server.uri().toString())
            .post(bytes.toRequestBody("application/json".toMediaType()))
            .build()
}
