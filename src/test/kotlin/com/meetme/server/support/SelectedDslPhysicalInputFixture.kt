package com.meetme.server.support

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.shared.domain.InputInterpretation
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

/** Accepted final-state fixture, not precheck HTTP proof or user confirmation. Original Q fixture stays PENDING. */
object SelectedDslPhysicalInputFixture {
    const val REVIEW_REASON = "SYNTHETIC_FINAL_REVIEW"

    fun contextInput(): ScheduleDslOfflineInput =
        ScheduleDslQuestionContextFixture.input2().let { input ->
            input.copy(
                inputs =
                    input.inputs.map { item ->
                        item.copy(
                            evidence =
                                requireNotNull(item.evidence).copy(
                                    interpretation = InputInterpretation.UNRESOLVED,
                                    unresolvedReason = REVIEW_REASON,
                                ),
                        )
                    },
            )
        }

    fun approvedInput(): ScheduleDslOfflineInput =
        contextInput().copy(
            responseContractVersion = "schedule-dsl-response-3",
            semanticVersion = "schedule-ir-product-4",
            policy = ApprovedScheduleDslMatchingPolicy.contextual(),
        )

    fun contextChoice() =
        FinalAnalysisRouteTestFixture.choice().copy(
            context = ScheduleDslOfflinePipeline.snapshotContext(contextInput()).snapshot.context,
        )

    fun seed(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
        input: ScheduleDslOfflineInput = contextInput(),
    ) {
        FinalAnalysisRouteTestFixture.seed(jdbc, mapper, input)
    }
}
