package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.MonotonicTimePort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.shared.domain.SubmissionBatchId
import java.time.Clock
import java.time.Duration

/** An explicit worker graph may delegate a frozen typed route; no default route is changed here. */
class SelectedFinalAnalysisDispatchService(
    private val runs: CoordinationRunRepository,
    private val routes: FinalAnalysisRouteRegistry,
    private val processor: SelectedScheduleDslBatchProcessor,
    private val invocations: AnalysisInvocationRepository? = null,
    private val matching: SelectedScheduleDslMatchingService? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val monotonicTime: MonotonicTimePort = MonotonicTimePort(System::nanoTime),
    private val outbox: OutboxRepository? = null,
    private val retryPayloads: SelectedMatchingRetryPayloadCodec? = null,
    private val attempts: CoordinationAttemptRepository? = null,
) {
    fun dispatchIfSelected(batchId: SubmissionBatchId): Boolean {
        val deadline = monotonicTime.nanoTime() + AnalysisInvocationBudget.TOTAL_TIMEOUT.toNanos()
        val run = runs.findByBatchId(batchId) ?: return false
        val route = routes.find(run.id.value) ?: return false
        check(route.runId == run.id.value && route.batchId == run.batch.id.value && route.roomId == run.roomId.value) {
            "Selected route does not belong to the worker batch"
        }
        when (route.choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> Unit
            is FinalAnalysisRouteChoice.DslContextModeV4 -> Unit
            FinalAnalysisRouteChoice.Legacy -> return false
            is FinalAnalysisRouteChoice.DslContextV2, is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> Unit
        }
        when (run.status) {
            CoordinationStatus.COMPLETED -> return true
            CoordinationStatus.MATCHING -> completeMatching(run, route, deadline)
            CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING -> {
                processor.processBounded(batchId, deadline)
                val current = runs.findById(run.id)
                if (current?.status == CoordinationStatus.MATCHING) completeMatching(current, route, deadline)
            }
            CoordinationStatus.ANALYSIS_DELAYED, CoordinationStatus.DEAD_LETTERED -> Unit
        }
        return true
    }

    private fun completeMatching(
        run: CoordinationRun,
        route: FrozenFinalAnalysisRoute,
        deadline: Long,
    ) {
        val repository = invocations ?: configuration()
        val matcher = matching ?: configuration()
        if (run.version <= 0 ||
            route.runId != run.id.value ||
            route.batchId != run.batch.id.value ||
            route.roomId != run.roomId.value
        ) {
            configuration()
        }
        if (routes.find(run.id.value) != route) configuration()
        val owned = repository.findByRunVersion(run.id, run.version - 1)
        if (owned == null) {
            completeHostMatchingRetry(run, route, deadline)
            return
        }
        val winner = owned.winnerAttemptId ?: configuration()
        if (owned.runId != run.id ||
            owned.runVersion != run.version - 1 ||
            owned.finishedAt == null ||
            owned.startedAt >= owned.deadlineAt ||
            !AnalysisInvocationBudget.isAllowedPolicy(owned.policyVersion, route.choice is FinalAnalysisRouteChoice.DslStructuralV5)
        ) {
            configuration()
        }
        val wallRemaining =
            Duration
                .between(
                    clock.instant(),
                    owned.deadlineAt,
                ).coerceIn(Duration.ZERO, AnalysisInvocationBudget.TOTAL_TIMEOUT)
        val cutoff = minOf(deadline, monotonicTime.nanoTime() + wallRemaining.toNanos())
        // This finished binding is a reference for publication; dispatch-current admission must never be reused here.
        val published =
            matcher.processBounded(FinalAnalysisAttemptBinding(route, owned), winner, run.version) {
                monotonicTime.nanoTime() < cutoff && clock.instant() < owned.deadlineAt
            }
        if (!published) {
            val current = runs.findById(run.id)
            if (current?.status == CoordinationStatus.MATCHING && current.version == run.version) configuration()
        }
    }

    private fun completeHostMatchingRetry(
        run: CoordinationRun,
        route: FrozenFinalAnalysisRoute,
        deadline: Long,
    ) {
        val events = (outbox ?: configuration()).findSelectedMatchingRetryEvents(run.id, run.version)
        if (events.size != 1) configuration()
        val authorization = (retryPayloads ?: configuration()).decode(events.single())
        if (authorization.runId != run.id ||
            authorization.batchId != run.batch.id ||
            authorization.matchingRunVersion != run.version ||
            authorization.routeFingerprint != route.fingerprint()
        ) {
            configuration()
        }
        val owned = invocations?.findLatestByRun(run.id) ?: configuration()
        if (owned.id != authorization.sourceInvocationId ||
            owned.runId != run.id ||
            owned.finishedAt == null ||
            owned.winnerAttemptId != authorization.sourceWinnerAttemptId ||
            owned.runVersion + 1 >= run.version ||
            !AnalysisInvocationBudget.isAllowedPolicy(owned.policyVersion, route.choice is FinalAnalysisRouteChoice.DslStructuralV5)
        ) {
            configuration()
        }
        val attempt = attempts?.findById(authorization.sourceWinnerAttemptId) ?: configuration()
        val model = if (attempt.provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna"
        if (attempt.id != authorization.sourceWinnerAttemptId ||
            attempt.coordinationRunId != run.id ||
            attempt.invocationId != owned.id ||
            attempt.finishedAt == null ||
            attempt.failureKind != null ||
            attempt.model != model ||
            attempt.policyVersion != owned.policyVersion
        ) {
            configuration()
        }
        val wall =
            Duration
                .between(
                    clock.instant(),
                    authorization.deadlineAt,
                ).coerceIn(Duration.ZERO, AnalysisInvocationBudget.TOTAL_TIMEOUT)
        val cutoff = minOf(deadline, monotonicTime.nanoTime() + wall.toNanos())
        val published =
            (matching ?: configuration()).processHostMatchingRetryBounded(FinalAnalysisAttemptBinding(route, owned), authorization) {
                monotonicTime.nanoTime() < cutoff &&
                    clock.instant() >= authorization.occurredAt &&
                    clock.instant() < authorization.deadlineAt
            }
        if (!published) {
            val current = runs.findById(run.id)
            if (current?.status == CoordinationStatus.MATCHING && current.version == run.version) configuration()
        }
    }

    private fun configuration(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
}
