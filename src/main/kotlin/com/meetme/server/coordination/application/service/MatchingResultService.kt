package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.input.CandidateListView
import com.meetme.server.coordination.application.port.input.CandidatePlaceView
import com.meetme.server.coordination.application.port.input.CandidateView
import com.meetme.server.coordination.application.port.input.ConfirmCandidateUseCase
import com.meetme.server.coordination.application.port.input.ConfirmedResultView
import com.meetme.server.coordination.application.port.input.GetCandidatesUseCase
import com.meetme.server.coordination.application.port.input.GetConfirmedResultUseCase
import com.meetme.server.coordination.application.port.input.GetUnappliedInputsUseCase
import com.meetme.server.coordination.application.port.input.MatchingResultErrorCode
import com.meetme.server.coordination.application.port.input.MatchingResultException
import com.meetme.server.coordination.application.port.input.RecommendationListView
import com.meetme.server.coordination.application.port.input.RecommendationOptionView
import com.meetme.server.coordination.application.port.input.RecommendationUseCase
import com.meetme.server.coordination.application.port.input.RecommendationVariantView
import com.meetme.server.coordination.application.port.input.SelectRecommendationCommand
import com.meetme.server.coordination.application.port.input.UnappliedInputView
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceStatus
import com.meetme.server.coordination.application.port.output.RecommendationQuery
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.MeetingCandidate
import com.meetme.server.coordination.domain.RECOMMENDATION_PROTOCOL
import com.meetme.server.coordination.domain.RecommendationAnalysis
import com.meetme.server.coordination.domain.RecommendationSelection
import com.meetme.server.coordination.domain.StoredRecommendationOption
import com.meetme.server.coordination.domain.matching.PlanType
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.CollectionStatus
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantRole
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Service
class MatchingResultService(
    private val roomRepository: MeetingRoomRepository,
    private val guestSessionRepository: GuestSessionRepository,
    private val participantRepository: ParticipantRepository,
    private val submissionRepository: SubmissionRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val normalizedPlaceRepository: NormalizedPlaceRepository,
    private val coordinationRunRepository: CoordinationRunRepository,
    private val credentialPort: GuestCredentialPort,
    private val messageSource: MessageSource,
    private val clock: Clock,
    private val recommendations: RecommendationRepository? = null,
) : GetCandidatesUseCase,
    GetUnappliedInputsUseCase,
    ConfirmCandidateUseCase,
    GetConfirmedResultUseCase,
    RecommendationUseCase {
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun getCandidates(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): CandidateListView {
        val room = room(inviteCode)
        participant(room.id, rawCredential)
        return candidateList(completedRun(room), room, locale)
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun getUnappliedInputs(
        inviteCode: String,
        rawCredential: String?,
    ): List<UnappliedInputView> {
        val room = room(inviteCode)
        val viewer = participant(room.id, rawCredential)
        if (viewer.role != ParticipantRole.HOST) fail(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
        val run = completedRun(room)
        val submissions = frozenSubmissions(run)
        val structured = structuredSubmissionRepository.findByBatch(run.batch.id).associateBy { it.submissionVersionId }
        val placeFailures =
            normalizedPlaceRepository
                .findByBatch(run.batch.id)
                .filter { it.status != NormalizedPlaceStatus.RESOLVED }
                .groupBy { it.submissionVersionId }
        return submissions.mapNotNull { submission ->
            val result = structured[submission.latest.id] ?: return@mapNotNull null
            val reason = result.rejectionCode ?: placeFailures[submission.latest.id]?.firstOrNull()?.status?.name ?: return@mapNotNull null
            val rawText = submission.latest.rawText
            val owner = participantRepository.findById(submission.participantId) ?: return@mapNotNull null
            UnappliedInputView(owner.displayName.value, rawText?.toPlainText(), reason)
        }
    }

    @Transactional
    override fun confirm(
        inviteCode: String,
        candidateId: UUID,
        rawCredential: String?,
        locale: Locale,
    ): ConfirmedResultView {
        val room = room(inviteCode, lock = true)
        val viewer = participant(room.id, rawCredential)
        if (viewer.role != ParticipantRole.HOST) fail(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
        if (room.activeRevisionRoundId != null) fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        val run =
            coordinationRunRepository.findLatestByRoomForUpdate(room.id)
                ?: fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        if (run.status != CoordinationStatus.COMPLETED) fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        if (run.recommendationProtocol != null) fail(MatchingResultErrorCode.RECOMMENDATION_SELECTION_REQUIRED)
        val requested = CandidateId(candidateId)
        val existing = run.confirmedCandidateId
        if (existing != null && existing != requested) fail(MatchingResultErrorCode.CANDIDATE_ALREADY_CONFIRMED)
        val candidate =
            run.candidates.firstOrNull { it.id == requested }
                ?: fail(MatchingResultErrorCode.CANDIDATE_NOT_FOUND)
        val confirmed =
            if (existing == requested) {
                run
            } else {
                val next = run.confirm(requested, clock.instant().truncatedTo(ChronoUnit.MICROS))
                coordinationRunRepository.update(next)
                roomRepository.update(room.transition())
                next
            }
        return ConfirmedResultView(candidate.toView(room, locale), requireNotNull(confirmed.confirmedAt))
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun getConfirmed(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): ConfirmedResultView {
        val room = room(inviteCode)
        participant(room.id, rawCredential)
        val run = completedRun(room)
        run.recommendationSelection?.let { return selectedResult(run, room, it, locale) }
        val id = run.confirmedCandidateId ?: fail(MatchingResultErrorCode.RESULT_NOT_CONFIRMED)
        val candidate = run.candidates.firstOrNull { it.id == id } ?: fail(MatchingResultErrorCode.CANDIDATE_NOT_FOUND)
        return ConfirmedResultView(candidate.toView(room, locale), requireNotNull(run.confirmedAt))
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun primary(
        inviteCode: String,
        rawCredential: String?,
        locale: Locale,
    ): RecommendationListView {
        val room = room(inviteCode)
        participant(room.id, rawCredential)
        val run = completedRun(room)
        val analysis = recommendations?.find(run.id.value, RecommendationQuery(primaryOnly = true, limit = 3))
        val primary =
            analysis
                ?.options
                .orEmpty()
                .filter { it.primaryRank != null }
                .sortedBy { it.primaryRank }
        return recommendationList(run, room, analysis, primary, null, locale)
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    override fun alternatives(
        inviteCode: String,
        analysisId: UUID,
        cursor: String?,
        limit: Int,
        rawCredential: String?,
        locale: Locale,
    ): RecommendationListView {
        require(limit in 1..100)
        val room = room(inviteCode)
        participant(room.id, rawCredential)
        val run = completedRun(room)
        if (run.id.value != analysisId) fail(MatchingResultErrorCode.STALE_ANALYSIS)
        val after =
            cursor?.let {
                require(it.length <= 128)
                val decoded =
                    String(
                        java.util.Base64
                            .getUrlDecoder()
                            .decode(it),
                        StandardCharsets.UTF_8,
                    ).split(':')
                require(decoded.size == 2)
                require(UUID.fromString(decoded[0]) == analysisId)
                val ordinal = decoded[1].toInt()
                require(recommendations?.containsAlternativeOrdinal(analysisId, ordinal) == true)
                ordinal
            } ?: -1
        val analysis =
            recommendations?.find(
                run.id.value,
                RecommendationQuery(alternativesOnly = true, afterOrdinal = after, limit = limit + 1),
            )
        val remaining = analysis?.options.orEmpty()
        val page = remaining.take(limit)
        val next =
            page.lastOrNull()?.takeIf { remaining.size > page.size }?.let {
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "$analysisId:${it.ordinal}".toByteArray(StandardCharsets.UTF_8),
                )
            }
        return recommendationList(run, room, analysis, page, next, locale)
    }

    @Transactional
    override fun confirm(command: SelectRecommendationCommand): ConfirmedResultView {
        val room = room(command.inviteCode, lock = true)
        if (participant(room.id, command.rawCredential).role != ParticipantRole.HOST) fail(MatchingResultErrorCode.HOST_PERMISSION_REQUIRED)
        if (room.activeRevisionRoundId != null || room.collectionStatus != CollectionStatus.CLOSED) {
            fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        }
        val run = coordinationRunRepository.findLatestByRoomForUpdate(room.id) ?: fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        if (run.id.value != command.analysisId) fail(MatchingResultErrorCode.STALE_ANALYSIS)
        if (run.status != CoordinationStatus.COMPLETED ||
            run.recommendationProtocol != RECOMMENDATION_PROTOCOL
        ) {
            fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        }
        require(command.startAt.nano % 1000 == 0 && command.endAt.nano % 1000 == 0) { "Selection supports microsecond precision" }
        val analysis = requireNotNull(recommendations?.find(run.id.value, RecommendationQuery(optionId = command.optionId)))
        check(analysis.roomId == room.id.value)
        val option = analysis.options.firstOrNull { it.id == command.optionId }
        require(option != null && command.variantId in option.variantIds)
        require(
            command.startAt < command.endAt &&
                command.startAt >= option.option.window.startInclusive &&
                command.endAt <= option.option.window.endExclusive,
        )
        val existing = run.recommendationSelection
        if (run.isConfirmed) {
            if (existing == null ||
                existing.optionId != command.optionId ||
                existing.variantId != command.variantId ||
                existing.selectedWindow.startInclusive != command.startAt ||
                existing.selectedWindow.endExclusive != command.endAt
            ) {
                fail(MatchingResultErrorCode.CANDIDATE_ALREADY_CONFIRMED)
            }
            return selectedResult(run, room, existing, command.locale)
        }
        val selection =
            RecommendationSelection(
                run.id.value,
                command.optionId,
                command.variantId,
                com.meetme.server.shared.domain.time
                    .InstantTimeRange(command.startAt, command.endAt),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        val confirmed = run.confirmSelection(selection)
        coordinationRunRepository.update(confirmed)
        roomRepository.update(room.transition())
        return selectedResult(confirmed, room, selection, command.locale)
    }

    private fun recommendationList(
        run: CoordinationRun,
        room: MeetingRoom,
        analysis: RecommendationAnalysis?,
        options: List<StoredRecommendationOption>,
        next: String?,
        locale: Locale,
    ): RecommendationListView {
        check(analysis == null || analysis.roomId == room.id.value)
        return RecommendationListView(
            analysis?.protocol,
            run.id.value,
            room.version,
            requireNotNull(run.quality),
            analysis?.totalOptionCount ?: 0,
            options.map { option ->
                RecommendationOptionView(
                    option.id,
                    option.primaryRank,
                    option.option.window,
                    option.option.variants.mapIndexed { index, variant ->
                        RecommendationVariantView(
                            option.variantIds[index],
                            variant.meetingMode,
                            variant.participantIds.size,
                            run.batch.submissionVersionIds.size,
                            variant.participantIds.size < run.batch.submissionVersionIds.size,
                            variant.representativeArea?.let { CandidatePlaceView(it.displayName, null, null) }
                                ?: variant.representativePlace?.let {
                                    CandidatePlaceView(
                                        "공통 가능 지역",
                                        it.latitude.toDouble(),
                                        it.longitude.toDouble(),
                                    )
                                },
                        )
                    },
                    renderWindow(option.option.window, room, locale),
                )
            },
            (analysis?.alternativeCount ?: 0) > 0,
            next,
        )
    }

    private fun selectedResult(
        run: CoordinationRun,
        room: MeetingRoom,
        selection: RecommendationSelection,
        locale: Locale,
    ): ConfirmedResultView {
        val analysis = requireNotNull(recommendations?.find(run.id.value, RecommendationQuery(optionId = selection.optionId)))
        val option = analysis.options.first { it.id == selection.optionId }
        val variant = option.option.variants[option.variantIds.indexOf(selection.variantId)]
        val partial = variant.participantIds.size < run.batch.submissionVersionIds.size
        val candidate =
            CandidateView(
                null,
                if (partial) {
                    PlanType.C
                } else if (variant.meetingMode ==
                    MeetingMode.REMOTE
                ) {
                    PlanType.B
                } else {
                    PlanType.A
                },
                variant.meetingMode,
                option.primaryRank ?: 1,
                variant.participantIds.size,
                run.batch.submissionVersionIds.size,
                listOf(selection.selectedWindow),
                variant.representativeArea?.let { CandidatePlaceView(it.displayName, null, null) }
                    ?: variant.representativePlace?.let { CandidatePlaceView("공통 가능 지역", it.latitude.toDouble(), it.longitude.toDouble()) },
                renderWindow(selection.selectedWindow, room, locale),
            )
        return ConfirmedResultView(candidate, selection.confirmedAt, selection)
    }

    private fun renderWindow(
        window: InstantTimeRange,
        room: MeetingRoom,
        locale: Locale,
    ): String {
        if (window.startInclusive.epochSecond % 60 != 0L ||
            window.endExclusive.epochSecond % 60 != 0L ||
            window.startInclusive.nano != 0 ||
            window.endExclusive.nano != 0
        ) {
            val zone = room.timeZone.value
            return messageSource.getMessage(
                "recommendation.summary.precise",
                arrayOf(
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(window.startInclusive.atZone(zone)),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(window.endExclusive.atZone(zone)),
                ),
                locale,
            )
        }
        return CandidateSummaryRenderer
            .render(
                CandidateSummaryRequest(
                    occurrences = listOf(window),
                    searchStartDate = room.searchRange.startInclusive,
                    searchEndDate = room.searchRange.endExclusive,
                    timeZone = room.timeZone.value,
                    locale = locale,
                ),
            ).text
    }

    private fun candidateList(
        run: CoordinationRun,
        room: MeetingRoom,
        locale: Locale,
    ): CandidateListView {
        val structured = structuredSubmissionRepository.findByBatch(run.batch.id)
        val rejectedIds = structured.filter { it.rejectionCode != null }.mapTo(mutableSetOf()) { it.submissionVersionId }
        normalizedPlaceRepository
            .findByBatch(run.batch.id)
            .filter { it.status != NormalizedPlaceStatus.RESOLVED }
            .mapTo(rejectedIds) { it.submissionVersionId }
        val rejected = rejectedIds.size
        return CandidateListView(
            quality = run.quality ?: CandidateQuality.COMPLETE,
            appliedSubmissions = run.batch.submissionVersionIds.size - rejected,
            totalSubmissions = run.batch.submissionVersionIds.size,
            unappliedInputs = rejected,
            candidates = run.candidates.map { it.toView(room, locale) },
            analysisId = run.id.value,
            stateVersion = room.version,
        )
    }

    private fun MeetingCandidate.toView(
        room: MeetingRoom,
        locale: Locale,
    ): CandidateView =
        CandidateView(
            candidateId = id.value,
            planType = planType,
            meetingMode = meetingMode,
            rank = rank,
            attendanceCount = participantIds.size,
            totalParticipants = totalParticipants,
            timeRanges = timeRanges,
            place =
                place?.let {
                    CandidatePlaceView(
                        it.displayName,
                        it.coordinate?.latitude?.toDouble(),
                        it.coordinate?.longitude?.toDouble(),
                    )
                },
            summary =
                messageSource.getMessage(
                    "candidate.summary.explicit",
                    arrayOf(
                        CandidateSummaryRenderer
                            .render(
                                CandidateSummaryRequest(
                                    occurrences = timeRanges,
                                    searchStartDate = room.searchRange.startInclusive,
                                    searchEndDate = room.searchRange.endExclusive,
                                    timeZone = room.timeZone.value,
                                    locale = locale,
                                    recurringPattern = inferredPattern(room),
                                ),
                            ).text,
                    ),
                    locale,
                ),
        )

    private fun MeetingCandidate.inferredPattern(room: MeetingRoom): RecurringCandidatePattern? {
        val localRanges =
            timeRanges.map {
                val start = it.startInclusive.atZone(room.timeZone.value)
                val end = it.endExclusive.atZone(room.timeZone.value)
                RecurringCandidatePattern(start.dayOfWeek, start.toLocalTime(), end.toLocalTime())
            }
        val dominant = localRanges.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key.dayOfWeek.value }))
        return dominant?.takeIf { it.value >= 2 }?.key
    }

    private fun room(
        rawInviteCode: String,
        lock: Boolean = false,
    ): MeetingRoom {
        val code = runCatching { InviteCode.of(rawInviteCode) }.getOrNull() ?: fail(MatchingResultErrorCode.ROOM_NOT_FOUND)
        return (if (lock) roomRepository.findByInviteCodeForUpdate(code) else roomRepository.findByInviteCode(code))
            ?: fail(MatchingResultErrorCode.ROOM_NOT_FOUND)
    }

    private fun participant(
        roomId: MeetingRoomId,
        rawCredential: String?,
    ): Participant {
        if (rawCredential.isNullOrBlank()) fail(MatchingResultErrorCode.GUEST_SESSION_REQUIRED)
        val session =
            guestSessionRepository
                .findByCredentialDigest(credentialPort.digest(rawCredential))
                ?.takeIf { it.isActive(clock.instant()) }
                ?: fail(MatchingResultErrorCode.GUEST_SESSION_INVALID)
        return participantRepository.findByRoomAndGuestSession(roomId, session.id)
            ?: fail(MatchingResultErrorCode.PARTICIPANT_REQUIRED)
    }

    private fun completedRun(room: MeetingRoom): CoordinationRun {
        if (room.activeRevisionRoundId != null) fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        val run = coordinationRunRepository.findLatestByRoom(room.id) ?: fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        if (run.status != CoordinationStatus.COMPLETED) fail(MatchingResultErrorCode.CANDIDATES_NOT_READY)
        return run
    }

    private fun frozenSubmissions(run: CoordinationRun) = FrozenSubmissionReader.read(submissionRepository, run.batch)

    private fun String.toPlainText(): String = filter { it == '\n' || it == '\t' || !it.isISOControl() }

    private fun fail(code: MatchingResultErrorCode): Nothing = throw MatchingResultException(code)
}
