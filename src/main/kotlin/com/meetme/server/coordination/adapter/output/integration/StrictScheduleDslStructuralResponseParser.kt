package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.ScheduleDslStructuralResponseParser
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import tools.jackson.databind.ObjectMapper

class StrictScheduleDslStructuralResponseParser
    @JvmOverloads
    constructor(
        private val mapper: ObjectMapper,
        private val allowScopedTermEvidence: Boolean = false,
        private val allowPlaceConstraints: Boolean = false,
    ) : ScheduleDslStructuralResponseParser {
        override val supportedResponseContractVersion: String = SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT

        override fun supportsResponseContractVersion(version: String): Boolean =
            version in
                setOf(
                    SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT,
                    SERVER_EVIDENCE_RESPONSE_CONTRACT,
                    SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
                    SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT,
                    SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
                    SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT,
                )

        override fun parse(
            responseJson: String,
            snapshot: ScheduleEvidenceSnapshot,
            responseContractVersion: String,
            semanticVersion: String,
            astVersion: String,
            vocabularyVersion: String,
        ): ScheduleDslStructuralEnvelope {
            require(supportsResponseContractVersion(responseContractVersion))
            require(semanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION && astVersion == SCHEDULE_AST_VERSION)
            require(vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
            if (responseContractVersion == SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT) {
                return WeeklyEvidenceProviderContract(mapper, allowPlaceConstraints = true).parseProviderResponse(
                    responseJson,
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
            }
            if (responseContractVersion == SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT) {
                return IndexedEvidenceProviderContract(mapper, allowPlaceConstraints = true).parseProviderResponse(
                    responseJson,
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
            }
            if (responseContractVersion == SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT) {
                return CalendarFactsEvidenceProviderContract(mapper, allowPlaceConstraints = true).parseProviderResponse(
                    responseJson,
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
            }
            if (responseContractVersion == SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT) {
                return SearchScopedEvidenceProviderContract(mapper, allowPlaceConstraints = true).parseProviderResponse(
                    responseJson,
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
            }
            if (responseContractVersion == SERVER_EVIDENCE_RESPONSE_CONTRACT) {
                return ServerEvidenceProviderContract(mapper, allowPlaceConstraints = true).parseProviderResponse(
                    responseJson,
                    snapshot,
                    semanticVersion,
                    astVersion,
                    vocabularyVersion,
                )
            }
            return ScheduleDslStructuralProviderContract(mapper, allowScopedTermEvidence, allowPlaceConstraints).parseProviderResponse(
                responseJson,
                snapshot,
                semanticVersion,
                astVersion,
                vocabularyVersion,
            )
        }
    }
