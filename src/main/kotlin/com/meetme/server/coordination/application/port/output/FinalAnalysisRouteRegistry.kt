package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import java.util.UUID

/** A reference to the existing durable invocation, not another provider budget. */
data class FinalAnalysisAttemptBinding(
    val route: FrozenFinalAnalysisRoute,
    val invocation: AnalysisInvocation,
)

interface FinalAnalysisRouteRegistry {
    fun freeze(
        runId: UUID,
        expectedRunVersion: Long,
        choice: FinalAnalysisRouteChoice = FinalAnalysisRouteChoice.Legacy,
    ): FrozenFinalAnalysisRoute

    fun find(runId: UUID): FrozenFinalAnalysisRoute?

    fun bindAttempt(
        route: FrozenFinalAnalysisRoute,
        invocation: AnalysisInvocation,
    ): FinalAnalysisAttemptBinding

    fun requireDispatchCurrent(binding: FinalAnalysisAttemptBinding)
}
