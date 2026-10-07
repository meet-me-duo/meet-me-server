package com.meetme.server.coordination.domain.matching

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs

data class RecommendationVariant(
    val meetingMode: MeetingMode,
    val participantIds: List<ParticipantId>,
    val representativeArea: CompatiblePlaceArea? = null,
    val representativePlace: GeoCoordinate? = null,
    val preferenceCount: Int = 0,
)

data class RecommendationOption(
    val window: InstantTimeRange,
    val variants: List<RecommendationVariant>,
)

data class RecommendationProjection(
    val options: List<RecommendationOption>,
    val primaryOptionIndexes: List<Int>,
)

object RecommendationProjector {
    fun generate(
        mode: MeetingMode,
        participants: List<ParticipantMatchInput>,
        zone: ZoneId,
        checkCancellation: () -> Unit = {},
    ): RecommendationProjection {
        require(participants.map { it.participantId }.distinct().size == participants.size)
        if (participants.size < 2) return RecommendationProjection(emptyList(), emptyList())
        checkCancellation()
        val ordered =
            participants.sortedBy { it.participantId.value.toString() }.map {
                it.copy(
                    explicitPreferences =
                        it.explicitPreferences.copy(
                            timeRanges = TimeRangeMatcher.normalize(it.explicitPreferences.timeRanges),
                        ),
                )
            }
        val windows = linkedMapOf<InstantTimeRange, MutableList<RecommendationVariant>>()
        for (size in ordered.size downTo maxOf(2, ordered.size - 2)) {
            combinations(ordered, size) { subset ->
                checkCancellation()
                var common = TimeRangeMatcher.normalize(subset.first().availableTimes)
                for (participant in subset.drop(1)) {
                    common = TimeRangeMatcher.intersect(common, participant.availableTimes)
                    if (common.isEmpty()) break
                }
                if (common.isNotEmpty()) {
                    val eligibleVariants = variants(mode, subset)
                    if (eligibleVariants.isNotEmpty()) {
                        for (available in common) {
                            for (window in preferenceWindows(available, subset, checkCancellation)) {
                                checkCancellation()
                                val scored =
                                    eligibleVariants.map { variant ->
                                        variant.copy(
                                            preferenceCount = subset.count { matchesPreference(it.explicitPreferences, window, variant) },
                                        )
                                    }
                                windows.getOrPut(window) { mutableListOf() }.addAll(scored)
                            }
                        }
                    }
                }
            }
        }
        checkCancellation()
        val options =
            windows
                .mapNotNull { (window, variants) ->
                    variants
                        .distinct()
                        .sortedWith(variantComparator())
                        .takeIf { it.isNotEmpty() }
                        ?.let { RecommendationOption(window, it) }
                }.sortedWith(
                    compareByDescending<RecommendationOption> {
                        it.variants
                            .first()
                            .participantIds.size
                    }.thenByDescending { it.variants.first().preferenceCount }
                        .thenBy { it.window.startInclusive }
                        .thenBy { it.window.endExclusive },
                )
        val selected = mutableListOf<Int>()
        while (selected.size < minOf(3, options.size)) {
            checkCancellation()
            val remaining = options.indices.filter { it !in selected }
            val best = options[remaining.first()].variants.first()
            val equal =
                remaining.filter {
                    val variant = options[it].variants.first()
                    variant.participantIds.size == best.participantIds.size && variant.preferenceCount == best.preferenceCount
                }
            val index =
                if (selected.isEmpty()) {
                    equal.first()
                } else {
                    equal
                        .sortedWith(
                            compareByDescending<Int> { index ->
                                val date =
                                    options[index]
                                        .window.startInclusive
                                        .atZone(zone)
                                        .toLocalDate()
                                if (selected.none {
                                        options[it]
                                            .window.startInclusive
                                            .atZone(zone)
                                            .toLocalDate() == date
                                    }
                                ) {
                                    1
                                } else {
                                    0
                                }
                            }.thenByDescending { index ->
                                val clock =
                                    options[index]
                                        .window.startInclusive
                                        .atZone(zone)
                                        .toLocalTime()
                                        .toNanoOfDay()
                                selected.minOf {
                                    abs(
                                        clock -
                                            options[it]
                                                .window.startInclusive
                                                .atZone(zone)
                                                .toLocalTime()
                                                .toNanoOfDay(),
                                    )
                                }
                            }.thenByDescending { index ->
                                selected.minOf {
                                    abs(
                                        Duration.between(options[index].window.startInclusive, options[it].window.startInclusive).seconds,
                                    )
                                }
                            }.thenBy { it },
                        ).first()
                }
            selected += index
        }
        return RecommendationProjection(options, selected)
    }

    private fun preferenceWindows(
        available: InstantTimeRange,
        subset: List<ParticipantMatchInput>,
        checkCancellation: () -> Unit,
    ): Set<InstantTimeRange> {
        val preferred = subset.flatMap { TimeRangeMatcher.intersect(listOf(available), it.explicitPreferences.timeRanges) }.distinct()
        val result = linkedSetOf(available)
        result.addAll(preferred)
        // Every intersection of multiple intervals is defined by a maximum start and minimum end,
        // hence two original intervals suffice to preserve all meaningful shared preference windows.
        for (leftIndex in preferred.indices) {
            checkCancellation()
            for (rightIndex in leftIndex + 1 until preferred.size) {
                if (rightIndex % 128 == 0) checkCancellation()
                val start = maxOf(preferred[leftIndex].startInclusive, preferred[rightIndex].startInclusive)
                val end = minOf(preferred[leftIndex].endExclusive, preferred[rightIndex].endExclusive)
                if (start < end) result += InstantTimeRange(start, end)
            }
        }
        return result
    }

    private fun matchesPreference(
        preferences: ParticipantPreferences,
        window: InstantTimeRange,
        variant: RecommendationVariant,
    ): Boolean {
        if (!preferences.canScore || (!preferences.hasTimePreference && !preferences.hasPlacePreference)) return false
        val timeMatches =
            !preferences.hasTimePreference ||
                preferences.timeRanges.any {
                    it.startInclusive <= window.startInclusive && window.endExclusive <= it.endExclusive
                }
        val placeMatches =
            !preferences.hasPlacePreference ||
                (variant.meetingMode == MeetingMode.IN_PERSON && variant.representativeArea?.key in preferences.areaKeys)
        return timeMatches && placeMatches
    }

    private fun variants(
        mode: MeetingMode,
        subset: List<ParticipantMatchInput>,
    ): List<RecommendationVariant> {
        val ids = subset.map { it.participantId }
        val result = mutableListOf<RecommendationVariant>()
        if (mode != MeetingMode.IN_PERSON) result += RecommendationVariant(MeetingMode.REMOTE, ids)
        if (mode != MeetingMode.REMOTE) {
            val areas = subset.map { it.offlineArea }
            if (areas.all { it != null }) {
                val available = areas.filterNotNull().map { it.alternatives }
                val keys = available.map { values -> values.map { it.key }.toSet() }.reduce { left, right -> left intersect right }
                for (key in keys.sorted()) {
                    val display = available.flatten().filter { it.key == key }.minOf { it.displayName }
                    result += RecommendationVariant(MeetingMode.IN_PERSON, ids, CompatiblePlaceArea(key, display))
                }
            } else {
                val regions = subset.mapNotNull { it.offlineRegion }
                if (regions.size == subset.size) {
                    GeoMatcher.representativePoint(regions)?.let {
                        result += RecommendationVariant(MeetingMode.IN_PERSON, ids, representativePlace = it)
                    }
                }
            }
        }
        return result
    }

    private fun variantComparator() =
        compareByDescending<RecommendationVariant> { it.participantIds.size }
            .thenByDescending { it.preferenceCount }
            .thenBy { it.participantIds.joinToString("|") { id -> id.value.toString() } }
            .thenBy { it.meetingMode.name }
            .thenBy { it.representativeArea?.key.orEmpty() }

    private fun combinations(
        values: List<ParticipantMatchInput>,
        size: Int,
        consume: (List<ParticipantMatchInput>) -> Unit,
    ) {
        fun collect(
            start: Int,
            current: MutableList<ParticipantMatchInput>,
        ) {
            if (current.size == size) {
                consume(current.toList())
                return
            }
            for (index in start..values.size - (size - current.size)) {
                current += values[index]
                collect(index + 1, current)
                current.removeAt(current.lastIndex)
            }
        }
        collect(0, mutableListOf())
    }
}
