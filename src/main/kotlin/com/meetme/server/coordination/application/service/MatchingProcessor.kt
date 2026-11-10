package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.AnalysisInvocationRepository
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NormalizedPlace
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.RecommendationRepository
import com.meetme.server.coordination.domain.CandidateQuality
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.CoordinationStatus
import com.meetme.server.coordination.domain.StoredRecommendationOption
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher
import com.meetme.server.coordination.domain.matching.RecommendationProjection
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.shared.application.port.output.ApplicationMetricsPort
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CandidateId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class MatchingProcessor(
    private val coordinationRunRepository: CoordinationRunRepository,
    private val submissionRepository: SubmissionRepository,
    private val structuredSubmissionRepository: StructuredSubmissionRepository,
    private val normalizedPlaceRepository: NormalizedPlaceRepository,
    private val idGenerator: IdGenerator,
    private val persistence: MatchingProcessingPersistenceService,
    private val metrics: ApplicationMetricsPort? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val recommendationRepository: RecommendationRepository? = null,
    private val finalAnalysisRoutes: FinalAnalysisRouteRegistry? = null,
) {
    fun processBounded(
        batchId: SubmissionBatchId,
        canPublish: () -> Boolean,
    ) = processInternal(batchId, canPublish)

    @Transactional
    fun process(batchId: SubmissionBatchId) = processInternal(batchId, null)

    private fun processInternal(
        batchId: SubmissionBatchId,
        canPublish: (() -> Boolean)?,
    ) {
        val startedNanos = System.nanoTime()
        val initial = coordinationRunRepository.findByBatchId(batchId) ?: return
        val selected = finalAnalysisRoutes?.find(initial.id.value)?.choice
        if (selected is FinalAnalysisRouteChoice.DslContextV2 ||
            selected is FinalAnalysisRouteChoice.DslApprovedTermsV3 ||
            selected is FinalAnalysisRouteChoice.DslContextModeV4 ||
            selected is FinalAnalysisRouteChoice.DslStructuralV5
        ) {
            throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
        }
        val run = persistence.start(initial) ?: return
        if (run.status != CoordinationStatus.MATCHING) return
        val room = persistence.room(run.roomId)
        val submissions = FrozenSubmissionReader.read(submissionRepository, run.batch)
        val existingStructured = structuredSubmissionRepository.findByBatch(batchId)
        val reconstructed = FrozenMatchingInputPolicy.reconstruct(submissions, existingStructured)
        if (reconstructed.legacyResults.isNotEmpty()) {
            structuredSubmissionRepository.replaceForBatch(batchId, reconstructed.availableResults, clock.instant())
        }
        val results = reconstructed.results
        val missingResults = reconstructed.missingResults
        val prepared = FrozenMatchingInputPolicy.prepare(room, submissions, results, batchId)
        val snapshots = prepared.snapshots
        var hasUnappliedInput = prepared.hasUnappliedInput
        val resolution = prepared.resolution
        val inputs = resolution.participants
        val persistInputDiagnostics: (() -> Unit)? =
            if (resolution.unsupportedParticipantIds.isEmpty() && missingResults.isEmpty()) {
                null
            } else {
                hasUnappliedInput = true
                val unsupportedVersions =
                    submissions.filter { it.participantId in resolution.unsupportedParticipantIds }.map { it.latest.id }.toSet()
                val rejected =
                    results.map {
                        if (it.submissionVersionId in
                            unsupportedVersions
                        ) {
                            it.copy(rejectionCode = "UNSUPPORTED_CONDITIONAL_CONSTRAINT")
                        } else {
                            it
                        }
                    }
                // Persist the owner-visible reason under the same active-run lock and transaction as the publication.
                val persist: () -> Unit = { structuredSubmissionRepository.replaceForBatch(batchId, rejected, clock.instant()) }
                persist
            }

        val generated = DeterministicCandidateMatcher.generate(room.mode, inputs)
        val total = inputs.size
        val candidates =
            generated.candidates.mapIndexed { index, candidate ->
                MatchingCandidateStorageMapper.map(candidate, CandidateId(idGenerator.next()), index + 1, total)
            }
        val completed = run.complete(if (hasUnappliedInput) CandidateQuality.PARTIAL else CandidateQuality.COMPLETE, candidates)
        if (canPublish == null) {
            if (recommendationRepository == null) {
                persistence.complete(completed, snapshots, persistInputDiagnostics)
            } else {
                persistence.completeRecommendations(
                    completed,
                    snapshots,
                    RecommendationProjector.generate(room.mode, inputs, room.timeZone.value),
                    persistInputDiagnostics,
                )
            }
        } else {
            val published =
                try {
                    val projection =
                        if (recommendationRepository == null) {
                            null
                        } else {
                            RecommendationProjector.generate(
                                room.mode,
                                inputs,
                                room.timeZone.value,
                            ) {
                                if (!canPublish()) throw AnalysisDeadlineExceededException()
                            }
                        }
                    persistence.completeBounded(completed, snapshots, projection, persistInputDiagnostics, canPublish)
                } catch (_: AnalysisDeadlineExceededException) {
                    false
                }
            if (!published) {
                // Publication has finished or rolled back before this fresh delay transaction.
                persistence.delay(run)
                metrics?.matching("ANALYSIS_DELAYED", Duration.ofNanos(System.nanoTime() - startedNanos), 0)
                return
            }
        }
        metrics?.matching("COMPLETED", Duration.ofNanos(System.nanoTime() - startedNanos), candidates.size)
    }
}

@Service
class MatchingProcessingPersistenceService(
    private val roomRepository: com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository,
    private val runRepository: CoordinationRunRepository,
    private val normalizedPlaceRepository: NormalizedPlaceRepository,
    private val recommendations: RecommendationRepository? = null,
    private val invocationRepository: AnalysisInvocationRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun completeBounded(
        run: CoordinationRun,
        places: List<NormalizedPlace>,
        projection: RecommendationProjection? = null,
        persistInputDiagnostics: (() -> Unit)? = null,
        canPublish: () -> Boolean,
    ): Boolean {
        val (room, current) = ActiveRunLock.acquire(roomRepository, runRepository, run) ?: return false
        if (current.status != CoordinationStatus.MATCHING || current.version + 1 != run.version) return false
        val invocation = invocationRepository?.findLatestByRun(current.id)?.takeIf { current.version == it.runVersion + 1 }

        fun withinDeadline(): Boolean = canPublish() && (invocation == null || clock.instant() < invocation.deadlineAt)
        if (!withinDeadline()) return false
        persistInputDiagnostics?.invoke()
        normalizedPlaceRepository.replaceForBatch(run.batch.id, places)
        runRepository.update(run)
        if (projection != null) publishRecommendations(run, room.id.value, projection)
        roomRepository.update(room.transition())
        if (!withinDeadline()) throw AnalysisDeadlineExceededException()
        return true
    }

    fun room(id: com.meetme.server.shared.domain.MeetingRoomId) =
        requireNotNull(roomRepository.findById(id)) { "Room for coordination run does not exist" }

    @Transactional
    fun start(run: CoordinationRun): CoordinationRun? {
        val (_, current) = ActiveRunLock.acquire(roomRepository, runRepository, run) ?: return null
        return current.takeIf { it.version == run.version && it.status == CoordinationStatus.MATCHING }
    }

    @Transactional
    fun complete(
        run: CoordinationRun,
        places: List<NormalizedPlace>,
        persistInputDiagnostics: (() -> Unit)? = null,
    ) {
        val (room, current) = ActiveRunLock.acquire(roomRepository, runRepository, run) ?: return
        if (current.status != CoordinationStatus.MATCHING || current.version + 1 != run.version) return
        persistInputDiagnostics?.invoke()
        normalizedPlaceRepository.replaceForBatch(run.batch.id, places)
        runRepository.update(run)
        roomRepository.update(room.transition())
    }

    @Transactional
    fun completeRecommendations(
        run: CoordinationRun,
        places: List<NormalizedPlace>,
        projection: RecommendationProjection,
        persistInputDiagnostics: (() -> Unit)? = null,
    ) {
        val (room, current) = ActiveRunLock.acquire(roomRepository, runRepository, run) ?: return
        if (current.status != CoordinationStatus.MATCHING || current.version + 1 != run.version) return
        persistInputDiagnostics?.invoke()
        normalizedPlaceRepository.replaceForBatch(run.batch.id, places)
        runRepository.update(run)
        publishRecommendations(run, room.id.value, projection)
        roomRepository.update(room.transition())
    }

    private fun publishRecommendations(
        run: CoordinationRun,
        roomId: UUID,
        projection: RecommendationProjection,
    ) {
        val options =
            projection.options.mapIndexed { index, option ->
                val id = stableId("${run.id.value}|${option.window.startInclusive}|${option.window.endExclusive}")
                StoredRecommendationOption(
                    id,
                    index,
                    projection.primaryOptionIndexes
                        .indexOf(index)
                        .takeIf { it >= 0 }
                        ?.plus(1),
                    option,
                    option.variants.map { variant ->
                        stableId(
                            "$id|${variant.meetingMode}|${variant.participantIds.joinToString(",") { it.value.toString() }}|" +
                                "${variant.representativeArea?.key}|${variant.representativePlace}",
                        )
                    },
                )
            }
        requireNotNull(recommendations).publish(
            com.meetme.server.coordination.domain
                .RecommendationAnalysis(run.id.value, roomId, options),
        )
    }

    private fun stableId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    @Transactional
    fun delay(run: CoordinationRun) {
        val (room, current) = ActiveRunLock.acquire(roomRepository, runRepository, run) ?: return
        if (current.status != CoordinationStatus.MATCHING || current.version != run.version) return
        runRepository.update(current.delayAnalysis())
        roomRepository.update(room.transition())
    }
}
