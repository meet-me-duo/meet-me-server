package com.meetme.server.precheck.application.port.output

import java.time.Duration

interface PrecheckMetricsPort {
    fun completed(
        status: String,
        queueWait: Duration,
        elapsed: Duration,
    )
}
