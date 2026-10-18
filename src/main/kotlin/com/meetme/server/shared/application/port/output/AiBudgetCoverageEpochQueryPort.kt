package com.meetme.server.shared.application.port.output

import java.time.Instant
import java.util.UUID

/** Read-only eligibility hint. The coordinator revalidates epoch/state after the last budget lock. */
fun interface AiBudgetCoverageEpochQueryPort {
    fun trackedEpoch(
        roomId: UUID,
        executionStartedAt: Instant,
    ): UUID?
}
