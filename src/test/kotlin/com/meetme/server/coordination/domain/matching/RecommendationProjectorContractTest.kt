package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Independently authored from issue #95 D1/D3/D4 and the agreed public skeleton. D2 is deliberately pending.
class RecommendationProjectorContractTest {
    @Test
    fun `95-D01 zero through four actual windows never fabricate three cards`() {
        val windows =
            (7..10).map {
                local(
                    "2026-10-${it.toString().padStart(2, '0')}T18:00",
                    "2026-10-${it.toString().padStart(2, '0')}T20:37",
                )
            }
        for (count in 0..4) {
            val actual = project(MeetingMode.REMOTE, member(1, windows.take(count)), member(2, windows.take(count)))
            assertEquals(windows.take(count).toSet(), actual.options.map { it.window }.toSet(), "Count $count")
            assertEquals(minOf(count, 3), actual.primaryOptionIndexes.size, "Count $count")
            assertEquals(actual.primaryOptionIndexes.distinct(), actual.primaryOptionIndexes, "Never duplicate an option")
            assertTrue(actual.primaryOptionIndexes.all { it in actual.options.indices })
        }
    }

    @Test
    fun `95-D02 fewer than two attendees produce no option`() {
        val window = local("2026-10-07T18:00", "2026-10-07T18:15")
        for (members in listOf(emptyList(), listOf(member(1, listOf(window))), listOf(member(1, listOf(window)), member(2, emptyList())))) {
            val actual = RecommendationProjector.generate(MeetingMode.REMOTE, members, SEOUL)
            assertEquals(emptyList(), actual.options)
            assertEquals(emptyList(), actual.primaryOptionIndexes)
        }
    }

    @Test
    fun `95-D03 same full window retains every N N-minus-one and N-minus-two participant set`() {
        val window = local("2026-10-07T18:00", "2026-10-07T22:00")
        val actual = project(MeetingMode.REMOTE, *(1L..4L).map { member(it, listOf(window)) }.toTypedArray())
        val variants = actual.options.single().variants
        assertEquals(window, actual.options.single().window)
        assertEquals(11, variants.size)
        assertEquals(mapOf(4 to 1, 3 to 4, 2 to 6), variants.groupingBy { it.participantIds.size }.eachCount())
        assertEquals(variants.size, variants.map { it.participantIds.toSet() }.toSet().size)
        assertTrue(variants.all { it.meetingMode == MeetingMode.REMOTE })
        assertEquals(listOf(0), actual.primaryOptionIndexes)
    }

    @Test
    fun `95-D03 full attendance does not hide other partial attendance windows`() {
        val full = local("2026-10-07T18:00", "2026-10-07T20:00")
        val triple = local("2026-10-08T18:00", "2026-10-08T20:00")
        val pair = local("2026-10-09T18:00", "2026-10-09T20:00")
        val actual =
            project(
                MeetingMode.REMOTE,
                member(1, listOf(full, triple, pair)),
                member(2, listOf(full, triple, pair)),
                member(3, listOf(full, triple)),
                member(4, listOf(full)),
            )
        assertEquals(setOf(full, triple, pair), actual.options.map { it.window }.toSet())
        assertEquals(
            listOf(4, 3, 2),
            actual.primaryOptionIndexes.map {
                actual.options[it].variants.maxOf { variant ->
                    variant.participantIds.size
                }
            },
        )
        assertEquals(
            setOf(id(1), id(2)),
            actual.options
                .single { it.window == pair }
                .variants
                .single()
                .participantIds
                .toSet(),
        )
    }

    @Test
    fun `95-D04 five participant room cannot lower eligibility to two attendees`() {
        val window = local("2026-10-07T18:00", "2026-10-07T20:00")
        val actual =
            project(
                MeetingMode.REMOTE,
                member(1, listOf(window)),
                member(2, listOf(window)),
                member(3, emptyList()),
                member(4, emptyList()),
                member(5, emptyList()),
            )
        assertEquals(emptyList(), actual.options)
        assertEquals(emptyList(), actual.primaryOptionIndexes)
    }

    @Test
    fun `95-D05 same absolute window has remote and in-person variants in one option`() {
        val window = local("2026-10-07T18:00", "2026-10-07T20:00")
        val actual = project(MeetingMode.EITHER, member(1, listOf(window), listOf(AREA_ONE)), member(2, listOf(window), listOf(AREA_ONE)))
        val option = actual.options.single()
        assertEquals(window, option.window)
        assertEquals(setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE), option.variants.map { it.meetingMode }.toSet())
        assertEquals(AREA_ONE, option.variants.single { it.meetingMode == MeetingMode.IN_PERSON }.representativeArea)
        assertEquals(null, option.variants.single { it.meetingMode == MeetingMode.REMOTE }.representativeArea)
        assertEquals(listOf(0), actual.primaryOptionIndexes)
    }

    @Test
    fun `95-D06 all common place alternatives remain independent variants without multiplying cards`() {
        val window = local("2026-10-07T18:00", "2026-10-07T20:00")
        val actual =
            project(
                MeetingMode.EITHER,
                member(1, listOf(window), listOf(AREA_ONE, AREA_TWO)),
                member(2, listOf(window), listOf(AREA_TWO, AREA_ONE)),
            )
        val variants = actual.options.single().variants
        assertEquals(3, variants.size)
        assertEquals(
            setOf(AREA_ONE, AREA_TWO),
            variants.filter { it.meetingMode == MeetingMode.IN_PERSON }.map { it.representativeArea }.toSet(),
        )
        assertEquals(1, variants.count { it.meetingMode == MeetingMode.REMOTE })
        assertEquals(listOf(0), actual.primaryOptionIndexes)
    }

    @Test
    fun `95-D07 participant range and area input order cannot change projection or primary selection`() {
        val early = local("2026-10-07T18:00", "2026-10-07T20:00")
        val late = local("2026-10-08T18:00", "2026-10-08T20:00")
        val members = (1L..4L).map { member(it, listOf(late, early, early), listOf(AREA_TWO, AREA_ONE)) }
        val first = RecommendationProjector.generate(MeetingMode.EITHER, members, SEOUL)
        val reversed =
            members.reversed().map {
                it.copy(availableTimes = it.availableTimes.reversed(), offlineArea = ParticipantAllowedArea(listOf(AREA_ONE, AREA_TWO)))
            }
        assertEquals(first, RecommendationProjector.generate(MeetingMode.EITHER, reversed, SEOUL))
        assertEquals(2, first.options.size)
        assertTrue(
            first.options.flatMap { it.variants }.all {
                it.participantIds ==
                    it.participantIds.sortedBy { participant -> participant.value.toString() }
            },
        )
    }

    @Test
    fun `95-D08 attendance quality outranks the appeal of another date`() {
        val full =
            (8..10).map {
                local(
                    "2026-10-${it.toString().padStart(2, '0')}T18:00",
                    "2026-10-${it.toString().padStart(2, '0')}T20:00",
                )
            }
        val earlierPartial = local("2026-10-07T09:00", "2026-10-07T12:00")
        val actual = project(MeetingMode.REMOTE, member(1, full + earlierPartial), member(2, full + earlierPartial), member(3, full))
        assertEquals(full.toSet(), primaryWindows(actual).toSet())
        assertTrue(earlierPartial in actual.options.map { it.window })
    }

    @Test
    fun `95-D09 equal quality chooses a new local start date before same-day distant clock time`() {
        val anchor = local("2026-10-07T09:00", "2026-10-07T10:00")
        val sameDate = local("2026-10-07T22:00", "2026-10-07T23:00")
        val nextDate = local("2026-10-08T09:00", "2026-10-08T10:00")
        val thirdDate = local("2026-10-09T09:00", "2026-10-09T10:00")
        val windows = listOf(anchor, sameDate, nextDate, thirdDate)
        val actual = project(MeetingMode.REMOTE, member(1, windows), member(2, windows))
        assertEquals(listOf(anchor, thirdDate, nextDate), primaryWindows(actual))
    }

    @Test
    fun `95-D09 equal new dates prefer greater local clock distance before absolute distance`() {
        val anchor = local("2026-10-07T09:00", "2026-10-07T10:00")
        val nearDateFarClock = local("2026-10-08T20:00", "2026-10-08T21:00")
        val farDateSameClock = local("2026-10-10T09:00", "2026-10-10T10:00")
        val sameDate = local("2026-10-07T12:00", "2026-10-07T13:00")
        val windows = listOf(anchor, sameDate, nearDateFarClock, farDateSameClock)
        val actual = project(MeetingMode.REMOTE, member(1, windows), member(2, windows))
        assertEquals(listOf(anchor, nearDateFarClock, farDateSameClock), primaryWindows(actual))
    }

    @Test
    fun `95-D10 exact fifteen-minute and two-hour-thirty-seven-minute windows are preserved`() {
        val short = local("2026-10-07T18:03", "2026-10-07T18:18")
        val long = local("2026-10-08T18:03", "2026-10-08T20:40")
        val actual = project(MeetingMode.REMOTE, member(1, listOf(short, long)), member(2, listOf(short, long)))
        assertEquals(listOf(short, long), actual.options.map { it.window })
        assertEquals(
            listOf(15L, 157L),
            actual.options.map { Duration.between(it.window.startInclusive, it.window.endExclusive).toMinutes() },
        )
    }

    @Test
    fun `95-D11 removed unavailable gap is never bridged by projection`() {
        val beforeBlocked = local("2026-10-07T18:00", "2026-10-07T19:00")
        val afterBlocked = local("2026-10-07T20:00", "2026-10-07T22:00")
        val available = local("2026-10-07T18:00", "2026-10-07T22:00")
        val actual = project(MeetingMode.REMOTE, member(1, listOf(beforeBlocked, afterBlocked)), member(2, listOf(available)))
        assertEquals(listOf(beforeBlocked, afterBlocked), actual.options.map { it.window })
        assertFalse(
            actual.options.any {
                it.window.startInclusive < afterBlocked.startInclusive &&
                    it.window.endExclusive > beforeBlocked.endExclusive
            },
        )
    }

    @Test
    fun `95-D12 absent or incompatible places remove in-person variants but retain remote alternatives`() {
        val window = local("2026-10-07T18:00", "2026-10-07T20:00")
        for (secondAreas in listOf(null, listOf(AREA_TWO))) {
            val actual = project(MeetingMode.EITHER, member(1, listOf(window), listOf(AREA_ONE)), member(2, listOf(window), secondAreas))
            assertEquals(
                listOf(MeetingMode.REMOTE),
                actual.options
                    .single()
                    .variants
                    .map { it.meetingMode },
            )
            assertEquals(
                null,
                actual.options
                    .single()
                    .variants
                    .single()
                    .representativeArea,
            )
        }
    }

    @Test
    fun `95-D13 room mode filters variants rather than silently changing meeting mode`() {
        val window = local("2026-10-07T18:00", "2026-10-07T20:00")
        for (mode in listOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE)) {
            val actual = project(mode, member(1, listOf(window), listOf(AREA_ONE)), member(2, listOf(window), listOf(AREA_ONE)))
            assertEquals(
                listOf(mode),
                actual.options
                    .single()
                    .variants
                    .map { it.meetingMode },
            )
        }
        assertEquals(
            emptyList(),
            project(MeetingMode.IN_PERSON, member(1, listOf(window), listOf(AREA_ONE)), member(2, listOf(window))).options,
        )
    }

    @Test
    fun `95-D14 nonapplicable participant stays outside variants while N-minus-one remains eligible`() {
        val window = local("2026-10-07T18:00", "2026-10-07T20:00")
        val actual = project(MeetingMode.REMOTE, member(1, listOf(window)), member(2, listOf(window)), member(3, emptyList()))
        assertEquals(
            listOf(id(1), id(2)),
            actual.options
                .single()
                .variants
                .single()
                .participantIds,
        )
    }

    @Test
    fun `95-D15 adjacent midnight parser ranges normalize to one continuous option`() {
        val firstDay = local("2026-10-07T23:30", "2026-10-08T00:00")
        val secondDay = local("2026-10-08T00:00", "2026-10-08T02:00")
        val combined = InstantTimeRange(firstDay.startInclusive, secondDay.endExclusive)
        val actual = project(MeetingMode.REMOTE, member(1, listOf(firstDay, secondDay)), member(2, listOf(combined)))
        assertEquals(combined, actual.options.single().window)
        assertEquals(150L, Duration.between(combined.startInclusive, combined.endExclusive).toMinutes())
    }

    @Test
    fun `95-D16 DST overlap windows with same local clock remain distinct absolute options`() {
        val summer = utc("2026-10-25T00:10:00Z", "2026-10-25T00:20:00Z")
        val winter = utc("2026-10-25T01:10:00Z", "2026-10-25T01:20:00Z")
        val windows = listOf(summer, winter)
        val actual =
            RecommendationProjector.generate(
                MeetingMode.REMOTE,
                listOf(member(1, windows), member(2, windows)),
                ZoneId.of("Europe/Berlin"),
            )
        assertEquals(windows, actual.options.map { it.window })
        assertEquals(2, actual.primaryOptionIndexes.size)
    }

    private fun project(
        mode: MeetingMode,
        vararg participants: ParticipantMatchInput,
    ) = RecommendationProjector.generate(mode, participants.toList(), SEOUL)

    private fun primaryWindows(projection: RecommendationProjection) = projection.primaryOptionIndexes.map { projection.options[it].window }

    private fun member(
        index: Long,
        windows: List<InstantTimeRange>,
        areas: List<CompatiblePlaceArea>? = null,
    ) = ParticipantMatchInput(id(index), windows, offlineArea = areas?.let(::ParticipantAllowedArea))

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
    }
}
