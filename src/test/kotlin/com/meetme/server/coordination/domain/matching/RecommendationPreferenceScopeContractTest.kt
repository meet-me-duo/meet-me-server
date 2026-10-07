package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

// D2 AND applies to explicitly declared dimensions even when expansion in the search period is empty.
class RecommendationPreferenceScopeContractTest {
    @Test
    fun `99-D2-16 an explicit empty time preference cannot turn into a satisfied place-only preference`() {
        assertPreferenceVotes(ParticipantPreferences(timeRanges = listOf(WINDOW), areaKeys = setOf("AREA_1")), inPersonCount = 1)
        val preferences = ParticipantPreferences(areaKeys = setOf("AREA_1"), hasTimePreference = true)
        assertPreferenceVotes(preferences)
    }

    @Test
    fun `99-D2-17 an explicit empty time preference is unsatisfied for every meeting mode`() {
        assertPreferenceVotes(ParticipantPreferences(timeRanges = listOf(WINDOW)), inPersonCount = 1, remoteCount = 1)
        val preferences = ParticipantPreferences(hasTimePreference = true)
        assertPreferenceVotes(preferences)
    }

    @Test
    fun `99-D2-18 an explicit empty place preference cannot turn into a satisfied time-only preference`() {
        assertPreferenceVotes(ParticipantPreferences(timeRanges = listOf(WINDOW), areaKeys = setOf("AREA_1")), inPersonCount = 1)
        val preferences = ParticipantPreferences(timeRanges = listOf(WINDOW), hasPlacePreference = true)
        assertPreferenceVotes(preferences)
    }

    private fun assertPreferenceVotes(
        preferences: ParticipantPreferences,
        inPersonCount: Int = 0,
        remoteCount: Int = 0,
    ) {
        for (mode in listOf(MeetingMode.REMOTE, MeetingMode.IN_PERSON, MeetingMode.EITHER)) {
            val actual =
                RecommendationProjector.generate(
                    mode,
                    listOf(member(1, preferences), member(2, ParticipantPreferences())),
                    ZoneId.of("Asia/Seoul"),
                )
            assertEquals(
                listOf(WINDOW),
                actual.options.map { it.window },
                "An unsatisfied preference still preserves hard availability: $mode",
            )
            val variants = actual.options.single().variants
            assertEquals(if (mode == MeetingMode.EITHER) 2 else 1, variants.size)
            for (variant in variants) {
                val expected = if (variant.meetingMode == MeetingMode.IN_PERSON) inPersonCount else remoteCount
                assertEquals(expected, variant.preferenceCount, "Explicit dimension satisfaction in $mode / ${variant.meetingMode}")
            }
        }
    }

    private fun member(
        value: Long,
        preferences: ParticipantPreferences,
    ) = ParticipantMatchInput(
        participantId = ParticipantId(UUID(0, value)),
        availableTimes = listOf(WINDOW),
        offlineArea = ParticipantAllowedArea(listOf(AREA)),
        explicitPreferences = preferences,
    )

    companion object {
        private val WINDOW = InstantTimeRange(Instant.parse("2026-10-07T09:00:00Z"), Instant.parse("2026-10-07T13:00:00Z"))
        private val AREA = CompatiblePlaceArea("AREA_1", "합성 지역 1")
    }
}
