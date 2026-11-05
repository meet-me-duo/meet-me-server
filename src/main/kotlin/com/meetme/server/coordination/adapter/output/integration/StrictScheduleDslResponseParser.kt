package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.databind.ObjectMapper

class StrictScheduleDslResponseParser(
    objectMapper: ObjectMapper,
    override val supportedResponseContractVersion: String,
) : ScheduleDslResponseParser {
    private val contract = ScheduleDslProviderContract(objectMapper)

    init {
        require(supportedResponseContractVersion.matches(Regex("[A-Za-z0-9._:-]{1,64}")))
    }

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
    ): ScheduleDslEnvelope {
        require(responseContractVersion == supportedResponseContractVersion) { "Unsupported schedule DSL response contract" }
        return contract.parseProviderResponse(responseJson, snapshot, semanticVersion, astVersion)
    }
}
