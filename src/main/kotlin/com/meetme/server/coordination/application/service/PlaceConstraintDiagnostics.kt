package com.meetme.server.coordination.application.service

import com.meetme.server.submission.domain.StructuredCondition

/** Diagnostics derive from the frozen structured conditions; they never relax matching constraints. */
internal object PlaceConstraintDiagnostics {
    const val UNSUPPORTED_TRAVEL_CONSTRAINT = "UNSUPPORTED_TRAVEL_CONSTRAINT"

    fun unappliedReason(conditions: List<StructuredCondition>): String? =
        if (conditions.any { it is StructuredCondition.TravelConstraint }) UNSUPPORTED_TRAVEL_CONSTRAINT else null
}
