package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleDslContextModeResponseParser
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.databind.ObjectMapper

class StrictScheduleDslContextModeResponseParser(
    private val mapper: ObjectMapper,
) : ScheduleDslContextModeResponseParser {
    override val supportedResponseContractVersion: String = SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
    private val contract = ScheduleDslContextModeProviderContract(mapper)

    override fun parse(
        responseJson: String,
        snapshot: ScheduleEvidenceSnapshot,
        responseContractVersion: String,
        semanticVersion: String,
        astVersion: String,
        vocabularyVersion: String,
    ): ScheduleDslContextModeEnvelope {
        require(responseContractVersion == supportedResponseContractVersion) { "Unsupported context-mode response contract" }
        require(
            semanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION &&
                astVersion == SCHEDULE_AST_VERSION &&
                vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
        ) { "Unsupported context-mode identity" }
        return contract.parseProviderResponse(responseJson, snapshot, semanticVersion, astVersion, vocabularyVersion)
    }
}
