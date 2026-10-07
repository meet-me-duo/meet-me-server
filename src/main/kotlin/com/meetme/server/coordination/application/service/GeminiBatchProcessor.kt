package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class GeminiBatchProcessor(
    private val coordinationRunRepository: CoordinationRunRepository,
    private val submissionRepository: SubmissionRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val attemptRepository: CoordinationAttemptRepository,
    private val parser: NaturalLanguageParserPort,
    private val idGenerator: IdGenerator,
    private val retryDelay: RetryDelayPort,
    private val jitter: JitterPort,
    private val monotonicTime: MonotonicTimePort,
    private val clock: Clock,
    private val persistence: GeminiProcessingPersistenceService,
    private val matchingProcessor: MatchingProcessor? = null,
    private val metrics: ApplicationMetricsPort? = null,
    @Qualifier("lunaParser") private val fallbackParser: NaturalLanguageParserPort? = null,
    private val invocationRepository: AnalysisInvocationRepository? = null,
) {
    fun process(batchId: SubmissionBatchId) {
        val processingStarted = monotonicTime.nanoTime()
        val deadlineNanos = processingStarted + TOTAL_TIMEOUT.toNanos()
        val initial = coordinationRunRepository.findByBatchId(batchId) ?: return
        if (initial.status !in setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING)) return
        val run = persistence.start(initial) ?: return
        val invocation =
            if (invocationRepository != null) {
                persistence.claimInvocation(run, idGenerator.next(), remaining(deadlineNanos)) ?: return
            } else {
                null
            }
        val submissions = FrozenSubmissionReader.read(submissionRepository, run.batch)
        val room = persistence.room(run.roomId)
        val naturalInputs =
            submissions.mapNotNull { submission ->
                submission.latest.rawText?.let {
                    NaturalLanguageInput(
                        submission.latest.id.value
                            .toString(),
                        it,
                        submission.latest.locale,
                        submission.latest.createdAt
                            .atZone(room.timeZone.value)
                            .toLocalDate(),
                    )
                }
            }
        check(naturalInputs.isNotEmpty()) { "Analysis batch must contain natural language" }
        val request =
            NaturalLanguageBatchRequest(
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                naturalInputs,
            )
        val startingAttempt = attemptRepository.countByRun(run.id)
        var physicalCalls = 0
        var admittedAttempts = 0
        val reserve = if (fallbackParser == null) Duration.ZERO else LUNA_TIMEOUT + COMPLETION_RESERVE
        val geminiDeadline = deadlineNanos - reserve.toNanos()
        val callDeadline = deadlineNanos - if (fallbackParser == null) 0 else COMPLETION_RESERVE.toNanos()

        fun delay() {
            if (invocation == null) persistence.delay(run) else persistence.delayBounded(run, invocation)
            metrics?.geminiBatch("ANALYSIS_DELAYED", elapsed(processingStarted), physicalCalls, null)
        }

        fun invoke(
            provider: AnalysisProvider,
            port: NaturalLanguageParserPort,
            providerDeadline: Long,
        ): CallOutcome? {
            if (!persistence.isCurrent(run)) return null
            if (remaining(providerDeadline).toMillis() < 1) return null
            val attempt =
                CoordinationAttempt(
                    idGenerator.next(),
                    run.id,
                    startingAttempt + admittedAttempts + 1,
                    clock.instant(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    provider = provider,
                    model = if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna",
                    policyVersion = AnalysisInvocation.POLICY_VERSION,
                    invocationId = invocation?.id,
                )
            if (invocation == null) {
                attemptRepository.insert(attempt)
            } else if (!persistence.claimAttempt(run, invocation, provider, attempt)) {
                return null
            }
            admittedAttempts++
            // Admission can wait on a database lock. Recalculate after it, before starting HTTP.
            val maximumCallTimeout =
                when (provider) {
                    AnalysisProvider.GEMINI -> CALL_TIMEOUT
                    AnalysisProvider.OPENAI -> LUNA_TIMEOUT
                }
            val timeout = remaining(providerDeadline).coerceAtMost(maximumCallTimeout)
            if (timeout.toMillis() < 1) {
                val failure = NaturalLanguageParserException(ParserFailureKind.TIMEOUT)
                attemptRepository.update(attempt.complete(clock.instant(), ParserUsage(null, null, null), failure.kind.name))
                return CallOutcome(attempt, null, failure)
            }
            physicalCalls++
            val callStarted = monotonicTime.nanoTime()
            return try {
                val result = port.parse(request.copy(callTimeout = timeout))
                if (monotonicTime.nanoTime() >= providerDeadline || elapsed(callStarted) >= timeout) {
                    val failure =
                        NaturalLanguageParserException(com.meetme.server.coordination.application.port.output.ParserFailureKind.TIMEOUT)
                    attemptRepository.update(attempt.complete(clock.instant(), result.usage, failure.kind.name))
                    metrics?.externalApi(provider.name, failure.kind.name, elapsed(callStarted))
                    CallOutcome(attempt, null, failure)
                } else {
                    metrics?.externalApi(provider.name, "SUCCESS", elapsed(callStarted))
                    CallOutcome(attempt, result, null)
                }
            } catch (exception: NaturalLanguageParserException) {
                attemptRepository.update(attempt.complete(clock.instant(), ParserUsage(null, null, null), exception.kind.name))
                metrics?.externalApi(provider.name, exception.kind.name, elapsed(callStarted))
                CallOutcome(attempt, null, exception)
            }
        }

        fun publish(outcome: CallOutcome): Boolean {
            val result = requireNotNull(outcome.result)
            val attempt = outcome.attempt.complete(clock.instant(), result.usage, null)
            if (monotonicTime.nanoTime() >= callDeadline) {
                attemptRepository.update(attempt.copy(failureKind = "TIMEOUT"))
                return false
            }
            val completed =
                if (invocation == null) {
                    persistence.complete(run, result.results, attempt)
                    true
                } else {
                    try {
                        persistence.completeBounded(run, result.results, attempt, invocation) {
                            monotonicTime.nanoTime() < deadlineNanos
                        }
                    } catch (_: AnalysisDeadlineExceededException) {
                        false
                    }
                }
            if (!completed) {
                attemptRepository.update(attempt.copy(failureKind = "PUBLICATION_FENCED"))
                return false
            }
            if (fallbackParser == null) {
                matchingProcessor?.process(batchId)
            } else {
                matchingProcessor?.processBounded(batchId) { monotonicTime.nanoTime() < deadlineNanos }
            }
            metrics?.geminiBatch("COMPLETED", elapsed(processingStarted), physicalCalls, estimateCost(result.usage, attempt.provider))
            return true
        }

        var fallbackEligible = false
        for (index in 0 until MAX_ATTEMPTS) {
            if (remaining(geminiDeadline).toMillis() < 1) break
            val outcome = invoke(AnalysisProvider.GEMINI, parser, geminiDeadline)
            if (outcome == null) {
                // A retry admission may wait past Gemini's budget while Luna still has time.
                // The fallback's own current-run and ownership claim remain mandatory.
                if (fallbackEligible && remaining(geminiDeadline).toMillis() < 1) break
                delay()
                return
            }
            if (outcome.result != null) {
                if (!publish(outcome)) delay()
                return
            }
            val failure = requireNotNull(outcome.failure)
            if (!failure.retryable) {
                delay()
                return
            }
            fallbackEligible = true
            if (index == MAX_ATTEMPTS - 1) break
            val upper = 1_000L shl index
            val retryAfter = failure.retryAfterMillis?.takeIf { it >= 0 }
            val delay = Duration.ofMillis(retryAfter ?: jitter.nextLong(upper + 1))
            if (delay >= remaining(geminiDeadline)) break
            retryDelay.sleep(delay)
        }
        val fallback = fallbackParser
        if (fallback != null && remaining(callDeadline).toMillis() >= 1) {
            val outcome = invoke(AnalysisProvider.OPENAI, fallback, callDeadline)
            if (outcome == null) {
                delay()
                return
            }
            if (outcome.result != null && publish(outcome)) return
        }
        delay()
    }

    private data class CallOutcome(
        val attempt: CoordinationAttempt,
        val result: com.meetme.server.coordination.application.port.output.NaturalLanguageBatchResult?,
        val failure: NaturalLanguageParserException?,
    )

    private fun remaining(deadlineNanos: Long): Duration = Duration.ofNanos((deadlineNanos - monotonicTime.nanoTime()).coerceAtLeast(0))

    private fun elapsed(startedNanos: Long): Duration = Duration.ofNanos((monotonicTime.nanoTime() - startedNanos).coerceAtLeast(0))

    private fun CoordinationAttempt.complete(
        finishedAt: java.time.Instant,
        usage: ParserUsage,
        failureKind: String?,
    ): CoordinationAttempt =
        copy(
            finishedAt = finishedAt,
            failureKind = failureKind,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            responseBytes = usage.responseBytes,
            estimatedCostUsd = estimateCost(usage, provider),
        )

    private fun estimateCost(
        usage: ParserUsage,
        provider: AnalysisProvider,
    ): BigDecimal? {
        if (provider != AnalysisProvider.GEMINI) return null
        val input = usage.inputTokens ?: return null
        val output = usage.outputTokens ?: return null
        return BigDecimal(input)
            .multiply(BigDecimal("0.75"))
            .add(BigDecimal(output).multiply(BigDecimal("3.75")))
            .divide(BigDecimal(1_000_000), 8, RoundingMode.HALF_UP)
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        val CALL_TIMEOUT: Duration = AnalysisInvocationBudget.GEMINI_CALL_TIMEOUT
        val LUNA_TIMEOUT: Duration = AnalysisInvocationBudget.LUNA_CALL_TIMEOUT
        val COMPLETION_RESERVE: Duration = AnalysisInvocationBudget.COMPLETION_RESERVE
        val TOTAL_TIMEOUT: Duration = AnalysisInvocationBudget.TOTAL_TIMEOUT
    }
}

@Service
class GeminiProcessingPersistenceService(
    private val roomRepository: com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val attemptRepository: CoordinationAttemptRepository,
    private val clock: Clock,
    private val invocationRepository: AnalysisInvocationRepository? = null,
) {
    @Transactional
    fun claimInvocation(
        run: CoordinationRun,
        ownerToken: UUID,
        remaining: Duration,
    ): AnalysisInvocation? {
        // Lock waiting consumes the caller's existing budget rather than moving its deadline.
        val startedAt = clock.instant()
        val deadlineAt = startedAt.plus(remaining.coerceAtMost(GeminiBatchProcessor.TOTAL_TIMEOUT))
        val (_, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return null
        if (current.version != run.version || current.status != CoordinationStatus.STRUCTURING) return null
        val repository = requireNotNull(invocationRepository)
        val previous = repository.findByRunVersion(current.id, current.version)
        if (previous != null) {
            if (clock.instant() >= previous.deadlineAt && previous.finishedAt == null) expire(previous)
            return null
        }
        if (remaining.isZero || remaining.isNegative || clock.instant() >= deadlineAt) {
            delay(current)
            return null
        }
        return AnalysisInvocation(
            UUID.randomUUID(),
            current.id,
            current.version,
            startedAt,
            deadlineAt,
            ownerToken,
        ).also(repository::insert)
    }

    @Transactional
    fun claimAttempt(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
        provider: AnalysisProvider,
        attempt: CoordinationAttempt,
    ): Boolean {
        val (_, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return false
        if (current.version != run.version || current.status != CoordinationStatus.STRUCTURING) return false
        val repository = requireNotNull(invocationRepository)
        val owned = ownedInvocation(run, invocation) ?: return false
        if (clock.instant() >= owned.deadlineAt.minus(GeminiBatchProcessor.COMPLETION_RESERVE)) return false
        val claimed =
            when (provider) {
                AnalysisProvider.GEMINI -> {
                    if (owned.geminiAttempts >= 4 ||
                        owned.lunaAttempts != 0 ||
                        clock.instant() >=
                        owned.deadlineAt.minus(GeminiBatchProcessor.LUNA_TIMEOUT + GeminiBatchProcessor.COMPLETION_RESERVE)
                    ) {
                        return false
                    }
                    owned.copy(geminiAttempts = owned.geminiAttempts + 1)
                }
                AnalysisProvider.OPENAI -> {
                    if (owned.lunaAttempts >= 1 || owned.geminiAttempts == 0) return false
                    owned.copy(lunaAttempts = owned.lunaAttempts + 1)
                }
            }
        require(attempt.coordinationRunId == run.id && attempt.invocationId == owned.id && attempt.provider == provider)
        require(attempt.policyVersion == AnalysisInvocation.POLICY_VERSION)
        repository.update(claimed)
        attemptRepository.insert(attempt)
        return true
    }

    @Transactional
    fun completeBounded(
        run: CoordinationRun,
        results: List<com.meetme.server.submission.domain.StructuredSubmissionResult>,
        attempt: CoordinationAttempt,
        invocation: AnalysisInvocation,
        canPublish: () -> Boolean,
    ): Boolean {
        val (room, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return false
        if (current.version != run.version || current.status != CoordinationStatus.STRUCTURING) return false
        val repository = requireNotNull(invocationRepository)
        val owned = ownedInvocation(run, invocation) ?: return false
        if (!canPublish() || clock.instant() >= owned.deadlineAt) return false
        if (attempt.coordinationRunId != run.id ||
            attempt.invocationId != owned.id ||
            attempt.finishedAt == null ||
            attempt.failureKind != null ||
            !repository.hasAttempt(owned.id, attempt.id)
        ) {
            return false
        }
        val refs = results.map { it.submissionVersionId }
        check(refs.distinct().size == refs.size && refs.all { it in run.batch.submissionVersionIds }) {
            "Structured result references do not match the frozen batch"
        }
        attemptRepository.update(attempt)
        structuredSubmissionRepository.replaceForBatch(current.batch.id, results, clock.instant())
        // Recheck the monotonic and durable deadlines after DB work, before admitting a winner.
        if (!canPublish() || clock.instant() >= owned.deadlineAt) throw AnalysisDeadlineExceededException()
        repository.update(owned.copy(finishedAt = clock.instant(), winnerAttemptId = attempt.id))
        coordinationRunRepository.update(current.finishStructuring())
        roomRepository.update(room.transition())
        if (!canPublish() || clock.instant() >= owned.deadlineAt) throw AnalysisDeadlineExceededException()
        return true
    }

    @Transactional
    fun delayBounded(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
    ) {
        val (room, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return
        if (current.version != run.version || current.status != CoordinationStatus.STRUCTURING) return
        val owned = ownedInvocation(run, invocation) ?: return
        requireNotNull(invocationRepository).update(owned.copy(finishedAt = clock.instant()))
        coordinationRunRepository.update(current.delayAnalysis())
        roomRepository.update(room.transition())
    }

    @Transactional
    fun expire(invocation: AnalysisInvocation) {
        val requested = coordinationRunRepository.findById(invocation.runId) ?: return
        val (room, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, requested) ?: return
        val repository = requireNotNull(invocationRepository)
        val owned = repository.findByRunVersion(invocation.runId, invocation.runVersion) ?: return
        if (owned.id != invocation.id || clock.instant() < owned.deadlineAt) return
        val structuring =
            current.status == CoordinationStatus.STRUCTURING && current.version == owned.runVersion && owned.finishedAt == null
        val matching =
            current.status == CoordinationStatus.MATCHING && current.version == owned.runVersion + 1 && owned.winnerAttemptId != null
        if (!structuring && !matching) return
        repository.update(owned.copy(finishedAt = owned.finishedAt ?: clock.instant()))
        coordinationRunRepository.update(current.delayAnalysis())
        roomRepository.update(room.transition())
    }

    private fun ownedInvocation(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
    ): AnalysisInvocation? {
        val current = requireNotNull(invocationRepository).findByRunVersion(run.id, run.version) ?: return null
        return current.takeIf {
            it.id == invocation.id && it.ownerToken == invocation.ownerToken && it.finishedAt == null && it.winnerAttemptId == null
        }
    }

    fun room(id: com.meetme.server.shared.domain.MeetingRoomId) =
        requireNotNull(roomRepository.findById(id)) { "Room for coordination run does not exist" }

    @Transactional
    fun start(run: CoordinationRun): CoordinationRun? {
        val (room, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return null
        if (current.version != run.version ||
            current.status !in setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING)
        ) {
            return null
        }
        if (current.status == CoordinationStatus.STRUCTURING) return current
        val next = current.startStructuring()
        coordinationRunRepository.update(next)
        roomRepository.update(room.transition())
        return next
    }

    @Transactional
    fun isCurrent(run: CoordinationRun): Boolean {
        val (_, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return false
        return current.version == run.version && current.status == CoordinationStatus.STRUCTURING
    }

    @Transactional
    fun complete(
        run: CoordinationRun,
        results: List<com.meetme.server.submission.domain.StructuredSubmissionResult>,
        attempt: CoordinationAttempt,
    ) {
        val (room, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return
        if (current.version != run.version || current.status != CoordinationStatus.STRUCTURING) return
        attemptRepository.update(attempt)
        structuredSubmissionRepository.replaceForBatch(current.batch.id, results, clock.instant())
        coordinationRunRepository.update(current.finishStructuring())
        roomRepository.update(room.transition())
    }

    @Transactional
    fun delay(run: CoordinationRun) {
        val (room, current) = ActiveRunLock.acquire(roomRepository, coordinationRunRepository, run) ?: return
        if (current.version != run.version || current.status !in setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING)) return
        coordinationRunRepository.update(current.delayAnalysis())
        roomRepository.update(room.transition())
    }
}
