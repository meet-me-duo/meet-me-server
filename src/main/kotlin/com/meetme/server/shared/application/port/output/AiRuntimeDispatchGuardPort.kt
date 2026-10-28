package com.meetme.server.shared.application.port.output

import java.time.Instant

/** Current execution authority only. No price, quote, accounting epoch or paid budget is implied. */
fun interface AiRuntimeDispatchGuardPort {
    fun authorize(request: AiBudgetReserveRequest, at: Instant): Instant?
}
