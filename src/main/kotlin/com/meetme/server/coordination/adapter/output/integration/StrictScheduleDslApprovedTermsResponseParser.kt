package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleDslApprovedTermsResponseParser
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.databind.ObjectMapper

class StrictScheduleDslApprovedTermsResponseParser(
    mapper: ObjectMapper,
) : ScheduleDslApprovedTermsResponseParser {
    private val contract = ScheduleDslApprovedTermsProviderContract(mapper)
    override val supportedResponseContractVersion: String = SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslApprovedTermsEnvelope {
        require(responseContractVersion == supportedResponseContractVersion) { "Unsupported approved term response contract" }
        require(
            semanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION &&
                astVersion == SCHEDULE_AST_VERSION &&
                vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
        ) {
            "Unsupported approved term identity"
        }
        return contract.parseProviderResponse(responseJson, snapshot, semanticVersion, astVersion, vocabularyVersion)
    }
}
