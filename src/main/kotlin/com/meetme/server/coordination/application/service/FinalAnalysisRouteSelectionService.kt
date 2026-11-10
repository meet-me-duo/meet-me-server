package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ApprovedScheduleDslMatchingPolicy
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralMatchingPolicy
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** Freeze after the persisted batch/active pointer, before its outbox event becomes visible. */
class FinalAnalysisRouteSelectionService(
    private val runs: CoordinationRunRepository,
    private val routes: FinalAnalysisRouteRegistry,
    private val unionPreferredAvailability: Boolean = false,
    private val serverEvidenceReferences: Boolean = false,
    private val searchRelativeDates: Boolean = false,
    private val calendarMonthFacts: Boolean = false,
    private val indexedEvidenceSelections: Boolean = false,
    private val weeklyScheduleRules: Boolean = false,
) {
    fun freeze(
        run: CoordinationRun,
        room: MeetingRoom,
        submissions: List<Submission>,
        selection: FinalAnalysisContractSelection,
    ) {
        if (selection == FinalAnalysisContractSelection.LEGACY) return
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "Contract selection requires the closure transaction" }
        require(submissions.all { it.latest.rawText != null }) { "Selected contract requires supported native text submissions" }
        val persisted = checkNotNull(runs.findById(run.id)) { "Selected run was not persisted" }
        check(persisted.id == run.id && persisted.roomId == room.id && persisted.version == run.version)
        val expectedIds = run.batch.submissionVersionIds
        val actualIds = persisted.batch.submissionVersionIds
        check(
            persisted.batch.id == run.batch.id &&
                actualIds.size == expectedIds.size &&
                actualIds.distinct().size == actualIds.size &&
                expectedIds.distinct().size == expectedIds.size &&
                actualIds.toSet() == expectedIds.toSet(),
        )
        val structural = selection == FinalAnalysisContractSelection.DSL_STRUCTURAL_V5
        require(
            !structural ||
                !weeklyScheduleRules ||
                (indexedEvidenceSelections && calendarMonthFacts && searchRelativeDates && serverEvidenceReferences),
        ) {
            "Weekly rules require indexed selections, calendar facts, scoped dates and server references"
        }
        require(
            !structural ||
                !indexedEvidenceSelections ||
                (calendarMonthFacts && searchRelativeDates && serverEvidenceReferences),
        ) {
            "Indexed evidence selections require calendar facts, scoped dates and server references"
        }
        require(!structural || !calendarMonthFacts || (serverEvidenceReferences && searchRelativeDates)) {
            "Month calendar facts require server evidence references and scoped dates"
        }
        require(
            !structural || !searchRelativeDates || serverEvidenceReferences,
        ) { "Search-relative dates require server evidence references" }
        val contextualMode = selection == FinalAnalysisContractSelection.DSL_CONTEXT_MODE_V4 || structural
        val policy =
            if (structural && unionPreferredAvailability) {
                ScheduleDslStructuralMatchingPolicy.union()
            } else if (contextualMode) {
                ScheduleDslContextModePolicy.contextual()
            } else {
                ApprovedScheduleDslMatchingPolicy.contextual()
            }
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                persisted,
                submissions,
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                policy.version,
            )
        val choice =
            if (structural) {
                FinalAnalysisRouteChoice.DslStructuralV5(
                    IrExecutionIdentity(
                        when {
                            weeklyScheduleRules -> SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
                            indexedEvidenceSelections -> SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
                            calendarMonthFacts -> SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
                            searchRelativeDates -> SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
                            serverEvidenceReferences -> SERVER_EVIDENCE_RESPONSE_CONTRACT
                            else -> SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
                        },
                        SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                        policy.version,
                    ),
                    policy,
                    snapshot.context,
                    room.mode,
                    APPROVED_KOREAN_TIME_TERM_VERSION,
                )
            } else if (contextualMode) {
                FinalAnalysisRouteChoice.DslContextModeV4(
                    IrExecutionIdentity(
                        SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT,
                        SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION,
                        policy.version,
                    ),
                    policy,
                    snapshot.context,
                    room.mode,
                    APPROVED_KOREAN_TIME_TERM_VERSION,
                )
            } else {
                FinalAnalysisRouteChoice.DslApprovedTermsV3(
                    IrExecutionIdentity(
                        SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT,
                        SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION,
                        policy.version,
                    ),
                    policy,
                    snapshot.context,
                    room.mode,
                    APPROVED_KOREAN_TIME_TERM_VERSION,
                )
            }
        routes.freeze(persisted.id.value, persisted.version, choice)
    }

    /** Preserve a selected descriptor while binding a genuinely new run to its new immutable cohort. */
    fun propagate(
        sourceRun: CoordinationRun,
        newRun: CoordinationRun,
        room: MeetingRoom,
        currentSubmissions: List<Submission>,
    ) {
        val source = routes.find(sourceRun.id.value) ?: return
        val choice = source.choice
        if (choice == FinalAnalysisRouteChoice.Legacy) return
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "Contract propagation requires the revision transaction" }
        check(source.runId == sourceRun.id.value && source.batchId == sourceRun.batch.id.value && source.roomId == sourceRun.roomId.value)
        check(sourceRun.roomId == room.id && newRun.roomId == room.id && sourceRun.id != newRun.id && sourceRun.batch.id != newRun.batch.id)
        require(currentSubmissions.all { it.latest.rawText != null }) { "Selected successor requires supported native text submissions" }
        val persisted = checkNotNull(runs.findById(newRun.id)) { "Selected successor was not persisted" }
        check(persisted.id == newRun.id && persisted.roomId == room.id && persisted.version == newRun.version)
        val expectedIds = newRun.batch.submissionVersionIds
        val actualIds = persisted.batch.submissionVersionIds
        check(
            persisted.batch.id == newRun.batch.id &&
                actualIds.size == expectedIds.size &&
                actualIds.distinct().size == actualIds.size &&
                expectedIds.distinct().size == expectedIds.size &&
                actualIds.toSet() == expectedIds.toSet(),
        )
        val policy =
            when (choice) {
                is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                    require(
                        choice.identity.responseContractVersion in
                            setOf(
                                SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT,
                            ) &&
                            choice.identity.irSemanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION &&
                            choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
                    )
                    require(choice.identity.policyVersion == choice.policy.version && choice.context.policyVersion == choice.policy.version)
                    require(
                        choice.policy ==
                            ScheduleDslStructuralMatchingPolicy.forVersion(choice.policy.version, choice.policy.alternativeChoices),
                    )
                    choice.policy
                }
                is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                    require(
                        choice.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT &&
                            choice.identity.irSemanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION &&
                            choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
                    )
                    require(choice.identity.policyVersion == choice.policy.version && choice.context.policyVersion == choice.policy.version)
                    require(choice.policy == ScheduleDslContextModePolicy.contextual(choice.policy.alternativeChoices))
                    choice.policy
                }
                is FinalAnalysisRouteChoice.DslContextV2 -> {
                    require(
                        choice.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT &&
                            choice.identity.irSemanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION,
                    )
                    require(choice.identity.policyVersion == choice.policy.version && choice.context.policyVersion == choice.policy.version)
                    choice.policy
                }
                is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                    require(
                        choice.identity.responseContractVersion == SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT &&
                            choice.identity.irSemanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION &&
                            choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION,
                    )
                    require(choice.identity.policyVersion == choice.policy.version && choice.context.policyVersion == choice.policy.version)
                    require(choice.policy == ApprovedScheduleDslMatchingPolicy.contextual(choice.policy.alternativeChoices))
                    choice.policy
                }
                FinalAnalysisRouteChoice.Legacy -> return
            }
        val newVersions = actualIds.map { it.value }.toSet()
        require(policy.alternativeChoices.all { it.submissionVersionId in newVersions }) {
            "Selected alternatives do not belong to the successor cohort"
        }
        val snapshot =
            ScheduleEvidenceSnapshotFactory().create(
                persisted,
                currentSubmissions,
                room.timeZone.value,
                room.searchRange.startInclusive,
                room.searchRange.endExclusive,
                policy.version,
            )
        val successor =
            when (choice) {
                is FinalAnalysisRouteChoice.DslStructuralV5 -> choice.copy(context = snapshot.context, meetingMode = room.mode)
                is FinalAnalysisRouteChoice.DslContextModeV4 -> choice.copy(context = snapshot.context, meetingMode = room.mode)
                is FinalAnalysisRouteChoice.DslContextV2 -> choice.copy(context = snapshot.context, meetingMode = room.mode)
                is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> choice.copy(context = snapshot.context, meetingMode = room.mode)
                FinalAnalysisRouteChoice.Legacy -> return
            }
        routes.freeze(persisted.id.value, persisted.version, successor)
    }

    /** A closed room cannot acquire or change an analysis contract through an idempotent close. */
    fun requireExisting(
        activeRunId: UUID?,
        selection: FinalAnalysisContractSelection,
    ) {
        if (selection == FinalAnalysisContractSelection.LEGACY) return
        val choice = activeRunId?.let(routes::find)?.choice
        val matches =
            when (selection) {
                FinalAnalysisContractSelection.LEGACY -> true
                FinalAnalysisContractSelection.DSL_APPROVED_TERMS_V3 ->
                    choice is FinalAnalysisRouteChoice.DslApprovedTermsV3 &&
                        choice.identity.responseContractVersion == SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT &&
                        choice.identity.irSemanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION &&
                        choice.identity.policyVersion == choice.policy.version &&
                        choice.context.policyVersion == choice.policy.version &&
                        choice.policy == ApprovedScheduleDslMatchingPolicy.contextual() &&
                        choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5 ->
                    choice is FinalAnalysisRouteChoice.DslStructuralV5 &&
                        choice.identity.responseContractVersion in
                        setOf(
                            SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT,
                            SERVER_EVIDENCE_RESPONSE_CONTRACT,
                            SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
                            SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT,
                            SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
                            SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT,
                        ) &&
                        choice.identity.irSemanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION &&
                        choice.identity.policyVersion == choice.policy.version &&
                        choice.context.policyVersion == choice.policy.version &&
                        choice.policy == ScheduleDslStructuralMatchingPolicy.forVersion(choice.policy.version) &&
                        choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION
                FinalAnalysisContractSelection.DSL_CONTEXT_MODE_V4 ->
                    choice is FinalAnalysisRouteChoice.DslContextModeV4 &&
                        choice.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT &&
                        choice.identity.irSemanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION &&
                        choice.identity.policyVersion == choice.policy.version &&
                        choice.context.policyVersion == choice.policy.version &&
                        choice.policy == ScheduleDslContextModePolicy.contextual() &&
                        choice.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION
            }
        if (!matches) throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_CLOSED)
    }
}
