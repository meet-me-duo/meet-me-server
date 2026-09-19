package com.meetme.server.domain.submission

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class TimePolarity {
    AVAILABLE,
    UNAVAILABLE,
}

sealed interface StructuredCondition {
    data class TimeWindow(
        val polarity: TimePolarity,
        val date: LocalDate?,
        val dayOfWeek: DayOfWeek?,
        val startTime: LocalTime,
        val endTime: LocalTime,
    ) : StructuredCondition {
        init {
            require((date == null) != (dayOfWeek == null)) { "Exactly one date scope is required" }
            require(startTime < endTime) { "Time window must be non-empty and cannot cross midnight" }
        }
    }

    data class SpecificPlace(
        val query: String,
        val radiusMeters: Int = 1_000,
    ) : StructuredCondition {
        init {
            require(query.isNotBlank())
            require(radiusMeters in 100..50_000)
        }
    }

    data class TravelConstraint(
        val expression: String,
    ) : StructuredCondition {
        init {
            require(expression.isNotBlank())
        }
    }

    data class UnresolvedPlace(
        val query: String,
    ) : StructuredCondition {
        init {
            require(query.isNotBlank())
        }
    }
}

data class StructuredSubmissionResult(
    val submissionVersionId: com.meetme.server.domain.common.SubmissionVersionId,
    val conditions: List<StructuredCondition>,
    val rejectionCode: String?,
) {
    init {
        require(conditions.size <= 32)
        require(conditions.isNotEmpty() || rejectionCode != null)
    }
}
