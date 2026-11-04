package com.meetme.server.coordination.application.service

import com.meetme.server.coordination.application.port.output.NormalizedPlace
import com.meetme.server.coordination.application.port.output.NormalizedPlaceStatus
import com.meetme.server.coordination.domain.matching.CompatiblePlaceArea
import com.meetme.server.coordination.domain.matching.ConditionalTimeResolution
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.coordination.domain.matching.ParticipantAllowedArea
import com.meetme.server.coordination.domain.matching.ParticipantMatchInput
import com.meetme.server.coordination.domain.matching.ParticipantPreferences
import com.meetme.server.coordination.domain.matching.RecommendationParticipantEvidence
import com.meetme.server.coordination.domain.matching.TimeRangeMatcher
import com.meetme.server.coordination.domain.matching.WholeRoomConditionalTimePolicy
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import com.meetme.server.submission.domain.TimePolarity
import java.text.Normalizer
import java.util.Locale

/** Pure reconstruction of the frozen versions; no repository calls, state transitions or provider access. */
object FrozenMatchingInputPolicy {
    data class ReconstructedResults(
        val legacyResults: List<StructuredSubmissionResult>,
        val availableResults: List<StructuredSubmissionResult>,
        val missingResults: List<StructuredSubmissionResult>,
        val results: List<StructuredSubmissionResult>,
    )

    data class PreparedInputs(
        val snapshots: List<NormalizedPlace>,
        val hasUnappliedInput: Boolean,
        val resolution: ConditionalTimeResolution,
    )

    fun reconstruct(
        submissions: List<Submission>,
        existingStructured: List<StructuredSubmissionResult>,
    ): ReconstructedResults {
        val legacyResults =
            submissions.filter { it.latest.rawText == null }.map {
                StructuredSubmissionResult(it.latest.id, emptyList(), "LEGACY_MANUAL_ONLY_UNSUPPORTED")
            }
        val legacyIds = legacyResults.mapTo(mutableSetOf()) { it.submissionVersionId }
        val availableResults = existingStructured.filter { it.submissionVersionId !in legacyIds } + legacyResults
        val resultIds = availableResults.mapTo(mutableSetOf()) { it.submissionVersionId }
        val missingResults =
            submissions.filter { it.latest.id !in resultIds }.map {
                StructuredSubmissionResult(it.latest.id, emptyList(), "STRUCTURED_RESULT_MISSING")
            }
        val results = availableResults + missingResults
        return ReconstructedResults(legacyResults, availableResults, missingResults, results)
    }

    fun prepare(
        room: MeetingRoom,
        submissions: List<Submission>,
        results: List<StructuredSubmissionResult>,
        batchId: SubmissionBatchId,
    ): PreparedInputs {
        val structured = results.associateBy { it.submissionVersionId }
        val snapshots = mutableListOf<NormalizedPlace>()
        var hasUnappliedInput = structured.values.any { it.rejectionCode != null } || submissions.any { it.latest.id !in structured }
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

        val primaryInputs =
            submissions.map { submission ->
                val result = structured[submission.latest.id]
                val conditions = result?.conditions.orEmpty()
                if (PlaceConstraintDiagnostics.unappliedReason(conditions) != null) hasUnappliedInput = true
                val naturalWindows = conditions.filterIsInstance<StructuredCondition.TimeWindow>()
                val failedWithoutAvailability =
                    result == null || (result.rejectionCode != null && naturalWindows.none { it.polarity == TimePolarity.AVAILABLE })
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
                ParticipantMatchInput(
                    participantId = submission.participantId,
                    availableTimes =
                        if (submission.latest.rawText == null ||
                            failedWithoutAvailability ||
                            result.rejectionCode in UNSAFE_TIME_REASONS
                        ) {
                            emptyList()
                        } else {
                            TimeRangeMatcher.calculateAvailability(
                                naturalWindows = naturalWindows,
                                datedManualAvailability = emptyList(),
                                weeklyManualAvailability = emptyList(),
                                blocked = emptyList(),
                                searchRange = room.searchRange,
                                zone = room.timeZone,
                            )
                        },
                    offlineArea = if (areas.isNotEmpty()) ParticipantAllowedArea(areas) else null,
                    offlinePlaceAssessment =
                        when {
                            result == null || submission.latest.rawText == null -> OfflinePlaceAssessment.LEGACY_REQUIRED
                            result.rejectionCode != null ||
                                conditions.any {
                                    it is StructuredCondition.TravelConstraint || it is StructuredCondition.UnresolvedPlace
                                } -> OfflinePlaceAssessment.UNVERIFIED
                            else -> OfflinePlaceAssessment.UNRESTRICTED
                        },
                    explicitPreferences =
                        ParticipantPreferences(
                            timeRanges =
                                TimeRangeMatcher.expandNaturalWindows(
                                    conditions.filterIsInstance<StructuredCondition.PreferredTimeWindow>().map {
                                        StructuredCondition.TimeWindow(
                                            TimePolarity.AVAILABLE,
                                            it.date,
                                            it.dayOfWeek,
                                            it.startTime,
                                            it.endTime,
                                            it.endsAtNextDayStart,
                                        )
                                    },
                                    room.searchRange,
                                    room.timeZone,
                                ),
                            areaKeys =
                                conditions.filterIsInstance<StructuredCondition.PreferredPlace>().mapTo(
                                    mutableSetOf(),
                                ) { it.areaKey },
                            hasTimePreference = conditions.any { it is StructuredCondition.PreferredTimeWindow },
                            hasPlacePreference = conditions.any { it is StructuredCondition.PreferredPlace },
                            canScore = result != null && result.rejectionCode == null,
                        ),
                )
            }

        val conditionalFallbacks =
            submissions
                .mapNotNull { submission ->
                    val conditions = structured[submission.latest.id]?.conditions.orEmpty()
                    val fallback = conditions.filterIsInstance<StructuredCondition.ConditionalFallbackTimeWindow>()
                    if (fallback.isEmpty()) return@mapNotNull null
                    submission.participantId to
                        TimeRangeMatcher.calculateAvailability(
                            naturalWindows =
                                fallback.map {
                                    StructuredCondition.TimeWindow(
                                        TimePolarity.AVAILABLE,
                                        it.date,
                                        it.dayOfWeek,
                                        it.startTime,
                                        it.endTime,
                                        it.endsAtNextDayStart,
                                    )
                                } +
                                    conditions.filterIsInstance<StructuredCondition.TimeWindow>().filter {
                                        it.polarity ==
                                            TimePolarity.UNAVAILABLE
                                    },
                            datedManualAvailability = emptyList(),
                            weeklyManualAvailability = emptyList(),
                            blocked = emptyList(),
                            searchRange = room.searchRange,
                            zone = room.timeZone,
                        )
                }.toMap()
        val primaryInputsKnown =
            submissions.all { submission ->
                val result = structured[submission.latest.id]
                submission.latest.rawText != null &&
                    result != null &&
                    result.rejectionCode == null &&
                    (
                        submission.participantId !in conditionalFallbacks ||
                            result.conditions.filterIsInstance<StructuredCondition.TimeWindow>().any {
                                it.polarity == TimePolarity.AVAILABLE
                            }
                    )
            }
        val resolution = WholeRoomConditionalTimePolicy.resolve(primaryInputs, conditionalFallbacks, primaryInputsKnown)
        return PreparedInputs(snapshots, hasUnappliedInput, resolution)
    }

    fun evidence(
        submissions: List<Submission>,
        originalResults: List<StructuredSubmissionResult>,
        prepared: PreparedInputs,
        placeFailures: Set<SubmissionVersionId> = emptySet(),
    ): Map<ParticipantId, RecommendationParticipantEvidence> {
        val byVersion = originalResults.groupBy { it.submissionVersionId }
        val completePlaceKeySet = submissions.all { byVersion[it.latest.id]?.size == 1 }
        val inputs = prepared.resolution.participants.associateBy { it.participantId }
        return submissions.associate { submission ->
            val original = byVersion[submission.latest.id]?.singleOrNull()
            val complete = submission.latest.rawText != null && original != null && original.rejectionCode == null
            submission.participantId to
                RecommendationParticipantEvidence(
                    inputs[submission.participantId]?.let { input ->
                        if (submission.latest.id in placeFailures && input.offlinePlaceAssessment == OfflinePlaceAssessment.UNRESTRICTED) {
                            input.copy(offlinePlaceAssessment = OfflinePlaceAssessment.UNVERIFIED)
                        } else {
                            input
                        }
                    },
                    complete,
                    complete && submission.participantId !in prepared.resolution.unsupportedParticipantIds,
                    // Legacy AREA_n identities depend on the whole batch's sorted queries.
                    // A missing peer could shift those identities even when this person's input survived.
                    completePlaceKeySet ||
                        original?.conditions.orEmpty().none { it is StructuredCondition.SpecificPlace && it.areaKey == null },
                )
        }
    }

    private fun normalizeQuery(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filterNot(Char::isWhitespace)

    private val UNSAFE_TIME_REASONS = setOf("UNSUPPORTED_CONDITIONAL_CONSTRAINT", "AMBIGUOUS_TIME_CONSTRAINT", "AMBIGUOUS_INPUT")
}
