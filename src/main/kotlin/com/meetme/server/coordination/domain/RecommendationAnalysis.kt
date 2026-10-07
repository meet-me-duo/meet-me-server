package com.meetme.server.coordination.domain

import com.meetme.server.coordination.domain.matching.RecommendationOption
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.Instant
import java.util.UUID

const val RECOMMENDATION_PROTOCOL = "diverse-time-v1"

data class StoredRecommendationOption(
    val id: UUID,
    val ordinal: Int,
    val primaryRank: Int?,
    val option: RecommendationOption,
    val variantIds: List<UUID>,
)

data class RecommendationAnalysis(
    val analysisId: UUID,
    val roomId: UUID,
    val options: List<StoredRecommendationOption>,
    val protocol: String = RECOMMENDATION_PROTOCOL,
    val totalOptionCount: Int = options.size,
    val alternativeCount: Int = options.count { it.primaryRank == null },
)

data class RecommendationSelection(
    val analysisId: UUID,
    val optionId: UUID,
    val variantId: UUID,
    val selectedWindow: InstantTimeRange,
    val confirmedAt: Instant,
)
