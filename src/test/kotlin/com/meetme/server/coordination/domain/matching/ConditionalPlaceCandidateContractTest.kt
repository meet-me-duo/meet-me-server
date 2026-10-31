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
import kotlin.test.assertTrue

/** Independent 2026-10-31 user contract: time options may retain explicitly unverified place conditions. */
class ConditionalPlaceCandidateContractTest {
    @Test
    fun `CP01 shared hard area and unverified travel retain an in person time candidate without confirming attendance`() {
        val members = listOf(member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED), member(2, AREA_ONE))
        assertPair(members, expectedArea = AREA_ONE, unverified = listOf(id(1)))
    }

    @Test
    fun `CP02 unknown personal origin and unrestricted peer preserve time without inventing a place`() {
        val members = listOf(member(1, assessment = OfflinePlaceAssessment.UNVERIFIED), unrestricted(2))
        assertPair(members, expectedArea = null, unverified = listOf(id(1)))
    }

    @Test
    fun `CP03 disjoint known hard areas remain incompatible even if both travel restrictions are unverified`() {
        val members =
            listOf(
                member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED),
                member(2, AREA_TWO, OfflinePlaceAssessment.UNVERIFIED),
            )
        assertEmpty(members)
    }

    @Test
    fun `CP04 travel uncertainty never restores a real time conflict`() {
        val members =
            listOf(
                member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED),
                member(2, AREA_ONE).copy(availableTimes = listOf(LATER)),
            )
        assertEmpty(members)
    }

    @Test
    fun `CP05 explicitly unrestricted participants retain in person time without inventing a venue`() {
        assertPair(listOf(unrestricted(1), unrestricted(2)), expectedArea = null, unverified = emptyList())
    }

    @Test
    fun `CP06 unrestricted peer honors the other participants known area`() {
        assertPair(listOf(member(1, AREA_TWO), unrestricted(2)), expectedArea = AREA_TWO, unverified = emptyList())
    }

    @Test
    fun `CP07 known alternatives still intersect before marking travel as unverified`() {
        val first =
            member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED).copy(
                offlineArea = ParticipantAllowedArea(listOf(AREA_ONE, AREA_TWO)),
            )
        assertPair(listOf(first, member(2, AREA_TWO)), expectedArea = AREA_TWO, unverified = listOf(id(1)))
    }

    @Test
    fun `CP08 legacy missing place data is not silently reinterpreted as unrestricted`() {
        val legacy = ParticipantMatchInput(id(1), listOf(WINDOW))
        assertEmpty(listOf(legacy, member(2, AREA_ONE)))
    }

    @Test
    fun `CP09 remote attendance does not acquire offline travel uncertainty`() {
        val members = listOf(member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED), member(2, AREA_TWO))
        val generated = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, members).candidates.single()
        assertEquals(MeetingMode.REMOTE, generated.meetingMode)
        assertEquals(emptyList(), generated.unverifiedPlaceParticipantIds)
        val projected =
            project(MeetingMode.REMOTE, members)
                .options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.REMOTE, projected.meetingMode)
        assertEquals(emptyList(), projected.unverifiedPlaceParticipantIds)
    }

    @Test
    fun `CP10 either mode retains both permitted modes and only in person attendance stays conditional`() {
        val members = listOf(member(1, assessment = OfflinePlaceAssessment.UNVERIFIED), unrestricted(2))
        val generated = DeterministicCandidateMatcher.generate(MeetingMode.EITHER, members).candidates
        assertEquals(setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE), generated.map { it.meetingMode }.toSet())
        val projected = project(MeetingMode.EITHER, members).options.single().variants
        assertEquals(setOf(MeetingMode.IN_PERSON, MeetingMode.REMOTE), projected.map { it.meetingMode }.toSet())
        assertEquals(listOf(id(1)), generated.single { it.meetingMode == MeetingMode.IN_PERSON }.unverifiedPlaceParticipantIds)
        assertEquals(listOf(id(1)), projected.single { it.meetingMode == MeetingMode.IN_PERSON }.unverifiedPlaceParticipantIds)
        assertEquals(emptyList(), projected.single { it.meetingMode == MeetingMode.REMOTE }.unverifiedPlaceParticipantIds)
    }

    @Test
    fun `CP11 known area conflict retains a compatible pair and never invents a full attendance group`() {
        val members =
            listOf(
                member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED),
                member(2, AREA_ONE),
                member(3, AREA_TWO, OfflinePlaceAssessment.UNVERIFIED),
            )
        val projected =
            project(MeetingMode.IN_PERSON, members)
                .options
                .single()
                .variants
                .single()
        assertEquals(listOf(id(1), id(2)), projected.participantIds)
        assertEquals(AREA_ONE, projected.representativeArea)
        assertEquals(listOf(id(1)), projected.unverifiedPlaceParticipantIds)
        val generated = DeterministicCandidateMatcher.generate(MeetingMode.IN_PERSON, members).candidates.single()
        assertEquals(listOf(id(1), id(2)), generated.participantIds)
        assertEquals(listOf(id(1)), generated.unverifiedPlaceParticipantIds)
    }

    @Test
    fun `CP12 unknown origin does not erase the known areas conflict when constructing partial groups`() {
        val members =
            listOf(
                member(1, AREA_ONE),
                member(2, AREA_TWO),
                member(3, assessment = OfflinePlaceAssessment.UNVERIFIED),
            )
        val projected = project(MeetingMode.IN_PERSON, members).options.single().variants
        assertEquals(setOf(setOf(id(1), id(3)), setOf(id(2), id(3))), projected.map { it.participantIds.toSet() }.toSet())
        projected.forEach { assertEquals(listOf(id(3)), it.unverifiedPlaceParticipantIds) }
        assertEquals(setOf(AREA_ONE, AREA_TWO), projected.map { it.representativeArea }.toSet())
    }

    @Test
    fun `CP13 reordering input cannot change options or conditional participant attribution`() {
        val members =
            listOf(
                member(1, AREA_ONE, OfflinePlaceAssessment.UNVERIFIED),
                member(2, assessment = OfflinePlaceAssessment.UNVERIFIED),
                unrestricted(3),
            )
        assertEquals(project(MeetingMode.EITHER, members), project(MeetingMode.EITHER, members.reversed()))
        assertEquals(
            DeterministicCandidateMatcher.generate(MeetingMode.IN_PERSON, members),
            DeterministicCandidateMatcher.generate(MeetingMode.IN_PERSON, members.reversed()),
        )
    }

    @Test
    fun `CP14 fifty simultaneous uncertain origins retain one maximal option with bounded work`() {
        val members = (1L..50L).map { member(it, assessment = OfflinePlaceAssessment.UNVERIFIED) }
        var checks = 0
        val actual =
            RecommendationProjector.generate(MeetingMode.IN_PERSON, members, SEOUL) {
                assertTrue(++checks <= 10_000, "A single shared time must not enumerate all participant subsets")
            }
        assertEquals(listOf(WINDOW), actual.options.map { it.window })
        assertEquals(
            1,
            actual.options
                .single()
                .variants.size,
        )
        val variant =
            actual.options
                .single()
                .variants
                .single()
        assertEquals((1L..50L).map(::id), variant.participantIds)
        assertEquals((1L..50L).map(::id), variant.unverifiedPlaceParticipantIds)
        assertEquals(listOf(0), actual.primaryOptionIndexes)
    }

    @Test
    fun `CP15 one known geographic region remains binding beside an uncertain origin`() {
        val region = ParticipantAllowedRegion(listOf(AllowedCircle(GeoCoordinate.of(BigDecimal("36.7"), BigDecimal("127.1")), 750)))
        val members =
            listOf(
                member(1).copy(offlineRegion = region),
                member(2, assessment = OfflinePlaceAssessment.UNVERIFIED),
            )
        val projected =
            project(MeetingMode.IN_PERSON, members)
                .options
                .single()
                .variants
                .single()
        assertEquals(listOf(id(1), id(2)), projected.participantIds)
        assertEquals(listOf(id(2)), projected.unverifiedPlaceParticipantIds)
        assertTrue(GeoMatcher.contains(region, requireNotNull(projected.representativePlace)))
        assertEquals(null, projected.representativeArea)
        val generated = DeterministicCandidateMatcher.generate(MeetingMode.IN_PERSON, members).candidates.single()
        assertEquals(listOf(id(2)), generated.unverifiedPlaceParticipantIds)
        assertTrue(GeoMatcher.contains(region, requireNotNull(generated.representativePlace)))
    }

    @Test
    fun `CP16 every shared known area remains a separate variant beside unverified travel`() {
        val members =
            listOf(
                member(1, assessment = OfflinePlaceAssessment.UNVERIFIED),
                member(2),
            ).map { it.copy(offlineArea = ParticipantAllowedArea(listOf(AREA_ONE, AREA_TWO))) }
        val projected = project(MeetingMode.IN_PERSON, members).options.single().variants
        assertEquals(setOf(AREA_ONE, AREA_TWO), projected.map { it.representativeArea }.toSet())
        assertEquals(2, projected.size)
        projected.forEach {
            assertEquals(listOf(id(1), id(2)), it.participantIds)
            assertEquals(listOf(id(1)), it.unverifiedPlaceParticipantIds)
        }
    }

    private fun assertPair(
        members: List<ParticipantMatchInput>,
        expectedArea: CompatiblePlaceArea?,
        unverified: List<ParticipantId>,
    ) {
        val generated = DeterministicCandidateMatcher.generate(MeetingMode.IN_PERSON, members).candidates.single()
        assertEquals(MeetingMode.IN_PERSON, generated.meetingMode, "Never auto-switch an in-person room online")
        assertEquals(listOf(WINDOW), generated.timeRanges)
        assertEquals(members.map { it.participantId }.sortedBy { it.value.toString() }, generated.participantIds)
        assertEquals(expectedArea, generated.representativeArea)
        assertEquals(null, generated.representativePlace, "Unverified text cannot create a coordinate")
        assertEquals(unverified, generated.unverifiedPlaceParticipantIds)
        val projection = project(MeetingMode.IN_PERSON, members)
        assertEquals(listOf(WINDOW), projection.options.map { it.window })
        val variant =
            projection.options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.IN_PERSON, variant.meetingMode)
        assertEquals(generated.participantIds, variant.participantIds)
        assertEquals(expectedArea, variant.representativeArea)
        assertEquals(null, variant.representativePlace)
        assertEquals(unverified, variant.unverifiedPlaceParticipantIds)
    }

    private fun assertEmpty(members: List<ParticipantMatchInput>) {
        assertEquals(emptyList(), DeterministicCandidateMatcher.generate(MeetingMode.IN_PERSON, members).candidates)
        assertEquals(emptyList(), project(MeetingMode.IN_PERSON, members).options)
    }

    private fun unrestricted(value: Long) = member(value, assessment = OfflinePlaceAssessment.UNRESTRICTED)

    private fun member(
        value: Long,
        area: CompatiblePlaceArea? = null,
        assessment: OfflinePlaceAssessment = OfflinePlaceAssessment.LEGACY_REQUIRED,
    ) = ParticipantMatchInput(
        id(value),
        listOf(WINDOW),
        offlineArea = area?.let { ParticipantAllowedArea(listOf(it)) },
        offlinePlaceAssessment = assessment,
    )

    private fun project(
        mode: MeetingMode,
        members: List<ParticipantMatchInput>,
    ) = RecommendationProjector.generate(mode, members, SEOUL)

    private fun id(value: Long) = ParticipantId(UUID(0, value))

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val WINDOW = InstantTimeRange(Instant.parse("2026-12-03T09:00:00Z"), Instant.parse("2026-12-03T11:00:00Z"))
        private val LATER = InstantTimeRange(Instant.parse("2026-12-03T11:00:00Z"), Instant.parse("2026-12-03T12:00:00Z"))
        private val AREA_ONE = CompatiblePlaceArea("AREA_1", "북쪽 합성 지역")
        private val AREA_TWO = CompatiblePlaceArea("AREA_2", "남쪽 합성 지역")
    }
}
