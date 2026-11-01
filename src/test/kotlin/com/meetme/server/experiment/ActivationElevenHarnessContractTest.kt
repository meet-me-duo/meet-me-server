package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiPhysicalWireProfile
import com.meetme.server.shared.adapter.output.aihttp.DispatchClock
import com.meetme.server.shared.adapter.output.aihttp.DispatchEndpoint
import com.meetme.server.shared.adapter.output.aihttp.dispatchNow
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual gate + loopback HTTP witnesses. No provider, key, holdout or production monetary authority. */
class ActivationElevenHarnessContractTest {
    @TempDir lateinit var temporary: Path
    private val privateMarker = "SYNTHETIC_PRIVATE_CONTENT_MUST_NOT_ESCAPE"
    private val roleCaps =
        linkedMapOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to 6,
            AiPhysicalOperation.PRECHECK_GENERATION to 3,
            AiPhysicalOperation.FINAL_PRIMARY to 1,
            AiPhysicalOperation.FINAL_FALLBACK to 1,
        )

    @Test
    fun `A11 harness admits eleven validated real HTTP requests and blocks twelfth on wire`() {
        val fixture = fixture()
        DispatchEndpoint().use { server ->
            roleCaps.forEach { (operation, maximum) ->
                repeat(maximum) {
                    assertEquals("{}", fixture.run(server, operation))
                }
            }
            fixture.harness.assertComplete()
            assertEquals(11, server.requests.size)
            assertEquals(11, fixture.harness.snapshot()["admissions"])
            assertEquals(1, fixture.harness.snapshot()["actualGemini"])
            assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION) }
            assertEquals(11, server.requests.size)
            assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.FINAL_FALLBACK) }
            assertEquals(11, server.requests.size)
            Mockito.verifyNoInteractions(fixture.financial)
            assertPrivateFree(fixture.harness.snapshot().toString())
        }
    }

    @Test
    fun `A11 original runtime source denial blocks actual request regardless of harness approval`() {
        val fixture = fixture(source = AiRuntimeDispatchGuardPort { _, _ -> null })
        DispatchEndpoint().use { server ->
            assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION) }
            assertEquals(0, server.requests.size)
            assertEquals(0, fixture.harness.snapshot()["admissions"])
        }
    }

    @Test
    fun `A11 changed or throwing snapshot verifier stops before action and never leaks diagnostics`() {
        for (throws in listOf(false, true)) {
            val valid = AtomicBoolean(true)
            val fixture =
                fixture("snapshot$throws", verify = {
                    if (!valid.get() && throws) throw IllegalStateException(privateMarker)
                    valid.get()
                })
            valid.set(false)
            val actions = AtomicInteger()
            val error =
                assertFailsWith<Exception> {
                    fixture.harness.execute(AiPhysicalOperation.PRECHECK_CLASSIFICATION, { actions.incrementAndGet() }, { accepted() })
                }
            assertEquals(0, actions.get())
            assertFailsWith<Exception> { fixture.harness.checkpoint() }
            assertPrivateFree(error.toString(), fixture.harness.snapshot().toString())
        }
    }

    @Test
    fun `A11 snapshot change after execute entry is rechecked at physical source authorization`() {
        val valid = AtomicBoolean(true)
        val fixture = fixture(verify = { valid.get() })
        DispatchEndpoint().use { server ->
            assertFailsWith<Exception> {
                fixture.harness.execute(
                    AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                    {
                        valid.set(false)
                        fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                    },
                    { accepted() },
                )
            }
            assertEquals(0, server.requests.size)
            assertFailsWith<Exception> { fixture.harness.checkpoint() }
        }
    }

    @Test
    fun `A11 zero wire wrong role and two wire actions cannot pass harness coverage`() {
        for (fault in listOf("zero", "role", "two")) {
            val fixture = fixture(fault)
            val validations = AtomicInteger()
            DispatchEndpoint().use { server ->
                assertFailsWith<Exception> {
                    fixture.harness.execute(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        {
                            when (fault) {
                                "zero" -> "local-only"
                                "role" -> fixture.wire(server, AiPhysicalOperation.PRECHECK_GENERATION)
                                else -> {
                                    fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                                    fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                                }
                            }
                        },
                        {
                            validations.incrementAndGet()
                            accepted()
                        },
                    )
                }
                assertEquals(if (fault == "two") 1 else 0, server.requests.size)
                assertEquals(0, validations.get())
                assertFailsWith<Exception> { fixture.harness.checkpoint() }
            }
        }
    }

    @Test
    fun `A11 schema semantic privacy validation failure stops the next actual HTTP request`() {
        for (verdict in listOf(
            ActivationElevenValidation.SCHEMA_FAILURE,
            ActivationElevenValidation.SEMANTIC_FAILURE,
            ActivationElevenValidation.PRIVACY_FAILURE,
        )) {
            val fixture = fixture(verdict.name)
            DispatchEndpoint().use { server ->
                assertFailsWith<Exception> {
                    fixture.harness.execute(AiPhysicalOperation.PRECHECK_CLASSIFICATION, {
                        fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                    }, { verdict })
                }
                assertEquals(verdict.name, fixture.harness.snapshot()["stopReason"])
                assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.FINAL_FALLBACK) }
                assertEquals(1, server.requests.size)
                assertEquals(1, fixture.harness.snapshot()["admissions"])
                assertFailsWith<Exception> { fixture.harness.assertComplete() }
            }
        }
    }

    @Test
    fun `A11 action or validation exceptions preserve consumed wire and hide private error content`() {
        for (actionThrows in listOf(false, true)) {
            val fixture = fixture("throws$actionThrows")
            DispatchEndpoint().use { server ->
                val error =
                    assertFailsWith<Exception> {
                        fixture.harness.execute(
                            AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                            {
                                val result = fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                                if (actionThrows) throw IllegalStateException(privateMarker)
                                result
                            },
                            { throw IllegalStateException(privateMarker) },
                        )
                    }
                assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.PRECHECK_GENERATION) }
                assertEquals(1, server.requests.size)
                assertEquals(1, fixture.harness.snapshot()["admissions"])
                assertPrivateFree(generateSequence<Throwable>(error) { it.cause }.joinToString(), fixture.harness.snapshot().toString())
            }
        }
    }

    @Test
    fun `A11 validation cannot emit another role before its first HTTP result is accepted`() {
        val fixture = fixture()
        DispatchEndpoint().use { server ->
            assertFailsWith<Exception> {
                fixture.harness.execute(
                    AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                    { fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION) },
                    {
                        fixture.wire(server, AiPhysicalOperation.PRECHECK_GENERATION)
                        accepted()
                    },
                )
            }
            assertEquals(1, server.requests.size)
            assertEquals(1, fixture.harness.snapshot()["admissions"])
        }
    }

    @Test
    fun `A11 real HTTP error performs no hidden retry and blocks subsequent fallback wire`() {
        val fixture = fixture()
        DispatchEndpoint(response = { _, _ -> 503 to privateMarker }, retryAfter = true).use { server ->
            assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.FINAL_PRIMARY) }
            assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.FINAL_FALLBACK) }
            assertEquals(1, server.requests.size)
            assertEquals(1, fixture.harness.snapshot()["admissions"])
            assertPrivateFree(fixture.harness.snapshot().toString())
        }
    }

    @Test
    fun `A11 unavailable durable ledger prevents actual HTTP headers`() {
        val fixture = fixture()
        Files.move(temporary.resolve("run"), temporary.resolve("saved-run"))
        Files.writeString(temporary.resolve("run"), "synthetic filesystem refusal")
        DispatchEndpoint().use { server ->
            assertFailsWith<Exception> { fixture.run(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION) }
            assertEquals(0, server.requests.size)
            assertFailsWith<Exception> { fixture.harness.checkpoint() }
        }
    }

    @Test
    fun `A11 missing completed transport coverage cannot be accepted by validator`() {
        val fixture = fixture()
        val validations = AtomicInteger()
        DispatchEndpoint().use { server ->
            var openResponse: okhttp3.Response? = null
            try {
                assertFailsWith<Exception> {
                    fixture.harness.execute(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        {
                            openResponse = fixture.exchange(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION).execute()
                            "unread"
                        },
                        {
                            validations.incrementAndGet()
                            accepted()
                        },
                    )
                }
                assertEquals(1, server.requests.size)
                assertEquals(0, validations.get())
                assertFailsWith<Exception> { fixture.harness.assertComplete() }
            } finally {
                openResponse?.close()
            }
        }
    }

    @Test
    fun `A11 no active stage and nested execution never gain source or wire authority`() {
        for (nested in listOf(false, true)) {
            val fixture = fixture("scope$nested")
            DispatchEndpoint().use { server ->
                assertFailsWith<Exception> {
                    if (nested) {
                        fixture.harness.execute(
                            AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                            { fixture.run(server, AiPhysicalOperation.PRECHECK_GENERATION) },
                            { accepted() },
                        )
                    } else {
                        fixture.wire(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                    }
                }
                assertEquals(0, server.requests.size)
                assertEquals(0, fixture.harness.snapshot()["admissions"])
                assertFailsWith<Exception> { fixture.harness.checkpoint() }
            }
        }
    }

    @Test
    fun `A11 snapshot is verified again immediately before physical headers`() {
        val valid = AtomicBoolean(true)
        val fixture = fixture(verify = { valid.get() })
        val base =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun requestHeadersStart(call: Call) {
                            valid.set(false)
                        }
                    },
                ).build()
        DispatchEndpoint().use { server ->
            assertFailsWith<Exception> {
                fixture.harness.execute(
                    AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                    { fixture.exchange(server, AiPhysicalOperation.PRECHECK_CLASSIFICATION, base).execute().use { it.body!!.string() } },
                    { accepted() },
                )
            }
            assertEquals(0, server.requests.size)
            assertEquals(0, fixture.harness.snapshot()["admissions"])
            assertFailsWith<Exception> { fixture.harness.checkpoint() }
        }
    }

    private fun fixture(
        name: String = "run",
        source: AiRuntimeDispatchGuardPort = AiRuntimeDispatchGuardPort { _, _ -> dispatchNow.plusSeconds(30) },
        verify: () -> Boolean = { true },
    ): Fixture {
        val approval = ActivationElevenApproval("Activation11_$name", "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        val harness = ActivationElevenHarness(approval, temporary.resolve("claims"), temporary.resolve(name).resolve("ledger.json"), verify)
        val financial = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate =
            AiHttpDispatchGate(
                AiDispatchGateProperties(true),
                financial,
                DispatchClock(),
                runtimeGuard = harness.sourceFirst(source),
                enforceBudget = false,
            )
        harness.install(gate)
        return Fixture(harness, gate, financial)
    }

    private inner class Fixture(
        val harness: ActivationElevenHarness,
        val gate: AiHttpDispatchGate,
        val financial: DurableAiBudgetPort,
    ) {
        fun run(
            server: DispatchEndpoint,
            operation: AiPhysicalOperation,
        ): String = harness.execute(operation, { wire(server, operation) }, { accepted() })

        fun wire(
            server: DispatchEndpoint,
            operation: AiPhysicalOperation,
        ): String =
            exchange(server, operation).execute().use {
                it.body!!.string()
            }

        fun exchange(
            server: DispatchEndpoint,
            operation: AiPhysicalOperation,
            base: OkHttpClient = OkHttpClient(),
        ): okhttp3.Call {
            val final = operation in setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)
            val execution =
                AiBudgetExecution(
                    UUID.randomUUID(),
                    if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                    UUID.randomUUID(),
                    1,
                    UUID.randomUUID(),
                    1,
                    "a".repeat(64),
                    "b".repeat(64),
                )
            val context =
                AiProviderDispatchContext(
                    execution,
                    System.nanoTime() + Duration.ofSeconds(5).toNanos(),
                    if (final) UUID.randomUUID() else null,
                )
            val profile = AiPhysicalWireProfile("c".repeat(64), "POST", server.uri(), 16384)
            val exchange = gate.forLazySingleCall(base, context, operation, profile)
            return exchange.client.newCall(
                Request
                    .Builder()
                    .url(server.uri().toString())
                    .post(privateMarker.toRequestBody())
                    .build(),
            )
        }
    }

    private fun accepted() = ActivationElevenValidation.ACCEPTED

    private fun assertPrivateFree(vararg values: String) {
        values.forEach { assertFalse(it.contains(privateMarker)) }
        assertTrue(values.isNotEmpty())
    }
}
