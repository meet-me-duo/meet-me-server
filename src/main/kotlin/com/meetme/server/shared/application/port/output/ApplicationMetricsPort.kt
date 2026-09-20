package com.meetme.server.shared.application.port.output

import java.math.BigDecimal
import java.time.Duration

interface ApplicationMetricsPort {
    fun outboxPublished(recovered: Boolean)

    fun workerCompleted(outcome: String)

    fun deadLettered(failureKind: String)

    fun rateLimit(
        category: String,
        outcome: String,
    )

    fun geminiBatch(
        outcome: String,
        elapsed: Duration,
        attempts: Int,
        estimatedCostUsd: BigDecimal?,
    )

    fun matching(
        outcome: String,
        elapsed: Duration,
        candidateCount: Int,
    )

    fun authenticationFailure(code: String)

    fun externalApi(
        provider: String,
        outcome: String,
        elapsed: Duration,
    )
}
