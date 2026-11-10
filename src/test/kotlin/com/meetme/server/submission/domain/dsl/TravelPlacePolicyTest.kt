package com.meetme.server.submission.domain.dsl

import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralCalculator
import com.meetme.server.coordination.domain.matching.OfflinePlaceAssessment
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.TravelPlaceTestFixture as F

/** ADR-066/067: source-bound declarations preserve time without pretending a route or venue was verified. */
class TravelPlacePolicyTest {
    private fun validate(
        constraints: List<ScheduleDslPlaceConstraint>,
        raw: String = F.RAW,
    ) = ScheduleDslPlaceConstraintPolicy.validate(constraints, F.base(raw), F.snapshot(raw))

    @Test
    fun `TPD01 source bound travel preserves absent mode and allows public display only through exact server aliases`() {
        val constraint = F.constraint()
        assertEquals(listOf(constraint), validate(listOf(constraint)))
        assertEquals(SchedulePlaceVerification.UNVERIFIED, constraint.verification)
        assertNull(constraint.travelMode)
        assertEquals(
            listOf(PublicPlaceConstraintDescription(F.firstParticipant, SchedulePlaceConstraintKind.TRAVEL_TIME_LIMIT, F.NAME, 45, null)),
            ScheduleDslPlaceConstraintPolicy.descriptions(listOf(constraint), F.snapshot(), F.publicNames),
        )
        assertEquals(F.NAME, F.publicNames.resolve("가상푸른역 별칭"))
        assertNull(F.publicNames.resolve(" ${F.NAME}"))
        assertNull(F.publicNames.resolve("${F.NAME} 근처"))
    }

    @Test
    fun `TPD02 registered label alone cannot disclose private unknown or unregistered native origins`() {
        val cases =
            listOf(
                Triple(F.RAW, F.constraint(referenceKind = SchedulePlaceReferenceKind.PRIVATE_REFERENCE), F.NAME),
                Triple(F.RAW, F.constraint(referenceKind = SchedulePlaceReferenceKind.UNKNOWN), F.NAME),
                Triple(
                    F.PRIVATE_RAW,
                    F.constraint(F.PRIVATE_RAW, F.PRIVATE_PLACE, F.PRIVATE_NAME),
                    F.PRIVATE_NAME,
                ),
                Triple(
                    F.UNREGISTERED_RAW,
                    F.constraint(F.UNREGISTERED_RAW, F.UNREGISTERED_PLACE, F.UNREGISTERED_NAME),
                    F.UNREGISTERED_NAME,
                ),
            )
        cases.forEach { (raw, constraint, _) ->
            assertEquals(listOf(constraint), validate(listOf(constraint), raw))
            val description = ScheduleDslPlaceConstraintPolicy.descriptions(listOf(constraint), F.snapshot(raw), F.publicNames).single()
            assertEquals(F.firstParticipant, description.participantId)
            assertNull(description.publicPlaceName)
            assertNull(description.maxMinutes)
            assertNull(description.travelMode)
        }
    }

    @Test
    fun `TPD03 source owner revision literal hash and containing expression are mandatory for travel evidence`() {
        val valid = F.constraint()
        val evidence = valid.evidence
        val place = requireNotNull(valid.placeEvidence)
        val raw = "${F.RAW} 참고 가상붉은역."
        val invalid =
            listOf(
                valid.copy(submissionVersionId = F.second),
                valid.copy(submissionVersionId = UUID.fromString("00000000-0000-0000-0000-000000009999")),
                valid.copy(evidence = evidence.copy(span = evidence.span.copy(revision = 4))),
                valid.copy(evidence = evidence.copy(span = evidence.span.copy(sourceId = "missing-source"))),
                valid.copy(evidence = evidence.copy(span = evidence.span.copy(contentSha256 = "0".repeat(64)))),
                valid.copy(placeEvidence = place.copy(span = place.span.copy(contentSha256 = "0".repeat(64)))),
                valid.copy(evidence = evidence.copy(derivation = EvidenceLocationDerivation.SERVER_CONDITION_TERM_QUOTE)),
            )
        invalid.forEach { constraint -> assertFailsWith<IllegalArgumentException> { validate(listOf(constraint)) } }
        // Both names are server-approved and present natively, but the red station is outside this declaration.
        val wrongRegisteredName = F.constraint(raw).copy(placeEvidence = F.nativeEvidence("가상붉은역", raw))
        assertFailsWith<IllegalArgumentException> { validate(listOf(wrongRegisteredName), raw) }
    }

    @Test
    fun `TPD04 positive bounded minutes valid kind combinations and metadata namespaces are enforced`() {
        val valid = F.constraint()
        for (minutes in listOf(1, 10080)) {
            val expression = "${F.NAME}에서 ${minutes}분 이내인 곳."
            val raw = "${F.TIME} $expression"
            val declared = F.constraint(raw, expression).copy(maxMinutes = minutes)
            assertEquals(minutes, validate(listOf(declared), raw).single().maxMinutes)
        }
        val invalid =
            listOf(
                valid.copy(maxMinutes = null),
                valid.copy(maxMinutes = 0),
                valid.copy(maxMinutes = -1),
                valid.copy(maxMinutes = 10081),
                valid.copy(maxMinutes = 46),
                valid.copy(maxMinutes = 90),
                valid.copy(travelMode = ScheduleTravelMode.TRANSIT),
                valid.copy(kind = SchedulePlaceConstraintKind.MEETING_PLACE),
                valid.copy(id = "half"),
                valid.copy(id = "root"),
                valid.copy(id = ""),
            )
        invalid.forEach { constraint -> assertFailsWith<IllegalArgumentException> { validate(listOf(constraint)) } }
        assertFailsWith<IllegalArgumentException> { validate(listOf(valid, valid)) }
        // This input already has one time claim; graph nodes have their own separate limit.
        val thirtyOnePlaces = (1..31).map { valid.copy(id = "travel-$it") }
        assertEquals(thirtyOnePlaces, validate(thirtyOnePlaces))
        assertFailsWith<IllegalArgumentException> { validate((1..32).map { valid.copy(id = "travel-$it") }) }
        val unresolved =
            valid.copy(
                kind = SchedulePlaceConstraintKind.UNRESOLVED,
                maxMinutes = null,
                timeScope = SchedulePlaceTimeScope.UNRESOLVED_TIME_LINK,
            )
        assertEquals(listOf(unresolved), validate(listOf(unresolved)))
    }

    @Test
    fun `TPD05 unverified travel preserves exact time overlap and affects only that participant in person`() {
        val envelope = F.base().copy(placeConstraints = listOf(F.constraint()), placeConstraintsVersion = SCHEDULE_PLACE_CONSTRAINT_VERSION)
        val result = ScheduleDslStructuralCalculator.calculate(envelope, F.snapshot(), F.policy, MeetingMode.EITHER)
        val first = ParticipantId(F.firstParticipant)
        val second = ParticipantId(F.secondParticipant)
        val byMode = result.preparedByMode
        val inPerson = byMode.getValue(MeetingMode.IN_PERSON).participants.associateBy { it.participantId }
        val remote = byMode.getValue(MeetingMode.REMOTE).participants.associateBy { it.participantId }
        assertEquals(OfflinePlaceAssessment.UNVERIFIED, inPerson.getValue(first).offlinePlaceAssessment)
        assertTrue(inPerson.getValue(second).offlinePlaceAssessment != OfflinePlaceAssessment.UNVERIFIED)
        assertTrue(remote.getValue(first).offlinePlaceAssessment != OfflinePlaceAssessment.UNVERIFIED)
        assertEquals(remote.getValue(first).availableTimes, inPerson.getValue(first).availableTimes)
        val overlap = InstantTimeRange(Instant.parse("2027-01-08T05:00:00Z"), Instant.parse("2027-01-08T09:00:00Z"))
        assertEquals(listOf(overlap), result.recommendations.options.map { it.window })
        val variants =
            result.recommendations.options
                .single()
                .variants
                .associateBy { it.meetingMode }
        val offline = variants.getValue(MeetingMode.IN_PERSON)
        assertEquals(setOf(first, second), offline.participantIds.toSet())
        assertEquals(listOf(first), offline.unverifiedPlaceParticipantIds)
        assertNull(offline.representativePlace)
        assertNull(offline.representativeArea)
        assertTrue(variants.getValue(MeetingMode.REMOTE).unverifiedPlaceParticipantIds.isEmpty())
    }

    @Test
    fun `TPD06 unresolved time link cannot promote affected participant time to confirmed in any mode`() {
        val unresolved = F.constraint().copy(timeScope = SchedulePlaceTimeScope.UNRESOLVED_TIME_LINK)
        val envelope = F.base().copy(placeConstraints = listOf(unresolved), placeConstraintsVersion = SCHEDULE_PLACE_CONSTRAINT_VERSION)
        val result = ScheduleDslStructuralCalculator.calculate(envelope, F.snapshot(), F.policy, MeetingMode.EITHER)
        assertEquals(listOf(unresolved), envelope.placeConstraints)
        result.preparedByMode.values.forEach { prepared ->
            assertTrue(
                prepared.participants
                    .single { it.participantId == ParticipantId(F.firstParticipant) }
                    .availableTimes
                    .isEmpty(),
            )
            assertTrue(
                prepared.participants
                    .single { it.participantId == ParticipantId(F.secondParticipant) }
                    .availableTimes
                    .isNotEmpty(),
            )
        }
        assertTrue(
            result.recommendations.options
                .flatMap { it.variants }
                .none { ParticipantId(F.firstParticipant) in it.participantIds },
        )
    }

    @Test
    fun `TPD07 one explicit duration expression supports unit conversion while independent durations cannot be silently chosen`() {
        for ((duration, minutes) in listOf("1시간30분" to 90, "한시간" to 60, "두 시간" to 120, "45분" to 45)) {
            val expression = "${F.NAME}에서 $duration 이내인 곳."
            val raw = "${F.TIME} $expression"
            val constraint = F.constraint(raw, expression).copy(maxMinutes = minutes)
            assertEquals(listOf(constraint), validate(listOf(constraint), raw))
        }
        for (duration in listOf("30분 또는 45분", "한참", "1시간과 30분")) {
            val expression = "${F.NAME}에서 $duration 이내인 곳."
            val raw = "${F.TIME} $expression"
            val constraint = F.constraint(raw, expression)
            assertFailsWith<IllegalArgumentException> { validate(listOf(constraint), raw) }
            val unresolved = constraint.copy(kind = SchedulePlaceConstraintKind.UNRESOLVED, maxMinutes = null)
            assertEquals(listOf(unresolved), validate(listOf(unresolved), raw))
        }
    }
}
