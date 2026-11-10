package com.meetme.server.submission.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.Instant

interface ScheduleDslContextModeRepository {
    fun publish(
        envelope: ScheduleDslContextModeEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    )

    fun find(context: ScheduleIrContext): ScheduleDslContextModeEnvelope?
}
