package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttempt
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslApprovedTermsResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleDslContextModeResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleDslStructuralResponseParser
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslInvocation
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslContextModeRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Explicitly constructed only. Provider I/O stays outside these short publication transactions. */
open class SelectedScheduleDslProcessingPersistenceService(
    private val runs: CoordinationRunRepository,
    private val rooms: MeetingRoomRepository,
    private val submissions: SubmissionRepository,
    private val routes: FinalAnalysisRouteRegistry,
    private val envelopes: ScheduleDslRepository,
    private val parser: ScheduleDslResponseParser,
    private val invocations: AnalysisInvocationRepository,
    private val attempts: CoordinationAttemptRepository,
    private val clock: Clock,
    private val approvedEnvelopes: ScheduleDslApprovedTermsRepository? = null,
    private val approvedParser: ScheduleDslApprovedTermsResponseParser? = null,
    private val contextModeEnvelopes: ScheduleDslContextModeRepository? = null,
    private val contextModeParser: ScheduleDslContextModeResponseParser? = null,
    private val structuralEnvelopes: ScheduleDslStructuralRepository? = null,
    private val structuralParser: ScheduleDslStructuralResponseParser? = null,
) {
    @Transactional
    open fun start(batchId: SubmissionBatchId): CoordinationRun? {
        val requested = runs.findByBatchId(batchId) ?: return null
        val (room, current) = ActiveRunLock.acquire(rooms, runs, requested) ?: return null
        if (current.status !in setOf(CoordinationStatus.QUEUED, CoordinationStatus.STRUCTURING)) return null
        val route = routes.find(current.id.value) ?: return null
        if (route.batchId != current.batch.id.value || route.roomId != current.roomId.value || !supported(route.choice)) return null
        if (current.status == CoordinationStatus.STRUCTURING) return current
        return current.startStructuring().also {
            runs.update(it)
            rooms.update(room.transition())
        }
    }

    @Transactional
    open fun admit(
        run: CoordinationRun,
        ownerToken: UUID,
        remaining: Duration,
    ): SelectedScheduleDslInvocation? = admitFirst(run, ownerToken, remaining, AnalysisProvider.GEMINI)

    /** Explicit C5 diagnostic selection; ordinary admission and old policies stay Gemini first. */
    @Transactional
    open fun admitLunaOnly(
        run: CoordinationRun,
        ownerToken: UUID,
        remaining: Duration,
    ): SelectedScheduleDslInvocation? = admitFirst(run, ownerToken, remaining, AnalysisProvider.OPENAI)

    private fun admitFirst(
        run: CoordinationRun,
        ownerToken: UUID,
        remaining: Duration,
        initialProvider: AnalysisProvider,
    ): SelectedScheduleDslInvocation? {
        val startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val deadlineAt = startedAt.plus(remaining.coerceAtMost(Duration.ofSeconds(60))).truncatedTo(ChronoUnit.MICROS)
        val (room, current) = ActiveRunLock.acquire(rooms, runs, run) ?: return null
        if (current.version != run.version || current.status != CoordinationStatus.STRUCTURING) return null
        val route = routes.find(current.id.value) ?: return null
        if (route.batchId != current.batch.id.value || route.roomId != current.roomId.value || !supported(route.choice)) return null
        if (initialProvider == AnalysisProvider.OPENAI && route.choice !is FinalAnalysisRouteChoice.DslStructuralV5) return null
        val previous = invocations.findByRunVersion(current.id, current.version)
        if (previous != null) {
            if (previous.finishedAt == null && previous.winnerAttemptId == null && clock.instant() >= previous.deadlineAt) {
                terminate(room, current, previous)
            }
            return null
        }
        if (remaining.isNegative || remaining.isZero || clock.instant() >= deadlineAt) {
            runs.update(current.delayAnalysis())
            rooms.update(room.transition())
            return null
        }
        val invocation =
            AnalysisInvocation(
                UUID.randomUUID(),
                current.id,
                current.version,
                startedAt,
                deadlineAt,
                ownerToken,
                geminiAttempts = if (initialProvider == AnalysisProvider.GEMINI) 1 else 0,
                lunaAttempts = if (initialProvider == AnalysisProvider.OPENAI) 1 else 0,
                policyVersion =
                    if (route.choice is FinalAnalysisRouteChoice.DslStructuralV5) {
                        AnalysisInvocation.C5_POLICY_VERSION
                    } else {
                        AnalysisInvocation.POLICY_VERSION
                    },
            )
        invocations.insert(invocation)
        val persisted = requireNotNull(invocations.findByRunVersion(current.id, current.version))
        val binding = routes.bindAttempt(route, persisted)
        val attempt = newAttempt(current, persisted, initialProvider)
        attempts.insert(attempt)
        return SelectedScheduleDslInvocation(binding, requireNotNull(attempts.findById(attempt.id)))
    }

    @Transactional
    open fun admitAttempt(
        binding: FinalAnalysisAttemptBinding,
        provider: AnalysisProvider,
    ): CoordinationAttempt? {
        val owned = current(binding) ?: return null
        routes.requireDispatchCurrent(binding)
        val invocation = owned.invocation
        if (invocation.geminiAttempts + invocation.lunaAttempts > 0) {
            val latest = attempts.findLatestByInvocation(invocation.id) ?: return null
            if (latest.invocationId != invocation.id ||
                latest.coordinationRunId != invocation.runId ||
                latest.finishedAt == null ||
                latest.policyVersion != invocation.policyVersion
            ) {
                return null
            }
        }
        val now = clock.instant()
        if (now >= invocation.deadlineAt.minusSeconds(3)) return null
        val next =
            when (provider) {
                AnalysisProvider.GEMINI -> {
                    if (invocation.geminiAttempts >= 4 ||
                        invocation.lunaAttempts != 0 ||
                        now >= invocation.deadlineAt.minusSeconds(30)
                    ) {
                        return null
                    }
                    invocation.copy(geminiAttempts = invocation.geminiAttempts + 1)
                }
                AnalysisProvider.OPENAI -> {
                    if (invocation.lunaAttempts >= 1 || invocation.geminiAttempts == 0) return null
                    invocation.copy(lunaAttempts = invocation.lunaAttempts + 1)
                }
            }
        invocations.update(next)
        val attempt = newAttempt(owned.run, next, provider)
        attempts.insert(attempt)
        return requireNotNull(attempts.findById(attempt.id))
    }

    @Transactional
    open fun request(
        binding: FinalAnalysisAttemptBinding,
        attempt: CoordinationAttempt,
        deadlineNanos: Long,
    ): SelectedScheduleDslProviderRequest {
        val owned = requireNotNull(current(binding)) { "Selected execution is no longer current" }
        routes.requireDispatchCurrent(binding)
        val persistedAttempt = requireNotNull(unfinishedAttempt(owned.invocation, attempt)) { "Attempt is not admitted or is finished" }
        check(attempts.findLatestByInvocation(owned.invocation.id) == persistedAttempt) { "Attempt is no longer the current dispatch" }
        val context = context(binding.route.choice)
        val native = native(owned.run)
        val capsule = capsule(context, native)
        val zone = ZoneId.of(context.timeZone)
        val inputs =
            native.sortedBy { it.participantId.value }.map {
                val version = it.latest
                AiDispatchInputSnapshot(
                    version.id.value.toString(),
                    requireNotNull(version.rawText),
                    version.locale.toLanguageTag(),
                    (version.evidence?.referenceAt ?: version.createdAt).atZone(zone).toLocalDate(),
                    version.evidence,
                )
            }
        val cap =
            if (attempt.provider == AnalysisProvider.GEMINI) {
                AnalysisInvocationBudget.GEMINI_CALL_TIMEOUT
            } else {
                AnalysisInvocationBudget.lunaCallTimeout(owned.invocation.policyVersion)
            }
        val phaseDeadline = owned.invocation.deadlineAt.minusSeconds(if (attempt.provider == AnalysisProvider.GEMINI) 30 else 3)
        val residual = owned.invocation.policyVersion == AnalysisInvocation.C5_POLICY_VERSION
        val providerDeadline = if (residual) minOf(phaseDeadline, persistedAttempt.startedAt.plus(cap)) else phaseDeadline
        val preparedAtNanos = System.nanoTime()
        val timeout =
            minOf(
                cap,
                Duration.between(clock.instant(), providerDeadline),
                Duration.ofNanos(deadlineNanos - (if (residual) preparedAtNanos else System.nanoTime())),
            )
        check(timeout.toMillis() >= 1) { "Selected dispatch deadline expired" }
        val dispatch =
            AiProviderDispatchContext(
                AiBudgetExecution(
                    owned.run.roomId.value,
                    AiBudgetExecutionKind.FINAL_ANALYSIS,
                    owned.invocation.id,
                    owned.invocation.runVersion,
                    owned.invocation.ownerToken,
                    owned.invocation.runVersion,
                    AiDispatchFingerprints.batchSource(inputs),
                    AiDispatchFingerprints.batchContext(zone, context.searchStart, context.searchEnd, inputs),
                ),
                if (residual) minOf(deadlineNanos, preparedAtNanos + timeout.toNanos()) else deadlineNanos,
                persistedAttempt.id,
                if (persistedAttempt.provider == AnalysisProvider.GEMINI) owned.invocation.geminiAttempts else 1,
            )
        return SelectedScheduleDslProviderRequest(binding, persistedAttempt, capsule, inputs, dispatch, timeout)
    }

    @Transactional
    open fun completeDsl(
        binding: FinalAnalysisAttemptBinding,
        attempt: CoordinationAttempt,
        response: ScheduleDslProviderResponse,
        canPublish: () -> Boolean,
    ): Boolean {
        val owned = current(binding) ?: return false
        if (!canPublish() || clock.instant() >= owned.invocation.deadlineAt) return false
        routes.requireDispatchCurrent(binding)
        val persistedAttempt = unfinishedAttempt(owned.invocation, attempt) ?: return false
        if (response.provider != persistedAttempt.provider || response.model != persistedAttempt.model) return false
        val snapshot = capsule(context(binding.route.choice), native(owned.run)).snapshot
        when (val selected = binding.route.choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                val selectedParser = requireNotNull(structuralParser)
                check(selectedParser.supportedResponseContractVersion == selected.identity.responseContractVersion)
                val envelope =
                    selectedParser.parse(
                        response.responseJson,
                        snapshot,
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        SCHEDULE_AST_VERSION,
                        selected.vocabularyVersion,
                    )
                if (!canPublish() || clock.instant() >= owned.invocation.deadlineAt) return false
                requireNotNull(structuralEnvelopes).publish(envelope, owned.run.version, clock.instant())
            }
            is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                val selectedParser = requireNotNull(contextModeParser)
                check(selectedParser.supportedResponseContractVersion == selected.identity.responseContractVersion)
                val envelope =
                    selectedParser.parse(
                        response.responseJson,
                        snapshot,
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        SCHEDULE_AST_VERSION,
                        selected.vocabularyVersion,
                    )
                if (!canPublish() || clock.instant() >= owned.invocation.deadlineAt) return false
                requireNotNull(contextModeEnvelopes).publish(envelope, owned.run.version, clock.instant())
            }
            is FinalAnalysisRouteChoice.DslContextV2 -> {
                check(parser.supportedResponseContractVersion == selected.identity.responseContractVersion)
                val envelope =
                    parser.parse(
                        response.responseJson,
                        snapshot,
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        SCHEDULE_AST_VERSION,
                    )
                if (!canPublish() || clock.instant() >= owned.invocation.deadlineAt) return false
                envelopes.publish(envelope, owned.run.version, clock.instant())
            }
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                val selectedParser = requireNotNull(approvedParser)
                check(selectedParser.supportedResponseContractVersion == selected.identity.responseContractVersion)
                val envelope =
                    selectedParser.parse(
                        response.responseJson,
                        snapshot,
                        selected.identity.responseContractVersion,
                        selected.identity.irSemanticVersion,
                        SCHEDULE_AST_VERSION,
                        selected.vocabularyVersion,
                    )
                if (!canPublish() || clock.instant() >= owned.invocation.deadlineAt) return false
                requireNotNull(approvedEnvelopes).publish(envelope, owned.run.version, clock.instant())
            }
            FinalAnalysisRouteChoice.Legacy -> return false
        }
        checkPublicationDeadline(owned.invocation, canPublish)
        attempts.update(finished(persistedAttempt, null, response.usage))
        invocations.update(owned.invocation.copy(finishedAt = clock.instant(), winnerAttemptId = persistedAttempt.id))
        runs.update(owned.run.finishStructuring())
        rooms.update(owned.room.transition())
        checkPublicationDeadline(owned.invocation, canPublish)
        return true
    }

    @Transactional
    open fun failAttempt(
        binding: FinalAnalysisAttemptBinding,
        attempt: CoordinationAttempt,
        kind: ParserFailureKind,
        usage: ParserUsage? = null,
    ) {
        val owned = current(binding) ?: return
        val persistedAttempt = unfinishedAttempt(owned.invocation, attempt) ?: return
        attempts.update(finished(persistedAttempt, kind, usage))
    }

    @Transactional
    open fun fail(
        binding: FinalAnalysisAttemptBinding,
        attempt: CoordinationAttempt,
        kind: ParserFailureKind,
        usage: ParserUsage? = null,
    ) {
        val owned = current(binding) ?: return
        val persistedAttempt = unfinishedAttempt(owned.invocation, attempt) ?: return
        attempts.update(finished(persistedAttempt, kind, usage))
        terminate(owned.room, owned.run, owned.invocation)
    }

    @Transactional
    open fun delay(binding: FinalAnalysisAttemptBinding) {
        val owned = current(binding) ?: return
        terminate(owned.room, owned.run, owned.invocation)
    }

    @Transactional
    open fun isCurrent(binding: FinalAnalysisAttemptBinding): Boolean {
        val owned = current(binding) ?: return false
        if (clock.instant() >= owned.invocation.deadlineAt) return false
        routes.requireDispatchCurrent(binding)
        return true
    }

    private data class Current(
        val room: MeetingRoom,
        val run: CoordinationRun,
        val invocation: AnalysisInvocation,
    )

    private fun current(binding: FinalAnalysisAttemptBinding): Current? {
        val route = binding.route
        val requested = runs.findById(CoordinationRunId(route.runId)) ?: return null
        val (room, run) = ActiveRunLock.acquire(rooms, runs, requested) ?: return null
        val supplied = binding.invocation
        if (run.status != CoordinationStatus.STRUCTURING ||
            run.version != supplied.runVersion ||
            route.roomId != room.id.value ||
            route.batchId != run.batch.id.value ||
            supplied.runId != run.id ||
            !supported(route.choice) ||
            routes.find(route.runId) != route
        ) {
            return null
        }
        val owned = invocations.findByRunVersion(run.id, run.version) ?: return null
        if (owned.id != supplied.id ||
            owned.ownerToken != supplied.ownerToken ||
            owned.startedAt != supplied.startedAt ||
            owned.deadlineAt != supplied.deadlineAt ||
            owned.policyVersion != supplied.policyVersion ||
            !AnalysisInvocationBudget.isAllowedPolicy(owned.policyVersion, route.choice is FinalAnalysisRouteChoice.DslStructuralV5) ||
            supplied.finishedAt != null ||
            supplied.winnerAttemptId != null ||
            owned.finishedAt != null ||
            owned.winnerAttemptId != null
        ) {
            return null
        }
        return Current(room, run, owned)
    }

    private fun unfinishedAttempt(
        invocation: AnalysisInvocation,
        supplied: CoordinationAttempt,
    ): CoordinationAttempt? {
        val stored = attempts.findById(supplied.id) ?: return null
        if (stored != supplied ||
            stored.finishedAt != null ||
            stored.failureKind != null ||
            stored.coordinationRunId != invocation.runId ||
            stored.invocationId != invocation.id ||
            stored.policyVersion != invocation.policyVersion ||
            !invocations.hasAttempt(invocation.id, stored.id)
        ) {
            return null
        }
        val expectedModel = if (stored.provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna"
        return stored.takeIf { it.model == expectedModel }
    }

    private fun newAttempt(
        run: CoordinationRun,
        invocation: AnalysisInvocation,
        provider: AnalysisProvider,
    ) = CoordinationAttempt(
        UUID.randomUUID(),
        run.id,
        attempts.countByRun(run.id) + 1,
        clock.instant().truncatedTo(ChronoUnit.MICROS),
        null,
        null,
        null,
        null,
        null,
        null,
        provider,
        if (provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna",
        invocation.policyVersion,
        invocation.id,
    )

    private fun finished(
        attempt: CoordinationAttempt,
        kind: ParserFailureKind?,
        usage: ParserUsage?,
    ) = attempt.copy(
        finishedAt = clock.instant(),
        failureKind = kind?.name,
        inputTokens = usage?.inputTokens,
        outputTokens = usage?.outputTokens,
        responseBytes = usage?.responseBytes,
    )

    private fun terminate(
        room: MeetingRoom,
        run: CoordinationRun,
        invocation: AnalysisInvocation,
    ) {
        invocations.update(invocation.copy(finishedAt = clock.instant()))
        runs.update(run.delayAnalysis())
        rooms.update(room.transition())
    }

    private fun checkPublicationDeadline(
        invocation: AnalysisInvocation,
        canPublish: () -> Boolean,
    ) {
        if (!canPublish() || clock.instant() >= invocation.deadlineAt) throw AnalysisDeadlineExceededException()
    }

    private fun supported(choice: FinalAnalysisRouteChoice): Boolean =
        when (choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> structuralEnvelopes != null && structuralParser != null
            is FinalAnalysisRouteChoice.DslContextModeV4 -> contextModeEnvelopes != null && contextModeParser != null
            is FinalAnalysisRouteChoice.DslContextV2 -> true
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> approvedEnvelopes != null && approvedParser != null
            FinalAnalysisRouteChoice.Legacy -> false
        }

    private fun context(choice: FinalAnalysisRouteChoice): ScheduleIrContext =
        when (choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> choice.context
            is FinalAnalysisRouteChoice.DslContextModeV4 -> choice.context
            is FinalAnalysisRouteChoice.DslContextV2 -> choice.context
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> choice.context
            FinalAnalysisRouteChoice.Legacy -> error("Selected DSL route required")
        }

    private fun native(run: CoordinationRun): List<Submission> = FrozenSubmissionReader.read(submissions, run.batch)

    private fun capsule(
        context: ScheduleIrContext,
        native: List<Submission>,
    ): ScheduleDslProviderInputCapsule {
        val texts =
            native.associate {
                val (binding, sources) = IrEvidenceBindings.create(it)
                binding.submissionVersionId to sources
            }
        return ScheduleDslProviderInputCapsule.create(ScheduleEvidenceSnapshot(context, texts), native)
    }
}
