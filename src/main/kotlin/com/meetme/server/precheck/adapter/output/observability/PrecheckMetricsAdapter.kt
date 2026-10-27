package com.meetme.server.precheck.adapter.output.observability

import com.meetme.server.precheck.application.port.output.PrecheckMetricsPort
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class PrecheckMetricsAdapter(
    private val registry: MeterRegistry,
    repository: PrecheckRepository,
    clock: Clock,
) : PrecheckMetricsPort {
    init {
        Gauge.builder("meetme.precheck.queue", repository) { it.queueSize().toDouble() }.register(registry)
        Gauge.builder("meetme.precheck.inflight", repository) { it.inFlight(clock.instant()).toDouble() }.register(registry)
    }

    override fun completed(
        status: String,
        queueWait: Duration,
        elapsed: Duration,
    ) {
        registry.counter("meetme.precheck.completed", "purpose", "PRE_SUBMIT_CHECK", "status", status).increment()
        Timer
            .builder("meetme.precheck.queue.wait")
            .tag("purpose", "PRE_SUBMIT_CHECK")
            .register(registry)
            .record(queueWait)
        Timer
            .builder(
                "meetme.precheck.duration",
            ).tag("purpose", "PRE_SUBMIT_CHECK")
            .tag("status", status)
            .register(registry)
            .record(elapsed)
    }
}
