package com.meetme.server.submission.application.port.output

import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import java.time.Instant

interface ScheduleIrV2Repository {
    fun publishV2(
        envelope: ScheduleIrV2Envelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    )

    fun findV2(context: ScheduleIrContext): ScheduleIrV2Envelope?
}
