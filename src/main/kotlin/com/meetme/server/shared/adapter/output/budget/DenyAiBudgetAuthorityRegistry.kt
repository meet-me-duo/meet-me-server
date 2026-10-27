package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import com.meetme.server.shared.application.port.output.AiBudgetBillRequest
import com.meetme.server.shared.application.port.output.AiBudgetQuoteRequest
import com.meetme.server.shared.application.port.output.AiVerifiedBudgetQuote
import com.meetme.server.shared.application.port.output.AiVerifiedCompleteBill

class DenyAiBudgetAuthorityRegistry : AiBudgetAuthorityRegistryPort {
    override fun quote(request: AiBudgetQuoteRequest): AiVerifiedBudgetQuote? = null

    override fun completeBill(request: AiBudgetBillRequest): AiVerifiedCompleteBill? = null
}
