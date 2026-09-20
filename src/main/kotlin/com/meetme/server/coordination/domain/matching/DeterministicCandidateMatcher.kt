package com.meetme.server.coordination.domain.matching

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import java.time.Duration

enum class PlanType {
    A,
    B,
    C,
}

enum class MatchOutcome {
    READY,
    NO_MATCH,
}

data class ParticipantMatchInput(
    val participantId: ParticipantId,
    val availableTimes: List<InstantTimeRange>,
    val offlineRegion: ParticipantAllowedRegion? = null,
    val offlineArea: ParticipantAllowedArea? = null,
)

data class CompatiblePlaceArea(
    val key: String,
    val displayName: String,
) {
    init {
        require(key.matches(Regex("AREA_[1-9][0-9]*")))
        require(displayName.isNotBlank())
    }
}

data class ParticipantAllowedArea(
    val alternatives: List<CompatiblePlaceArea>,
) {
    init {
        require(alternatives.isNotEmpty())
        require(alternatives.map { it.key }.distinct().size == alternatives.size)
    }
}

data class DeterministicCandidate(
    val planType: PlanType,
    val meetingMode: MeetingMode,
    val participantIds: List<ParticipantId>,
    val timeRanges: List<InstantTimeRange>,
    val representativePlace: GeoCoordinate?,
    val representativeArea: CompatiblePlaceArea? = null,
)

data class CandidateGenerationResult(
    val outcome: MatchOutcome,
    val candidates: List<DeterministicCandidate>,
)

object DeterministicCandidateMatcher {
    fun generate(
        preferredMode: MeetingMode,
        participants: List<ParticipantMatchInput>,
    ): CandidateGenerationResult {
        if (participants.size < MINIMUM_PARTICIPANTS) return noMatch()
        val orderedParticipants = participants.sortedBy { it.participantId.value.toString() }
        val allTimes = commonTimes(orderedParticipants)
        if (allTimes.isNotEmpty()) {
            val fullCandidates = fullCandidates(preferredMode, orderedParticipants, allTimes)
            if (fullCandidates.isNotEmpty()) return CandidateGenerationResult(MatchOutcome.READY, fullCandidates)
        }

        val minimumSize = maxOf(MINIMUM_PARTICIPANTS, orderedParticipants.size - 2)
        for (size in orderedParticipants.size - 1 downTo minimumSize) {
            val choices =
                combinations(orderedParticipants, size).mapNotNull { subset ->
                    val times = commonTimes(subset)
                    if (times.isEmpty()) null else SubsetTimes(subset, times)
                }
            if (choices.isNotEmpty()) {
                val selected = selectPartialCandidate(preferredMode, choices)
                if (selected != null) {
                    return CandidateGenerationResult(MatchOutcome.READY, listOf(selected.copy(planType = PlanType.C)))
                }
            }
        }
        return noMatch()
    }

    private fun fullCandidates(
        preferredMode: MeetingMode,
        participants: List<ParticipantMatchInput>,
        times: List<InstantTimeRange>,
    ): List<DeterministicCandidate> =
        when (preferredMode) {
            MeetingMode.IN_PERSON -> offlineCandidate(PlanType.A, participants, times)?.let(::listOf).orEmpty()
            MeetingMode.REMOTE -> listOf(remoteCandidate(PlanType.B, participants, times))
            MeetingMode.EITHER ->
                listOfNotNull(
                    offlineCandidate(PlanType.A, participants, times),
                    remoteCandidate(PlanType.B, participants, times),
                )
        }

    private fun selectPartialCandidate(
        preferredMode: MeetingMode,
        choices: List<SubsetTimes>,
    ): DeterministicCandidate? {
        val ranked = choices.sortedWith(subsetComparator())
        return when (preferredMode) {
            MeetingMode.REMOTE -> ranked.first().let { remoteCandidate(PlanType.C, it.participants, it.times) }
            MeetingMode.EITHER ->
                ranked.first().let {
                    offlineCandidate(PlanType.C, it.participants, it.times)
                        ?: remoteCandidate(PlanType.C, it.participants, it.times)
                }
            MeetingMode.IN_PERSON ->
                ranked.firstNotNullOfOrNull {
                    offlineCandidate(PlanType.C, it.participants, it.times)
                }
        }
    }

    private fun offlineCandidate(
        planType: PlanType,
        participants: List<ParticipantMatchInput>,
        times: List<InstantTimeRange>,
    ): DeterministicCandidate? {
        val areas = participants.map { it.offlineArea }
        if (areas.all { it != null }) {
            val commonKeys =
                areas
                    .filterNotNull()
                    .map { area -> area.alternatives.mapTo(mutableSetOf()) { it.key } }
                    .reduce { common, keys -> common.apply { retainAll(keys) } }
            val selectedKey = commonKeys.sorted().firstOrNull() ?: return null
            val displayName =
                areas
                    .filterNotNull()
                    .flatMap { it.alternatives }
                    .filter { it.key == selectedKey }
                    .map { it.displayName }
                    .sorted()
                    .first()
            return candidate(
                planType,
                MeetingMode.IN_PERSON,
                participants,
                times,
                place = null,
                area = CompatiblePlaceArea(selectedKey, displayName),
            )
        }

        val regions = participants.map { it.offlineRegion ?: return null }
        val place = GeoMatcher.representativePoint(regions) ?: return null
        return candidate(planType, MeetingMode.IN_PERSON, participants, times, place)
    }

    private fun remoteCandidate(
        planType: PlanType,
        participants: List<ParticipantMatchInput>,
        times: List<InstantTimeRange>,
    ): DeterministicCandidate = candidate(planType, MeetingMode.REMOTE, participants, times, null)

    private fun candidate(
        planType: PlanType,
        meetingMode: MeetingMode,
        participants: List<ParticipantMatchInput>,
        times: List<InstantTimeRange>,
        place: GeoCoordinate?,
        area: CompatiblePlaceArea? = null,
    ) = DeterministicCandidate(
        planType = planType,
        meetingMode = meetingMode,
        participantIds = participants.map { it.participantId }.sortedBy { it.value.toString() },
        timeRanges = times,
        representativePlace = place,
        representativeArea = area,
    )

    private fun commonTimes(participants: List<ParticipantMatchInput>): List<InstantTimeRange> {
        var common = TimeRangeMatcher.normalize(participants.first().availableTimes)
        for (participant in participants.drop(1)) {
            common = TimeRangeMatcher.intersect(common, participant.availableTimes)
            if (common.isEmpty()) return emptyList()
        }
        return common
    }

    private fun candidateComparator(): Comparator<DeterministicCandidate> =
        compareByDescending<DeterministicCandidate> { candidate ->
            candidate.timeRanges.sumOf { Duration.between(it.startInclusive, it.endExclusive).toMillis() }
        }.thenBy { it.timeRanges.first().startInclusive }
            .thenBy { it.participantIds.joinToString("|") { id -> id.value.toString() } }
            .thenBy { if (it.meetingMode == MeetingMode.IN_PERSON) 0 else 1 }

    private fun subsetComparator(): Comparator<SubsetTimes> =
        compareByDescending<SubsetTimes> { candidate ->
            candidate.times.sumOf { Duration.between(it.startInclusive, it.endExclusive).toMillis() }
        }.thenBy { it.times.first().startInclusive }
            .thenBy { it.participants.joinToString("|") { participant -> participant.participantId.value.toString() } }

    private fun <T> combinations(
        values: List<T>,
        size: Int,
    ): List<List<T>> {
        val result = mutableListOf<List<T>>()

        fun collect(
            start: Int,
            current: MutableList<T>,
        ) {
            if (current.size == size) {
                result += current.toList()
                return
            }
            for (index in start..values.size - (size - current.size)) {
                current += values[index]
                collect(index + 1, current)
                current.removeAt(current.lastIndex)
            }
        }
        collect(0, mutableListOf())
        return result
    }

    private fun noMatch() = CandidateGenerationResult(MatchOutcome.NO_MATCH, emptyList())

    private data class SubsetTimes(
        val participants: List<ParticipantMatchInput>,
        val times: List<InstantTimeRange>,
    )

    private const val MINIMUM_PARTICIPANTS = 2
}
