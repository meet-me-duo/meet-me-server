package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.application.port.output.AiCallBudgetRequest
import com.meetme.server.shared.application.port.output.AiCallBudgetReservation
import com.meetme.server.shared.application.port.output.AiCallObservation

class DefaultDenyAiCallBudget : AiCallBudgetPort {
    override fun reserve(request: AiCallBudgetRequest): AiCallBudgetReservation? = null

    override fun record(
        reservation: AiCallBudgetReservation,
        observation: AiCallObservation,
    ): Unit = throw IllegalStateException("NO_GRANTED_RESERVATION")
}
