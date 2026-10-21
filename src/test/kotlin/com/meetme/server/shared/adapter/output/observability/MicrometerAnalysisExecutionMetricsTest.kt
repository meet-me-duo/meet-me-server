package com.meetme.server.shared.adapter.output.observability

import com.meetme.server.shared.application.port.output.AnalysisAttemptObservation
import com.meetme.server.shared.application.port.output.AnalysisExecutionObservation
import com.meetme.server.shared.application.port.output.AnalysisExecutionOutcome
import com.meetme.server.shared.application.port.output.ObservedAnalysisProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #110: provider-separated known observations must not imply full execution cost coverage. */
class MicrometerAnalysisExecutionMetricsTest {
    @Test
    fun `observation contract has no free form payload identity or credential field`() {
        assertEquals(
            setOf("provider", "parserDispatched", "inputTokens", "outputTokens", "estimatedCostUsd"),
            AnalysisAttemptObservation::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
        assertEquals(
            setOf("outcome", "elapsed", "attempts"),
            AnalysisExecutionObservation::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `one execution records admitted attempts separately from parser dispatches`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerApplicationMetrics(registry)

        metrics.analysisExecution(mixedObservation())

        assertEquals(
            1.0,
            registry
                .get("meetme.analysis.execution.completed")
                .tag("outcome", "ANALYSIS_DELAYED")
                .counter()
                .count(),
        )
        val timer = registry.get("meetme.analysis.execution.duration").tag("outcome", "ANALYSIS_DELAYED").timer()
        assertEquals(1L, timer.count())
        assertEquals(3.0, timer.totalTime(TimeUnit.SECONDS))
        assertEquals(5.0, registry.get("meetme.analysis.admitted.attempts").summary().totalAmount())
        assertEquals(3.0, registry.get("meetme.analysis.parser.dispatches").summary().totalAmount())
        assertEquals(1.0, stage(registry, "GEMINI", "ADMITTED_ONLY"))
        assertEquals(2.0, stage(registry, "GEMINI", "PARSER_DISPATCHED"))
        assertEquals(1.0, stage(registry, "OPENAI", "ADMITTED_ONLY"))
        assertEquals(1.0, stage(registry, "OPENAI", "PARSER_DISPATCHED"))
    }

    @Test
    fun `usage coverage uses dispatched attempts and measures each field independently`() {
        val registry = SimpleMeterRegistry()

        MicrometerApplicationMetrics(registry).analysisExecution(mixedObservation())

        assertEquals(2.0, coverage(registry, "GEMINI", "input_tokens", "KNOWN"))
        assertEquals(0.0, coverage(registry, "GEMINI", "input_tokens", "UNKNOWN"))
        assertEquals(1.0, coverage(registry, "GEMINI", "output_tokens", "KNOWN"))
        assertEquals(1.0, coverage(registry, "GEMINI", "output_tokens", "UNKNOWN"))
        assertEquals(1.0, coverage(registry, "GEMINI", "cost_usd", "KNOWN"))
        assertEquals(1.0, coverage(registry, "GEMINI", "cost_usd", "UNKNOWN"))
        assertEquals(1.0, coverage(registry, "OPENAI", "input_tokens", "UNKNOWN"))
        assertEquals(1.0, coverage(registry, "OPENAI", "output_tokens", "KNOWN"))
        assertEquals(1.0, coverage(registry, "OPENAI", "cost_usd", "UNKNOWN"))
        for (provider in listOf("GEMINI", "OPENAI")) {
            for (field in listOf("input_tokens", "output_tokens", "cost_usd")) {
                val dispatches = if (provider == "GEMINI") 2.0 else 1.0
                assertEquals(
                    dispatches,
                    coverage(registry, provider, field, "KNOWN") + coverage(registry, provider, field, "UNKNOWN"),
                )
            }
        }
    }

    @Test
    fun `known zero is recorded while unknown usage creates no zero samples`() {
        val registry = SimpleMeterRegistry()

        MicrometerApplicationMetrics(registry).analysisExecution(mixedObservation())

        val input = registry.get("meetme.analysis.attempt.input.tokens").tag("provider", "GEMINI").summary()
        assertEquals(2L, input.count())
        assertEquals(100.0, input.totalAmount())
        val output = registry.get("meetme.analysis.attempt.output.tokens").tag("provider", "GEMINI").summary()
        assertEquals(1L, output.count())
        assertEquals(0.0, output.totalAmount())
        val cost = registry.get("meetme.analysis.attempt.cost.usd").tag("provider", "GEMINI").summary()
        assertEquals(1L, cost.count())
        assertEquals(0.0, cost.totalAmount())
        assertNull(registry.find("meetme.analysis.attempt.input.tokens").tag("provider", "OPENAI").summary())
        assertNull(registry.find("meetme.analysis.attempt.cost.usd").tag("provider", "OPENAI").summary())
        assertEquals(
            20.0,
            registry
                .get("meetme.analysis.attempt.output.tokens")
                .tag("provider", "OPENAI")
                .summary()
                .totalAmount(),
        )
    }

    @Test
    fun `known per-attempt costs remain provider separated without creating legacy execution totals`() {
        val registry = SimpleMeterRegistry()
        val attempts =
            listOf(
                AnalysisAttemptObservation(ObservedAnalysisProvider.GEMINI, true, 10, 20, BigDecimal("0.01")),
                AnalysisAttemptObservation(ObservedAnalysisProvider.GEMINI, true),
                AnalysisAttemptObservation(ObservedAnalysisProvider.OPENAI, true, 30, 40, BigDecimal("0.02")),
            )

        MicrometerApplicationMetrics(registry).analysisExecution(
            AnalysisExecutionObservation(AnalysisExecutionOutcome.COMPLETED, Duration.ofMillis(50), attempts),
        )

        assertEquals(
            0.01,
            registry
                .get("meetme.analysis.attempt.cost.usd")
                .tag("provider", "GEMINI")
                .summary()
                .totalAmount(),
            0.0000001,
        )
        assertEquals(
            0.02,
            registry
                .get("meetme.analysis.attempt.cost.usd")
                .tag("provider", "OPENAI")
                .summary()
                .totalAmount(),
            0.0000001,
        )
        assertNull(registry.find("meetme.ai.cost.usd").summary())
        assertNull(registry.find("meetme.ai.cost.budget").counter())
    }

    @Test
    fun `admitted only execution does not enter dispatched usage coverage`() {
        val registry = SimpleMeterRegistry()

        MicrometerApplicationMetrics(registry).analysisExecution(
            AnalysisExecutionObservation(
                AnalysisExecutionOutcome.ANALYSIS_DELAYED,
                Duration.ofSeconds(60),
                listOf(AnalysisAttemptObservation(ObservedAnalysisProvider.GEMINI)),
            ),
        )

        assertEquals(1.0, registry.get("meetme.analysis.admitted.attempts").summary().totalAmount())
        assertEquals(0.0, registry.get("meetme.analysis.parser.dispatches").summary().totalAmount())
        assertTrue(registry.find("meetme.analysis.usage.observations").meters().isEmpty())
        assertNull(registry.find("meetme.analysis.attempt.cost.usd").summary())
    }

    @Test
    fun `completed and delayed are separate logical executions with bounded enum tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerApplicationMetrics(registry)
        metrics.analysisExecution(mixedObservation())
        metrics.analysisExecution(
            AnalysisExecutionObservation(AnalysisExecutionOutcome.COMPLETED, Duration.ZERO, emptyList()),
        )

        assertEquals(
            1.0,
            registry
                .get("meetme.analysis.execution.completed")
                .tag("outcome", "COMPLETED")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            registry
                .get("meetme.analysis.execution.completed")
                .tag("outcome", "ANALYSIS_DELAYED")
                .counter()
                .count(),
        )
        val allowed =
            mapOf(
                "outcome" to setOf("COMPLETED", "ANALYSIS_DELAYED"),
                "provider" to setOf("GEMINI", "OPENAI"),
                "stage" to setOf("ADMITTED_ONLY", "PARSER_DISPATCHED"),
                "field" to setOf("input_tokens", "output_tokens", "cost_usd"),
                "knowledge" to setOf("KNOWN", "UNKNOWN"),
            )
        assertTrue(
            registry.meters.all { meter -> meter.id.tags.all { tag -> tag.value in allowed[tag.key].orEmpty() } },
        )
    }

    private fun mixedObservation() =
        AnalysisExecutionObservation(
            AnalysisExecutionOutcome.ANALYSIS_DELAYED,
            Duration.ofSeconds(3),
            listOf(
                AnalysisAttemptObservation(ObservedAnalysisProvider.GEMINI),
                AnalysisAttemptObservation(ObservedAnalysisProvider.GEMINI, true, 100),
                AnalysisAttemptObservation(ObservedAnalysisProvider.GEMINI, true, 0, 0, BigDecimal.ZERO),
                AnalysisAttemptObservation(ObservedAnalysisProvider.OPENAI, true, outputTokens = 20),
                AnalysisAttemptObservation(ObservedAnalysisProvider.OPENAI),
            ),
        )

    private fun stage(
        registry: SimpleMeterRegistry,
        provider: String,
        stage: String,
    ): Double =
        registry
            .find("meetme.analysis.attempts")
            .tags("provider", provider, "stage", stage)
            .counter()
            ?.count() ?: 0.0

    private fun coverage(
        registry: SimpleMeterRegistry,
        provider: String,
        field: String,
        knowledge: String,
    ): Double =
        registry
            .find("meetme.analysis.usage.observations")
            .tags("provider", provider, "field", field, "knowledge", knowledge)
            .counter()
            ?.count() ?: 0.0
}
