package com.meetme.server.coordination.application.port.input

import java.time.Instant
import java.util.UUID

data class OperatorReanalysisRequest(
    val requestId: UUID,
    val sourceAnalysisId: UUID,
    val expectedStateVersion: Long,
)

/** Immutable acceptance metadata, not the current state of an asynchronous analysis. */
data class OperatorReanalysisReceipt(
    val requestId: UUID,
    val sourceAnalysisId: UUID,
    val analysisId: UUID,
    val acceptedStateVersion: Long,
    val acceptedAt: Instant,
    val analysisContractVersion: String,
)

interface OperatorReanalysisUseCase {
    /** Authentication precedes materializing caller-controlled target and request content. */
    fun request(
        rawRoomId: String,
        decode: () -> OperatorReanalysisRequest,
    ): OperatorReanalysisReceipt
}
