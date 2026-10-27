package com.meetme.server.coordination.domain.matching

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.Duration
import java.time.Instant
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
                    availableTimes = TimeRangeMatcher.normalize(it.availableTimes),
                    explicitPreferences =
                        it.explicitPreferences.copy(
                            timeRanges = TimeRangeMatcher.normalize(it.explicitPreferences.timeRanges),
                        ),
                )
            }
        val windows = linkedMapOf<InstantTimeRange, MutableList<RecommendationVariant>>()
        for (subset in attendeeGroups(mode, ordered, checkCancellation)) {
            checkCancellation()
            var common = subset.first().availableTimes
            for (participant in subset.drop(1)) {
                common = TimeRangeMatcher.intersect(common, participant.availableTimes)
                if (common.isEmpty()) break
            }
            val eligibleVariants = variants(mode, subset)
            for (available in common) {
                for (window in preferenceWindows(available, subset, checkCancellation)) {
                    checkCancellation()
                    val scored =
                        eligibleVariants.map { variant ->
                            variant.copy(preferenceCount = subset.count { matchesPreference(it.explicitPreferences, window, variant) })
                        }
                    windows.getOrPut(window) { mutableListOf() }.addAll(scored)
                }
            }
        }
        checkCancellation()
        val options =
            windows
                .mapNotNull { (window, variants) ->
                    maximalVariants(variants, checkCancellation)
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

    /** Location compatibility is independent of time; discover it once, not for every time boundary. */
    private fun attendeeGroups(
        mode: MeetingMode,
        ordered: List<ParticipantMatchInput>,
        checkCancellation: () -> Unit,
    ): List<List<ParticipantMatchInput>> {
        val contexts = mutableListOf<List<ParticipantMatchInput>>()
        if (mode != MeetingMode.IN_PERSON) contexts += ordered
        if (mode != MeetingMode.REMOTE) {
            val areas =
                ordered
                    .flatMap { it.offlineArea?.alternatives.orEmpty() }
                    .map { it.key }
                    .distinct()
                    .sorted()
            for (key in areas) {
                checkCancellation()
                contexts +=
                    ordered.filter { participant ->
                        participant.offlineArea
                            ?.alternatives
                            .orEmpty()
                            .any { it.key == key }
                    }
            }
            val located = ordered.filter { it.offlineRegion != null }
            if (located.size >= 2) {
                val regions = located.map { requireNotNull(it.offlineRegion) }
                for (group in GeoMatcher.overlappingParticipantGroups(regions, checkCancellation)) {
                    contexts += group.map { located[it] }
                }
            }
        }
        val groups = linkedMapOf<List<ParticipantId>, List<ParticipantMatchInput>>()
        for (context in contexts.distinct()) {
            checkCancellation()
            for (group in temporalGroups(context, checkCancellation)) {
                groups.putIfAbsent(group.map { it.participantId }, group)
            }
        }
        return groups.values.toList()
    }

    /** Suffix intersections form a nested chain of at most N-1 attendee sets per boundary; never a powerset. */
    private fun temporalGroups(
        participants: List<ParticipantMatchInput>,
        checkCancellation: () -> Unit,
    ): List<List<ParticipantMatchInput>> {
        val events = sortedMapOf<Instant, MutableList<Pair<ParticipantId, Boolean>>>()
        for (participant in participants) {
            for (range in participant.availableTimes) {
                events.getOrPut(range.startInclusive) { mutableListOf() } += participant.participantId to true
                events.getOrPut(range.endExclusive) { mutableListOf() } += participant.participantId to false
            }
        }
        val current = mutableSetOf<ParticipantId>()
        var suffixes = emptyList<Set<ParticipantId>>()
        val groups = linkedMapOf<List<ParticipantId>, List<ParticipantMatchInput>>()
        for ((index, entry) in events.entries.withIndex()) {
            checkCancellation()
            for ((id, starts) in entry.value) {
                if (starts) current += id else current -= id
            }
            if (index < events.size - 1 && current.size >= 2) {
                val next = linkedSetOf<Set<ParticipantId>>(current.toSet())
                for (previous in suffixes) {
                    checkCancellation()
                    val intersection = previous intersect current
                    if (intersection.size >= 2) next += intersection
                }
                suffixes = next.toList()
                for (attendees in suffixes) {
                    val group = participants.filter { it.participantId in attendees }
                    groups.putIfAbsent(group.map { it.participantId }, group)
                }
            } else {
                suffixes = emptyList()
            }
        }
        return groups.values.toList()
    }

    private fun maximalVariants(
        variants: List<RecommendationVariant>,
        checkCancellation: () -> Unit,
    ): List<RecommendationVariant> {
        val unique = variants.distinct()
        return unique.filter { variant ->
            checkCancellation()
            unique.none { other ->
                checkCancellation()
                other.participantIds.size > variant.participantIds.size &&
                    other.meetingMode == variant.meetingMode &&
                    other.representativeArea?.key == variant.representativeArea?.key &&
                    other.representativePlace == variant.representativePlace &&
                    other.participantIds.containsAll(variant.participantIds)
            }
        }
    }
}
