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
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approval limits, source-first binding and uncertain/error fail-closed behavior. No provider traffic. */
class BlindFlowBudgetContractTest {
    @TempDir lateinit var temporary: Path
    private val at = Instant.parse("2026-10-30T06:48:00Z")
    private val approval = "Sentinel_f19d2f59a82e7fd652feb99ee65890ba"
    private val fingerprint = "a".repeat(64)
    private val manifest = "b".repeat(64)

    @Test
    fun `source refusal cannot register wire authority or consume budget`() {
        val budget = budget()
        var originalChecks = 0
        val wrapped =
            budget.sourceFirst(
                AiRuntimeDispatchGuardPort { _, _ ->
                    originalChecks++
                    null
                },
            )
        val request = request(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
        assertNull(wrapped.authorize(request, at))
        assertEquals(1, originalChecks)
        assertEquals(0, budget.snapshot()["sourceChecks"])
        val call = call(request)
        budget.callStarted(call.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(call) }
        assertEquals(0, budget.snapshot()["admissions"])
    }

    @Test
    fun `source rechecks do not spend and bundle count metadata grants no count request`() {
        val budget = budget()
        val count = AiPhysicalCallSpec(AiPhysicalOperation.PRECHECK_COUNT, 1, fingerprint, fingerprint)
        val generation = count.copy(operation = AiPhysicalOperation.PRECHECK_GENERATION)
        val request = request(AiPhysicalOperation.PRECHECK_GENERATION).copy(calls = listOf(count, generation))
        val source = budget.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> at.plusSeconds(15) })
        repeat(3) { assertNotNull(source.authorize(request, at)) }
        assertEquals(3, budget.snapshot()["sourceChecks"])
        assertEquals(0, budget.snapshot()["admissions"])
        val call = call(request.copy(calls = listOf(generation)))
        complete(budget, call)
        assertEquals(1, budget.snapshot()["admissions"])
        assertFailsWith<Exception> { budget.callStarted(call(request.copy(calls = listOf(count)))) }
        assertEquals(1, budget.snapshot()["admissions"])
    }

    @Test
    fun `all twenty legal requests persist exact role maxima before rejecting twenty first`() {
        val budget = budget()
        for ((operation, count) in listOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to 12,
            AiPhysicalOperation.PRECHECK_GENERATION to 6,
            AiPhysicalOperation.FINAL_FALLBACK to 2,
        )) {
            repeat(count) { complete(budget, authorize(budget, request(operation))) }
        }
        budget.assertComplete()
        assertEquals(20, budget.snapshot()["admissions"])
        val onDisk = StandaloneGeminiOnceDiagnostic.JSON.readTree(temporary.resolve("ledger.json").toFile())
        assertEquals(20, onDisk["admissions"].asInt())
        assertEquals(12, onDisk["counts"]["PRECHECK_CLASSIFICATION"].asInt())
        assertEquals(6, onDisk["counts"]["PRECHECK_GENERATION"].asInt())
        assertEquals(2, onDisk["counts"]["FINAL_FALLBACK"].asInt())
        val extra = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(extra.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(extra) }
        assertEquals(20, budget.snapshot()["admissions"])
    }

    @Test
    fun `role sublimits apply even when aggregate capacity remains`() {
        for ((operation, count) in listOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to 12,
            AiPhysicalOperation.PRECHECK_GENERATION to 6,
            AiPhysicalOperation.FINAL_FALLBACK to 2,
        )) {
            val budget = budget(operation.name)
            repeat(count) { complete(budget, authorize(budget, request(operation))) }
            val extra = authorize(budget, request(operation))
            budget.callStarted(extra.copy(admissionId = null))
            assertFailsWith<Exception> { budget.beforeHeaders(extra) }
            assertEquals(count, budget.snapshot()["admissions"])
            assertEquals("LIMIT_REACHED", budget.snapshot()["stopReason"])
        }
    }

    @Test
    fun `duplicate call and new call replaying consumed admission cannot resend`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
        complete(budget, call)
        val replay = call.copy(callId = UUID.randomUUID())
        budget.callStarted(replay.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(replay) }
        assertEquals(1, budget.snapshot()["admissions"])
        assertEquals("DUPLICATE_REQUEST", budget.snapshot()["stopReason"])
    }

    @Test
    fun `unknown and primary operations are blocked without provider admission`() {
        for (operation in listOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.PRECHECK_COUNT)) {
            val budget = budget(operation.name)
            assertFailsWith<Exception> { budget.callStarted(call(request(operation))) }
            assertEquals(0, budget.snapshot()["admissions"])
        }
    }

    @Test
    fun `first real HTTP or transport error prevents all subsequent roles`() {
        for ((status, failure) in listOf(
            503 to null,
            429 to null,
            529 to null,
            null to AiHttpExperimentTransportFailure.TIMEOUT,
            null to AiHttpExperimentTransportFailure.NETWORK,
        )) {
            val budget = budget(UUID.randomUUID().toString())
            val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
            budget.callStarted(call.copy(admissionId = null))
            budget.beforeHeaders(call)
            budget.headersEmitted(call)
            budget.callFinished(call, status, failure)
            assertEquals("PROVIDER_FAILURE", budget.snapshot()["stopReason"])
            assertFailsWith<Exception> { budget.callStarted(call(request(AiPhysicalOperation.FINAL_FALLBACK))) }
            assertEquals(1, budget.snapshot()["admissions"])
        }
    }

    @Test
    fun `HTTP success with technical schema failure is explicitly stopped by caller`() {
        val budget = budget()
        complete(budget, authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION)))
        budget.stop(BlindFlowStop.PROVIDER_FAILURE)
        assertFailsWith<Exception> { budget.callStarted(call(request(AiPhysicalOperation.FINAL_FALLBACK))) }
        assertEquals(1, budget.snapshot()["admissions"])
    }

    @Test
    fun `unknown completion and missing header coverage fail closed`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(call.copy(admissionId = null))
        budget.beforeHeaders(call)
        budget.headersEmitted(call)
        budget.callFinished(call, null, null)
        assertEquals("COVERAGE_UNKNOWN", budget.snapshot()["stopReason"])
        assertFailsWith<Exception> { budget.assertComplete() }
        val incomplete = budget("incomplete")
        incomplete.callStarted(call.copy(admissionId = null))
        assertFailsWith<Exception> { incomplete.assertComplete() }
        assertEquals("COVERAGE_UNKNOWN", incomplete.snapshot()["stopReason"])
    }

    @Test
    fun `mutated source identity is rejected before any wire`() {
        val budget = budget()
        val request = request(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
        val source = budget.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> at.plusSeconds(15) })
        assertNotNull(source.authorize(request, at))
        assertNull(
            source.authorize(request.copy(calls = listOf(request.calls.single().copy(wholePayloadFingerprint = "c".repeat(64)))), at),
        )
        assertEquals(0, budget.snapshot()["admissions"])
    }

    @Test
    fun `concurrent wire admissions cannot overlap and failed persistence cannot permit wire`() {
        val budget = budget()
        val first = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        val second = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(first.copy(admissionId = null))
        budget.callStarted(second.copy(admissionId = null))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val outcomes =
                pool.invokeAll(
                    listOf(
                        java.util.concurrent.Callable {
                            runCatching {
                                budget.beforeHeaders(first)
                            }.isSuccess
                        },
                        java.util.concurrent.Callable { runCatching { budget.beforeHeaders(second) }.isSuccess },
                    ),
                )
            assertEquals(1, outcomes.count { it.get() })
            assertEquals(1, budget.snapshot()["admissions"])
        } finally {
            pool.shutdownNow()
        }
        val blocked = budget("blocked")
        val call = authorize(blocked, request(AiPhysicalOperation.PRECHECK_GENERATION))
        blocked.callStarted(call.copy(admissionId = null))
        Files.writeString(temporary.resolve("blocked.pending"), "indeterminate interrupted write")
        assertFailsWith<Exception> { blocked.beforeHeaders(call) }
        assertEquals("COVERAGE_UNKNOWN", blocked.snapshot()["stopReason"])
    }

    @Test
    fun `approval and ledger are one use including after incomplete execution`() {
        val claim = BlindFlowBudget.claim(temporary.resolve("claims"), approval, manifest, fingerprint)
        assertTrue(Files.readString(claim).contains(manifest))
        assertFailsWith<Exception> { BlindFlowBudget.claim(temporary.resolve("claims"), approval, "c".repeat(64), fingerprint) }
        budget()
        assertFailsWith<Exception> { budget() }
    }

    @Test
    fun `poll checkpoint allows in flight call but rejects every action after global stop`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(call.copy(admissionId = null))
        budget.checkpoint()
        budget.beforeHeaders(call)
        budget.headersEmitted(call)
        budget.checkpoint()
        budget.stop(BlindFlowStop.PROVIDER_FAILURE)
        assertFailsWith<Exception> { budget.checkpoint() }
        budget.callFinished(call, 200, null)
        assertFailsWith<Exception> { budget.assertComplete() }
        assertEquals(1, budget.snapshot()["admissions"])
    }

    private fun budget(name: String = "ledger.json") = BlindFlowBudget(temporary.resolve(name), approval, manifest, fingerprint)

    private fun request(operation: AiPhysicalOperation): AiBudgetReserveRequest {
        val final = operation == AiPhysicalOperation.FINAL_FALLBACK || operation == AiPhysicalOperation.FINAL_PRIMARY
        return AiBudgetReserveRequest(
            AiBudgetExecution(
                UUID.randomUUID(),
                if (final) AiBudgetExecutionKind.FINAL_ANALYSIS else AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                fingerprint,
                fingerprint,
            ),
            listOf(AiPhysicalCallSpec(operation, 1, fingerprint, fingerprint, if (final) UUID.randomUUID() else null)),
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
        budget: BlindFlowBudget,
        request: AiBudgetReserveRequest,
    ): AiHttpExperimentCall {
        assertNotNull(budget.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> at.plusSeconds(15) }).authorize(request, at))
        return call(request)
    }

    private fun complete(
        budget: BlindFlowBudget,
        call: AiHttpExperimentCall,
    ) {
        budget.callStarted(call.copy(admissionId = null))
        budget.beforeHeaders(call)
        budget.headersEmitted(call)
        budget.callFinished(call, 200, null)
    }
}
