package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.analysis.FrozenFinalAnalysisRoute
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslNodeDisposition
import com.meetme.server.coordination.domain.dsl.ScheduleDslPreparedInputs
import com.meetme.server.coordination.domain.dsl.ScheduleDslStoredCalculation
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.ScheduleDslApprovedTermsRepository
import com.meetme.server.submission.application.port.output.ScheduleDslContextModeRepository
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.ScheduleDslStructuralRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslApprovedTermsValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralValidator
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.UUID

/** No provider, dispatch or publication dependency; joins the authorized result reader's snapshot. */
open class ScheduleDslStoredReadService(
    private val runs: CoordinationRunRepository,
    private val rooms: MeetingRoomRepository,
    private val submissions: SubmissionRepository,
    private val routes: FinalAnalysisRouteRegistry,
    private val envelopes: ScheduleDslRepository,
    private val approvedEnvelopes: ScheduleDslApprovedTermsRepository? = null,
    private val contextModeEnvelopes: ScheduleDslContextModeRepository? = null,
    private val structuralEnvelopes: ScheduleDslStructuralRepository? = null,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    open fun readStored(
        runId: CoordinationRunId,
        expectedCurrentRunVersion: Long,
        expectedSelectionFingerprint: String,
    ): ScheduleDslStoredCalculation {
        val restored = restore(runId, expectedCurrentRunVersion, expectedSelectionFingerprint)
        val (calculation, contextMode) = restored.calculate()
        return ScheduleDslStoredCalculation(restored.route, restored.version, calculation, contextMode)
    }

    /** Only opaque IDs and fixed reasons leave this boundary; no candidate generation or source text. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    open fun readInputDiagnostics(
        runId: CoordinationRunId,
        expectedCurrentRunVersion: Long,
        expectedSelectionFingerprint: String,
    ): Map<UUID, String> {
        val restored = restore(runId, expectedCurrentRunVersion, expectedSelectionFingerprint)
        val issues =
            restored.project().flatMap { it.evidence.values }.filter { evidence ->
                evidence.submissionVersionId in restored.quarantined ||
                    evidence.unresolvedTime ||
                    evidence.programProblems.isNotEmpty() ||
                    evidence.conditions.any { it.disposition == IrConditionDisposition.WITHHELD } ||
                    evidence.nodes.any { it.disposition == ScheduleDslNodeDisposition.WITHHELD }
            }
        return issues.associate { it.submissionVersionId to "AMBIGUOUS_INPUT" }
    }

    private data class Restored(
        val route: FrozenFinalAnalysisRoute,
        val version: Long,
        val quarantined: Set<UUID>,
        val project: () -> List<ScheduleDslPreparedInputs>,
        val calculate: () -> Pair<ScheduleDslCalculation, ScheduleDslContextModeCalculation?>,
    )

    private fun restore(
        runId: CoordinationRunId,
        expectedCurrentRunVersion: Long,
        expectedSelectionFingerprint: String,
    ): Restored {
        // REQUIRED must not silently join a weaker writer transaction and claim repeatable-read consistency.
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Stored DSL read requires a transaction" }
        check(TransactionSynchronizationManager.isCurrentTransactionReadOnly()) { "Stored DSL read requires a read-only snapshot" }
        check(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() == Connection.TRANSACTION_REPEATABLE_READ) {
            "Stored DSL read requires repeatable-read isolation"
        }
        require(expectedCurrentRunVersion >= 0 && expectedSelectionFingerprint.matches(Regex("[a-f0-9]{64}")))
        val run = checkNotNull(runs.findById(runId)) { "Missing stored DSL execution" }
        check(run.version == expectedCurrentRunVersion && run.status in setOf(CoordinationStatus.MATCHING, CoordinationStatus.COMPLETED)) {
            "Stale stored DSL execution"
        }
        check(!run.isConfirmed || run.status == CoordinationStatus.COMPLETED) { "Invalid confirmed execution phase" }
        val room = checkNotNull(rooms.findById(run.roomId)) { "Missing stored DSL room" }
        check(room.activeRunId == run.id.value && room.activeRevisionRoundId == null) { "Inactive stored DSL execution" }
        check(room.collectionStatus == CollectionStatus.CLOSED && room.closedAt != null) { "Unfrozen stored DSL execution" }
        val route = checkNotNull(routes.find(run.id.value)) { "Missing selected DSL route" }
        check(route.runId == run.id.value && route.batchId == run.batch.id.value && route.roomId == run.roomId.value)
        check(route.fingerprint() == expectedSelectionFingerprint) { "Substituted stored DSL selection" }
        require(route.selectedAt >= run.batch.fixedAt)
        val structural = route.choice as? FinalAnalysisRouteChoice.DslStructuralV5
        if (structural != null) {
            require(structural.identity.responseContractVersion == SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT)
            require(structural.identity.irSemanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION)
            require(structural.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
            require(
                structural.identity.policyVersion == structural.policy.version &&
                    structural.context.policyVersion == structural.policy.version,
            )
            require(structural.policy == ScheduleDslContextModePolicy.contextual(structural.policy.alternativeChoices))
            check(structural.meetingMode == room.mode) { "Changed stored structural meeting mode" }
            val snapshot =
                ScheduleEvidenceSnapshotFactory().create(
                    run,
                    FrozenSubmissionReader.read(submissions, run.batch),
                    room.timeZone.value,
                    room.searchRange.startInclusive,
                    room.searchRange.endExclusive,
                    structural.policy.version,
                )
            check(snapshot.context == structural.context) { "Changed stored structural sources or context" }
            val envelope =
                checkNotNull(requireNotNull(structuralEnvelopes).find(structural.context)) {
                    "Selected structural route has no stored result"
                }
            require(
                envelope.semanticVersion == structural.identity.irSemanticVersion &&
                    envelope.astVersion == SCHEDULE_AST_VERSION &&
                    envelope.base.base.vocabularyVersion == structural.vocabularyVersion,
            )
            require(envelope.context == structural.context) { "Stored structural context mismatch" }
            val validated = ScheduleDslStructuralValidator().validateRestored(envelope, snapshot)
            check(validated == envelope) { "Stored structural restoration mismatch" }
            return Restored(
                route,
                run.version,
                validated.base.base.declaration.claims.results
                    .filter { it.quarantined.isNotEmpty() }
                    .map { it.submissionVersionId }
                    .toSet(),
                {
                    ScheduleDslStructuralCalculator
                        .prepare(
                            validated,
                            snapshot,
                            structural.policy,
                            structural.meetingMode,
                        ).preparedByMode.values
                        .toList()
                },
                {
                    val calculation =
                        ScheduleDslStructuralCalculator.calculate(
                            validated,
                            snapshot,
                            structural.policy,
                            structural.meetingMode,
                        )
                    // This genuine atomic view carries cohort IDs only; mode-specific authorization never unions these maps.
                    ScheduleDslCalculation(
                        calculation.preparedByMode.values.first(),
                        calculation.candidates,
                        calculation.recommendations,
                        calculation.status,
                        calculation.precisionOutcome,
                    ) to calculation
                },
            )
        }
        val contextual = route.choice as? FinalAnalysisRouteChoice.DslContextModeV4
        if (contextual != null) {
            require(contextual.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT)
            require(contextual.identity.irSemanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION)
            require(contextual.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
            require(
                contextual.identity.policyVersion == contextual.policy.version &&
                    contextual.context.policyVersion == contextual.policy.version,
            )
            require(contextual.policy == ScheduleDslContextModePolicy.contextual(contextual.policy.alternativeChoices))
            check(contextual.meetingMode == room.mode) { "Changed stored context-mode meeting mode" }
            val snapshot =
                ScheduleEvidenceSnapshotFactory().create(
                    run,
                    FrozenSubmissionReader.read(submissions, run.batch),
                    room.timeZone.value,
                    room.searchRange.startInclusive,
                    room.searchRange.endExclusive,
                    contextual.policy.version,
                )
            check(snapshot.context == contextual.context) { "Changed stored context-mode sources or context" }
            val envelope =
                checkNotNull(requireNotNull(contextModeEnvelopes).find(contextual.context)) {
                    "Selected context-mode route has no stored result"
                }
            require(
                envelope.semanticVersion == contextual.identity.irSemanticVersion &&
                    envelope.astVersion == SCHEDULE_AST_VERSION &&
                    envelope.base.vocabularyVersion == contextual.vocabularyVersion,
            )
            val validated = ScheduleDslContextModeValidator().validateRestored(envelope, snapshot)
            check(validated == envelope) { "Stored context-mode restoration mismatch" }
            return Restored(
                route,
                run.version,
                validated.base.declaration.claims.results
                    .filter { it.quarantined.isNotEmpty() }
                    .map { it.submissionVersionId }
                    .toSet(),
                {
                    ScheduleDslContextModeCalculator
                        .prepare(
                            validated,
                            snapshot,
                            contextual.policy,
                            contextual.meetingMode,
                        ).preparedByMode.values
                        .toList()
                },
                {
                    val calculation =
                        ScheduleDslContextModeCalculator.calculate(
                            validated,
                            snapshot,
                            contextual.policy,
                            contextual.meetingMode,
                        )
                    // This genuine atomic view carries cohort IDs only; mode-specific authorization never unions these maps.
                    ScheduleDslCalculation(
                        calculation.preparedByMode.values.first(),
                        calculation.candidates,
                        calculation.recommendations,
                        calculation.status,
                        calculation.precisionOutcome,
                    ) to calculation
                },
            )
        }
        val approved = route.choice as? FinalAnalysisRouteChoice.DslApprovedTermsV3
        if (approved != null) {
            require(approved.identity.responseContractVersion == SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT)
            require(approved.identity.irSemanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION)
            require(approved.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
            require(approved.identity.policyVersion == approved.policy.version && approved.context.policyVersion == approved.policy.version)
            require(approved.policy == ApprovedScheduleDslMatchingPolicy.contextual(approved.policy.alternativeChoices))
            check(approved.meetingMode == room.mode) { "Changed stored approved meeting mode" }
            val snapshot =
                ScheduleEvidenceSnapshotFactory().create(
                    run,
                    FrozenSubmissionReader.read(submissions, run.batch),
                    room.timeZone.value,
                    room.searchRange.startInclusive,
                    room.searchRange.endExclusive,
                    approved.policy.version,
                )
            check(snapshot.context == approved.context) { "Changed stored approved sources or context" }
            val envelope =
                checkNotNull(requireNotNull(approvedEnvelopes).find(approved.context)) { "Selected approved route has no stored result" }
            require(
                envelope.semanticVersion == approved.identity.irSemanticVersion &&
                    envelope.astVersion == SCHEDULE_AST_VERSION &&
                    envelope.vocabularyVersion == approved.vocabularyVersion,
            )
            val validated = ScheduleDslApprovedTermsValidator().validateRestored(envelope, snapshot)
            check(validated == envelope) { "Stored approved restoration mismatch" }
            return Restored(
                route,
                run.version,
                validated.declaration.claims.results
                    .filter { it.quarantined.isNotEmpty() }
                    .map { it.submissionVersionId }
                    .toSet(),
                { listOf(ScheduleDslInputPolicy.projectWithPreferredAvailability(validated.calculationProjection, approved.policy)) },
                { ScheduleDslCalculator.calculateApprovedTerms(validated, snapshot, approved.policy, approved.meetingMode) to null },
            )
        }
        val choice = checkNotNull(route.choice as? FinalAnalysisRouteChoice.DslContextV2) { "Not a selected context DSL route" }
        require(choice.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT)
        require(choice.identity.irSemanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION)
        require(choice.identity.policyVersion == choice.policy.version && choice.context.policyVersion == choice.policy.version)
        require(IrInterpretation.UNRESOLVED !in choice.policy.eligibleInterpretations)
        check(choice.meetingMode == room.mode) { "Changed stored DSL meeting mode" }
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                run,
                FrozenSubmissionReader.read(submissions, run.batch),
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                choice.policy.version,
            )
        check(snapshot.context == choice.context) { "Changed stored DSL sources or context" }
        val envelope = checkNotNull(envelopes.find(choice.context)) { "Selected DSL has no stored result" }
        require(envelope.semanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION && envelope.astVersion == SCHEDULE_AST_VERSION)
        val validated = ScheduleDslValidator().validateRestored(envelope, snapshot)
        check(validated == envelope) { "Stored DSL restoration mismatch" }
        return Restored(
            route,
            run.version,
            validated.claims.results
                .filter { it.quarantined.isNotEmpty() }
                .map { it.submissionVersionId }
                .toSet(),
            { listOf(ScheduleDslInputPolicy.project(validated, choice.policy)) },
            { ScheduleDslCalculator.calculate(validated, choice.policy, choice.meetingMode) to null },
        )
    }
}
