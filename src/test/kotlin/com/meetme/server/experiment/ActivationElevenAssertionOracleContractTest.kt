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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ordinary semantic-oracle AssertionError is an expected validation failure, never an execution bypass. */
class ActivationElevenAssertionOracleContractTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["ACTION", "VALIDATOR", "SNAPSHOT"])
    fun `semantic oracle AssertionError is private globally latched and prevents later wire`(mode: String) {
        val marker = "SYNTHETIC_PRIVATE_ASSERTION_ORACLE_DETAIL"
        val invalid = AtomicBoolean(true)
        val approval =
            ActivationElevenApproval("Activation11_assertion_$mode", "a".repeat(40), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        val harness =
            ActivationElevenHarness(approval, temporary.resolve("claims"), temporary.resolve("ledger.json")) {
                if (mode == "SNAPSHOT" && invalid.get()) throw AssertionError(marker)
                true
            }
        val gate =
            AiHttpDispatchGate(
                AiDispatchGateProperties(true),
                Mockito.mock(DurableAiBudgetPort::class.java),
                DispatchClock(),
                runtimeGuard = harness.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> dispatchNow.plusSeconds(30) }),
                enforceBudget = false,
            )
        harness.install(gate)
        DispatchEndpoint().use { server ->
            fun wire(): String {
                val execution =
                    AiBudgetExecution(
                        UUID.randomUUID(),
                        AiBudgetExecutionKind.PRECHECK,
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        1,
                        "a".repeat(64),
                        "b".repeat(64),
                    )
                val context = AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(5).toNanos())
                val exchange =
                    gate.forLazySingleCall(
                        OkHttpClient(),
                        context,
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        AiPhysicalWireProfile("c".repeat(64), "POST", server.uri(), 16384),
                    )
                return exchange.client
                    .newCall(
                        Request
                            .Builder()
                            .url(server.uri().toString())
                            .post("synthetic".toRequestBody())
                            .build(),
                    ).execute()
                    .use {
                        it.body!!.string()
                    }
            }
            val failure =
                runCatching {
                    harness.execute(
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                        { wire().also { if (mode == "ACTION") throw AssertionError(marker) } },
                        {
                            if (mode == "VALIDATOR") throw AssertionError(marker)
                            ActivationElevenValidation.ACCEPTED
                        },
                    )
                }.exceptionOrNull()
            val checkpointStopped = runCatching { harness.checkpoint() }.isFailure
            val requestsAfterFailure = server.requests.size
            invalid.set(false)
            val laterStopped =
                runCatching {
                    harness.execute(AiPhysicalOperation.PRECHECK_CLASSIFICATION, { wire() }, { ActivationElevenValidation.ACCEPTED })
                }.isFailure
            val diagnostic = failure?.let { generateSequence(it) { previous -> previous.cause }.joinToString() }.orEmpty()
            assertAll(
                { assertNotNull(failure) },
                { assertFalse(diagnostic.contains(marker), "Semantic assertion content must be sanitized") },
                { assertTrue(checkpointStopped, "Semantic assertion must latch global stop immediately") },
                { assertTrue(laterStopped, "A later valid stage must remain stopped") },
                { assertEquals(requestsAfterFailure, server.requests.size, "No physical request may follow failed assertion") },
            )
        }
    }
}
