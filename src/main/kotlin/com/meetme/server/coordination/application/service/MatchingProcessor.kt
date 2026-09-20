package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlace
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.NormalizedPlaceStatus
import com.meetme.server.coordination.domain.CandidatePlace
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.MeetingCandidate
import com.meetme.server.coordination.domain.matching.CompatiblePlaceArea
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.ParticipantAllowedArea
import com.meetme.server.coordination.domain.matching.ParticipantMatchInput
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.ManualAvailability
import com.meetme.server.submission.domain.StructuredCondition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.time.Duration
import java.util.Locale

@Service
class MatchingProcessor(
    private val coordinationRunRepository: CoordinationRunRepository,
    private val submissionRepository: SubmissionRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val normalizedPlaceRepository: NormalizedPlaceRepository,
    private val idGenerator: IdGenerator,
    private val persistence: MatchingProcessingPersistenceService,
    private val metrics: ApplicationMetricsPort? = null,
) {
    fun process(batchId: SubmissionBatchId) {
        val startedNanos = System.nanoTime()
        val run = coordinationRunRepository.findByBatchId(batchId) ?: return
        if (run.status != CoordinationStatus.MATCHING) return
        val room = persistence.room(run.roomId)
        val frozenIds = run.batch.submissionVersionIds.toSet()
        val submissions = submissionRepository.findLatestByRoom(run.roomId).filter { it.latest.id in frozenIds }
        check(submissions.size == frozenIds.size) { "Frozen submission batch is incomplete" }
        val structured = structuredSubmissionRepository.findByBatch(batchId).associateBy { it.submissionVersionId }
        val snapshots = mutableListOf<NormalizedPlace>()
        var hasUnappliedInput = structured.values.any { it.rejectionCode != null }
        val legacyAreaKeys =
            structured.values
                .flatMap { it.conditions }
                .filterIsInstance<StructuredCondition.SpecificPlace>()
                .filter { it.areaKey == null }
                .map { normalizeQuery(it.query) }
                .distinct()
                .sorted()
                .mapIndexed { index, query -> query to "AREA_${index + 1}" }
                .toMap()

        val inputs =
            submissions.map { submission ->
                val conditions = structured[submission.latest.id]?.conditions.orEmpty()
                val areas =
                    conditions
                        .filterIsInstance<StructuredCondition.SpecificPlace>()
                        .map { condition ->
                            CompatiblePlaceArea(
                                key = condition.areaKey ?: legacyAreaKeys.getValue(normalizeQuery(condition.query)),
                                displayName = condition.areaName ?: condition.query,
                            )
                        }.distinctBy { it.key }
                val unresolved =
                    conditions.withIndex().filter { it.value is StructuredCondition.UnresolvedPlace }.map { indexed ->
                        val condition = indexed.value as StructuredCondition.UnresolvedPlace
                        NormalizedPlace(
                            batchId = batchId,
                            submissionVersionId = submission.latest.id,
                            conditionIndex = indexed.index,
                            query = condition.query,
                            radiusMeters = 1_000,
                            status = NormalizedPlaceStatus.NO_EXACT_MATCH,
                        )
                    }
                if (unresolved.isNotEmpty()) {
                    hasUnappliedInput = true
                    snapshots += unresolved
                }
                val preventsOffline =
                    conditions.any {
                        it is StructuredCondition.TravelConstraint || it is StructuredCondition.UnresolvedPlace
                    }
                val dated =
                    submission.latest.manualAvailability
                        .filterIsInstance<ManualAvailability.Dated>()
                        .map { it.range }
                val weekly =
                    submission.latest.manualAvailability
                        .filterIsInstance<ManualAvailability.Weekly>()
                        .map { it.range }
                ParticipantMatchInput(
                    participantId = submission.participantId,
                    availableTimes =
                        com.meetme.server.coordination.domain.matching.TimeRangeMatcher.calculateAvailability(
                            naturalWindows = conditions.filterIsInstance<StructuredCondition.TimeWindow>(),
                            datedManualAvailability = dated,
                            weeklyManualAvailability = weekly,
                            blocked = emptyList(),
                            searchRange = room.searchRange,
                            zone = room.timeZone,
                        ),
                    offlineArea = if (!preventsOffline && areas.isNotEmpty()) ParticipantAllowedArea(areas) else null,
                )
            }

        val generated = DeterministicCandidateMatcher.generate(room.mode, inputs)
        val total = inputs.size
        val candidates =
            generated.candidates.mapIndexed { index, candidate ->
                MeetingCandidate(
                    id = CandidateId(idGenerator.next()),
                    rank = index + 1,
                    timeRanges = candidate.timeRanges,
                    planType = candidate.planType,
                    meetingMode = candidate.meetingMode,
                    participantIds = candidate.participantIds,
                    totalParticipants = total,
                    place =
                        candidate.representativeArea?.let {
                            CandidatePlace(displayName = it.displayName)
                        } ?: candidate.representativePlace?.let {
                            CandidatePlace(
                                displayName = "공통 가능 지역",
                                coordinate = it,
                            )
                        },
                )
            }
        val completed = run.complete(if (hasUnappliedInput) CandidateQuality.PARTIAL else CandidateQuality.COMPLETE, candidates)
        persistence.complete(completed, snapshots)
        metrics?.matching("COMPLETED", Duration.ofNanos(System.nanoTime() - startedNanos), candidates.size)
    }

    private fun normalizeQuery(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
}

@Service
class MatchingProcessingPersistenceService(
    private val roomRepository: com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository,
    private val runRepository: CoordinationRunRepository,
    private val normalizedPlaceRepository: NormalizedPlaceRepository,
) {
    fun room(id: com.meetme.server.shared.domain.MeetingRoomId) =
        requireNotNull(roomRepository.findById(id)) { "Room for coordination run does not exist" }

    @Transactional
    fun complete(
        run: CoordinationRun,
        places: List<NormalizedPlace>,
    ) {
        normalizedPlaceRepository.replaceForBatch(run.batch.id, places)
        runRepository.update(run)
    }

    @Transactional
    fun delay(run: CoordinationRun) {
        runRepository.update(run.delayAnalysis())
    }
}
