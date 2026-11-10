package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import java.time.Duration

data class SelectedScheduleDslInvocation(
    val binding: FinalAnalysisAttemptBinding,
    val attempt: CoordinationAttempt,
)

/** Production admission is mandatory; pilot tickets and optional budget contexts are not accepted. */
data class SelectedScheduleDslProviderRequest(
    val binding: FinalAnalysisAttemptBinding,
    val attempt: CoordinationAttempt,
    val inputCapsule: ScheduleDslProviderInputCapsule,
    val dispatchInputs: List<AiDispatchInputSnapshot>,
    val dispatchContext: AiProviderDispatchContext,
    val timeout: Duration,
)

fun interface SelectedScheduleDslProviderPort {
    fun generate(request: SelectedScheduleDslProviderRequest): ScheduleDslProviderResponse
}
