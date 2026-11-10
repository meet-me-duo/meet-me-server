package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Timeout
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic loopback or manual public-listener events. No real provider, secret or native accounting claim. */
class ExperimentWireTraceObservationContractTest {
    @Test
    fun `WTO01 actual header callbacks share call admission target timeout HTTP status and provider request ids`() {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse()
                    .setBody("SYNTHETIC_RESPONSE")
                    .setHeader("x-goog-request-id", "synthetic-google-id")
                    .setHeader("openai-request-id", "synthetic-openai-id")
                    .setHeader("Set-Cookie", "PRIVATE_COOKIE")
                    .setHeader("Authorization", "Bearer PRIVATE_RESPONSE_CREDENTIAL")
                    .setHeader("x-other-id", "PRIVATE_OTHER_HEADER"),
            )
            val origin = URI("http://127.0.0.1:${server.port}")
            val target = origin.resolve("/v1/test")
            val hook = RecordingTraceHook()
            val gate = gate(hook)
            val base = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(7)).build()
            gate
                .forLazySingleCall(base, dispatchContext(final = true), AiPhysicalOperation.FINAL_PRIMARY, profile(target))
                .client
                .newCall(
                    Request
                        .Builder()
                        .url(
                            target.toString(),
                        ).header("Authorization", "Bearer PRIVATE_REQUEST_CREDENTIAL")
                        .post("PRIVATE_INPUT_BODY".toRequestBody())
                        .build(),
                ).execute()
                .use { assertEquals("SYNTHETIC_RESPONSE", it.body?.string()) }
            assertEquals(1, server.requestCount)
            val request = hook.requests.single()
            val response = hook.responses.single()
            assertEquals(target.toString(), request.endpoint)
            assertEquals(Duration.ofSeconds(7).toNanos(), request.timeoutNanos)
            assertEquals(request.call, response.call)
            assertEquals(hook.started.single().callId, request.call.callId)
            assertTrue(request.call.admissionId!!.matches(Regex("[a-f0-9]{64}")))
            assertEquals(200, response.status)
            assertEquals(mapOf("x-goog-request-id" to "synthetic-google-id", "openai-request-id" to "synthetic-openai-id"), response.ids)
            assertFalse((hook.requests.toString() + hook.responses.toString()).contains("PRIVATE_"))
        }
    }

    @Test
    fun `WTO02 endpoint excludes userinfo query fragment and response ids are allowlisted and length bounded`() {
        val request =
            Request
                .Builder()
                .url("https://PRIVATE_USER:PRIVATE_PASSWORD@example.invalid/public/path?key=PRIVATE_QUERY#PRIVATE_FRAGMENT")
                .header("Authorization", "Bearer PRIVATE_HEADER")
                .build()
        val call = mock(Call::class.java)
        `when`(call.request()).thenReturn(request)
        `when`(call.timeout()).thenReturn(Timeout().timeout(7, TimeUnit.SECONDS))
        val hook = RecordingTraceHook()
        val factory =
            AiHttpExperimentListenerFactory(
                EventListener.Factory { EventListener.NONE },
                hook,
                dispatchContext(final = true),
                AiPhysicalOperation.FINAL_PRIMARY,
            )
        val listener = factory.create(call)
        listener.callStart(call)
        factory.bind(call, AiHttpExperimentAdmission("a".repeat(64), AiPhysicalOperation.FINAL_PRIMARY))
        listener.requestHeadersEnd(call, request)
        val response =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(400)
                .message("SYNTHETIC_INVALID_ARGUMENT")
                .addHeader("X-Request-ID", "public-request-id")
                .addHeader("x-goog-request-id", "public-google-id")
                .addHeader("request-id", "public-generic-id")
                .addHeader("openai-request-id", "x".repeat(257))
                .addHeader("Authorization", "PRIVATE_AUTHORIZATION")
                .addHeader("Set-Cookie", "PRIVATE_COOKIE")
                .build()
        listener.responseHeadersEnd(call, response)
        assertEquals("https://example.invalid/public/path", hook.requests.single().endpoint)
        assertEquals(Duration.ofSeconds(7).toNanos(), hook.requests.single().timeoutNanos)
        assertEquals(hook.requests.single().call, hook.responses.single().call)
        assertEquals(400, hook.responses.single().status)
        assertEquals(
            mapOf("x-request-id" to "public-request-id", "x-goog-request-id" to "public-google-id", "request-id" to "public-generic-id"),
            hook.responses.single().ids,
        )
        assertFalse((hook.requests.toString() + hook.responses.toString()).contains("PRIVATE_"))
        listener.callEnd(call)
    }

    @Test
    fun `WTO03 observation callback failure preserves actual success or provider failure without an extra request`() {
        for (status in listOf(200, 400)) {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                server.enqueue(MockResponse().setResponseCode(status).setBody("SYNTHETIC_ORIGINAL_RESPONSE"))
                val target = URI("http://127.0.0.1:${server.port}/v1/test")
                val hook = RecordingTraceHook(failObservation = true)
                val exchange =
                    gate(hook).forLazySingleCall(
                        OkHttpClient(),
                        dispatchContext(final = true),
                        AiPhysicalOperation.FINAL_PRIMARY,
                        profile(target),
                    )
                val request =
                    Request
                        .Builder()
                        .url(target.toString())
                        .post("SYNTHETIC_REQUEST".toRequestBody())
                        .build()
                if (status == 200) {
                    exchange.client
                        .newCall(request)
                        .execute()
                        .use { assertEquals("SYNTHETIC_ORIGINAL_RESPONSE", it.body?.string()) }
                } else {
                    val original =
                        assertFailsWith<AiHttpProviderStatusException> {
                            exchange.client
                                .newCall(request)
                                .execute()
                                .close()
                        }
                    assertEquals(400, original.status)
                    assertEquals("SYNTHETIC_ORIGINAL_RESPONSE", original.responseBytes.toString(Charsets.UTF_8))
                }
                assertEquals(1, server.requestCount)
                assertEquals(1, hook.requests.size)
                assertEquals(1, hook.responses.size)
            }
        }
    }

    private fun gate(hook: RecordingTraceHook): AiHttpDispatchGate =
        AiHttpDispatchGate(AiDispatchGateProperties(true), DispatchBudget(), DispatchClock()).apply { installExperimentWireHook(hook) }

    private fun profile(target: URI) = AiPhysicalWireProfile("synthetic-trace-contract-1", "POST", target, 16_384)

    private data class RequestObservation(
        val call: AiHttpExperimentCall,
        val endpoint: String,
        val timeoutNanos: Long,
    )

    private data class ResponseObservation(
        val call: AiHttpExperimentCall,
        val status: Int,
        val ids: Map<String, String>,
    )

    private class RecordingTraceHook(
        private val failObservation: Boolean = false,
    ) : AiHttpExperimentWireHook {
        val started = mutableListOf<AiHttpExperimentCall>()
        val requests = mutableListOf<RequestObservation>()
        val responses = mutableListOf<ResponseObservation>()

        override fun callStarted(call: AiHttpExperimentCall) {
            started.add(call)
        }

        override fun beforeHeaders(call: AiHttpExperimentCall) = Unit

        override fun requestObserved(
            call: AiHttpExperimentCall,
            endpointIdentifier: String,
            callTimeoutNanos: Long,
        ) {
            requests.add(RequestObservation(call, endpointIdentifier, callTimeoutNanos))
            if (failObservation) error("PRIVATE_REQUEST_OBSERVER_FAILURE")
        }

        override fun responseObserved(
            call: AiHttpExperimentCall,
            httpStatus: Int,
            requestIds: Map<String, String>,
        ) {
            responses.add(ResponseObservation(call, httpStatus, requestIds))
            if (failObservation) error("PRIVATE_RESPONSE_OBSERVER_FAILURE")
        }
    }
}
