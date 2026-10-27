package com.meetme.server.coordination.domain.matching

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent 2026-10-21 user contract: actual time/attendee sets replace the arbitrary N-2 cutoff. */
class RecommendationPartialAttendanceContractTest {
    @Test
    fun `PA01 two of five attendees retain their exact contiguous time option`() {
        val members = (1L..5L).map { member(it, if (it <= 2) listOf(WINDOW) else emptyList()) }
        val result = project(MeetingMode.REMOTE, members)
        assertEquals(listOf(WINDOW), result.options.map { it.window })
        assertEquals(
            listOf(id(1), id(2)),
            result.options
                .single()
                .variants
                .single()
                .participantIds,
        )
        assertEquals(listOf(0), result.primaryOptionIndexes)
    }

    @Test
    fun `PA02 all five available at the same time never manufacture unnecessary remote subsets`() {
        val result = project(MeetingMode.REMOTE, (1L..5L).map { member(it, listOf(WINDOW)) })
        val option = result.options.single()
        assertEquals(WINDOW, option.window)
        assertEquals(1, option.variants.size)
        assertEquals((1L..5L).map(::id), option.variants.single().participantIds)
    }

    @Test
    fun `PA03 fifty simultaneous attendees produce one option and one maximal variant with bounded work`() {
        var checks = 0
        val members = (1L..50L).map { member(it, listOf(WINDOW)) }
        val result =
            RecommendationProjector.generate(MeetingMode.REMOTE, members, SEOUL) {
                assertTrue(++checks <= 10_000, "A two-boundary room must not enumerate exponentially many participant subsets")
            }
        assertEquals(1, result.options.size)
        assertEquals(
            1,
            result.options
                .single()
                .variants.size,
        )
        assertEquals(
            (1L..50L).map(::id),
            result.options
                .single()
                .variants
                .single()
                .participantIds,
        )
        assertEquals(listOf(0), result.primaryOptionIndexes)
    }

    @Test
    fun `PA04 two available attendees are eligible even when the other forty eight are unavailable`() {
        val members = (1L..50L).map { member(it, if (it == 7L || it == 43L) listOf(WINDOW) else emptyList()) }
        val result = project(MeetingMode.REMOTE, members)
        assertEquals(listOf(WINDOW), result.options.map { it.window })
        assertEquals(
            listOf(id(7), id(43)),
            result.options
                .single()
                .variants
                .single()
                .participantIds,
        )
    }

    @Test
    fun `PA05 remote room respects online mode despite incompatible offline groups`() {
        val members = (1L..5L).map { member(it, listOf(WINDOW), if (it <= 2) listOf(AREA_ONE) else listOf(AREA_TWO)) }
        val result = project(MeetingMode.REMOTE, members)
        val variant =
            result.options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.REMOTE, variant.meetingMode)
        assertEquals((1L..5L).map(::id), variant.participantIds)
        assertEquals(null, variant.representativeArea)
        assertEquals(null, variant.representativePlace)
    }

    @Test
    fun `PA06 in person room retains compatible groups without switching to online`() {
        val members = (1L..5L).map { member(it, listOf(WINDOW), if (it <= 2) listOf(AREA_ONE) else listOf(AREA_TWO)) }
        val result = project(MeetingMode.IN_PERSON, members)
        val variants = result.options.single().variants
        assertEquals(2, variants.size)
        assertTrue(variants.all { it.meetingMode == MeetingMode.IN_PERSON })
        assertEquals(
            mapOf("AREA_1" to setOf(id(1), id(2)), "AREA_2" to setOf(id(3), id(4), id(5))),
            variants.associate { requireNotNull(it.representativeArea).key to it.participantIds.toSet() },
        )
    }

    @Test
    fun `PA07 either mode preserves one all attendee online choice and maximal compatible place groups`() {
        val members = (1L..5L).map { member(it, listOf(WINDOW), if (it <= 2) listOf(AREA_ONE) else listOf(AREA_TWO)) }
        val result = project(MeetingMode.EITHER, members)
        val variants = result.options.single().variants
        assertEquals(3, variants.size)
        assertEquals((1L..5L).map(::id), variants.single { it.meetingMode == MeetingMode.REMOTE }.participantIds)
        assertEquals(
            setOf(setOf(id(1), id(2)), setOf(id(3), id(4), id(5))),
            variants.filter { it.meetingMode == MeetingMode.IN_PERSON }.map { it.participantIds.toSet() }.toSet(),
        )
    }

    @Test
    fun `PA08 all attendees sharing two allowed areas retain both alternatives without smaller attendee duplicates`() {
        val result = project(MeetingMode.EITHER, (1L..5L).map { member(it, listOf(WINDOW), listOf(AREA_ONE, AREA_TWO)) })
        val variants = result.options.single().variants
        assertEquals(3, variants.size)
        assertTrue(variants.all { it.participantIds == (1L..5L).map(::id) })
        assertEquals(setOf(AREA_ONE, AREA_TWO), variants.mapNotNull { it.representativeArea }.toSet())
    }

    @Test
    fun `PA09 adjacent intervals with different actual attendees never invent common availability`() {
        val first = range("2026-10-21T10:00:00Z", "2026-10-21T11:00:00Z")
        val second = range("2026-10-21T11:00:00Z", "2026-10-21T12:00:00Z")
        val members =
            listOf(
                member(1, listOf(first)),
                member(2, listOf(first)),
                member(3, listOf(second)),
                member(4, listOf(second)),
                member(5, emptyList()),
            )
        val result = project(MeetingMode.REMOTE, members)
        assertEquals(setOf(first, second), result.options.map { it.window }.toSet())
        assertEquals(
            setOf(id(1), id(2)),
            result.options
                .single { it.window == first }
                .variants
                .single()
                .participantIds
                .toSet(),
        )
        assertEquals(
            setOf(id(3), id(4)),
            result.options
                .single { it.window == second }
                .variants
                .single()
                .participantIds
                .toSet(),
        )
        assertTrue(result.options.all { it.variants.all { variant -> variant.participantIds.size == 2 } })
    }

    @Test
    fun `PA10 fifty participants in twenty five disjoint actual pair windows retain every alternative once`() {
        val windows =
            (0L..24L).map { offset ->
                InstantTimeRange(
                    WINDOW.startInclusive.plusSeconds(offset * 7200),
                    WINDOW.startInclusive.plusSeconds(
                        offset * 7200 + 3600,
                    ),
                )
            }
        val members = (1L..50L).map { member(it, listOf(windows[((it - 1) / 2).toInt()])) }
        var checks = 0
        val result =
            RecommendationProjector.generate(MeetingMode.REMOTE, members, SEOUL) {
                assertTrue(++checks <= 100_000, "Disjoint pair windows must not expand into all participant subsets")
            }
        assertEquals(windows.toSet(), result.options.map { it.window }.toSet())
        assertEquals(3, result.primaryOptionIndexes.size)
        assertEquals(3, result.primaryOptionIndexes.distinct().size)
        for ((index, window) in windows.withIndex()) {
            val variants = result.options.single { it.window == window }.variants
            assertEquals(1, variants.size)
            assertEquals(listOf(id(index * 2L + 1), id(index * 2L + 2)), variants.single().participantIds)
        }
    }

    @Test
    fun `PA11 reversed participants ranges and area alternatives preserve deterministic projection`() {
        val second = range("2026-10-22T10:00:00Z", "2026-10-22T12:00:00Z")
        val members =
            (1L..5L).map {
                member(
                    it,
                    if (it <=
                        2
                    ) {
                        listOf(WINDOW, second, WINDOW)
                    } else {
                        listOf(second)
                    },
                    listOf(AREA_ONE, AREA_TWO),
                )
            }
        val reversed =
            members.reversed().map {
                it.copy(
                    availableTimes = it.availableTimes.reversed(),
                    offlineArea = ParticipantAllowedArea(it.offlineArea!!.alternatives.reversed()),
                )
            }
        assertEquals(project(MeetingMode.EITHER, members), project(MeetingMode.EITHER, reversed))
    }

    @Test
    fun `PA12 existing minimum of two attendees remains unchanged and unavailable input never appears`() {
        for (members in listOf(
            emptyList(),
            listOf(member(1, listOf(WINDOW))),
            (1L..5L).map {
                member(
                    it,
                    if (it ==
                        1L
                    ) {
                        listOf(WINDOW)
                    } else {
                        emptyList()
                    },
                )
            },
        )) {
            val result = project(MeetingMode.REMOTE, members)
            assertEquals(emptyList(), result.options)
            assertEquals(emptyList(), result.primaryOptionIndexes)
        }
        val members = (1L..5L).map { member(it, if (it <= 2) listOf(WINDOW) else emptyList()) }
        val result = project(MeetingMode.REMOTE, members)
        assertFalse(
            result.options
                .flatMap { it.variants }
                .flatMap { it.participantIds }
                .any { it in setOf(id(3), id(4), id(5)) },
        )
    }

    @Test
    fun `PA13 existing geographic regions retain a compatible two of five group without changing room mode`() {
        val first = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0"), 1_000)))
        val second = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("0", "0.015"), 1_000)))
        assertFalse(GeoMatcher.contains(first, second.alternatives.single().center))
        assertFalse(GeoMatcher.contains(second, first.alternatives.single().center))
        val regions =
            listOf(first, second) + (1..3).map { ParticipantAllowedRegion(listOf(AllowedCircle(coordinate(it.toString(), "0"), 1_000))) }
        val members = regions.mapIndexed { index, region -> member(index + 1L, listOf(WINDOW)).copy(offlineRegion = region) }
        val result = project(MeetingMode.IN_PERSON, members)
        val variant =
            result.options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.IN_PERSON, variant.meetingMode)
        assertEquals(listOf(id(1), id(2)), variant.participantIds)
        val point = requireNotNull(variant.representativePlace)
        assertTrue(GeoMatcher.contains(first, point))
        assertTrue(GeoMatcher.contains(second, point))
    }

    @Test
    fun `PA14 fifty identical geographic regions retain one full attendee variant`() {
        val region = ParticipantAllowedRegion(listOf(AllowedCircle(coordinate("37.5", "127.0"), 1_000)))
        val members = (1L..50L).map { member(it, listOf(WINDOW)).copy(offlineRegion = region) }
        val result = project(MeetingMode.IN_PERSON, members)
        val variant =
            result.options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.IN_PERSON, variant.meetingMode)
        assertEquals((1L..50L).map(::id), variant.participantIds)
        assertTrue(GeoMatcher.contains(region, requireNotNull(variant.representativePlace)))
    }

    @Test
    fun `PA15 alternating additional attendees do not hide a longer continuous pair option`() {
        val whole = range("2026-10-21T09:00:00Z", "2026-10-21T13:00:00Z")
        val first = range("2026-10-21T09:00:00Z", "2026-10-21T11:00:00Z")
        val second = range("2026-10-21T11:00:00Z", "2026-10-21T13:00:00Z")
        val result =
            project(
                MeetingMode.REMOTE,
                listOf(member(1, listOf(whole)), member(2, listOf(whole)), member(3, listOf(first)), member(4, listOf(second))),
            )
        assertEquals(
            setOf(whole, first, second),
            result.options
                .map {
                    it.window
                }.toSet(),
            "A longer actual continuous choice is not a redundant subset at another time window",
        )
        assertEquals(
            listOf(id(1), id(2)),
            result.options
                .single { it.window == whole }
                .variants
                .single()
                .participantIds,
        )
        assertEquals(
            listOf(id(1), id(2), id(3)),
            result.options
                .single { it.window == first }
                .variants
                .single()
                .participantIds,
        )
        assertEquals(
            listOf(id(1), id(2), id(4)),
            result.options
                .single { it.window == second }
                .variants
                .single()
                .participantIds,
        )
        assertEquals(3, result.primaryOptionIndexes.size)
        assertEquals(3, result.primaryOptionIndexes.distinct().size)
    }

    @Test
    fun `PA16 retaining continuous cohort intersections never bridges an unavailable gap`() {
        val first = range("2026-10-21T09:00:00Z", "2026-10-21T10:00:00Z")
        val second = range("2026-10-21T11:00:00Z", "2026-10-21T13:00:00Z")
        val result =
            project(
                MeetingMode.REMOTE,
                listOf(
                    member(1, listOf(first, second)),
                    member(2, listOf(first, second)),
                    member(3, listOf(first)),
                    member(4, listOf(second)),
                ),
            )
        assertEquals(setOf(first, second), result.options.map { it.window }.toSet())
        assertTrue(
            result.options.all { option ->
                option.window.endExclusive <= first.endExclusive ||
                    option.window.startInclusive >= second.startInclusive
            },
        )
        assertEquals(
            listOf(id(1), id(2), id(3)),
            result.options
                .single { it.window == first }
                .variants
                .single()
                .participantIds,
        )
        assertEquals(
            listOf(id(1), id(2), id(4)),
            result.options
                .single { it.window == second }
                .variants
                .single()
                .participantIds,
        )
    }

    private fun coordinate(
        latitude: String,
        longitude: String,
    ) = GeoCoordinate.of(BigDecimal(latitude), BigDecimal(longitude))

    private fun project(
        mode: MeetingMode,
        members: List<ParticipantMatchInput>,
    ) = RecommendationProjector.generate(mode, members, SEOUL)

    private fun member(
        value: Long,
        windows: List<InstantTimeRange>,
        areas: List<CompatiblePlaceArea>? = null,
    ) = ParticipantMatchInput(id(value), windows, offlineArea = areas?.let(::ParticipantAllowedArea))

    private fun id(value: Long) = ParticipantId(UUID(0, value))

    private fun range(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val WINDOW = InstantTimeRange(Instant.parse("2026-10-21T10:00:00Z"), Instant.parse("2026-10-21T12:00:00Z"))
        private val AREA_ONE = CompatiblePlaceArea("AREA_1", "합성 지역 1")
        private val AREA_TWO = CompatiblePlaceArea("AREA_2", "합성 지역 2")
    }
}
