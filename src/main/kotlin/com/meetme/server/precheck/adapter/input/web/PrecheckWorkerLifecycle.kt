package com.meetme.server.precheck.adapter.input.web

import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Dedicated bounded pool: final whole-room analysis keeps its existing scheduler and budget. */
@Component
@ConditionalOnProperty(prefix = "meetme.reliability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PrecheckWorkerLifecycle(
    private val worker: PreSubmissionCheckWorker,
) : SmartLifecycle {
    @Volatile private var running = false
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        if (running) return
        running = true
        executor =
            Executors.newScheduledThreadPool(2) { task -> Thread(task, "pre-submit-worker").apply { isDaemon = true } }.also { pool ->
                repeat(2) {
                    pool.scheduleWithFixedDelay({
                        try {
                            worker.runOne()
                        } catch (_: RuntimeException) {
                            // Durable queued/claimed rows recover without logging a provider or database payload.
                        }
                    }, 1, 1, TimeUnit.SECONDS)
                }
            }
    }

    override fun stop() {
        running = false
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = running
}
