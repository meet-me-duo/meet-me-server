package com.meetme.server.coordination.application.port.output

import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import java.time.Instant
import java.util.UUID

/** DB event reference only; constructor data never grants authority without current event/history/native validation. */
data class SelectedMatchingRetryAuthorization(
    val eventId: OutboxEventId,
    val runId: CoordinationRunId,
    val batchId: SubmissionBatchId,
    val matchingRunVersion: Long,
    val sourceInvocationId: UUID,
    val sourceWinnerAttemptId: UUID,
    val routeFingerprint: String,
    val occurredAt: Instant,
) {
    /** New host-authorized pure-compute deadline, never the old invocation deadline. */
    val deadlineAt: Instant get() = occurredAt.plus(AnalysisInvocationBudget.TOTAL_TIMEOUT)
}
