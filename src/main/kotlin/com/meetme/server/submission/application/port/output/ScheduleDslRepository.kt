package com.meetme.server.submission.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.Instant

interface ScheduleDslRepository {
    fun publish(
        envelope: ScheduleDslEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    )

    fun find(context: ScheduleIrContext): ScheduleDslEnvelope?
}
