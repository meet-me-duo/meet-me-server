package com.meetme.server.coordination.application.port.output

import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot

interface ScheduleDslStructuralResponseParser {
    val supportedResponseContractVersion: String

    fun supportsResponseContractVersion(version: String): Boolean = version == supportedResponseContractVersion

    fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslStructuralEnvelope
}
