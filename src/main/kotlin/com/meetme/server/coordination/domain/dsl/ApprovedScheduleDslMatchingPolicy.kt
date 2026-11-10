package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.submission.domain.ir.IrInterpretation

/** Explicit selection for the approved vocabulary and contextual model proposals. */
object ApprovedScheduleDslMatchingPolicy {
    const val VERSION = "schedule-approved-availability-1"

    fun contextual(choices: List<IrAlternativeChoice> = emptyList()): IrMatchingPolicy =
        IrMatchingPolicy(
            VERSION,
            setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED),
            choices.toList(),
            true,
        )
}
