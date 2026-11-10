package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.shared.domain.SubmissionBatchId
import java.time.Duration
import java.util.UUID

/** Explicitly selected route only; legacy and operating bean selection remain unchanged. */
class SelectedScheduleDslBatchProcessor(
    private val persistence: SelectedScheduleDslProcessingPersistenceService,
    private val gemini: SelectedScheduleDslProviderPort,
    private val luna: SelectedScheduleDslProviderPort,
    private val retryDelay: RetryDelayPort,
    private val jitter: JitterPort,
    private val monotonicTime: MonotonicTimePort,
) {
    fun process(batchId: SubmissionBatchId) {
        processBounded(batchId, monotonicTime.nanoTime() + AnalysisInvocationBudget.TOTAL_TIMEOUT.toNanos())
    }

    fun processBounded(
        batchId: SubmissionBatchId,
        executionDeadlineNanos: Long,
    ) = processSelected(batchId, executionDeadlineNanos, false)

    /** Explicit direct provider evaluation only; never registered as the default worker route. */
    fun processLunaOnlyBounded(
        batchId: SubmissionBatchId,
        executionDeadlineNanos: Long,
    ) = processSelected(batchId, executionDeadlineNanos, true)

    private fun processSelected(
        batchId: SubmissionBatchId,
        executionDeadlineNanos: Long,
        lunaOnly: Boolean,
    ) {
        val requestedDeadline = minOf(executionDeadlineNanos, monotonicTime.nanoTime() + AnalysisInvocationBudget.TOTAL_TIMEOUT.toNanos())
        val run = persistence.start(batchId) ?: return
        val admissionNanos = monotonicTime.nanoTime()
        val admitted =
            if (lunaOnly) {
                persistence.admitLunaOnly(run, UUID.randomUUID(), remaining(requestedDeadline))
            } else {
                persistence.admit(run, UUID.randomUUID(), remaining(requestedDeadline))
            } ?: return
        val binding = admitted.binding
        val policyVersion = binding.invocation.policyVersion
        if (!AnalysisInvocationBudget.isAllowedPolicy(policyVersion, binding.route.choice is FinalAnalysisRouteChoice.DslStructuralV5)) {
            persistence.delay(binding)
            return
        }
        val deadline =
            if (policyVersion == AnalysisInvocation.C5_POLICY_VERSION) {
                minOf(
                    requestedDeadline,
                    admissionNanos + Duration.between(binding.invocation.startedAt, binding.invocation.deadlineAt).toNanos(),
                )
            } else {
                requestedDeadline
            }
        val primaryDeadline =
            deadline - AnalysisInvocationBudget.PRIMARY_PHASE_RESERVE.toNanos()
        val fallbackDeadline = deadline - AnalysisInvocationBudget.COMPLETION_RESERVE.toNanos()

        fun delay() {
            if (persistence.isCurrent(binding)) persistence.delay(binding)
        }

        fun call(
            attempt: CoordinationAttempt,
            port: SelectedScheduleDslProviderPort,
            phaseDeadline: Long,
        ): Outcome? {
            if (!persistence.isCurrent(binding)) return null
            val started =
                if (policyVersion == AnalysisInvocation.C5_POLICY_VERSION) {
                    admissionNanos + Duration.between(binding.invocation.startedAt, attempt.startedAt).toNanos()
                } else {
                    monotonicTime.nanoTime()
                }
            val cap =
                if (attempt.provider ==
                    AnalysisProvider.GEMINI
                ) {
                    AnalysisInvocationBudget.GEMINI_CALL_TIMEOUT
                } else {
                    AnalysisInvocationBudget.lunaCallTimeout(policyVersion)
                }
            val cutoff = minOf(phaseDeadline, started + cap.toNanos())
            if (remaining(cutoff).toMillis() < 1) {
                return Outcome(attempt, null, NaturalLanguageParserException(ParserFailureKind.TIMEOUT))
            }
            return try {
                val request = persistence.request(binding, attempt, cutoff)
                val transportStarted = monotonicTime.nanoTime()
                val response = port.generate(request)
                if (monotonicTime.nanoTime() >= cutoff ||
                    monotonicTime.nanoTime() - transportStarted >= request.timeout.toNanos()
                ) {
                    Outcome(attempt, response, NaturalLanguageParserException(ParserFailureKind.TIMEOUT))
                } else {
                    Outcome(attempt, response, null)
                }
            } catch (failure: NaturalLanguageParserException) {
                Outcome(attempt, null, failure)
            }
        }

        fun publish(outcome: Outcome) {
            val response = requireNotNull(outcome.response)
            if (!persistence.isCurrent(binding)) return
            if (monotonicTime.nanoTime() >= fallbackDeadline) {
                persistence.fail(binding, outcome.attempt, ParserFailureKind.TIMEOUT, response.usage)
                return
            }
            try {
                if (persistence.completeDsl(binding, outcome.attempt, response) { monotonicTime.nanoTime() < deadline }) return
                if (persistence.isCurrent(binding)) persistence.fail(binding, outcome.attempt, ParserFailureKind.TIMEOUT, response.usage)
            } catch (failure: NaturalLanguageParserException) {
                if (persistence.isCurrent(binding)) persistence.fail(binding, outcome.attempt, failure.kind, response.usage)
            }
        }

        if (lunaOnly) {
            val outcome = call(admitted.attempt, luna, fallbackDeadline) ?: return
            if (!persistence.isCurrent(binding)) return
            if (outcome.failure == null) {
                publish(outcome)
            } else {
                persistence.fail(binding, admitted.attempt, outcome.failure.kind, outcome.response?.usage)
            }
            return
        }

        var attempt = admitted.attempt
        for (index in 0 until 4) {
            val outcome = call(attempt, gemini, primaryDeadline) ?: return
            if (!persistence.isCurrent(binding)) return
            val failure = outcome.failure
            if (failure == null) {
                publish(outcome)
                return
            }
            if (!failure.retryable) {
                persistence.fail(binding, attempt, failure.kind, outcome.response?.usage)
                return
            }
            persistence.failAttempt(binding, attempt, failure.kind, outcome.response?.usage)
            if (failure.kind == ParserFailureKind.TIMEOUT) break
            if (index == 3 || remaining(primaryDeadline).toMillis() < 1) break
            val upper = 1000L shl index
            val sleep = Duration.ofMillis(failure.retryAfterMillis?.takeIf { it >= 0 } ?: jitter.nextLong(upper + 1))
            if (sleep >= remaining(primaryDeadline)) break
            if (!persistence.isCurrent(binding)) return
            retryDelay.sleep(sleep)
            if (!persistence.isCurrent(binding)) return
            attempt = persistence.admitAttempt(binding, AnalysisProvider.GEMINI) ?: break
        }
        if (remaining(fallbackDeadline).toMillis() < 1) {
            delay()
            return
        }
        if (!persistence.isCurrent(binding)) return
        val fallbackAttempt = persistence.admitAttempt(binding, AnalysisProvider.OPENAI)
        if (fallbackAttempt == null) {
            delay()
            return
        }
        val outcome = call(fallbackAttempt, luna, fallbackDeadline) ?: return
        if (!persistence.isCurrent(binding)) return
        if (outcome.failure == null) {
            publish(outcome)
        } else {
            persistence.fail(binding, fallbackAttempt, outcome.failure.kind, outcome.response?.usage)
        }
    }

    private data class Outcome(
        val attempt: CoordinationAttempt,
        val response: ScheduleDslProviderResponse?,
        val failure: NaturalLanguageParserException?,
    )

    private fun remaining(deadline: Long): Duration = Duration.ofNanos((deadline - monotonicTime.nanoTime()).coerceAtLeast(0))
}
