package com.meetme.server.coordination.domain.matching

import com.meetme.server.coordination.domain.location.GeoCoordinate
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class RecommendationParticipantStatusPolicyTest {
    private val a = ParticipantId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    private val b = ParticipantId(UUID.fromString("00000000-0000-4000-8000-000000000002"))
    private val window = time("10:00", "12:00")
    private val area = CompatiblePlaceArea("AREA_1", "synthetic first area")
    private val otherArea = CompatiblePlaceArea("AREA_2", "synthetic second area")

    @Test
    fun `included participant with complete supported conditions is available`() {
        assertEquals(RecommendationParticipantStatus.AVAILABLE, assess(input(), variant()))
    }

    @Test
    fun `excluded membership alone cannot establish unavailability`() {
        assertEquals(RecommendationParticipantStatus.UNKNOWN, assess(input(), variant(included = false)))
    }

    @Test
    fun `confirmed full-window time mismatch establishes unavailability for an excluded person`() {
        assertEquals(
            RecommendationParticipantStatus.UNAVAILABLE,
            assess(input(times = listOf(time("13:00", "14:00"))), variant(included = false)),
        )
    }

    @Test
    fun `a shorter later actual selection must not replace the entire offered window`() {
        assertEquals(
            RecommendationParticipantStatus.UNAVAILABLE,
            assess(input(times = listOf(time("10:30", "11:00"))), variant(included = false)),
        )
    }

    @Test
    fun `confirmed current variant area mismatch establishes unavailability`() {
        assertEquals(
            RecommendationParticipantStatus.UNAVAILABLE,
            assess(input(areas = listOf(otherArea)), variant(included = false, mode = MeetingMode.IN_PERSON, place = area)),
        )
    }

    @Test
    fun `current matching area is checked rather than choosing a different compatible area`() {
        assertEquals(
            RecommendationParticipantStatus.AVAILABLE,
            assess(input(areas = listOf(otherArea, area)), variant(mode = MeetingMode.IN_PERSON, place = area)),
        )
    }

    @Test
    fun `stored individual place uncertainty affects only that participant`() {
        val candidate = variant(mode = MeetingMode.IN_PERSON, pending = listOf(a))
        assertEquals(RecommendationParticipantStatus.UNKNOWN, assess(input(), candidate))
        assertEquals(
            RecommendationParticipantStatus.AVAILABLE,
            RecommendationParticipantStatusPolicy.assess(
                b,
                window,
                candidate,
                RecommendationParticipantEvidence(input().copy(participantId = b), true, true),
                MeetingMode.EITHER,
            ),
        )
    }

    @Test
    fun `unverified travel or place does not become a confirmed feasible attendance`() {
        assertEquals(
            RecommendationParticipantStatus.UNKNOWN,
            assess(input(assessment = OfflinePlaceAssessment.UNVERIFIED), variant(mode = MeetingMode.IN_PERSON)),
        )
    }

    @Test
    fun `known time conflict remains binding despite unverified place`() {
        assertEquals(
            RecommendationParticipantStatus.UNAVAILABLE,
            assess(
                input(times = listOf(time("13:00", "14:00")), assessment = OfflinePlaceAssessment.UNVERIFIED),
                variant(included = false, mode = MeetingMode.IN_PERSON),
            ),
        )
    }

    @Test
    fun `known area conflict remains binding despite additional unverified travel`() {
        assertEquals(
            RecommendationParticipantStatus.UNAVAILABLE,
            assess(
                input(areas = listOf(otherArea), assessment = OfflinePlaceAssessment.UNVERIFIED),
                variant(included = false, mode = MeetingMode.IN_PERSON, place = area),
            ),
        )
    }

    @Test
    fun `missing rejected or unsupported time evidence remains unknown rather than unavailable`() {
        for (evidence in listOf(
            RecommendationParticipantEvidence(null, false, false),
            RecommendationParticipantEvidence(input(times = emptyList()), false, false),
            RecommendationParticipantEvidence(input(times = emptyList()), true, false),
        )) {
            assertEquals(
                RecommendationParticipantStatus.UNKNOWN,
                RecommendationParticipantStatusPolicy.assess(a, window, variant(included = false), evidence, MeetingMode.EITHER),
            )
        }
    }

    @Test
    fun `inconsistent saved inclusion is not rewritten by newly restored conditions`() {
        assertEquals(
            RecommendationParticipantStatus.UNKNOWN,
            assess(input(times = listOf(time("13:00", "14:00"))), variant()),
        )
    }

    @Test
    fun `historical coordinate without a supported area conversion remains unknown`() {
        val candidate =
            variant(
                mode = MeetingMode.IN_PERSON,
            ).copy(representativePlace = GeoCoordinate.of("37.5".toBigDecimal(), "127.0".toBigDecimal()))
        assertEquals(RecommendationParticipantStatus.UNKNOWN, assess(input(areas = listOf(area)), candidate))
    }

    @Test
    fun `required area with an unspecified variant location remains unknown`() {
        assertEquals(
            RecommendationParticipantStatus.UNKNOWN,
            assess(input(areas = listOf(area)), variant(mode = MeetingMode.IN_PERSON)),
        )
    }

    @Test
    fun `another owner evidence or inconsistent room mode cannot establish availability`() {
        assertEquals(RecommendationParticipantStatus.UNKNOWN, assess(input().copy(participantId = b), variant()))
        assertEquals(
            RecommendationParticipantStatus.UNKNOWN,
            RecommendationParticipantStatusPolicy.assess(
                a,
                window,
                variant(),
                RecommendationParticipantEvidence(input(), true, true),
                MeetingMode.IN_PERSON,
            ),
        )
    }

    @Test
    fun `unrestricted verified person does not inherit another persons missing place reference`() {
        val candidate = variant(mode = MeetingMode.IN_PERSON, place = area)
        assertEquals(
            RecommendationParticipantStatus.AVAILABLE,
            RecommendationParticipantStatusPolicy.assess(
                a,
                window,
                candidate,
                RecommendationParticipantEvidence(input(), true, true, false),
                MeetingMode.EITHER,
            ),
        )
        assertEquals(
            RecommendationParticipantStatus.UNKNOWN,
            RecommendationParticipantStatusPolicy.assess(
                a,
                window,
                candidate,
                RecommendationParticipantEvidence(input(areas = listOf(area)), true, true, false),
                MeetingMode.EITHER,
            ),
        )
    }

    @Test
    fun `a verified unrestricted person needs no legacy area to coordinate conversion`() {
        val candidate =
            variant(mode = MeetingMode.IN_PERSON)
                .copy(representativePlace = GeoCoordinate.of("37.5".toBigDecimal(), "127.0".toBigDecimal()))
        assertEquals(RecommendationParticipantStatus.AVAILABLE, assess(input(), candidate))
    }

    private fun assess(
        input: ParticipantMatchInput,
        variant: RecommendationVariant,
    ): RecommendationParticipantStatus =
        RecommendationParticipantStatusPolicy.assess(
            a,
            window,
            variant,
            RecommendationParticipantEvidence(input, true, true),
            MeetingMode.EITHER,
        )

    private fun input(
        times: List<InstantTimeRange> = listOf(window),
        areas: List<CompatiblePlaceArea> = emptyList(),
        assessment: OfflinePlaceAssessment = OfflinePlaceAssessment.UNRESTRICTED,
    ) = ParticipantMatchInput(
        a,
        times,
        offlineArea = areas.takeIf { it.isNotEmpty() }?.let(::ParticipantAllowedArea),
        offlinePlaceAssessment = assessment,
    )

    private fun variant(
        included: Boolean = true,
        mode: MeetingMode = MeetingMode.REMOTE,
        place: CompatiblePlaceArea? = null,
        pending: List<ParticipantId> = emptyList(),
    ) = RecommendationVariant(
        mode,
        if (included) listOf(a, b) else listOf(b),
        representativeArea = place,
        unverifiedPlaceParticipantIds = pending,
    )

    private fun time(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse("2026-11-08T$start:00Z"), Instant.parse("2026-11-08T$end:00Z"))
}
