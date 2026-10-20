package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
import com.meetme.server.shared.application.port.output.AiVerifiedFinalReserve
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Clock

class ConfiguredAiBudgetAuthorityRegistry(
    private val selection: ManagedAiRuntimeSelection,
    private val clock: Clock,
) : AiBudgetAuthorityRegistryPort {
    override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote? {
        val policy = selection.policy?.takeIf { selection.enabled } ?: return null
        val operation = policy.operations[request.call.operation] ?: return null
        if (request.policyFingerprint != policy.fingerprint ||
            request.call.profileFingerprint != operation.profileFingerprint ||
            !HASH.matches(request.call.wholePayloadFingerprint) ||
            operation.validUntil <= clock.instant()
        ) {
            return null
        }
        val fields =
            listOf(
                request.call.operation.name,
                policy.fingerprint,
                operation.profileFingerprint,
                request.call.wholePayloadFingerprint,
                operation.evidenceFingerprint,
                operation.maximumAmount.toString(),
                policy.unit.code,
                policy.unit.scale.toString(),
                operation.validUntil.toString(),
            )
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(fields.joinToString("\n").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return AiVerifiedBudgetQuote(
            fingerprint,
            operation.profileFingerprint,
            request.call.wholePayloadFingerprint,
            policy.fingerprint,
            operation.evidenceFingerprint,
            operation.maximumAmount,
            policy.unit,
            operation.validUntil,
        )
    }

    override fun protectedFinalReserve(request: AiBudgetQuoteRequest): AiVerifiedFinalReserve? {
        val policy = selection.policy?.takeIf { selection.enabled } ?: return null
        if (request.execution.kind != AiBudgetExecutionKind.PRECHECK || quote(request) == null) return null
        val primary = policy.operations[AiPhysicalOperation.FINAL_PRIMARY] ?: return null
        val fallback = policy.operations[AiPhysicalOperation.FINAL_FALLBACK] ?: return null
        val unit = policy.unit
        if (unit.code.isBlank() ||
            unit.code.length > 32 ||
            unit.code != unit.code.trim() ||
            unit.code.any { it == '\u0000' || it == '\r' || it == '\n' } ||
            unit.scale !in 0..38 ||
            listOf(primary, fallback).any {
                !HASH.matches(it.profileFingerprint) ||
                    !HASH.matches(it.evidenceFingerprint) ||
                    it.maximumAmount.signum() < 0 ||
                    it.maximumAmount > MAX_AMOUNT ||
                    it.validUntil <= clock.instant()
            }
        ) {
            return null
        }
        val amount = primary.maximumAmount.multiply(BigInteger.valueOf(4)) + fallback.maximumAmount
        if (amount > MAX_AMOUNT) return null
        return AiVerifiedFinalReserve(policy.fingerprint, amount, unit, minOf(primary.validUntil, fallback.validUntil))
    }

    override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private val MAX_AMOUNT = BigInteger.TEN.pow(38) - BigInteger.ONE
    }
}
