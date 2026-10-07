package com.meetme.server.coordination.domain

import com.meetme.server.shared.domain.MeetingRoomId
import java.time.Instant
import java.util.UUID

enum class RevisionRoundStatus { OPEN, CONSUMED }

enum class RevisionAnalysisOutcome { QUEUED, REUSED }

data class InputRevisionRound(
    val id: UUID,
    val roomId: MeetingRoomId,
    val generation: Long,
    val sourceRunId: UUID,
    val reopenRequestId: UUID,
    val expectedGeneration: Long,
    val openedAt: Instant,
    val status: RevisionRoundStatus = RevisionRoundStatus.OPEN,
    val consumedAt: Instant? = null,
    val analyzeRequestId: UUID? = null,
    val forceReparse: Boolean? = null,
    val resolvedRunId: UUID? = null,
    val outcome: RevisionAnalysisOutcome? = null,
) {
    fun consume(
        requestId: UUID,
        force: Boolean,
        runId: UUID,
        outcome: RevisionAnalysisOutcome,
        at: Instant,
    ): InputRevisionRound {
        check(status == RevisionRoundStatus.OPEN)
        return copy(
            status = RevisionRoundStatus.CONSUMED,
            consumedAt = at,
            analyzeRequestId = requestId,
            forceReparse = force,
            resolvedRunId = runId,
            outcome = outcome,
        )
    }
}
