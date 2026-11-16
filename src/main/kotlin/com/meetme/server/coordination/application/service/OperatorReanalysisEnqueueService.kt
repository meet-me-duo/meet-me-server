package com.meetme.server.coordination.application.service

import com.meetme.server.config.RateLimitProperties
import com.meetme.server.coordination.application.port.input.OperatorReanalysisReceipt
import com.meetme.server.coordination.application.port.input.OperatorReanalysisRequest
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandPort
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandRecord
import com.meetme.server.coordination.application.port.output.OperatorReanalysisCommandRepository
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.precheck.application.port.output.PrecheckRepository
import com.meetme.server.shared.application.port.input.operations.OperatorQueryError
import com.meetme.server.shared.application.port.input.operations.OperatorQueryException
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.application.port.output.RateLimitPort
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class OperatorReanalysisEnqueueService(
    private val rooms: MeetingRoomRepository,
    private val runs: CoordinationRunRepository,
    private val submissions: SubmissionRepository,
    private val outbox: OutboxRepository,
    private val selection: FinalAnalysisRouteSelectionService,
    private val finalAnalysisRoutes: FinalAnalysisRouteRegistry,
    private val commands: OperatorReanalysisCommandRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val rateLimit: RateLimitPort,
    private val rateProperties: RateLimitProperties,
    private val prechecks: PrecheckRepository? = null,
) : OperatorReanalysisCommandPort {
    @Transactional
    override fun enqueue(
        actorId: UUID,
        roomId: UUID,
        request: OperatorReanalysisRequest,
    ): OperatorReanalysisReceipt {
        val room =
            rooms.findByIdForUpdate(MeetingRoomId(roomId))
                ?: throw OperatorQueryException(OperatorQueryError.OPERATOR_NOT_FOUND)
        commands.find(roomId, request.requestId)?.let { stored ->
            if (stored.actorId != actorId || stored.request != request) conflict()
            return stored.receipt
        }
        if (room.collectionStatus != CollectionStatus.CLOSED ||
            room.activeRevisionRoundId != null ||
            room.activeRunId != request.sourceAnalysisId ||
            room.version != request.expectedStateVersion
        ) {
            conflict()
        }
        if (commands.hasOpenRevisionRound(roomId)) conflict()
        val source = runs.findByIdForUpdate(CoordinationRunId(request.sourceAnalysisId)) ?: conflict()
        if (source.roomId != room.id ||
            source.isConfirmed ||
            source.status !in setOf(CoordinationStatus.COMPLETED, CoordinationStatus.ANALYSIS_DELAYED, CoordinationStatus.DEAD_LETTERED)
        ) {
            conflict()
        }
        val exact = FrozenSubmissionReader.read(submissions, source.batch)
        if (exact.any { it.latest.rawText == null }) unsupported()
        val fixedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (exact.any { it.latest.createdAt > fixedAt }) unsupported()
        exact.filter { it.latest.evidence == null }.forEach {
            val anchor = prechecks?.anchor(it.latest.id.value)
            if (anchor != null &&
                anchor.atZone(room.timeZone.value).toLocalDate() !=
                it.latest.createdAt
                    .atZone(room.timeZone.value)
                    .toLocalDate()
            ) {
                unsupported()
            }
        }
        val digest =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest((actorId.toString() + ":" + roomId).toByteArray(Charsets.UTF_8)),
            )
        val rule = rateProperties.hostCommand
        val decision =
            try {
                rateLimit.check("meetme:rate:operator-reanalysis:" + digest, rule.limit, rule.window)
            } catch (_: Exception) {
                throw OperatorQueryException(OperatorQueryError.OPERATOR_UNAVAILABLE)
            }
        if (!decision.allowed) {
            throw OperatorQueryException(
                OperatorQueryError.OPERATOR_REANALYSIS_RATE_LIMITED,
                decision.retryAfter.seconds.coerceAtLeast(1),
            )
        }
        val batch = SubmissionBatch(SubmissionBatchId(ids.next()), room.id, exact.map { it.latest.id }, fixedAt)
        val run = CoordinationRun.queued(CoordinationRunId(ids.next()), batch)
        runs.insert(run)
        val active = room.transition(activeRunId = run.id.value)
        rooms.update(active)
        selection.freeze(run, active, exact, FinalAnalysisContractSelection.DSL_STRUCTURAL_V5)
        val descriptor = checkNotNull(finalAnalysisRoutes.find(run.id.value)).choice
        check(descriptor is FinalAnalysisRouteChoice.DslStructuralV5) { "Operator reanalysis requires a frozen V5 route" }
        val receipt =
            OperatorReanalysisReceipt(
                request.requestId,
                source.id.value,
                run.id.value,
                active.version,
                fixedAt,
                descriptor.identity.responseContractVersion,
            )
        commands.insert(OperatorReanalysisCommandRecord(roomId, actorId, request, receipt))
        val eventId = OutboxEventId(ids.next())
        outbox.insert(
            OutboxEvent(
                eventId,
                "CoordinationRun",
                run.id.value,
                CollectionClosureService.STRUCTURING_REQUESTED,
                "{\"event_id\":\"" + eventId.value + "\",\"submission_batch_id\":\"" + batch.id.value + "\"}",
                occurredAt = fixedAt,
            ),
        )
        return receipt
    }

    private fun conflict(): Nothing = throw OperatorQueryException(OperatorQueryError.OPERATOR_REANALYSIS_CONFLICT)

    private fun unsupported(): Nothing = throw OperatorQueryException(OperatorQueryError.OPERATOR_REANALYSIS_UNSUPPORTED_INPUT)
}
