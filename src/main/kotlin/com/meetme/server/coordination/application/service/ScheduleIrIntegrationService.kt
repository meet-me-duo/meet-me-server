package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.application.port.output.ScheduleIrResponseParser
import com.meetme.server.coordination.domain.ir.IrCalculationResult
import com.meetme.server.coordination.domain.ir.IrCalculationStatus
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.coordination.domain.matching.CandidateGenerationResult
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.coordination.domain.matching.MatchOutcome
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.coordination.domain.matching.ScheduleIrMatchInputPolicy
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.ScheduleIrRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

/** Explicit Spring-managed entry point only; not registered in any operational worker or controller. */
open class ScheduleIrIntegrationService(
    private val runs: CoordinationRunRepository,
    private val rooms: MeetingRoomRepository,
    private val submissions: SubmissionRepository,
    private val registry: ScheduleIrExecutionRegistry,
    private val irRepository: ScheduleIrRepository,
    private val responseParser: ScheduleIrResponseParser,
) {
    private val execution =
        ScheduleIrExecutionContextSupport(
            runs,
            rooms,
            submissions,
            registry,
            responseParser.supportedResponseContractVersion,
            SCHEDULE_IR_VERSION,
        )

    /**
     * The first selection commits independently of consume. Call before dispatch and without caller-held room/run locks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun prepare(
        runId: CoordinationRunId,
        expectedRunVersion: Long,
        identity: IrExecutionIdentity,
        policy: IrMatchingPolicy,
        preparedAt: Instant,
    ): IrExecutionTicket = execution.prepare(runId, expectedRunVersion, identity, policy, preparedAt)

    /** Publication, source references, fresh restoration and calculation join the caller's transaction. */
    @Transactional
    open fun consume(
        ticket: IrExecutionTicket,
        responseJson: String,
        processedAt: Instant,
    ): IrCalculationResult {
        val snapshot = execution.requireCurrent(ticket)
        require(processedAt >= ticket.preparedAt)
        val parsed =
            responseParser.parse(
                responseJson,
                snapshot,
                ticket.identity.responseContractVersion,
                ticket.identity.irSemanticVersion,
            )
        irRepository.publish(parsed, ticket.expectedRunVersion, processedAt)
        val restored = checkNotNull(irRepository.find(ticket.context)) { "Missing consumed IR" }
        check(restored == parsed) { "IR restoration mismatch" }
        return calculate(ticket, restored)
    }

    @Transactional
    open fun replay(ticket: IrExecutionTicket): IrCalculationResult {
        execution.requireCurrent(ticket)
        return calculate(ticket, checkNotNull(irRepository.find(ticket.context)) { "Prepared selection has no consumed IR" })
    }

    private fun calculate(
        ticket: IrExecutionTicket,
        envelope: ScheduleIrEnvelope,
    ): IrCalculationResult {
        val prepared = ScheduleIrMatchInputPolicy.project(envelope, ticket.policy)
        val unsupportedMode = ticket.meetingMode != MeetingMode.REMOTE
        // The IR has no typed place/mode capability. An inactive technical result cannot invent that policy.
        val candidates =
            if (unsupportedMode) {
                CandidateGenerationResult(MatchOutcome.NO_MATCH, emptyList())
            } else {
                DeterministicCandidateMatcher.generate(ticket.meetingMode, prepared.participants)
            }
        val recommendations =
            if (unsupportedMode) {
                RecommendationProjection(emptyList(), emptyList())
            } else {
                RecommendationProjector.generate(ticket.meetingMode, prepared.participants, ZoneId.of(ticket.context.timeZone))
            }
        val partial =
            prepared.evidence.values.any { evidence ->
                evidence.unresolvedTime ||
                    evidence.unsupportedDimensions.isNotEmpty() ||
                    evidence.conditions.any { it.disposition == IrConditionDisposition.WITHHELD }
            }
        val status =
            when {
                unsupportedMode -> IrCalculationStatus.REQUIRES_DECISION
                partial -> IrCalculationStatus.PARTIAL
                else -> IrCalculationStatus.CALCULATED
            }
        registry.requireCurrent(ticket)
        return IrCalculationResult(ticket, envelope, prepared, candidates, recommendations, status, ticket.fingerprint())
    }
}
