package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Equal wire length isolates the actual quote/hash boundary from OkHttp Content-Length validation. */
class Issue123DispatchEqualLengthBodyTest {
    @Test
    fun `BD10 same byte length downstream mutation is denied by exact body authority before HTTP`() {
        val original = "{\"input\":\"AAAA\"}"
        val changed = "{\"input\":\"BBBB\"}"
        assertEquals(original.toByteArray(Charsets.UTF_8).size, changed.toByteArray(Charsets.UTF_8).size)
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
                                .post(changed.toRequestBody("application/json".toMediaType()))
                                .build(),
                        )
                    }.build()
            val exchange =
                AiHttpDispatchGate(AiDispatchGateProperties(true), budget, DispatchClock()).forLazySingleCall(
                    base,
                    dispatchContext(true),
                    AiPhysicalOperation.FINAL_PRIMARY,
                    AiPhysicalWireProfile("synthetic-equal-size", "POST", server.uri(), 16384),
                )
            val result =
                runCatching {
                    exchange.client
                        .newCall(
                            Request
                                .Builder()
                                .url(server.uri().toURL())
                                .post(original.toRequestBody("application/json".toMediaType()))
                                .build(),
                        ).execute()
                        .use { assertEquals(200, it.code) }
                }
            assertIs<AiHttpDispatchException>(result.exceptionOrNull())
            assertEquals(1, budget.reserves.size)
            assertEquals(1, budget.claims.size)
            assertTrue(server.requests.isEmpty(), "Same length must still require the exact admitted UTF8 body")
        }
    }
}
