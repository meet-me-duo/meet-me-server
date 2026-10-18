package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Supplemental cycle: claimed wall expiry and tightened monotonic cutoff both fence actual API bytes. */
class Issue123DispatchWriteDeadlineTest {
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["forward-wall", "backward-wall-after-tightened-monotonic"])
    fun `TM02 network write rechecks claimed expiry after downstream application delay`(movement: String) {
        val clock = DispatchClock()
        val nanos = AtomicLong(System.nanoTime())
        val context = dispatchContext(true, nanos.get())
        val budget = DispatchBudget().apply { permitDeadline = dispatchNow.plusSeconds(1) }
        DispatchEndpoint().use { server ->
            val base =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        // Gate installs its application admission first, so this runs AFTER successful claim.
                        assertEquals(1, budget.claims.size)
                        if (movement == "forward-wall") {
                            clock.current = dispatchNow.plusSeconds(2)
                        } else {
                            clock.current = dispatchNow.minusSeconds(60)
                            // Original context still has 3s, but the claimed permit tightened it to 1s.
                            nanos.addAndGet(2_000_000_000)
                        }
                        chain.proceed(chain.request())
                    }.build()
            val exchange =
                gate(budget, clock = clock, nanos = nanos).forLazySingleCall(
                    base,
                    context,
                    AiPhysicalOperation.FINAL_PRIMARY,
                    AiPhysicalWireProfile("synthetic-deadline-profile", "POST", server.uri(), 16384),
                )
            val failure =
                assertFailsWith<AiHttpDispatchException> {
                    exchange.client
                        .newCall(
                            Request
                                .Builder()
                                .url(server.uri().toString())
                                .post("{}".toRequestBody("application/json".toMediaType()))
                                .build(),
                        ).execute()
                        .close()
                }
            assertEquals("CONFIGURATION", failure.message)
            assertEquals(1, budget.claims.size)
            assertEquals(1, budget.reserves.size)
            assertTrue(server.requests.isEmpty(), "TCP setup may occur; expired claimed permission must send zero API requests")
        }
    }
}
