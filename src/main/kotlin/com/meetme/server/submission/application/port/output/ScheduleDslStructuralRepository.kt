package com.meetme.server.submission.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.Instant

interface ScheduleDslStructuralRepository {
    fun publish(
        envelope: ScheduleDslStructuralEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    )

    fun find(context: ScheduleIrContext): ScheduleDslStructuralEnvelope?
}
