package com.meetme.server.experiment

import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentAdmissionIds
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Independent ADR-061 experiment contract; synthetic values only, no providers or sealed fixtures. */
class ActivationElevenBudgetContractTest {
    @TempDir lateinit var temporary: Path
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val hash = "a".repeat(64)
    private val roles =
        linkedMapOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to 6,
            AiPhysicalOperation.PRECHECK_GENERATION to 3,
            AiPhysicalOperation.FINAL_PRIMARY to 1,
            AiPhysicalOperation.FINAL_FALLBACK to 1,
        )

    @Test
    fun `A11 source authority is checked before registration and source refusal grants no wire`() {
        val budget = budget()
        var checks = 0
        val source =
            budget.sourceFirst(
                AiRuntimeDispatchGuardPort { _, _ ->
                    checks++
                    null
                },
            )
        val request = request(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
        assertNull(source.authorize(request, at))
        assertEquals(1, checks)
        assertFailsWith<Exception> { send(budget, call(request)) }
        assertEquals(0, budget.snapshot()["admissions"])
    }

    @Test
    fun `A11 source rechecks consume nothing and cannot replace an immutable payload binding`() {
        val budget = budget()
        val source = budget.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> at.plusSeconds(15) })
        val request = request(AiPhysicalOperation.PRECHECK_GENERATION)
        repeat(3) { assertNotNull(source.authorize(request, at)) }
        assertEquals(0, budget.snapshot()["admissions"])
        assertNull(
            source.authorize(request.copy(calls = listOf(request.calls.single().copy(wholePayloadFingerprint = "b".repeat(64)))), at),
        )
        assertEquals(0, budget.snapshot()["admissions"])
        assertFailsWith<Exception> { send(budget, call(request)) }
    }

    @Test
    fun `A11 fixed role maxima admit eleven physical requests and persist before headers`() {
        val budget = budget()
        for ((operation, limit) in roles) {
            repeat(limit) {
                val call = authorize(budget, request(operation))
                budget.callStarted(call.copy(admissionId = null))
                budget.beforeHeaders(call)
                val disk = StandaloneGeminiOnceDiagnostic.JSON.readTree(temporary.resolve("run/ledger.json").toFile())
                assertEquals(budget.snapshot()["admissions"], disk["admissions"].asInt())
                budget.headersEmitted(call)
                budget.callFinished(call, 200, null)
                budget.validateFinished(call.callId, ActivationElevenValidation.ACCEPTED)
            }
        }
        budget.assertComplete()
        assertEquals(11, budget.snapshot()["maximumPhysicalRequests"])
        assertEquals(11, budget.snapshot()["admissions"])
        assertEquals(roles.mapKeys { it.key.name }, budget.snapshot()["counts"])
        assertEquals(roles.mapKeys { it.key.name }, budget.snapshot()["caps"])
        assertEquals(1, budget.snapshot()["actualGemini"])
        assertFailsWith<Exception> { send(budget, authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))) }
        assertEquals(11, budget.snapshot()["admissions"])
        assertFailsWith<Exception> { budget.checkpoint() }
    }

    @Test
    fun `A11 each role cap rejects an extra request while other aggregate capacity remains`() {
        for ((operation, limit) in roles) {
            val budget = budget(operation.name)
            repeat(limit) { send(budget, authorize(budget, request(operation))) }
            assertFailsWith<Exception> { send(budget, authorize(budget, request(operation))) }
            assertEquals(limit, budget.snapshot()["admissions"])
            assertEquals("LIMIT_REACHED", budget.snapshot()["stopReason"])
            assertFailsWith<Exception> { budget.checkpoint() }
        }
    }

    @Test
    fun `A11 count unknown metadata and primary retry ordinal never obtain physical admission`() {
        val invalid =
            listOf(
                request(AiPhysicalOperation.PRECHECK_COUNT),
                request(AiPhysicalOperation.FINAL_PRIMARY).let { it.copy(calls = listOf(it.calls.single().copy(ordinal = 2))) },
            )
        invalid.forEachIndexed { index, request ->
            val budget = budget("invalid$index")
            assertFailsWith<Exception> { send(budget, authorize(budget, request)) }
            assertEquals(0, budget.snapshot()["admissions"])
        }
        val budget = budget("missing-admission")
        val call = call(request(AiPhysicalOperation.PRECHECK_CLASSIFICATION)).copy(admissionId = null)
        assertFailsWith<Exception> { send(budget, call) }
        assertEquals(0, budget.snapshot()["admissions"])
    }

    @Test
    fun `A11 consumed admission and duplicate header events cannot emit again`() {
        for (fault in listOf("call", "admission", "header")) {
            val budget = budget(fault)
            val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
            send(budget, call)
            assertFailsWith<Exception> {
                when (fault) {
                    "call" -> send(budget, call)
                    "admission" -> send(budget, call.copy(callId = UUID.randomUUID()))
                    else -> budget.headersEmitted(call)
                }
            }
            assertEquals(1, budget.snapshot()["admissions"])
            assertFailsWith<Exception> { budget.checkpoint() }
        }
    }

    @Test
    fun `A11 concurrent admissions across roles cannot overlap unfinished physical requests`() {
        val budget = budget()
        val first = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        val second = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
        budget.callStarted(first.copy(admissionId = null))
        budget.callStarted(second.copy(admissionId = null))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(first, second).map { call ->
                    pool.submit<Boolean> {
                        start.await()
                        runCatching { budget.beforeHeaders(call) }.isSuccess
                    }
                }
            start.countDown()
            assertEquals(1, results.count { it.get() })
            assertEquals(1, budget.snapshot()["admissions"])
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `A11 persistence failure never returns wire permission or refunds uncertain admission`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(call.copy(admissionId = null))
        // Make the destination directory unusable, independently of temporary-file naming.
        Files.move(temporary.resolve("run"), temporary.resolve("saved-run"))
        Files.writeString(temporary.resolve("run"), "synthetic filesystem failure")
        val wouldEmit = AtomicInteger()
        assertFailsWith<Exception> {
            budget.beforeHeaders(call)
            wouldEmit.incrementAndGet()
        }
        assertEquals(0, wouldEmit.get())
        assertEquals("COVERAGE_UNKNOWN", budget.snapshot()["stopReason"])
        assertFailsWith<Exception> { budget.checkpoint() }
    }

    @Test
    fun `A11 first actual provider error stops every role and leaves the physical attempt consumed`() {
        for (
        (index, error) in
        listOf(
            401 to null,
            403 to null,
            429 to null,
            503 to null,
            null to AiHttpExperimentTransportFailure.TIMEOUT,
            null to AiHttpExperimentTransportFailure.NETWORK,
        ).withIndex()
        ) {
            val budget = budget("error$index")
            val call = authorize(budget, request(AiPhysicalOperation.FINAL_PRIMARY))
            budget.callStarted(call.copy(admissionId = null))
            budget.beforeHeaders(call)
            budget.headersEmitted(call)
            budget.callFinished(call, error.first, error.second)
            assertEquals(1, budget.snapshot()["admissions"])
            assertEquals("PROVIDER_FAILURE", budget.snapshot()["stopReason"])
            roles.keys.forEach { operation ->
                assertFailsWith<Exception> { send(budget, authorize(budget, request(operation))) }
            }
            assertEquals(1, budget.snapshot()["admissions"])
        }
    }

    @Test
    fun `A11 schema semantic and privacy stop is global even after transport success`() {
        for (reason in listOf(
            ActivationElevenStop.SCHEMA_FAILURE,
            ActivationElevenStop.SEMANTIC_FAILURE,
            ActivationElevenStop.PRIVACY_FAILURE,
        )) {
            val budget = budget(reason.name)
            send(budget, authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION)))
            budget.stop(reason)
            assertFailsWith<Exception> { send(budget, authorize(budget, request(AiPhysicalOperation.FINAL_FALLBACK))) }
            assertFailsWith<Exception> { budget.assertComplete() }
            assertEquals(reason.name, budget.snapshot()["stopReason"])
            assertEquals(1, budget.snapshot()["admissions"])
        }
    }

    @Test
    fun `A11 unfinished missing headers unknown completion and null status cannot pass coverage`() {
        for (fault in listOf("unfinished", "headers", "unknown", "status")) {
            val budget = budget(fault)
            val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
            budget.callStarted(call.copy(admissionId = null))
            budget.checkpoint()
            if (fault != "unfinished") budget.beforeHeaders(call)
            if (fault == "status") budget.headersEmitted(call)
            if (fault != "unfinished") {
                budget.callFinished(
                    if (fault ==
                        "unknown"
                    ) {
                        call.copy(callId = UUID.randomUUID())
                    } else {
                        call
                    },
                    if (fault == "status") null else 200,
                    null,
                )
            }
            assertFailsWith<Exception> { budget.assertComplete() }
            assertEquals("COVERAGE_UNKNOWN", budget.snapshot()["stopReason"])
        }
    }

    @Test
    fun `A11 checkpoint permits a legitimate in flight call but never clears a latched stop`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(call.copy(admissionId = null))
        budget.beforeHeaders(call)
        budget.headersEmitted(call)
        budget.checkpoint()
        budget.stop(ActivationElevenStop.SEMANTIC_FAILURE)
        budget.callFinished(call, 200, null)
        assertFailsWith<Exception> { budget.checkpoint() }
        assertEquals("SEMANTIC_FAILURE", budget.snapshot()["stopReason"])
        assertEquals(1, budget.snapshot()["admissions"])
    }

    @Test
    fun `A11 exact approval bindings are persisted and approval cannot reopen under another ledger path`() {
        val approval = approval("binding")
        val claims = temporary.resolve("binding-claims")
        val path = temporary.resolve("binding/ledger.json")
        val budget = ActivationElevenBudget.open(approval, claims, path)
        val disk = StandaloneGeminiOnceDiagnostic.JSON.readTree(path.toFile())
        assertEquals(approval.approvalId, disk["approvalId"].asString())
        assertEquals(approval.source40, disk["source40"].asString())
        assertEquals(approval.sourceFingerprint64, disk["sourceFingerprint64"].asString())
        assertEquals(approval.configHash64, disk["configHash64"].asString())
        assertEquals(approval.scenarioHash64, disk["scenarioHash64"].asString())
        assertFailsWith<Exception> { ActivationElevenBudget.open(approval, claims, path) }
        assertFailsWith<Exception> { ActivationElevenBudget.open(approval, claims, temporary.resolve("other-ledger.json")) }
        assertFailsWith<Exception> {
            ActivationElevenBudget.open(approval.copy(source40 = "b".repeat(40)), claims, temporary.resolve("changed.json"))
        }
        assertEquals(0, budget.snapshot()["admissions"])
    }

    @Test
    fun `A11 failed ledger creation burns its approval instead of allowing a retry`() {
        val approval = approval("partial")
        val claims = temporary.resolve("partial-claims")
        val ledger = temporary.resolve("already-present.json")
        Files.writeString(ledger, "synthetic previous ledger")
        assertFailsWith<Exception> { ActivationElevenBudget.open(approval, claims, ledger) }
        assertFailsWith<Exception> { ActivationElevenBudget.open(approval, claims, temporary.resolve("fresh.json")) }
        assertEquals("synthetic previous ledger", Files.readString(ledger))
    }

    @Test
    fun `A11 malformed approval fields never acquire execution authority`() {
        val original = approval("malformed")
        val invalid =
            listOf(
                original.copy(approvalId = "../outside"),
                original.copy(source40 = "a".repeat(39)),
                original.copy(sourceFingerprint64 = "g".repeat(64)),
                original.copy(configHash64 = "b".repeat(63)),
                original.copy(scenarioHash64 = "c".repeat(65)),
            )
        invalid.forEachIndexed { index, approval ->
            assertFailsWith<Exception> {
                ActivationElevenBudget.open(approval, temporary.resolve("invalid-claims$index"), temporary.resolve("invalid$index.json"))
            }
        }
    }

    @Test
    fun `A11 HTTP200 remains blocked until independent validation completes`() {
        val budget = budget()
        val first = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        val second = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
        budget.callStarted(first.copy(admissionId = null))
        budget.beforeHeaders(first)
        budget.headersEmitted(first)
        budget.callFinished(first, 200, null)
        budget.checkpoint()
        budget.callStarted(second.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(second) }
        assertEquals(1, budget.snapshot()["admissions"])
        assertFailsWith<Exception> { budget.assertComplete() }
    }

    @Test
    fun `A11 validation rejection and unknown validation fail closed before another role`() {
        for (verdict in listOf(
            ActivationElevenValidation.SCHEMA_FAILURE,
            ActivationElevenValidation.SEMANTIC_FAILURE,
            ActivationElevenValidation.PRIVACY_FAILURE,
        )) {
            val budget = budget(verdict.name)
            val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
            budget.callStarted(call.copy(admissionId = null))
            budget.beforeHeaders(call)
            budget.headersEmitted(call)
            budget.callFinished(call, 200, null)
            budget.validateFinished(call.callId, verdict)
            assertEquals(verdict.name, budget.snapshot()["stopReason"])
            assertFailsWith<Exception> { budget.checkpoint() }
            assertFailsWith<Exception> { budget.assertComplete() }
        }
        val unknown = budget("unknown-validation")
        assertFailsWith<Exception> { unknown.validateFinished(UUID.randomUUID(), ActivationElevenValidation.ACCEPTED) }
        assertFailsWith<Exception> { unknown.checkpoint() }
    }

    @Test
    fun `A11 accepted validation cannot be replayed or claimed before completed transport`() {
        for (fault in listOf("early", "duplicate")) {
            val budget = budget(fault)
            val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
            if (fault == "early") budget.callStarted(call.copy(admissionId = null)) else send(budget, call)
            assertFailsWith<Exception> { budget.validateFinished(call.callId, ActivationElevenValidation.ACCEPTED) }
            assertFailsWith<Exception> { budget.checkpoint() }
        }
    }

    private fun approval(name: String) =
        ActivationElevenApproval("Activation11_$name", "a".repeat(40), hash, "b".repeat(64), "c".repeat(64))

    private fun budget(name: String = "run") =
        ActivationElevenBudget.open(approval(name), temporary.resolve("claims"), temporary.resolve(name).resolve("ledger.json"))

    private fun request(operation: AiPhysicalOperation): AiBudgetReserveRequest {
        val final = operation in setOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.FINAL_FALLBACK)
        return AiBudgetReserveRequest(
            AiBudgetExecution(
                UUID.randomUUID(),
                if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                hash,
                hash,
            ),
            listOf(AiPhysicalCallSpec(operation, 1, hash, hash, if (final) UUID.randomUUID() else null)),
        )
    }

    private fun call(request: AiBudgetReserveRequest): AiHttpExperimentCall {
        val spec = request.calls.single()
        return AiHttpExperimentCall(
            UUID.randomUUID(),
            request.execution.executionId,
            spec.attemptId,
            spec.operation,
            spec.ordinal,
            AiHttpExperimentAdmissionIds.of(request, spec).opaqueId,
        )
    }

    private fun authorize(
        budget: ActivationElevenBudget,
        request: AiBudgetReserveRequest,
    ): AiHttpExperimentCall {
        checkNotNull(budget.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> at.plusSeconds(15) }).authorize(request, at))
        return call(request)
    }

    private fun send(
        budget: ActivationElevenBudget,
        call: AiHttpExperimentCall,
    ) {
        budget.callStarted(call.copy(admissionId = null))
        budget.beforeHeaders(call)
        budget.headersEmitted(call)
        budget.callFinished(call, 200, null)
        budget.validateFinished(call.callId, ActivationElevenValidation.ACCEPTED)
    }
}
