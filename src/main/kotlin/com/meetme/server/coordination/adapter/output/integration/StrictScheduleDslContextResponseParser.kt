package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import tools.jackson.databind.ObjectMapper

/** Context2 is an explicit selection of the unchanged semantic3/AST1 response decoder. */
class StrictScheduleDslContextResponseParser(
    mapper: ObjectMapper,
) : ScheduleDslResponseParser {
    private val contract = ScheduleDslProviderContract(mapper)
    override val supportedResponseContractVersion: String = SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
    ): ScheduleDslEnvelope {
        require(responseContractVersion == supportedResponseContractVersion) { "Unsupported context response contract" }
        require(semanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION && astVersion == SCHEDULE_AST_VERSION) {
            "Unsupported context response identity"
        }
        return contract.parseProviderResponse(responseJson, snapshot, semanticVersion, astVersion)
    }
}
