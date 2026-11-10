package com.meetme.server.submission.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import java.time.Instant

interface ScheduleDslApprovedTermsRepository {
    fun publish(
        envelope: ScheduleDslApprovedTermsEnvelope,
        expectedRunVersion: Long,
        processedAt: Instant,
    )

    fun find(context: ScheduleIrContext): ScheduleDslApprovedTermsEnvelope?
}
