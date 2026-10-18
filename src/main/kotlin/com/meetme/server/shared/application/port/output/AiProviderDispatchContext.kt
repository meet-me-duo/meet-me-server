package com.meetme.server.shared.application.port.output

import java.util.UUID

/** Local execution context only. Persisted ownership/epoch checks remain authoritative. */
data class AiProviderDispatchContext(
    val execution: AiBudgetExecution,
    val deadlineNanos: Long,
    val finalAttemptId: UUID? = null,
    val providerOrdinal: Int = 1,
)
