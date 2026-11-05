package com.meetme.server.coordination.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot

interface ScheduleDslResponseParser {
    val supportedResponseContractVersion: String

    fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
    ): ScheduleDslEnvelope
}
