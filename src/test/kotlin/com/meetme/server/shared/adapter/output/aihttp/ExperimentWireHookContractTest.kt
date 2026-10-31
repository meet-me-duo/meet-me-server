package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.coordination.adapter.output.integration.ParserTransportObservationSession
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import com.meetme.server.experiment.ExperimentCallLedger
import com.meetme.server.experiment.ExperimentCounterAdmission
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Timeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Actual loopback wire and public listener contracts only. No paid API and no PostgreSQL accounting claim. */
class ExperimentWireHookContractTest {
    @TempDir lateinit var directory: Path
    private val body = "{\"synthetic\":\"PRIVATE_INPUT_BODY\"}"

    @Test
    fun `absent experiment hook preserves ordinary source checks listener and one response`() {
        val starts = AtomicInteger()
        val ends = AtomicInteger()
        val sourceCalls = AtomicInteger()
        val base =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            starts.incrementAndGet()
                        }

                        override fun callEnd(call: Call) {
                            ends.incrementAndGet()
                        }
                    },
                ).build()
        DispatchEndpoint().use { server ->
            val gate =
                gate(
                    AiRuntimeDispatchGuardPort { _, _ ->
                        sourceCalls.incrementAndGet()
                        dispatchNow.plusSeconds(30)
                    },
                )
            gate
                .forLazySingleCall(base, context(), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                .client
                .newCall(request(server))
                .execute()
                .use {
                    assertEquals(200, it.code)
                    it.body?.string()
                }
            assertEquals(1, server.requests.size)
        }
        assertTrue(sourceCalls.get() > 0)
        assertEquals(1, starts.get())
        assertEquals(1, ends.get())
    }

    @Test
    fun `actual gate composes durable reservation hook and existing diagnostic listener`() {
        val context = context()
        val ledger = ExperimentCallLedger(directory.resolve("ledger.json"), UUID(0, 901), "a".repeat(64), "b".repeat(64), "c".repeat(64))
        ledger.beginConnectivityAttempt()
        ledger.bindRoom(context.execution.roomId)
        val original = mutableListOf<ParserTransportSnapshot>()
        val diagnostics = mutableListOf<ParserTransportDiagnosticSnapshot>()
        val session =
            ParserTransportObservationSession(
                ParserObservationContext(UUID(0, 902), context.finalAttemptId!!),
                AnalysisProvider.GEMINI,
                ParserTransportObservationPort { original += it },
                ParserTransportDiagnosticPort { diagnostics += it },
            )
        session.configureTimeouts(5_000_000_000, 5_000_000_000, context.deadlineNanos)
        val source = ExperimentCounterAdmission(ledger)
        val gate = gate(AiRuntimeDispatchGuardPort { request, _ -> source.authorize({ dispatchNow.plusSeconds(30) }, request) })
        gate.installExperimentWireHook(ledger)
        DispatchEndpoint().use { server ->
            val client = OkHttpClient.Builder().eventListenerFactory(session.eventListenerFactory).build()
            gate
                .forLazySingleCall(client, context, AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                .client
                .newCall(request(server))
                .execute()
                .use {
                    assertEquals(200, it.code)
                    it.body?.string()
                }
            session.finish()
            assertEquals(1, server.requests.size)
        }
        assertEquals(1, ledger.snapshot().admittedCalls)
        assertEquals(1, ledger.snapshot().sdkCalls)
        assertEquals(1, ledger.snapshot().headersStarted)
        assertEquals(1, ledger.snapshot().headersEmitted)
        assertTrue(ledger.snapshot().observationCoverageKnown)
        assertEquals(1, original.single().calls.size)
        assertEquals(1, diagnostics.single().calls.size)
        assertTrue(
            diagnostics
                .single()
                .calls
                .single()
                .settled,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["source", "deadline"])
    fun `experiment hook cannot turn a denied source or expired timer into emitted headers`(reason: String) {
        val hook = RecordingHook()
        val gate = gate(AiRuntimeDispatchGuardPort { _, _ -> if (reason == "source") null else dispatchNow.plusSeconds(30) })
        gate.installExperimentWireHook(hook)
        DispatchEndpoint().use { server ->
            val context = context().let { if (reason == "deadline") it.copy(deadlineNanos = System.nanoTime() - 1) else it }
            val failure =
                runCatching {
                    gate
                        .forLazySingleCall(OkHttpClient(), context, AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                        .client
                        .newCall(request(server))
                        .execute()
                        .use { it.body?.string() }
                }
            assertTrue(failure.isFailure)
            assertTrue(server.requests.isEmpty())
        }
        assertEquals(0, hook.headers.size)
        assertEquals(0, hook.emitted.size)
    }

    @Test
    fun `hook refusal after valid original authority prevents actual request headers`() {
        val sourceCalls = AtomicInteger()
        val hook = RecordingHook(refuse = true)
        val gate =
            gate(
                AiRuntimeDispatchGuardPort { _, _ ->
                    sourceCalls.incrementAndGet()
                    dispatchNow.plusSeconds(30)
                },
            )
        gate.installExperimentWireHook(hook)
        DispatchEndpoint().use { server ->
            val failure =
                runCatching {
                    gate
                        .forLazySingleCall(OkHttpClient(), context(), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
                        .client
                        .newCall(request(server))
                        .execute()
                        .use { it.body?.string() }
                }
            assertTrue(failure.isFailure)
            assertTrue(server.requests.isEmpty())
        }
        assertTrue(sourceCalls.get() > 0)
        assertEquals(1, hook.headers.size)
        assertTrue(hook.emitted.isEmpty())
    }

    @Test
    fun `fresh SDK Call identity is distinct even when the second call reuses one prepared attempt`() {
        val hook = RecordingHook()
        val gate = gate(AiRuntimeDispatchGuardPort { _, _ -> dispatchNow.plusSeconds(30) })
        gate.installExperimentWireHook(hook)
        DispatchEndpoint().use { server ->
            val exchange = gate.forLazySingleCall(OkHttpClient(), context(), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            exchange.client
                .newCall(request(server))
                .execute()
                .use { it.body?.string() }
            assertTrue(
                runCatching {
                    exchange.client
                        .newCall(request(server))
                        .execute()
                        .close()
                }.isFailure,
            )
            assertEquals(1, server.requests.size)
        }
        assertEquals(2, hook.started.size)
        assertEquals(
            2,
            hook.started
                .map { it.callId }
                .toSet()
                .size,
        )
        assertEquals(1, hook.headers.size)
        assertEquals(1, hook.emitted.size)
        assertTrue(
            hook.headers
                .single()
                .admissionId!!
                .matches(Regex("[a-f0-9]{64}")),
        )
        assertEquals(hook.headers.single().callId, hook.emitted.single().callId)
        assertFalse(hook.started.toString().contains("PRIVATE_"))
    }

    @Test
    fun `all twenty nine public original listener callbacks are forwarded once`() {
        val original = mock(EventListener::class.java)
        val gate = gate(AiRuntimeDispatchGuardPort { _, _ -> dispatchNow.plusSeconds(30) })
        gate.installExperimentWireHook(RecordingHook())
        DispatchEndpoint().use { server ->
            val base = OkHttpClient.Builder().eventListenerFactory { original }.build()
            val exchange = gate.forLazySingleCall(base, context(), AiPhysicalOperation.FINAL_PRIMARY, profile(server))
            val callbacks =
                EventListener::class.java.declaredMethods.filter {
                    Modifier.isPublic(
                        it.modifiers,
                    ) &&
                        it.returnType == Void.TYPE
                }
            assertEquals(29, callbacks.size)
            callbacks.forEach { method ->
                val call = mock(Call::class.java)
                val request = request(server)
                `when`(call.request()).thenReturn(request)
                `when`(call.timeout()).thenReturn(Timeout())
                val listener = exchange.client.eventListenerFactory.create(call)
                if (method.name != "callStart") listener.callStart(call)
                clearInvocations(original)
                val arguments = method.parameterTypes.map { type -> argument(type, call, request) }.toTypedArray()
                method.invoke(listener, *arguments)
                assertEquals(1, mockingDetails(original).invocations.count { it.method == method }, method.name)
            }
            assertTrue(server.requests.isEmpty(), "Manual listener observation must never create a request")
        }
    }

    @Test
    fun `opaque admission hashes bind every immutable execution and physical spec field without exporting them`() {
        val execution = context().execution
        val spec = AiPhysicalCallSpec(AiPhysicalOperation.FINAL_PRIMARY, 1, "c".repeat(64), "d".repeat(64), UUID(0, 701))

        fun id(
            e: AiBudgetExecution = execution,
            s: AiPhysicalCallSpec = spec,
        ) = AiHttpExperimentAdmissionIds.of(AiBudgetReserveRequest(e, listOf(s)), s).opaqueId
        val original = id()
        assertTrue(original.matches(Regex("[a-f0-9]{64}")))
        assertEquals(original, id(execution.copy(), spec.copy()))
        listOf(
            execution.copy(roomId = UUID(0, 999)),
            execution.copy(executionId = UUID(0, 999)),
            execution.copy(generation = execution.generation + 1),
            execution.copy(ownerToken = UUID(0, 999)),
            execution.copy(version = execution.version + 1),
            execution.copy(sourceFingerprint = "e".repeat(64)),
            execution.copy(contextFingerprint = "e".repeat(64)),
        ).forEach { assertNotEquals(original, id(e = it)) }
        listOf(
            spec.copy(operation = AiPhysicalOperation.FINAL_FALLBACK),
            spec.copy(ordinal = 2),
            spec.copy(attemptId = UUID(0, 999)),
            spec.copy(wholePayloadFingerprint = "e".repeat(64)),
            spec.copy(profileFingerprint = "e".repeat(64)),
        ).forEach { assertNotEquals(original, id(s = it)) }
    }

    @Test
    fun `wire metadata exposes only opaque identities and bounded enums`() {
        fun fields(type: Class<*>) =
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .toSet()
        assertEquals(setOf("opaqueId", "operation"), fields(AiHttpExperimentAdmission::class.java))
        assertEquals(
            setOf("callId", "executionId", "attemptId", "operation", "ordinal", "admissionId"),
            fields(AiHttpExperimentCall::class.java),
        )
    }

    private fun gate(guard: AiRuntimeDispatchGuardPort) =
        AiHttpDispatchGate(
            AiDispatchGateProperties(true),
            DispatchBudget(),
            DispatchClock(),
            runtimeGuard = guard,
            enforceBudget = false,
        )

    private fun context(): AiProviderDispatchContext =
        dispatchContext(final = true).let {
            it.copy(execution = it.execution.copy(sourceFingerprint = "a".repeat(64), contextFingerprint = "b".repeat(64)))
        }

    private fun profile(server: DispatchEndpoint) = AiPhysicalWireProfile("experiment-v1", "POST", server.uri(), 16_384)

    private fun request(server: DispatchEndpoint) =
        Request
            .Builder()
            .url(server.uri().toURL())
            .header("Authorization", "PRIVATE_SYNTHETIC_KEY")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    private fun argument(
        type: Class<*>,
        call: Call,
        request: Request,
    ): Any? =
        when (type) {
            Call::class.java -> call
            Request::class.java -> request
            Response::class.java ->
                Response
                    .Builder()
                    .request(
                        request,
                    ).protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("PRIVATE_RESPONSE")
                    .build()
            HttpUrl::class.java -> request.url
            String::class.java -> "PRIVATE_HOST_OR_MESSAGE"
            List::class.java -> emptyList<Any>()
            Proxy::class.java -> Proxy.NO_PROXY
            InetSocketAddress::class.java -> InetSocketAddress("127.0.0.1", 9)
            Protocol::class.java -> Protocol.HTTP_1_1
            IOException::class.java -> IOException("PRIVATE_FAILURE")
            Connection::class.java -> mock(Connection::class.java)
            Handshake::class.java -> null
            java.lang.Long.TYPE -> 1L
            else -> error("Missing safe public callback fixture for ${type.name}")
        }

    private class RecordingHook(
        private val refuse: Boolean = false,
    ) : AiHttpExperimentWireHook {
        val started = CopyOnWriteArrayList<AiHttpExperimentCall>()
        val headers = CopyOnWriteArrayList<AiHttpExperimentCall>()
        val emitted = CopyOnWriteArrayList<AiHttpExperimentCall>()

        override fun callStarted(call: AiHttpExperimentCall) {
            started += call
        }

        override fun beforeHeaders(call: AiHttpExperimentCall) {
            headers += call
            if (refuse) throw IOException("Synthetic protected stop")
        }

        override fun headersEmitted(call: AiHttpExperimentCall) {
            emitted += call
        }
    }
}
