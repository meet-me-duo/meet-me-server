package com.meetme.server.submission.domain

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
        val endsAtNextDayStart: Boolean = false,
    ) : StructuredCondition {
        init {
            require((date == null) != (dayOfWeek == null)) { "Exactly one date scope is required" }
            if (endsAtNextDayStart) {
                require(endTime == LocalTime.MIDNIGHT) { "Next-day boundary must end at midnight" }
            } else {
                require(startTime < endTime) { "Time window must be non-empty and cannot cross midnight" }
            }
        }
    }

    data class SpecificPlace(
        val query: String,
        val radiusMeters: Int = 1_000,
        val areaKey: String? = null,
        val areaName: String? = null,
    ) : StructuredCondition {
        init {
            require(query.isNotBlank())
            require(query.codePointCount(0, query.length) <= 500)
            require(radiusMeters in 100..50_000)
            require((areaKey == null) == (areaName == null)) { "Area key and name must be provided together" }
            require(areaKey == null || areaKey.matches(Regex("AREA_[1-9][0-9]*"))) { "Area key must use the AREA_n format" }
            require(areaName == null || areaName.isNotBlank()) { "Area name must not be blank" }
            require(areaName == null || areaName.codePointCount(0, areaName.length) <= 500)
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
    val submissionVersionId: com.meetme.server.shared.domain.SubmissionVersionId,
    val conditions: List<StructuredCondition>,
    val rejectionCode: String?,
) {
    init {
        require(conditions.size <= 32)
        require(conditions.isNotEmpty() || rejectionCode != null)
    }
}
