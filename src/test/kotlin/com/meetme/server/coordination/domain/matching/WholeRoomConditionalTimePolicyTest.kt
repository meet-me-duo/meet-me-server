package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Accepted A policy: a single full-room primary decision precedes every subset calculation. */
class WholeRoomConditionalTimePolicyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val primary = listOf(window(4, "14:00", "18:00"), window(6, "10:00", "12:00"))
    private val fallback = window(7, "11:00", "15:00")
    private val conditional = participant(1, primary).copy(explicitPreferences = ParticipantPreferences(timeRanges = primary))

    @ParameterizedTest
    @ValueSource(strings = ["S1", "S2", "S3", "S4", "S5", "S6"])
    fun `A policy preserves the six accepted legacy and modern matching examples`(scenario: String) {
        val fixture = scenario(scenario)
        val original = fixture.inputs.toList()
        val resolved = WholeRoomConditionalTimePolicy.resolve(fixture.inputs, mapOf(id(1) to listOf(fallback)))

        assertTrue(resolved.unsupportedParticipantIds.isEmpty())
        assertEquals(original, fixture.inputs, "Resolution must not mutate the original primary inputs")
        val actualConditional = resolved.participants.single { it.participantId == id(1) }
        assertEquals(conditional.explicitPreferences, actualConditional.explicitPreferences)
        assertEquals(
            if (scenario == "S1") primary else primary + fallback,
            actualConditional.availableTimes,
        )
        assertEquals(fixture.inputs.drop(1), resolved.participants.filter { it.participantId != id(1) })

        val legacy = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, resolved.participants)
        assertEquals(if (scenario == "S5") MatchOutcome.NO_MATCH else MatchOutcome.READY, legacy.outcome)
        assertEquals(fixture.legacy, legacy.candidates.map { Legacy(it.participantIds, it.timeRanges) })
        val projection = RecommendationProjector.generate(MeetingMode.REMOTE, resolved.participants, zone)
        val selected = projection.primaryOptionIndexes.map { projection.options[it] }
        assertEquals(
            fixture.modern,
            selected.map { Modern(it.window, it.variants.first().participantIds, it.variants.first().preferenceCount) },
        )
        if (scenario == "S1") {
            assertFalse(
                projection.options.any { option ->
                    option.window == fallback && option.variants.any { id(1) in it.participantIds }
                },
                "Thirty minutes of full-room primary availability keeps the fallback closed, without a one-hour threshold",
            )
        }
        if (scenario == "S3") {
            assertTrue(
                projection.options
                    .single { it.window == fallback }
                    .variants
                    .any { it.participantIds == ids(1, 2, 3) },
                "An existing primary subset must not re-close the fallback after the full-room primary failed",
            )
        }
    }

    @Test
    fun `participant order cannot change the gate or either matcher result`() {
        for (name in listOf("S1", "S2", "S3", "S4", "S5", "S6")) {
            val inputs = scenario(name).inputs
            val reference = WholeRoomConditionalTimePolicy.resolve(inputs, mapOf(id(1) to listOf(fallback)))
            val legacy = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, reference.participants)
            val modern = RecommendationProjector.generate(MeetingMode.REMOTE, reference.participants, zone)
            for (permutation in permutations(inputs)) {
                val actual = WholeRoomConditionalTimePolicy.resolve(permutation, mapOf(id(1) to listOf(fallback)))
                assertEquals(reference.participants.associateBy { it.participantId }, actual.participants.associateBy { it.participantId })
                assertEquals(legacy, DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, actual.participants), name)
                assertEquals(modern, RecommendationProjector.generate(MeetingMode.REMOTE, actual.participants, zone), name)
            }
        }
    }

    @Test
    fun `known empty primary differs from unknown primary`() {
        val ordinary = participant(2, emptyList())
        val inputs = listOf(conditional, ordinary)
        val known = WholeRoomConditionalTimePolicy.resolve(inputs, mapOf(id(1) to listOf(fallback)))
        assertTrue(known.unsupportedParticipantIds.isEmpty())
        assertEquals(primary + fallback, known.participants.first().availableTimes)

        val unknown = WholeRoomConditionalTimePolicy.resolve(inputs, mapOf(id(1) to listOf(fallback)), primaryInputsKnown = false)
        assertEquals(setOf(id(1)), unknown.unsupportedParticipantIds)
        assertTrue(
            unknown.participants
                .first()
                .availableTimes
                .isEmpty(),
        )
        assertFalse(
            unknown.participants
                .first()
                .explicitPreferences.canScore,
        )
        assertEquals(
            primary,
            unknown.participants
                .first()
                .explicitPreferences.timeRanges,
        )
        assertEquals(ordinary, unknown.participants.last())
    }

    @Test
    fun `multiple conditional attendees cannot create a chain or cycle and ordinary inputs remain intact`() {
        val second =
            participant(2, listOf(window(6, "10:00", "11:00"))).copy(
                explicitPreferences = ParticipantPreferences(areaKeys = setOf("AREA_1")),
            )
        val ordinary = participant(3, listOf(fallback))
        val result =
            WholeRoomConditionalTimePolicy.resolve(
                listOf(conditional, second, ordinary),
                mapOf(id(1) to listOf(fallback), id(2) to listOf(fallback)),
            )
        assertEquals(setOf(id(1), id(2)), result.unsupportedParticipantIds)
        for (input in result.participants.filter { it.participantId in result.unsupportedParticipantIds }) {
            assertTrue(input.availableTimes.isEmpty())
            assertFalse(input.explicitPreferences.canScore)
        }
        assertEquals(ordinary, result.participants.single { it.participantId == id(3) })
        assertEquals(
            primary,
            result.participants
                .first()
                .explicitPreferences.timeRanges,
        )
        assertEquals(setOf("AREA_1"), result.participants[1].explicitPreferences.areaKeys)
    }

    @Test
    fun `old flat inputs are unchanged even when no conditional primary knowledge is available`() {
        val inputs = scenario("S1").inputs
        val result = WholeRoomConditionalTimePolicy.resolve(inputs, emptyMap(), primaryInputsKnown = false)
        assertEquals(inputs, result.participants)
        assertTrue(result.unsupportedParticipantIds.isEmpty())
    }

    private fun scenario(name: String): Scenario {
        val wed30 = window(4, "14:00", "14:30")
        val wed60 = window(4, "14:00", "15:00")
        val fri = window(6, "10:00", "12:00")
        val fri45 = window(6, "10:00", "10:45")
        val thu20 = window(5, "16:00", "16:20")
        return when (name) {
            "S1" ->
                Scenario(
                    listOf(conditional) + (2L..4L).map { participant(it, listOf(wed30, fallback)) },
                    listOf(Legacy(ids(1, 2, 3, 4), listOf(wed30))),
                    listOf(Modern(wed30, ids(1, 2, 3, 4), 1), Modern(fallback, ids(2, 3, 4), 0)),
                )
            "S2" ->
                Scenario(
                    listOf(
                        conditional,
                        participant(2, listOf(wed60, fallback)),
                        participant(3, listOf(wed60, fallback)),
                        participant(4, listOf(fri, fallback)),
                    ),
                    listOf(Legacy(ids(1, 2, 3, 4), listOf(fallback))),
                    listOf(Modern(fallback, ids(1, 2, 3, 4), 0), Modern(wed60, ids(1, 2, 3), 1), Modern(fri, ids(1, 4), 1)),
                )
            "S3" ->
                Scenario(
                    listOf(
                        conditional,
                        participant(2, listOf(wed30, fri, fallback)),
                        participant(3, listOf(wed30, fallback)),
                        participant(4, listOf(fri)),
                    ),
                    listOf(Legacy(ids(1, 2, 3), listOf(wed30, fallback))),
                    listOf(Modern(wed30, ids(1, 2, 3), 1), Modern(fri, ids(1, 2, 4), 1), Modern(fallback, ids(1, 2, 3), 0)),
                )
            "S4" ->
                Scenario(
                    listOf(
                        conditional,
                        participant(2, listOf(wed30, fallback)),
                        participant(3, listOf(fri45)),
                        participant(4, listOf(thu20)),
                    ),
                    listOf(Legacy(ids(1, 2), listOf(wed30, fallback))),
                    listOf(Modern(wed30, ids(1, 2), 1), Modern(fri45, ids(1, 3), 1), Modern(fallback, ids(1, 2), 0)),
                )
            "S5" ->
                Scenario(
                    listOf(
                        conditional,
                        participant(2, listOf(window(5, "10:00", "11:00"))),
                        participant(3, listOf(window(6, "13:00", "14:00"))),
                        participant(4, listOf(window(7, "16:00", "17:00"))),
                    ),
                    emptyList(),
                    emptyList(),
                )
            "S6" -> {
                val first = window(7, "11:00", "11:20")
                val second = window(7, "11:20", "12:00")
                Scenario(
                    listOf(conditional, participant(2, listOf(first)), participant(3, listOf(second)), participant(4, listOf(thu20))),
                    listOf(Legacy(ids(1, 3), listOf(second))),
                    listOf(Modern(first, ids(1, 2), 0), Modern(second, ids(1, 3), 0)),
                )
            }
            else -> error("Unknown accepted fixture $name")
        }
    }

    private fun participant(
        number: Long,
        times: List<InstantTimeRange>,
    ) = ParticipantMatchInput(id(number), times)

    private fun id(number: Long) = ParticipantId(UUID(0, number))

    private fun ids(vararg numbers: Long) = numbers.map(::id)

    private fun window(
        day: Int,
        start: String,
        end: String,
    ) = InstantTimeRange(
        LocalDateTime.parse("2026-11-${day.toString().padStart(2, '0')}T$start").atZone(zone).toInstant(),
        LocalDateTime.parse("2026-11-${day.toString().padStart(2, '0')}T$end").atZone(zone).toInstant(),
    )

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { value -> permutations(values - value).map { listOf(value) + it } }

    private data class Legacy(
        val participants: List<ParticipantId>,
        val windows: List<InstantTimeRange>,
    )

    private data class Modern(
        val window: InstantTimeRange,
        val participants: List<ParticipantId>,
        val preferenceCount: Int,
    )

    private data class Scenario(
        val inputs: List<ParticipantMatchInput>,
        val legacy: List<Legacy>,
        val modern: List<Modern>,
    )
}
