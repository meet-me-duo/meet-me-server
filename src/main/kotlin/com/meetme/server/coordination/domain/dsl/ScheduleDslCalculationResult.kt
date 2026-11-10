package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope

data class ScheduleDslCalculationResult(
    val ticket: IrExecutionTicket,
    val envelope: ScheduleDslEnvelope,
    val prepared: ScheduleDslPreparedInputs,
    val candidates: CandidateGenerationResult,
    val recommendations: RecommendationProjection,
    val status: IrCalculationStatus,
    val precisionOutcome: IrPrecisionOutcome,
    val selectionFingerprint: String,
)
