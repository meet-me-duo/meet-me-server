package com.meetme.server.precheck.application.port.output

import com.meetme.server.precheck.domain.ClockCandidate
import com.meetme.server.precheck.domain.ClockChoice
import com.meetme.server.precheck.domain.ClockPeriod
import com.meetme.server.precheck.domain.ClockRelevance
import com.meetme.server.precheck.domain.PeriodClarificationInput

fun interface ClockRelevancePort {
    fun classify(
        input: PeriodClarificationInput,
        candidates: List<ClockCandidate>,
    ): Map<String, ClockChoice<ClockRelevance>>
}

fun interface ClockPeriodPort {
    fun classify(
        input: PeriodClarificationInput,
        relevantCandidates: List<ClockCandidate>,
    ): Map<String, ClockChoice<ClockPeriod>>
}

/** Technical errors stop a batch; they are neither semantic questions nor permission for a fallback. */
class ClockClassificationException(
    val failureCode: String,
) : RuntimeException(failureCode)
