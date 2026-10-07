package com.meetme.server.coordination.application.port.output

import com.meetme.server.coordination.domain.RecommendationAnalysis
import com.meetme.server.coordination.domain.RecommendationSelection
import java.util.UUID

data class RecommendationQuery(
    val primaryOnly: Boolean = false,
    val alternativesOnly: Boolean = false,
    val afterOrdinal: Int = -1,
    val limit: Int? = null,
    val optionId: UUID? = null,
)

interface RecommendationRepository {
    fun publish(analysis: RecommendationAnalysis)

    fun find(
        analysisId: UUID,
        query: RecommendationQuery = RecommendationQuery(),
    ): RecommendationAnalysis?

    fun containsAlternativeOrdinal(
        analysisId: UUID,
        ordinal: Int,
    ): Boolean

    fun findSelection(analysisId: UUID): RecommendationSelection?

    fun insertSelection(selection: RecommendationSelection)
}
