package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.submission.domain.dsl.SCHEDULE_CONTEXT_MODE_POLICY_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION

/** Frozen V5 policy identity; earlier context-mode policies keep their original interpretation. */
object ScheduleDslStructuralMatchingPolicy {
    fun union(choices: List<IrAlternativeChoice> = emptyList()): IrMatchingPolicy =
        ScheduleDslContextModePolicy.contextual(choices).copy(version = SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION)

    fun forVersion(
        version: String,
        choices: List<IrAlternativeChoice> = emptyList(),
    ): IrMatchingPolicy =
        when (version) {
            SCHEDULE_CONTEXT_MODE_POLICY_VERSION -> ScheduleDslContextModePolicy.contextual(choices)
            SCHEDULE_STRUCTURAL_UNION_POLICY_VERSION -> union(choices)
            else -> throw IllegalArgumentException("Unsupported structural matching policy")
        }
}
