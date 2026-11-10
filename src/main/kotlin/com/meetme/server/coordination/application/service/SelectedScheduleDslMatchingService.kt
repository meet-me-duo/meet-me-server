package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocation
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedMatchingRetryAuthorization
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

/** Explicit selection only. Calculation leaves the read snapshot before bounded publication starts. */
class SelectedScheduleDslMatchingService(
    private val runs: CoordinationRunRepository,
    private val rooms: MeetingRoomRepository,
    private val submissions: SubmissionRepository,
    private val routes: FinalAnalysisRouteRegistry,
    private val invocations: AnalysisInvocationRepository,
    private val attempts: CoordinationAttemptRepository,
    private val reader: ScheduleDslStoredReadService,
    private val persistence: MatchingProcessingPersistenceService,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val outbox: OutboxRepository? = null,
    private val retryPayloads: SelectedMatchingRetryPayloadCodec? = null,
) {
    fun processBounded(
        binding: FinalAnalysisAttemptBinding,
        winningAttemptId: UUID,
        expectedMatchingVersion: Long,
        canPublish: () -> Boolean,
    ): Boolean {
        if (binding.invocation.runVersion < 0 || expectedMatchingVersion != binding.invocation.runVersion + 1) return false
        return processSelected(binding, winningAttemptId, expectedMatchingVersion, canPublish, null)
    }

    fun processHostMatchingRetryBounded(
        binding: FinalAnalysisAttemptBinding,
        authorization: SelectedMatchingRetryAuthorization,
        canPublish: () -> Boolean,
    ): Boolean {
        if (outbox == null || retryPayloads == null) configuration()
        if (authorization.matchingRunVersion <= binding.invocation.runVersion + 1 ||
            authorization.matchingRunVersion == Long.MAX_VALUE
        ) {
            configuration()
        }
        return processSelected(binding, authorization.sourceWinnerAttemptId, authorization.matchingRunVersion, canPublish, authorization)
    }

    private fun processSelected(
        binding: FinalAnalysisAttemptBinding,
        winningAttemptId: UUID,
        expectedMatchingVersion: Long,
        canPublish: () -> Boolean,
        authorization: SelectedMatchingRetryAuthorization?,
    ): Boolean {
        val selection = selection(binding.route.choice) ?: return false
        val initial = runs.findById(CoordinationRunId(binding.route.runId)) ?: return false
        if (initial.status != CoordinationStatus.MATCHING || initial.version != expectedMatchingVersion || initial.isConfirmed) return false
        if (initial.id != binding.invocation.runId ||
            initial.roomId.value != binding.route.roomId ||
            initial.batch.id.value != binding.route.batchId
        ) {
            return false
        }
        if (ownedWinner(binding, winningAttemptId) == null) return false
        if (authorization != null) {
            requireRetryAuthority(binding, authorization)
            if (clock.instant() < authorization.occurredAt) configuration()
            if (clock.instant() >= authorization.deadlineAt) {
                persistence.delay(initial)
                return false
            }
        }

        // The proxied reader owns the read-only REPEATABLE_READ transaction. Its failures are not recast as fencing.
        val stored = reader.readStored(initial.id, expectedMatchingVersion, binding.route.fingerprint())
        if (stored.route != binding.route || stored.currentRunVersion != expectedMatchingVersion) return false
        val calculation = stored.calculation
        val participants = calculation.prepared.participants.map { it.participantId.value }
        if (participants.size != selection.context.inputs.size ||
            participants.toSet() !=
            selection.context.inputs
                .map { it.participantId }
                .toSet()
        ) {
            return false
        }
        val candidates =
            calculation.candidates.candidates.mapIndexed { index, candidate ->
                MatchingCandidateStorageMapper.map(candidate, CandidateId(idGenerator.next()), index + 1, participants.size)
            }
        val quality = if (calculation.status == IrCalculationStatus.CALCULATED) CandidateQuality.COMPLETE else CandidateQuality.PARTIAL
        val completed = initial.complete(quality, candidates)
        var checkedMatching = false
        val published =
            try {
                persistence.completeBounded(completed, emptyList(), calculation.recommendations) {
                    check(
                        TransactionSynchronizationManager.isActualTransactionActive(),
                    ) { "Selected matching publication requires a transaction" }
                    check(
                        !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    ) { "Selected matching publication requires a writer transaction" }
                    val phase = if (checkedMatching) CoordinationStatus.COMPLETED else CoordinationStatus.MATCHING
                    val version = if (checkedMatching) expectedMatchingVersion + 1 else expectedMatchingVersion
                    val current = current(binding, winningAttemptId, initial, selection, phase, version, canPublish, authorization)
                    if (current) checkedMatching = true
                    current
                }
            } catch (_: AnalysisDeadlineExceededException) {
                false
            }
        if (!published) {
            // The proxy has committed or rolled back; this is the existing independent delay transaction.
            persistence.delay(initial)
        }
        return published
    }

    private fun current(
        binding: FinalAnalysisAttemptBinding,
        winningAttemptId: UUID,
        initial: CoordinationRun,
        selection: Selection,
        phase: CoordinationStatus,
        version: Long,
        canPublish: () -> Boolean,
        authorization: SelectedMatchingRetryAuthorization?,
    ): Boolean {
        val deadline = authorization?.deadlineAt ?: binding.invocation.deadlineAt
        if (!canPublish() || clock.instant() >= deadline) return false
        if (authorization != null) {
            if (clock.instant() < authorization.occurredAt) return false
            requireRetryAuthority(binding, authorization)
        }
        val run = runs.findById(initial.id) ?: return false
        if (run.status != phase ||
            run.version != version ||
            run.isConfirmed ||
            run.roomId != initial.roomId ||
            run.batch != initial.batch
        ) {
            return false
        }
        val room = rooms.findById(run.roomId) ?: return false
        if (room.activeRunId != run.id.value || room.activeRevisionRoundId != null || room.mode != selection.mode) return false
        if (room.collectionStatus != CollectionStatus.CLOSED || room.closedAt == null) return false
        if (routes.find(run.id.value) != binding.route || binding.route.selectedAt < run.batch.fixedAt) return false
        val owned = ownedWinner(binding, winningAttemptId) ?: return false
        val native = freshFrozen(run) ?: return false
        val snapshot =
            try {
                // Pure validation only; repository and transaction failures above are always propagated.
                ScheduleEvidenceSnapshotFactory().create(
                    run,
                    native,
                    room.timeZone.value,
                    room.searchRange.startInclusive,
                    room.searchRange.endExclusive,
                    selection.context.policyVersion,
                )
            } catch (_: IllegalArgumentException) {
                return false
            }
        if (snapshot.context != selection.context) return false
        return canPublish() &&
            clock.instant() < (authorization?.deadlineAt ?: owned.deadlineAt) &&
            (authorization == null || clock.instant() >= authorization.occurredAt)
    }

    private fun requireRetryAuthority(
        binding: FinalAnalysisAttemptBinding,
        authorization: SelectedMatchingRetryAuthorization,
    ) {
        val repository = outbox ?: configuration()
        val codec = retryPayloads ?: configuration()
        val events = repository.findSelectedMatchingRetryEvents(authorization.runId, authorization.matchingRunVersion)
        if (events.size != 1 || events.single().id != authorization.eventId) configuration()
        val actual = repository.findById(authorization.eventId) ?: configuration()
        if (codec.decode(actual) != authorization) configuration()
        if (authorization.runId != binding.invocation.runId ||
            authorization.runId.value != binding.route.runId ||
            authorization.batchId.value != binding.route.batchId ||
            authorization.routeFingerprint != binding.route.fingerprint() ||
            authorization.sourceInvocationId != binding.invocation.id ||
            authorization.sourceWinnerAttemptId != binding.invocation.winnerAttemptId
        ) {
            configuration()
        }
        val latest = invocations.findLatestByRun(authorization.runId) ?: configuration()
        if (latest.id != authorization.sourceInvocationId ||
            latest.finishedAt == null ||
            latest.winnerAttemptId != authorization.sourceWinnerAttemptId ||
            latest.ownerToken != binding.invocation.ownerToken ||
            latest.startedAt != binding.invocation.startedAt ||
            latest.deadlineAt != binding.invocation.deadlineAt ||
            latest.runVersion != binding.invocation.runVersion ||
            latest.policyVersion != binding.invocation.policyVersion
        ) {
            configuration()
        }
        if (ownedWinner(binding, authorization.sourceWinnerAttemptId) == null) configuration()
    }

    private fun configuration(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)

    private fun ownedWinner(
        binding: FinalAnalysisAttemptBinding,
        winningAttemptId: UUID,
    ): AnalysisInvocation? {
        val supplied = binding.invocation
        val owned = invocations.findByRunVersion(supplied.runId, supplied.runVersion) ?: return null
        if (owned.id != supplied.id ||
            owned.runId != supplied.runId ||
            owned.runVersion != supplied.runVersion ||
            owned.ownerToken != supplied.ownerToken ||
            owned.startedAt != supplied.startedAt ||
            owned.deadlineAt != supplied.deadlineAt ||
            owned.policyVersion != supplied.policyVersion ||
            !AnalysisInvocationBudget.isAllowedPolicy(
                owned.policyVersion,
                binding.route.choice is FinalAnalysisRouteChoice.DslStructuralV5,
            ) ||
            owned.runId.value != binding.route.runId ||
            owned.finishedAt == null ||
            owned.winnerAttemptId != winningAttemptId
        ) {
            return null
        }
        val attempt = attempts.findById(winningAttemptId) ?: return null
        if (attempt.id != winningAttemptId ||
            attempt.coordinationRunId != owned.runId ||
            attempt.invocationId != owned.id ||
            attempt.finishedAt == null ||
            attempt.failureKind != null ||
            attempt.policyVersion != owned.policyVersion
        ) {
            return null
        }
        val expectedModel = if (attempt.provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna"
        if (attempt.model != expectedModel || !invocations.hasAttempt(owned.id, attempt.id)) return null
        return owned
    }

    /** Same exact-version cohort as FrozenSubmissionReader; mismatches are values, not caught repository failures. */
    private fun freshFrozen(run: CoordinationRun): List<Submission>? {
        val ids = run.batch.submissionVersionIds
        if (ids.size !in 2..50 || ids.distinct().size != ids.size || run.batch.roomId != run.roomId) return null
        val requested = ids.toSet()
        val heads = submissions.findLatestByRoom(run.roomId).filter { it.latest.id in requested }
        val missing = ids.filter { id -> heads.none { it.latest.id == id } }
        val historical = if (missing.isEmpty()) emptyList() else submissions.findFrozenByVersionIds(run.roomId, missing)
        if (historical.any { it.latest.id !in missing }) return null
        val all = heads + historical
        if (all.size != ids.size ||
            all.map { it.latest.id }.toSet() != requested ||
            all.map { it.latest.id }.distinct().size != all.size
        ) {
            return null
        }
        if (all.any { it.roomId != run.roomId } || all.map { it.participantId }.distinct().size != all.size) return null
        return all.sortedBy { it.participantId.value }
    }

    private data class Selection(
        val context: ScheduleIrContext,
        val mode: MeetingMode,
    )

    private fun selection(choice: FinalAnalysisRouteChoice): Selection? =
        when (choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> Selection(choice.context, choice.meetingMode)
            is FinalAnalysisRouteChoice.DslContextModeV4 -> Selection(choice.context, choice.meetingMode)
            is FinalAnalysisRouteChoice.DslContextV2 -> Selection(choice.context, choice.meetingMode)
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> Selection(choice.context, choice.meetingMode)
            FinalAnalysisRouteChoice.Legacy -> null
        }
}
