package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill
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

    override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
