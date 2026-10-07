package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.JitterPort
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.RetryDelayPort
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration

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
) {
    fun process(batchId: SubmissionBatchId) {
        val processingStarted = monotonicTime.nanoTime()
        val initial = coordinationRunRepository.findByBatchId(batchId) ?: return
        if (initial.status !in setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING)) return
        val run = persistence.start(initial) ?: return
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
        check(naturalInputs.isNotEmpty()) { "Gemini batch must contain natural language" }
        val request =
            NaturalLanguageBatchRequest(
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                naturalInputs,
            )
        val startingAttempt = attemptRepository.countByRun(run.id)
        val deadlineNanos = monotonicTime.nanoTime() + TOTAL_TIMEOUT.toNanos()
        repeat(MAX_ATTEMPTS) { index ->
            if (!persistence.isCurrent(run)) return
            if (monotonicTime.nanoTime() >= deadlineNanos) {
                persistence.delay(run)
                metrics?.geminiBatch("ANALYSIS_DELAYED", elapsed(processingStarted), index, null)
                return
            }
            val attemptNumber = startingAttempt + index + 1
            val started = clock.instant()
            val attempt =
                CoordinationAttempt(
                    idGenerator.next(),
                    run.id,
                    attemptNumber,
                    started,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                )
            attemptRepository.insert(attempt)
            try {
                val result = parser.parse(request)
                persistence.complete(
                    run,
                    result.results,
                    attempt.complete(clock.instant(), result.usage, null),
                )
                matchingProcessor?.process(batchId)
                metrics?.geminiBatch("COMPLETED", elapsed(processingStarted), index + 1, estimateCost(result.usage))
                return
            } catch (exception: NaturalLanguageParserException) {
                attemptRepository.update(attempt.complete(clock.instant(), ParserUsage(null, null, null), exception.kind.name))
                val last = index == MAX_ATTEMPTS - 1
                if (!exception.retryable || last) {
                    persistence.delay(run)
                    metrics?.geminiBatch("ANALYSIS_DELAYED", elapsed(processingStarted), index + 1, null)
                    return
                }
                val upper = 1_000L shl index
                val delayMillis = exception.retryAfterMillis ?: jitter.nextLong(upper + 1)
                val delay = Duration.ofMillis(delayMillis)
                if (delay.toNanos() >= deadlineNanos - monotonicTime.nanoTime()) {
                    persistence.delay(run)
                    metrics?.geminiBatch("ANALYSIS_DELAYED", elapsed(processingStarted), index + 1, null)
                    return
                }
                retryDelay.sleep(delay)
            }
        }
    }

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
            estimatedCostUsd = estimateCost(usage),
        )

    private fun estimateCost(usage: ParserUsage): BigDecimal? {
        val input = usage.inputTokens ?: return null
        val output = usage.outputTokens ?: return null
        return BigDecimal(input)
            .multiply(BigDecimal("0.75"))
            .add(BigDecimal(output).multiply(BigDecimal("3.75")))
            .divide(BigDecimal(1_000_000), 8, RoundingMode.HALF_UP)
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        val TOTAL_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}

@Service
class GeminiProcessingPersistenceService(
    private val roomRepository: com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val attemptRepository: CoordinationAttemptRepository,
    private val clock: Clock,
) {
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
