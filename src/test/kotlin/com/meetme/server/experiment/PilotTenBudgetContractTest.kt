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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Independent contracts for the new approval. No application, provider, scenario or previous ledger is opened. */
class PilotTenBudgetContractTest {
    @TempDir lateinit var temporary: Path
    private val at = Instant.parse("2026-10-30T12:00:00Z")
    private val approval = "Sentinel_300cb71b79ff69b2003894c216d6c028"
    private val manifest = "a".repeat(64)
    private val source = "b".repeat(64)
    private val limits =
        mapOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to 6,
            AiPhysicalOperation.PRECHECK_GENERATION to 3,
            AiPhysicalOperation.FINAL_FALLBACK to 1,
        )

    @Test
    fun `new approval snapshot and durable ledger declare exactly six three one and ten`() {
        val budget = budget()
        val snapshot = budget.snapshot()
        assertEquals(approval, snapshot["approvalId"])
        assertEquals(10, snapshot["maximumPhysicalRequests"])
        assertEquals(limits.mapKeys { it.key.name }, snapshot["caps"])
        assertEquals(0, snapshot["admissions"])
        assertEquals(0, snapshot["actualGemini"])
        val disk = StandaloneGeminiOnceDiagnostic.JSON.readTree(temporary.resolve("ledger.json").toFile())
        assertEquals(10, disk["maximumPhysicalRequests"].asInt())
        assertEquals(6, disk["caps"]["PRECHECK_CLASSIFICATION"].asInt())
        assertEquals(3, disk["caps"]["PRECHECK_GENERATION"].asInt())
        assertEquals(1, disk["caps"]["FINAL_FALLBACK"].asInt())
    }

    @Test
    fun `ten approved physical requests finish and no eleventh request reaches headers`() {
        val budget = budget()
        limits.forEach { (operation, maximum) ->
            repeat(maximum) { succeed(budget, authorize(budget, request(operation))) }
        }
        budget.assertComplete()
        assertEquals(10, budget.snapshot()["admissions"])
        assertEquals(limits.mapKeys { it.key.name }, budget.snapshot()["counts"])
        val extra = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(extra.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(extra) }
        assertEquals(10, budget.snapshot()["admissions"])
        val disk = StandaloneGeminiOnceDiagnostic.JSON.readTree(temporary.resolve("ledger.json").toFile())
        assertEquals(10, disk["admissions"].asInt())
        assertEquals("LIMIT_REACHED", disk["stopReason"].asString())
    }

    @Test
    fun `every role stops at its own cap while total capacity remains`() {
        limits.forEach { (operation, maximum) ->
            val budget = budget(operation.name)
            repeat(maximum) { succeed(budget, authorize(budget, request(operation))) }
            val extra = authorize(budget, request(operation))
            budget.callStarted(extra.copy(admissionId = null))
            assertFailsWith<Exception> { budget.beforeHeaders(extra) }
            assertEquals(maximum, budget.snapshot()["admissions"])
            assertEquals("LIMIT_REACHED", budget.snapshot()["stopReason"])
            assertFailsWith<Exception> { budget.checkpoint() }
        }
    }

    @Test
    fun `aggregate cap independently stops a request still within its role allowance`() {
        val budget = budget(maximum = 9)
        repeat(6) { succeed(budget, authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))) }
        repeat(2) { succeed(budget, authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))) }
        succeed(budget, authorize(budget, request(AiPhysicalOperation.FINAL_FALLBACK)))
        val lastPersonal = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
        budget.callStarted(lastPersonal.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(lastPersonal) }
        assertEquals(9, budget.snapshot()["admissions"])
        assertEquals("LIMIT_REACHED", budget.snapshot()["stopReason"])
    }

    @Test
    fun `first actual error globally denies all subsequent provider operations`() {
        for ((status, failure) in listOf(
            503 to null,
            429 to null,
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
            for (operation in AiPhysicalOperation.entries) {
                assertFailsWith<Exception> { budget.callStarted(call(request(operation))) }
            }
            assertEquals(1, budget.snapshot()["admissions"])
            assertFailsWith<Exception> { budget.assertComplete() }
        }
    }

    @Test
    fun `completion without any header admission stays unknown and spends zero`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_CLASSIFICATION))
        budget.callStarted(call.copy(admissionId = null))
        budget.callFinished(call, null, null)
        assertEquals(0, budget.snapshot()["admissions"])
        assertEquals("COVERAGE_UNKNOWN", budget.snapshot()["stopReason"])
        assertEquals(false, budget.snapshot()["coverageComplete"])
        assertFailsWith<Exception> { budget.checkpoint() }
        val disk = StandaloneGeminiOnceDiagnostic.JSON.readTree(temporary.resolve("ledger.json").toFile())
        assertEquals(0, disk["admissions"].asInt())
        assertEquals(10, disk["maximumPhysicalRequests"].asInt())
    }

    @Test
    fun `original source denial precedes experiment authority and immutable source cannot change`() {
        val denied = budget("denied")
        val request = request(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
        var originalChecks = 0
        val delegate =
            AiRuntimeDispatchGuardPort { _, _ ->
                originalChecks++
                null
            }
        assertNull(denied.sourceFirst(delegate).authorize(request, at))
        assertEquals(1, originalChecks)
        assertEquals(0, denied.snapshot()["sourceChecks"])
        assertEquals(0, denied.snapshot()["admissions"])
        val immutable = budget("immutable")
        val sourceFirst = immutable.sourceFirst(AiRuntimeDispatchGuardPort { _, _ -> at.plusSeconds(15) })
        repeat(2) { assertNotNull(sourceFirst.authorize(request, at)) }
        val changed = request.copy(calls = listOf(request.calls.single().copy(wholePayloadFingerprint = "c".repeat(64))))
        assertNull(sourceFirst.authorize(changed, at))
        assertEquals(0, immutable.snapshot()["admissions"])
        assertEquals("INVALID_METADATA", immutable.snapshot()["stopReason"])
    }

    @Test
    fun `same admission replay and ordinal two cannot become a second physical request`() {
        val budget = budget()
        val call = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
        succeed(budget, call)
        val replay = call.copy(callId = UUID.randomUUID())
        budget.callStarted(replay.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(replay) }
        assertEquals(1, budget.snapshot()["admissions"])
        val ordinal = budget("ordinal")
        val illegal = call(request(AiPhysicalOperation.PRECHECK_CLASSIFICATION)).copy(ordinal = 2)
        assertFailsWith<Exception> { ordinal.callStarted(illegal) }
        assertEquals(0, ordinal.snapshot()["admissions"])
    }

    @Test
    fun `Gemini primary and token counting requests remain forbidden`() {
        for (operation in listOf(AiPhysicalOperation.FINAL_PRIMARY, AiPhysicalOperation.PRECHECK_COUNT)) {
            val budget = budget(operation.name)
            assertFailsWith<Exception> { budget.callStarted(call(request(operation))) }
            assertEquals(0, budget.snapshot()["admissions"])
            assertEquals(0, budget.snapshot()["actualGemini"])
        }
    }

    @Test
    fun `caller mutation of the supplied cap map cannot broaden the approval`() {
        val supplied = limits.toMutableMap()
        val budget = budget(caps = supplied)
        supplied[AiPhysicalOperation.PRECHECK_GENERATION] = 30
        supplied[AiPhysicalOperation.FINAL_PRIMARY] = 1
        assertEquals(limits.mapKeys { it.key.name }, budget.snapshot()["caps"])
        repeat(3) { succeed(budget, authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))) }
        val fourth = authorize(budget, request(AiPhysicalOperation.PRECHECK_GENERATION))
        budget.callStarted(fourth.copy(admissionId = null))
        assertFailsWith<Exception> { budget.beforeHeaders(fourth) }
        assertEquals(3, budget.snapshot()["admissions"])
    }

    @Test
    fun `new approval claims are one use and cannot consume or reset the old approval`() {
        val claims = temporary.resolve("claims")
        val oldApproval = "Sentinel_f19d2f59a82e7fd652feb99ee65890ba"
        val oldClaim = BlindFlowBudget.claim(claims, oldApproval, "d".repeat(64), source)
        val oldBytes = Files.readAllBytes(oldClaim)
        val fresh = BlindFlowBudget.claim(claims, approval, manifest, source)
        assertFalse(oldClaim == fresh)
        assertEquals(oldBytes.toList(), Files.readAllBytes(oldClaim).toList())
        assertFailsWith<Exception> { BlindFlowBudget.claim(claims, approval, "e".repeat(64), source) }
        assertEquals(oldBytes.toList(), Files.readAllBytes(oldClaim).toList())
    }

    private fun budget(
        name: String = "ledger.json",
        caps: Map<AiPhysicalOperation, Int> = limits,
        maximum: Int = 10,
    ) = BlindFlowBudget(temporary.resolve(name), approval, manifest, source, caps = caps, maximumPhysicalRequests = maximum)

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
                source,
                source,
            ),
            listOf(AiPhysicalCallSpec(operation, 1, source, source, if (final) UUID.randomUUID() else null)),
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

    private fun succeed(
        budget: BlindFlowBudget,
        call: AiHttpExperimentCall,
    ) {
        budget.callStarted(call.copy(admissionId = null))
        budget.beforeHeaders(call)
        budget.headersEmitted(call)
        budget.callFinished(call, 200, null)
    }
}
