package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleIrResponseParser
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import tools.jackson.databind.ObjectMapper

/** Local decoding only; the caller explicitly selects a configured response contract. */
class StrictScheduleIrResponseParser(
    objectMapper: ObjectMapper,
    override val supportedResponseContractVersion: String,
) : ScheduleIrResponseParser {
    private val contract = ScheduleIrProviderContract(objectMapper)

    init {
        require(supportedResponseContractVersion.matches(Regex("[A-Za-z0-9._:-]{1,64}")))
    }

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
    ): ScheduleIrEnvelope {
        require(responseContractVersion == supportedResponseContractVersion) { "Unsupported IR response contract" }
        return contract.parseProviderResponse(responseJson, snapshot, semanticVersion)
    }
}
