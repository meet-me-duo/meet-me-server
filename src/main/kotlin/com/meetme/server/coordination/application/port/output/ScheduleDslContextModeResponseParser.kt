package com.meetme.server.coordination.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot

interface ScheduleDslContextModeResponseParser {
    val supportedResponseContractVersion: String

    fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslContextModeEnvelope
}
