package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.RetryAnalysisUseCase
import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.CoordinationAttemptRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.OutboxEvent
import com.meetme.server.coordination.application.port.output.OutboxRepository
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.SelectedMatchingRetryAuthorization
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.meetingroom.application.port.input.GetRoomUseCase
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleErrorCode
import com.meetme.server.meetingroom.application.port.input.RoomLifecycleException
import com.meetme.server.meetingroom.application.port.input.RoomView
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.application.service.CollectionClosureService
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.participant.application.service.OwnershipOperation
import com.meetme.server.participant.application.service.ParticipantOwnershipResolver
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.OutboxEventId
import com.meetme.server.submission.application.port.input.SubmissionErrorCode
import com.meetme.server.submission.application.port.input.SubmissionException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class RetryAnalysisService(
    private val roomRepository: MeetingRoomRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val outboxRepository: OutboxRepository,
    private val idGenerator: IdGenerator,
    private val getRoom: GetRoomUseCase,
    private val clock: Clock,
    private val ownership: ParticipantOwnershipResolver,
    private val finalAnalysisRoutes: FinalAnalysisRouteRegistry? = null,
    private val invocationRepository: AnalysisInvocationRepository? = null,
    private val attemptRepository: CoordinationAttemptRepository? = null,
    private val retryPayloads: SelectedMatchingRetryPayloadCodec? = null,
) : RetryAnalysisUseCase {
    @Transactional
    override fun retry(
        inviteCode: String,
        rawCredential: String?,
    ): RoomView {
        val code =
            runCatching { InviteCode.of(inviteCode) }.getOrNull()
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        val room =
            roomRepository.findByInviteCodeForUpdate(code)
                ?: throw RoomLifecycleException(RoomLifecycleErrorCode.ROOM_NOT_FOUND)
        ownership.resolve(room.id, rawCredential, OwnershipOperation.HOST_COMMAND)
        if (room.activeRevisionRoundId != null) throw SubmissionException(SubmissionErrorCode.ANALYSIS_NOT_DELAYED)
        val run =
            coordinationRunRepository.findLatestByRoomForUpdate(room.id)
                ?: throw SubmissionException(SubmissionErrorCode.ANALYSIS_NOT_DELAYED)
        val hasPending =
            outboxRepository.existsPending(run.id.value, CollectionClosureService.STRUCTURING_REQUESTED) ||
                outboxRepository.existsPending(run.id.value, CollectionClosureService.MATCHING_REQUESTED)
        if (run.status == CoordinationStatus.QUEUED && hasPending) {
            return getRoom.get(inviteCode, rawCredential)
        }
        if (run.status != CoordinationStatus.ANALYSIS_DELAYED) {
            throw SubmissionException(SubmissionErrorCode.ANALYSIS_NOT_DELAYED)
        }
        if (!hasPending) {
            val retried = run.retryAnalysis()
            coordinationRunRepository.update(retried)
            roomRepository.update(room.transition())
            val eventId = OutboxEventId(idGenerator.next())
            val eventType =
                if (retried.status == CoordinationStatus.MATCHING) {
                    CollectionClosureService.MATCHING_REQUESTED
                } else {
                    CollectionClosureService.STRUCTURING_REQUESTED
                }
            val choice = finalAnalysisRoutes?.find(run.id.value)
            val selectedMatching =
                retried.status == CoordinationStatus.MATCHING &&
                    (
                        choice?.choice is FinalAnalysisRouteChoice.DslContextV2 ||
                            choice?.choice is FinalAnalysisRouteChoice.DslApprovedTermsV3 ||
                            choice?.choice is FinalAnalysisRouteChoice.DslContextModeV4 ||
                            choice?.choice is FinalAnalysisRouteChoice.DslStructuralV5
                    )
            val occurredAt = if (selectedMatching) clock.instant().truncatedTo(ChronoUnit.MICROS) else clock.instant()
            val payload =
                if (selectedMatching) {
                    val route = checkNotNull(choice)
                    if (route.runId != run.id.value || route.batchId != run.batch.id.value || route.roomId != room.id.value) configuration()
                    val invocation = invocationRepository?.findLatestByRun(run.id) ?: configuration()
                    val winner = invocation.winnerAttemptId ?: configuration()
                    if (invocation.runId != run.id ||
                        invocation.finishedAt == null ||
                        invocation.runVersion + 1 >= retried.version ||
                        !AnalysisInvocationBudget.isAllowedPolicy(
                            invocation.policyVersion,
                            route.choice is FinalAnalysisRouteChoice.DslStructuralV5,
                        )
                    ) {
                        configuration()
                    }
                    val attempt = attemptRepository?.findById(winner) ?: configuration()
                    val model = if (attempt.provider == AnalysisProvider.GEMINI) "gemini-3.8-flash" else "gpt-6-luna"
                    if (attempt.id != winner ||
                        attempt.coordinationRunId != run.id ||
                        attempt.invocationId != invocation.id ||
                        attempt.finishedAt == null ||
                        attempt.failureKind != null ||
                        attempt.model != model ||
                        attempt.policyVersion != invocation.policyVersion ||
                        invocationRepository?.hasAttempt(invocation.id, winner) != true
                    ) {
                        configuration()
                    }
                    (retryPayloads ?: configuration()).encode(
                        SelectedMatchingRetryAuthorization(
                            eventId,
                            run.id,
                            run.batch.id,
                            retried.version,
                            invocation.id,
                            winner,
                            route.fingerprint(),
                            occurredAt,
                        ),
                    )
                } else {
                    "{\"event_id\":\"${eventId.value}\",\"submission_batch_id\":\"${run.batch.id.value}\"}"
                }
            outboxRepository.insert(
                OutboxEvent(
                    eventId,
                    "CoordinationRun",
                    run.id.value,
                    eventType,
                    payload,
                    occurredAt = occurredAt,
                ),
            )
        }
        return getRoom.get(inviteCode, rawCredential)
    }

    private fun configuration(): Nothing = throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
}
