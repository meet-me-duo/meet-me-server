package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleIrV2ResponseParser
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import tools.jackson.databind.ObjectMapper

/** Local decoding for an explicitly selected response contract; never a production registration. */
class StrictScheduleIrV2ResponseParser(
    objectMapper: ObjectMapper,
    override val supportedResponseContractVersion: String,
) : ScheduleIrV2ResponseParser {
    private val contract = ScheduleIrV2ProviderContract(objectMapper)

    init {
        require(supportedResponseContractVersion.matches(Regex("[A-Za-z0-9._:-]{1,64}")))
    }

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
    ): ScheduleIrV2Envelope {
        require(responseContractVersion == supportedResponseContractVersion) { "Unsupported IR2 response contract" }
        return contract.parseProviderResponse(responseJson, snapshot, semanticVersion)
    }
}
