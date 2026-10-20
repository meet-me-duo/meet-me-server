package com.meetme.server.precheck.application.service

import com.meetme.server.precheck.application.port.output.ClockClassificationException
import com.meetme.server.precheck.application.port.output.ClockPeriodPort
import com.meetme.server.precheck.application.port.output.ClockRelevancePort
import com.meetme.server.precheck.domain.ClockCandidate
import com.meetme.server.precheck.domain.ClockCandidateDetector
import com.meetme.server.precheck.domain.ClockChoice
import com.meetme.server.precheck.domain.ClockRelevance
import com.meetme.server.precheck.domain.PeriodClarificationInput
import com.meetme.server.precheck.domain.PeriodClarificationOutcome
import com.meetme.server.precheck.domain.PeriodClarificationPolicy
import com.meetme.server.precheck.domain.PeriodClarificationResult

/** Standalone, unbound service. Raw conditions always remain available to the final whole-room structurer. */
class PeriodClarificationService(
    private val relevancePort: ClockRelevancePort,
    private val periodPort: ClockPeriodPort,
    private val detector: ClockCandidateDetector = ClockCandidateDetector(),
    private val policy: PeriodClarificationPolicy = PeriodClarificationPolicy(),
) {
    fun check(input: PeriodClarificationInput): PeriodClarificationResult {
        var candidates = emptyList<ClockCandidate>()
        var relevance = emptyMap<String, ClockChoice<ClockRelevance>>()
        try {
            candidates = detector.detect(input.rawText)
            if (candidates.isEmpty()) return policy.aggregate(input, candidates, emptyMap(), emptyMap())
            val proposed = relevancePort.classify(input, candidates.toList())
            require(proposed.keys == candidates.map { it.id }.toSet())
            proposed.values.forEach { it.uniqueChoice() }
            relevance = proposed.toMap()
            val periodTargets =
                candidates.filter {
                    relevance.getValue(it.id).uniqueChoice() == ClockRelevance.RELEVANT && !it.fixed24Hour
                }
            val periods = if (periodTargets.isEmpty()) emptyMap() else periodPort.classify(input, periodTargets.toList())
            return policy.aggregate(input, candidates, relevance, periods)
        } catch (failure: Exception) {
            val code =
                if (failure is ClockClassificationException && failure.failureCode in SAFE_FAILURES) {
                    failure.failureCode
                } else {
                    "CLASSIFICATION_FAILURE"
                }
            return PeriodClarificationResult(
                input,
                candidates,
                relevance,
                emptyMap(),
                emptyList(),
                candidates.map { it.id }.toSet(),
                PeriodClarificationOutcome.HOLD,
                code,
            )
        }
    }

    companion object {
        private val SAFE_FAILURES =
            setOf(
                "CONFIGURATION",
                "INVALID_REQUEST",
                "INVALID_RESPONSE",
                "CHOICE_ARGMAX_MISMATCH",
                "USAGE_UNKNOWN",
                "HTTP_STATUS",
                "TIMEOUT",
                "CONNECTION",
                "ACCOUNTING_FAILURE",
            )
    }
}
