package com.meetme.server.shared.application.service

import com.meetme.server.shared.application.port.output.AiBudgetCallState
import com.meetme.server.shared.application.port.output.AiBudgetSettlementRequest
import com.meetme.server.shared.application.port.output.AiCompleteBillEvidenceCatalogPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository

data class AiBudgetSettlementImportResult(
    val settled: Int,
    val unresolved: Int,
)

class AiBudgetSettlementImportService(
    private val catalog: AiCompleteBillEvidenceCatalogPort,
    private val repository: DurableAiBudgetRepository,
    private val budget: DurableAiBudgetPort,
) {
    /** No enclosing transaction: each call goes through the existing transactional budget proxy and its lock order. */
    fun importReviewedBills(): AiBudgetSettlementImportResult {
        val entries = catalog.entries()
        if (entries.size > 128) return AiBudgetSettlementImportResult(0, entries.size)
        var settled = 0
        var unresolved = 0
        entries.forEach { evidence ->
            val accepted =
                try {
                    val call = repository.findCall(evidence.reservationId)
                    val token = call?.dispatchToken
                    call != null &&
                        token != null &&
                        call.state != AiBudgetCallState.RESERVED &&
                        call.quote.quoteFingerprint == evidence.quoteFingerprint &&
                        call.quote.unit == evidence.unit &&
                        budget.settle(AiBudgetSettlementRequest(call.id, token, evidence.evidenceReference))
                } catch (_: Exception) {
                    false
                }
            if (accepted) settled++ else unresolved++
        }
        return AiBudgetSettlementImportResult(settled, unresolved)
    }
}
