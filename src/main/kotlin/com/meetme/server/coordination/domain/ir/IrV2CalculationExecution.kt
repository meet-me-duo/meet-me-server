package com.meetme.server.coordination.domain.ir

import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.IrV2PreparedMatchingInputs
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope

enum class IrPrecisionOutcome { PRECISE_CANDIDATES, NO_PRECISE_CANDIDATES }

data class IrV2CalculationResult(
    val ticket: IrExecutionTicket,
    val envelope: ScheduleIrV2Envelope,
    val prepared: IrV2PreparedMatchingInputs,
    val candidates: CandidateGenerationResult,
    val recommendations: RecommendationProjection,
    val status: IrCalculationStatus,
    val precisionOutcome: IrPrecisionOutcome,
    val selectionFingerprint: String,
)
