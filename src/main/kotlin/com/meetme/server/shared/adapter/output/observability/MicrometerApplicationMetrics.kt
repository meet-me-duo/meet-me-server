package com.meetme.server.shared.adapter.output.observability

import com.meetme.server.config.AiCostProperties
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

@Component
class MicrometerApplicationMetrics(
    private val registry: MeterRegistry,
    private val costProperties: AiCostProperties = AiCostProperties(),
) : ApplicationMetricsPort {
    override fun outboxPublished(recovered: Boolean) {
        registry.counter("meetme.outbox.published", "recovered", recovered.toString()).increment()
    }

    override fun workerCompleted(outcome: String) {
        registry.counter("meetme.coordination.worker.completed", "outcome", outcome).increment()
    }

    override fun deadLettered(failureKind: String) {
        registry.counter("meetme.coordination.dlq.entries", "failure_kind", sanitizeFailure(failureKind)).increment()
    }

    override fun rateLimit(
        category: String,
        outcome: String,
    ) {
        registry.counter("meetme.http.rate_limit", "category", category, "outcome", outcome).increment()
    }

    override fun geminiBatch(
        outcome: String,
        elapsed: Duration,
        attempts: Int,
        estimatedCostUsd: BigDecimal?,
    ) {
        registry.timer("meetme.gemini.batch.duration", "outcome", outcome).record(elapsed)
        registry.counter("meetme.gemini.batch.completed", "outcome", outcome).increment()
        registry.summary("meetme.gemini.batch.attempts", "outcome", outcome).record(attempts.toDouble())
        estimatedCostUsd?.let { costUsd ->
            registry.summary("meetme.ai.cost.usd", "provider", "gemini").record(costUsd.toDouble())
            val costKrw = costUsd.multiply(costProperties.usdToKrw)
            registry.summary("meetme.ai.cost.krw", "provider", "gemini").record(costKrw.toDouble())
            registry
                .counter(
                    "meetme.ai.cost.budget",
                    "provider",
                    "gemini",
                    "outcome",
                    if (costKrw < costProperties.maximumKrwPerMeeting) "UNDER_LIMIT" else "LIMIT_EXCEEDED",
                ).increment()
        }
    }

    override fun matching(
        outcome: String,
        elapsed: Duration,
        candidateCount: Int,
    ) {
        registry.timer("meetme.matching.duration", "outcome", outcome).record(elapsed)
        registry.summary("meetme.matching.candidates", "outcome", outcome).record(candidateCount.toDouble())
    }

    override fun authenticationFailure(code: String) {
        registry.counter("meetme.authentication.failures", "code", code).increment()
    }

    override fun externalApi(
        provider: String,
        outcome: String,
        elapsed: Duration,
    ) {
        registry.timer("meetme.external.api.duration", "provider", provider, "outcome", outcome).record(elapsed)
        registry.counter("meetme.external.api.completed", "provider", provider, "outcome", outcome).increment()
    }

    private fun sanitizeFailure(value: String): String = if (value in ALLOWED_FAILURES) value else "OTHER"

    private companion object {
        val ALLOWED_FAILURES =
            setOf(
                "IllegalArgumentException",
                "IllegalStateException",
                "JsonProcessingException",
                "DataIntegrityViolationException",
                "DESERIALIZATION_FAILURE",
                "OTHER",
            )
    }
}
