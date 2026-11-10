package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrPrecisionOutcome
import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.RecommendationProjection

data class ScheduleDslCalculation(
    val prepared: ScheduleDslPreparedInputs,
    val candidates: CandidateGenerationResult,
    val recommendations: RecommendationProjection,
    val status: IrCalculationStatus,
    val precisionOutcome: IrPrecisionOutcome,
)

data class ScheduleDslStoredCalculation(
    val route: FrozenFinalAnalysisRoute,
    val currentRunVersion: Long,
    val calculation: ScheduleDslCalculation,
    val contextMode: ScheduleDslContextModeCalculation? = null,
)
