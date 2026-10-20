package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ApprovedAiOperationPolicy
import com.meetme.server.config.ApprovedAiRuntimePolicy
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiBudgetUnit
import com.meetme.server.shared.application.port.output.AiPhysicalCallSpec
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ADR055 + agreed one-invocation protection. TEST units are not approved prices or runtime caps. */
class Issue123ProtectedFinalReserveRegistryTest {
    private val now = Instant.parse("2026-10-20T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policyHash = "1".repeat(64)
    private val unit = AiBudgetUnit("TEST", 0)
    private val policy =
        ApprovedAiRuntimePolicy(
            policyHash,
            unit,
            BigInteger.valueOf(100),
            0.73,
            AiPhysicalOperation.entries.associateWith { operation ->
                ApprovedAiOperationPolicy(
                    "2".repeat(64),
                    "3".repeat(64),
                    BigInteger.valueOf(if (operation == AiPhysicalOperation.FINAL_FALLBACK) 7 else 10),
                    now.plusSeconds(if (operation == AiPhysicalOperation.FINAL_FALLBACK) 30 else 60),
                )
            },
        )

    @Test
    fun `PF01 derive four primary plus one fallback in the exact approved unit with earliest validity`() {
        val result = assertNotNull(authority(policy).protectedFinalReserve(request()))
        assertEquals(BigInteger.valueOf(47), result.amount)
        assertEquals(policyHash, result.policyFingerprint)
        assertEquals(unit, result.unit)
        assertEquals(now.plusSeconds(30), result.validUntil)
        assertEquals(result, authority(policy).protectedFinalReserve(request()))
    }

    @Test
    fun `PF02 disabled and unconfigured authorities never supply protected capacity`() {
        assertNull(ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(false, policy), clock).protectedFinalReserve(request()))
        assertNull(ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, null), clock).protectedFinalReserve(request()))
    }

    @Test
    fun `PF02 derived protection is not complete bill proof and never releases an observed usage hold`() {
        val registry = authority(policy)
        assertNotNull(registry.protectedFinalReserve(request()))
        assertNull(registry.completeBill(AiBudgetBillRequest(UUID.randomUUID(), "8".repeat(64), "usage-only-not-a-bill")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["POLICY", "PROFILE", "PAYLOAD", "MISSING_PRIMARY", "MISSING_FALLBACK", "EXPIRED_PRIMARY", "EXPIRED_FALLBACK"])
    fun `PF03 foreign request and missing or expired final evidence cannot protect future capacity`(mutation: String) {
        val changedPolicy =
            when (mutation) {
                "MISSING_PRIMARY" -> policy.copy(operations = policy.operations - AiPhysicalOperation.FINAL_PRIMARY)
                "MISSING_FALLBACK" -> policy.copy(operations = policy.operations - AiPhysicalOperation.FINAL_FALLBACK)
                "EXPIRED_PRIMARY" -> changed(AiPhysicalOperation.FINAL_PRIMARY) { it.copy(validUntil = now) }
                "EXPIRED_FALLBACK" -> changed(AiPhysicalOperation.FINAL_FALLBACK) { it.copy(validUntil = now) }
                else -> policy
            }
        val original = request()
        val changedRequest =
            when (mutation) {
                "POLICY" -> original.copy(policyFingerprint = "4".repeat(64))
                "PROFILE" -> original.copy(call = original.call.copy(profileFingerprint = "4".repeat(64)))
                "PAYLOAD" -> original.copy(call = original.call.copy(wholePayloadFingerprint = "invalid"))
                else -> original
            }
        assertNull(assertDoesNotThrow { authority(changedPolicy).protectedFinalReserve(changedRequest) })
    }

    @ParameterizedTest
    @ValueSource(strings = ["NEGATIVE_PRIMARY", "NEGATIVE_FALLBACK", "OVERFLOW", "BAD_UNIT", "BAD_SCALE"])
    fun `PF04 invalid arithmetic and unit fail closed without clipping`(mutation: String) {
        val changed =
            when (mutation) {
                "NEGATIVE_PRIMARY" -> changed(AiPhysicalOperation.FINAL_PRIMARY) { it.copy(maximumAmount = BigInteger.valueOf(-1)) }
                "NEGATIVE_FALLBACK" -> changed(AiPhysicalOperation.FINAL_FALLBACK) { it.copy(maximumAmount = BigInteger.valueOf(-1)) }
                "OVERFLOW" ->
                    changed(
                        AiPhysicalOperation.FINAL_PRIMARY,
                    ) { it.copy(maximumAmount = BigInteger.TEN.pow(38).divide(BigInteger.valueOf(4))) }
                "BAD_UNIT" -> policy.copy(unit = unit.copy(code = " TEST"))
                "BAD_SCALE" -> policy.copy(unit = unit.copy(scale = 39))
                else -> error("Unknown synthetic mutation")
            }
        assertNull(assertDoesNotThrow { authority(changed).protectedFinalReserve(request()) })
    }

    private fun authority(policy: ApprovedAiRuntimePolicy) =
        ConfiguredAiBudgetAuthorityRegistry(ManagedAiRuntimeSelection(true, policy), clock)

    private fun changed(
        operation: AiPhysicalOperation,
        transform: (ApprovedAiOperationPolicy) -> ApprovedAiOperationPolicy,
    ) = policy.copy(operations = policy.operations + (operation to transform(policy.operations.getValue(operation))))

    private fun request() =
        AiBudgetQuoteRequest(
            AiBudgetExecution(
                UUID.randomUUID(),
                AiBudgetExecutionKind.PRECHECK,
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                1,
                "5".repeat(64),
                "6".repeat(64),
            ),
            AiPhysicalCallSpec(AiPhysicalOperation.PRECHECK_CLASSIFICATION, 1, "7".repeat(64), "2".repeat(64)),
            policyHash,
        )
}
