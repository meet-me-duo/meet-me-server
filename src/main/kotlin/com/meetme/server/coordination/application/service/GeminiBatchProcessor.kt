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
) {
    fun process(batchId: SubmissionBatchId) {
        val initial = coordinationRunRepository.findByBatchId(batchId) ?: return
        if (initial.status !in setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING)) return
        val run = if (initial.status == CoordinationStatus.QUEUED) persistence.start(initial) else initial
        val ids = run.batch.submissionVersionIds.toSet()
        val submissions = submissionRepository.findLatestByRoom(run.roomId).filter { it.latest.id in ids }
        check(submissions.size == ids.size) { "Frozen submission batch is incomplete" }
        val naturalInputs =
            submissions.mapNotNull { submission ->
                submission.latest.rawText?.let {
                    NaturalLanguageInput(
                        submission.latest.id.value
                            .toString(),
                        it,
                        submission.latest.locale,
                    )
                }
            }
        check(naturalInputs.isNotEmpty()) { "Gemini batch must contain natural language" }
        val room = persistence.room(run.roomId)
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
            if (monotonicTime.nanoTime() >= deadlineNanos) {
                persistence.delay(run)
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
                return
            } catch (exception: NaturalLanguageParserException) {
                attemptRepository.update(attempt.complete(clock.instant(), ParserUsage(null, null, null), exception.kind.name))
                val last = index == MAX_ATTEMPTS - 1
                if (!exception.retryable || last) {
                    persistence.delay(run)
                    return
                }
                val upper = 1_000L shl index
                val delayMillis = exception.retryAfterMillis ?: jitter.nextLong(upper + 1)
                val delay = Duration.ofMillis(delayMillis)
                if (delay.toNanos() >= deadlineNanos - monotonicTime.nanoTime()) {
                    persistence.delay(run)
                    return
                }
                retryDelay.sleep(delay)
            }
        }
    }

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
    fun start(run: CoordinationRun): CoordinationRun = run.startStructuring().also(coordinationRunRepository::update)

    @Transactional
    fun complete(
        run: CoordinationRun,
        results: List<com.meetme.server.submission.domain.StructuredSubmissionResult>,
        attempt: CoordinationAttempt,
    ) {
        structuredSubmissionRepository.replaceForBatch(run.batch.id, results, clock.instant())
        attemptRepository.update(attempt)
        val structuring = if (run.status == CoordinationStatus.QUEUED) run.startStructuring() else run
        coordinationRunRepository.update(structuring.finishStructuring())
    }

    @Transactional
    fun delay(run: CoordinationRun) {
        val active = if (run.status == CoordinationStatus.QUEUED) run.startStructuring() else run
        coordinationRunRepository.update(active.delayAnalysis())
    }
}
