package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Independent expectations: ISSUE_95_CONTRACT D2, D2_IMPLEMENTATION_HANDOFF, PRD FR-011 and Rule 3.
class RecommendationProjectorD2ContractTest {
    @Test
    fun `99-D2-01 attendance outranks preferences and date diversity`() {
        val partial = local("2026-10-07T09:00", "2026-10-07T11:00")
        val full = local("2026-10-08T18:00", "2026-10-08T20:00")
        val actual =
            project(
                member(1, listOf(partial, full), preferredTimes = listOf(partial)),
                member(2, listOf(partial, full), preferredTimes = listOf(partial)),
                member(3, listOf(full)),
            )
        assertEquals(full, primaryWindows(actual).first())
        assertEquals(0, score(actual, full, setOf(id(1), id(2), id(3))))
        assertEquals(2, score(actual, partial, setOf(id(1), id(2))))
        assertTrue(partial in actual.options.map { it.window })
    }

    @Test
    fun `99-D2-02 repeated time and place alternatives still give one point per participant`() {
        val window = local("2026-10-07T18:00", "2026-10-07T22:00")
        val actual =
            project(
                member(1, listOf(window), listOf(AREA_ONE, AREA_TWO), listOf(window, window, window), setOf("AREA_1", "AREA_2")),
                member(2, listOf(window), listOf(AREA_ONE, AREA_TWO), listOf(window, window), setOf("AREA_1", "AREA_2")),
                mode = MeetingMode.IN_PERSON,
            )
        assertEquals(
            setOf(AREA_ONE, AREA_TWO),
            actual.options
                .single()
                .variants
                .map { it.representativeArea }
                .toSet(),
        )
        assertTrue(
            actual.options
                .single()
                .variants
                .all { it.preferenceCount == 2 },
        )
    }

    @Test
    fun `99-D2-03 same dimension alternatives are OR and time with place is AND`() {
        val first = local("2026-10-07T18:00", "2026-10-07T20:00")
        val second = local("2026-10-08T18:00", "2026-10-08T20:00")
        val otherTime = local("2026-10-09T18:00", "2026-10-09T20:00")
        val allTimes = listOf(first, second, otherTime)
        val areas = listOf(AREA_ONE, AREA_TWO, AREA_THREE)
        val actual =
            project(
                member(1, allTimes, areas, listOf(first, second), setOf("AREA_1", "AREA_2")),
                member(2, allTimes, areas),
                mode = MeetingMode.EITHER,
            )
        for (window in allTimes) {
            val variants = actual.options.single { it.window == window }.variants
            assertEquals(4, variants.size)
            for (variant in variants) {
                val expected = if (window != otherTime && variant.representativeArea in setOf(AREA_ONE, AREA_TWO)) 1 else 0
                assertEquals(expected, variant.preferenceCount, "$window ${variant.meetingMode} ${variant.representativeArea}")
            }
        }
    }

    @Test
    fun `99-D2-04 availability never implies a preference vote`() {
        val window = local("2026-10-07T18:00", "2026-10-07T22:00")
        val actual = project(member(1, listOf(window)), member(2, listOf(window)))
        assertEquals(listOf(window), actual.options.map { it.window })
        assertEquals(
            0,
            actual.options
                .single()
                .variants
                .single()
                .preferenceCount,
        )
    }

    @Test
    fun `99-D2-05 adjacent and overlapping preference union covers a whole window`() {
        val whole = local("2026-10-07T18:00", "2026-10-07T22:00")
        val first = local("2026-10-07T18:00", "2026-10-07T20:00")
        val adjacent = local("2026-10-07T20:00", "2026-10-07T22:00")
        val overlap = local("2026-10-07T19:00", "2026-10-07T22:00")
        for (preferences in listOf(listOf(first, adjacent), listOf(first, overlap))) {
            val actual = project(member(1, listOf(whole), preferredTimes = preferences), member(2, listOf(whole)))
            assertEquals(1, score(actual, whole), preferences.toString())
        }
    }

    @Test
    fun `99-D2-06 a preference union with a gap cannot satisfy the whole window`() {
        val whole = local("2026-10-07T18:00", "2026-10-07T22:00")
        val first = local("2026-10-07T18:00", "2026-10-07T19:00")
        val second = local("2026-10-07T20:00", "2026-10-07T22:00")
        val actual = project(member(1, listOf(whole), preferredTimes = listOf(first, second)), member(2, listOf(whole)))
        assertEquals(0, score(actual, whole))
        assertEquals(1, score(actual, first))
        assertEquals(1, score(actual, second))
    }

    @Test
    fun `99-D2-07 preserve original each preference and their shared nonuniform window`() {
        val whole = local("2026-10-07T18:00", "2026-10-07T22:37")
        val first = local("2026-10-07T19:07", "2026-10-07T21:13")
        val second = local("2026-10-07T20:19", "2026-10-07T22:11")
        val shared = local("2026-10-07T20:19", "2026-10-07T21:13")
        val actual =
            project(member(1, listOf(whole), preferredTimes = listOf(first)), member(2, listOf(whole), preferredTimes = listOf(second)))
        assertEquals(0, score(actual, whole))
        assertEquals(1, score(actual, first))
        assertEquals(1, score(actual, second))
        assertEquals(2, score(actual, shared))
        assertEquals(shared, primaryWindows(actual).first())
        val windows = actual.options.map { it.window }
        assertEquals(windows.size, windows.distinct().size)
        val boundaries =
            setOf(
                whole.startInclusive,
                whole.endExclusive,
                first.startInclusive,
                first.endExclusive,
                second.startInclusive,
                second.endExclusive,
            )
        assertTrue(windows.all { it.startInclusive in boundaries && it.endExclusive in boundaries })
        assertTrue(windows.all { whole.startInclusive <= it.startInclusive && it.endExclusive <= whole.endExclusive })
    }

    @Test
    fun `99-D2-08 preference is clipped to actual availability and never extends it`() {
        val available = local("2026-10-07T19:00", "2026-10-07T21:00")
        val preference = local("2026-10-07T20:00", "2026-10-07T23:00")
        val preferredAvailable = local("2026-10-07T20:00", "2026-10-07T21:00")
        val actual = project(member(1, listOf(available), preferredTimes = listOf(preference)), member(2, listOf(available)))
        assertEquals(0, score(actual, available))
        assertEquals(1, score(actual, preferredAvailable))
        assertTrue(
            actual.options.all {
                available.startInclusive <= it.window.startInclusive &&
                    it.window.endExclusive <= available.endExclusive
            },
        )
    }

    @Test
    fun `99-D2-09 blocked gaps remain blocked even when explicitly preferred`() {
        val before = local("2026-10-07T18:00", "2026-10-07T19:00")
        val after = local("2026-10-07T20:00", "2026-10-07T22:00")
        val blocked = local("2026-10-07T19:00", "2026-10-07T20:00")
        val whole = local("2026-10-07T18:00", "2026-10-07T22:00")
        val actual = project(member(1, listOf(before, after), preferredTimes = listOf(blocked)), member(2, listOf(whole)))
        assertEquals(setOf(before, after), actual.options.map { it.window }.toSet())
        assertTrue(actual.options.flatMap { it.variants }.all { it.preferenceCount == 0 })
        assertFalse(
            actual.options.any { it.window.startInclusive < blocked.endExclusive && blocked.startInclusive < it.window.endExclusive },
        )
    }

    @Test
    fun `99-D2-10 preferences of nonattendees do not score or create their windows`() {
        val whole = local("2026-10-07T18:00", "2026-10-07T22:00")
        val absentPreference = local("2026-10-07T20:00", "2026-10-07T21:00")
        val actual =
            project(
                member(1, listOf(whole)),
                member(2, listOf(whole)),
                member(3, emptyList(), preferredTimes = listOf(whole, absentPreference)),
            )
        assertEquals(listOf(whole), actual.options.map { it.window })
        assertEquals(
            listOf(id(1), id(2)),
            actual.options
                .single()
                .variants
                .single()
                .participantIds,
        )
        assertEquals(
            0,
            actual.options
                .single()
                .variants
                .single()
                .preferenceCount,
        )
    }

    @Test
    fun `99-D2-11 preferred areas never create compatibility or remove other valid areas`() {
        val whole = local("2026-10-07T18:00", "2026-10-07T22:00")
        val actual =
            project(
                member(1, listOf(whole), listOf(AREA_ONE, AREA_TWO), preferredAreas = setOf("AREA_3")),
                member(2, listOf(whole), listOf(AREA_ONE, AREA_TWO)),
                mode = MeetingMode.EITHER,
            )
        assertEquals(
            setOf(AREA_ONE, AREA_TWO),
            actual.options
                .single()
                .variants
                .mapNotNull { it.representativeArea }
                .toSet(),
        )
        assertTrue(
            actual.options
                .single()
                .variants
                .all { it.preferenceCount == 0 },
        )
        assertEquals(
            1,
            actual.options
                .single()
                .variants
                .count { it.meetingMode == MeetingMode.REMOTE },
        )
    }

    @Test
    fun `99-D2-12 preferences outrank diversity before equal quality date diversity`() {
        val anchor = local("2026-10-07T09:00", "2026-10-07T10:00")
        val sameDate = local("2026-10-07T22:00", "2026-10-07T23:00")
        val nextDate = local("2026-10-08T09:00", "2026-10-08T10:00")
        val thirdDate = local("2026-10-09T09:00", "2026-10-09T10:00")
        val unpreferredDistant = local("2026-10-12T03:00", "2026-10-12T04:00")
        val preferred = listOf(anchor, sameDate, nextDate, thirdDate)
        val all = preferred + unpreferredDistant
        val actual = project(member(1, all, preferredTimes = preferred), member(2, all))
        assertEquals(listOf(anchor, thirdDate, nextDate), primaryWindows(actual))
        assertEquals(0, score(actual, unpreferredDistant))
        assertTrue(unpreferredDistant in actual.options.map { it.window })
    }

    @Test
    fun `99-D2-13 participant range and preference ordering cannot change projection`() {
        val whole = local("2026-10-07T18:00", "2026-10-07T22:37")
        val first = local("2026-10-07T19:07", "2026-10-07T21:13")
        val second = local("2026-10-07T20:19", "2026-10-07T22:11")
        val members =
            listOf(
                member(1, listOf(whole, whole), listOf(AREA_TWO, AREA_ONE), listOf(first, second, first), linkedSetOf("AREA_2", "AREA_1")),
                member(2, listOf(whole), listOf(AREA_ONE, AREA_TWO), listOf(second), setOf("AREA_1")),
            )
        val expected = RecommendationProjector.generate(MeetingMode.EITHER, members, SEOUL)
        val reversed =
            members.reversed().map {
                it.copy(
                    availableTimes = it.availableTimes.reversed(),
                    offlineArea = ParticipantAllowedArea(it.offlineArea!!.alternatives.reversed()),
                    explicitPreferences =
                        it.explicitPreferences.copy(
                            timeRanges = it.explicitPreferences.timeRanges.reversed(),
                            areaKeys =
                                it.explicitPreferences.areaKeys
                                    .reversed()
                                    .toSet(),
                        ),
                )
            }
        assertEquals(expected, RecommendationProjector.generate(MeetingMode.EITHER, reversed, SEOUL))
    }

    @Test
    fun `99-D2-14 touching midnight preference ranges jointly cover continuous availability`() {
        val before = local("2026-10-07T23:30", "2026-10-08T00:00")
        val after = local("2026-10-08T00:00", "2026-10-08T02:17")
        val whole = InstantTimeRange(before.startInclusive, after.endExclusive)
        val actual = project(member(1, listOf(before, after), preferredTimes = listOf(before, after)), member(2, listOf(whole)))
        assertEquals(1, score(actual, whole))
        assertTrue(actual.options.all { whole.startInclusive <= it.window.startInclusive && it.window.endExclusive <= whole.endExclusive })
    }

    @Test
    fun `99-D2-15 DST repeated local clock does not transfer preference to another instant`() {
        val summer = utc("2026-10-25T00:10:00Z", "2026-10-25T00:20:00Z")
        val winter = utc("2026-10-25T01:10:00Z", "2026-10-25T01:20:00Z")
        val actual =
            RecommendationProjector.generate(
                MeetingMode.REMOTE,
                listOf(member(1, listOf(summer, winter), preferredTimes = listOf(summer)), member(2, listOf(summer, winter))),
                ZoneId.of("Europe/Berlin"),
            )
        assertEquals(setOf(summer, winter), actual.options.map { it.window }.toSet())
        assertEquals(1, score(actual, summer))
        assertEquals(0, score(actual, winter))
        assertEquals(summer, primaryWindows(actual).first())
    }

    private fun project(
        vararg participants: ParticipantMatchInput,
        mode: MeetingMode = MeetingMode.REMOTE,
    ) = RecommendationProjector.generate(mode, participants.toList(), SEOUL)

    private fun primaryWindows(projection: RecommendationProjection) = projection.primaryOptionIndexes.map { projection.options[it].window }

    private fun score(
        projection: RecommendationProjection,
        window: InstantTimeRange,
        attendees: Set<ParticipantId> = setOf(id(1), id(2)),
    ): Int {
        val option =
            assertNotNull(
                projection.options.singleOrNull { it.window == window },
                "Exactly one required preference or original window must remain: $window",
            )
        val variant =
            assertNotNull(
                option.variants.singleOrNull { it.participantIds.toSet() == attendees },
                "Required attendees must retain a variant: $attendees",
            )
        return variant.preferenceCount
    }

    private fun member(
        index: Long,
        windows: List<InstantTimeRange>,
        areas: List<CompatiblePlaceArea>? = null,
        preferredTimes: List<InstantTimeRange> = emptyList(),
        preferredAreas: Set<String> = emptySet(),
    ) = ParticipantMatchInput(
        participantId = id(index),
        availableTimes = windows,
        offlineArea = areas?.let(::ParticipantAllowedArea),
        explicitPreferences = ParticipantPreferences(preferredTimes, preferredAreas),
    )

    private fun id(value: Long) = ParticipantId(UUID(0, value))

    private fun local(
        start: String,
        end: String,
    ) = InstantTimeRange(LocalDateTime.parse(start).atZone(SEOUL).toInstant(), LocalDateTime.parse(end).atZone(SEOUL).toInstant())

    private fun utc(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val AREA_ONE = CompatiblePlaceArea("AREA_1", "합성 지역 1")
        private val AREA_TWO = CompatiblePlaceArea("AREA_2", "합성 지역 2")
        private val AREA_THREE = CompatiblePlaceArea("AREA_3", "합성 지역 3")
    }
}
