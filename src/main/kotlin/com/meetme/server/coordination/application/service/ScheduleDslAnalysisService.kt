package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.ScheduleDslResponseParser
import com.meetme.server.coordination.application.port.output.ScheduleIrExecutionRegistry
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculationResult
import com.meetme.server.coordination.domain.dsl.ScheduleDslCalculator
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.coordination.domain.ir.IrExecutionTicket
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.submission.application.port.output.ScheduleDslRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Explicit Spring-managed entry point only; not registered in any operational worker or controller. */
open class ScheduleDslAnalysisService(
    private val runs: CoordinationRunRepository,
    private val rooms: MeetingRoomRepository,
    private val submissions: SubmissionRepository,
    private val registry: ScheduleIrExecutionRegistry,
    private val irRepository: ScheduleDslRepository,
    private val responseParser: ScheduleDslResponseParser,
) {
    private val execution =
        ScheduleIrExecutionContextSupport(
            runs,
            rooms,
            submissions,
            registry,
            responseParser.supportedResponseContractVersion,
            SCHEDULE_DSL_SEMANTIC_VERSION,
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

    @Transactional
    open fun input(ticket: IrExecutionTicket): ScheduleEvidenceSnapshot = execution.requireCurrent(ticket)

    @Transactional
    open fun inputCapsule(ticket: IrExecutionTicket): ScheduleDslProviderInputCapsule {
        require(ticket.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT)
        val snapshot = execution.requireCurrent(ticket)
        val run = checkNotNull(runs.findById(CoordinationRunId(ticket.context.runId))) { "Missing IR execution" }
        return ScheduleDslProviderInputCapsule.create(snapshot, FrozenSubmissionReader.read(submissions, run.batch))
    }

    @Transactional
    open fun replayIfPresent(ticket: IrExecutionTicket): ScheduleDslCalculationResult? {
        execution.requireCurrent(ticket)
        return irRepository.find(ticket.context)?.let { calculate(ticket, it) }
    }

    /** Publication, source references, fresh restoration and calculation join the caller's transaction. */
    @Transactional
    open fun consume(
        ticket: IrExecutionTicket,
        responseJson: String,
        processedAt: Instant,
    ): ScheduleDslCalculationResult {
        val snapshot = execution.requireCurrent(ticket)
        require(processedAt >= ticket.preparedAt)
        val parsed =
            responseParser.parse(
                responseJson,
                snapshot,
                ticket.identity.responseContractVersion,
                ticket.identity.irSemanticVersion,
                SCHEDULE_AST_VERSION,
            )
        irRepository.publish(parsed, ticket.expectedRunVersion, processedAt)
        val restored = checkNotNull(irRepository.find(ticket.context)) { "Missing consumed IR" }
        check(restored == parsed) { "IR restoration mismatch" }
        return calculate(ticket, restored)
    }

    @Transactional
    open fun replay(ticket: IrExecutionTicket): ScheduleDslCalculationResult {
        execution.requireCurrent(ticket)
        return calculate(ticket, checkNotNull(irRepository.find(ticket.context)) { "Prepared selection has no consumed IR" })
    }

    private fun calculate(
        ticket: IrExecutionTicket,
        envelope: ScheduleDslEnvelope,
    ): ScheduleDslCalculationResult {
        val calculation = ScheduleDslCalculator.calculate(envelope, ticket.policy, ticket.meetingMode)
        registry.requireCurrent(ticket)
        return ScheduleDslCalculationResult(
            ticket,
            envelope,
            calculation.prepared,
            calculation.candidates,
            calculation.recommendations,
            calculation.status,
            calculation.precisionOutcome,
            ticket.fingerprint(),
        )
    }
}
