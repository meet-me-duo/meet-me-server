package com.meetme.server.submission.application.port.output

import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import java.time.Instant

/** Separate storage format. Missing or incompatible IR is never read as legacy conditions. */
interface ScheduleIrRepository {
    fun publish(
        envelope: ScheduleIrEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    )

    fun find(context: ScheduleIrContext): ScheduleIrEnvelope?
}
