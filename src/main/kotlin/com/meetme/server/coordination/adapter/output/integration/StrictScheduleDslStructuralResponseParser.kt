package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleDslStructuralResponseParser
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.databind.ObjectMapper

class StrictScheduleDslStructuralResponseParser(
    private val mapper: ObjectMapper,
) : ScheduleDslStructuralResponseParser {
    override val supportedResponseContractVersion: String = SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslStructuralEnvelope {
        require(responseContractVersion == supportedResponseContractVersion)
        require(semanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION && astVersion == SCHEDULE_AST_VERSION)
        require(vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
        return ScheduleDslStructuralProviderContract(mapper).parseProviderResponse(
            responseJson,
            snapshot,
            semanticVersion,
            astVersion,
            vocabularyVersion,
        )
    }
}
