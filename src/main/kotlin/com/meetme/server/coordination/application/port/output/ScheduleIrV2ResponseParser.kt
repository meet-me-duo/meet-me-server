package com.meetme.server.coordination.application.port.output

import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope

interface ScheduleIrV2ResponseParser {
    val supportedResponseContractVersion: String

    fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
    ): ScheduleIrV2Envelope
}
