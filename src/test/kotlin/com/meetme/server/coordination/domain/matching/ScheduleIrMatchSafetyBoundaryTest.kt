package com.meetme.server.coordination.domain.matching

import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrAvailabilityState
import com.meetme.server.submission.domain.ir.IrCondition
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInputBinding
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrQuarantine
import com.meetme.server.submission.domain.ir.IrResult
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleIrContext
import com.meetme.server.submission.domain.ir.ScheduleIrEnvelope
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Independent safety oracles for inactive scenario calculation, never public attendance or confirmation. */
class ScheduleIrMatchSafetyBoundaryTest {
    private val a = UUID(0, 801)
    private val b = UUID(0, 802)
    private val pa = ParticipantId(UUID(0, 901))
    private val pb = ParticipantId(UUID(0, 902))
    private val friday = scope("2027-01-08T18:00:00", "2027-01-08T21:00:00")
    private val middle = scope("2027-01-08T19:00:00", "2027-01-08T20:00:00")
    private val raw = "합성 일정 조건. 가능, 불가, 미정, 장소와 해석 대안을 각각 보존한다."

    @ParameterizedTest
    @ValueSource(strings = ["UNAVAILABLE:INFERRED", "UNAVAILABLE:UNRESOLVED", "UNRESOLVED:INFERRED", "UNRESOLVED:UNRESOLVED"])
    fun `S01 withholding a negative interpretation never opens its affected interval`(combination: String) {
        val (effectName, labelName) = combination.split(':')
        val effect = IrEffect.valueOf(effectName)
        val label = IrInterpretation.valueOf(labelName)
        val negative = condition("negative", effect, label, middle)
        val input = envelope(listOf(available(), negative))
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        assertEquals(
            listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z"), instant("2027-01-08T11:00:00Z", "2027-01-08T12:00:00Z")),
            windows(prepared, pa),
        )
        assertEquals(listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T12:00:00Z")), windows(prepared, pb))
        val retained =
            prepared.evidence
                .getValue(pa)
                .conditions
                .single { it.conditionId == "negative" }
        assertEquals(label, retained.interpretation)
        assertEquals(middle, retained.scope)
        if (effect == IrEffect.UNRESOLVED) assertTrue(prepared.evidence.getValue(pa).unresolvedTime)
        assertEquals(
            negative,
            input.results
                .first()
                .conditions
                .last(),
        )
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `S02 eligible unresolved effect remains an exclusion with unresolved time evidence`(label: IrInterpretation) {
        val negative = condition("unknown-time", IrEffect.UNRESOLVED, label, middle)
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT, label), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(available(), negative)), policy)

        assertEquals(
            listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T10:00:00Z"), instant("2027-01-08T11:00:00Z", "2027-01-08T12:00:00Z")),
            windows(prepared, pa),
        )
        assertTrue(prepared.evidence.getValue(pa).unresolvedTime)
        assertEquals(
            label,
            prepared.evidence
                .getValue(pa)
                .conditions
                .single { it.conditionId == "unknown-time" }
                .interpretation,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["NONE", "EARLY", "LATE"])
    fun `S03 negative alternatives protect both possible scopes until one branch is explicitly selected`(choice: String) {
        val choices = if (choice == "NONE") emptyList() else listOf(IrAlternativeChoice(a, "clock", choice))
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), choices, false)

        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(available()) + negativeAlternatives()), policy)

        val expected =
            when (choice) {
                "EARLY" -> instant("2027-01-08T10:00:00Z", "2027-01-08T12:00:00Z")
                "LATE" -> instant("2027-01-08T09:00:00Z", "2027-01-08T11:00:00Z")
                else -> instant("2027-01-08T10:00:00Z", "2027-01-08T11:00:00Z")
            }
        assertEquals(listOf(expected), windows(prepared, pa))
        val evidence = prepared.evidence.getValue(pa)
        assertEquals(
            setOf("early", "late"),
            evidence.conditions
                .filter { it.alternativeGroup == "clock" }
                .map { it.conditionId }
                .toSet(),
        )
        if (choice == "NONE") {
            assertTrue(evidence.unresolvedTime)
        } else {
            val unselected = if (choice == "EARLY") "late" else "early"
            assertEquals(IrConditionDisposition.UNSELECTED_BRANCH, evidence.conditions.single { it.conditionId == unselected }.disposition)
        }
    }

    @Test
    fun `S04 identical alternative group names in different frozen versions have independent selections`() {
        val input = envelope(listOf(available()) + negativeAlternatives(), listOf(available()) + negativeAlternatives())
        val policy =
            IrMatchingPolicy(
                "safety-synthetic-1",
                setOf(IrInterpretation.EXPLICIT),
                listOf(IrAlternativeChoice(a, "clock", "EARLY"), IrAlternativeChoice(b, "clock", "LATE")),
                false,
            )

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        assertEquals(listOf(instant("2027-01-08T10:00:00Z", "2027-01-08T12:00:00Z")), windows(prepared, pa))
        assertEquals(listOf(instant("2027-01-08T09:00:00Z", "2027-01-08T11:00:00Z")), windows(prepared, pb))
        assertEquals(a, prepared.evidence.getValue(pa).submissionVersionId)
        assertEquals(b, prepared.evidence.getValue(pb).submissionVersionId)
        assertEquals(
            IrConditionDisposition.UNSELECTED_BRANCH,
            prepared.evidence
                .getValue(pa)
                .conditions
                .single { it.conditionId == "late" }
                .disposition,
        )
        assertEquals(
            IrConditionDisposition.UNSELECTED_BRANCH,
            prepared.evidence
                .getValue(pb)
                .conditions
                .single { it.conditionId == "early" }
                .disposition,
        )
    }

    @Test
    fun `S05 valid place only uncertainty preserves independent remote times and its unsupported dimension`() {
        val place = condition("place-unknown", IrEffect.UNRESOLVED, IrInterpretation.UNRESOLVED, IrScope(IrDimension.PLACE))
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(available(), place)), policy)

        val expected = instant("2027-01-08T09:00:00Z", "2027-01-08T12:00:00Z")
        assertEquals(listOf(expected), windows(prepared, pa))
        val evidence = prepared.evidence.getValue(pa)
        assertFalse(evidence.unresolvedTime)
        assertEquals(setOf(IrDimension.PLACE), evidence.unsupportedDimensions)
        assertEquals(place.scope, evidence.conditions.single { it.conditionId == "place-unknown" }.scope)
        val candidate = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).candidates.single()
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
        assertEquals(listOf(pa, pb), candidate.participantIds)
        assertEquals(listOf(expected), candidate.timeRanges)
    }

    @Test
    fun `S06 forged bounded quarantine cannot authorize precision outside its claimed scope`() {
        val input =
            envelope(listOf(available())).let { original ->
                original.copy(
                    results =
                        original.results.map { result ->
                            if (result.submissionVersionId == a) {
                                result.copy(quarantined = listOf(IrQuarantine("bad-source", IrProblem.EVIDENCE, middle)))
                            } else {
                                result
                            }
                        },
                )
            }
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        assertRejectedOrUnresolved(input, policy, setOf(pa))
    }

    @ParameterizedTest
    @ValueSource(strings = ["GAP_START", "GAP_END", "OVERLAP_START", "OVERLAP_END"])
    fun `S07 invalid New York local endpoints are never shifted or resolved by default offset`(transition: String) {
        val invalid =
            when (transition) {
                "GAP_START" -> scope("2027-03-14T02:30:00", "2027-03-14T04:00:00")
                "GAP_END" -> scope("2027-03-14T01:30:00", "2027-03-14T02:30:00")
                "OVERLAP_START" -> scope("2027-11-07T01:30:00", "2027-11-07T03:00:00")
                else -> scope("2027-11-07T00:30:00", "2027-11-07T01:30:00")
            }
        val input =
            envelope(listOf(condition("invalid-endpoint", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, invalid)))
                .withSearch("America/New_York", if (transition.startsWith("GAP")) "2027-03-14" else "2027-11-07")
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)

        assertRejectedOrUnresolved(input, policy, setOf(pa))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MIDNIGHT_GAP", "MIDNIGHT_OVERLAP"])
    fun `S08 ambiguous search midnight is not silently normalized into an unrestricted search interval`(transition: String) {
        val gap = transition == "MIDNIGHT_GAP"
        val date = if (gap) "2018-11-04" else "2026-11-01"
        val wholeDay =
            if (gap) {
                scope("2018-11-04T00:00:00", "2018-11-05T00:00:00")
            } else {
                scope("2026-11-01T00:00:00", "2026-11-02T00:00:00")
            }
        val unconstrained = condition("whole-search", IrEffect.UNCONSTRAINED, IrInterpretation.EXPLICIT, wholeDay)
        val input =
            envelope(listOf(unconstrained), listOf(unconstrained))
                .withSearch(if (gap) "America/Sao_Paulo" else "America/Havana", date)
                .let { it.copy(results = it.results.map { result -> result.copy(availability = IrAvailabilityState.UNCONSTRAINED) }) }
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), true)

        assertRejectedOrUnresolved(input, policy, setOf(pa, pb))
    }

    @ParameterizedTest
    @ValueSource(strings = ["SPRING", "AUTUMN"])
    fun `S09 valid endpoints across a DST transition retain the actual elapsed duration`(season: String) {
        val spring = season == "SPRING"
        val local =
            if (spring) {
                scope("2027-03-14T01:30:00", "2027-03-14T03:30:00")
            } else {
                scope("2027-11-07T00:30:00", "2027-11-07T02:30:00")
            }
        val input =
            envelope(listOf(condition("crossing", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, local)))
                .withSearch("America/New_York", if (spring) "2027-03-14" else "2027-11-07")
        val policy = IrMatchingPolicy("safety-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), false)
        // EST -05 to EDT -04 in spring; EDT -04 to EST -05 in autumn. No production time helper is an oracle.
        val expected =
            if (spring) {
                instant("2027-03-14T06:30:00Z", "2027-03-14T07:30:00Z")
            } else {
                instant("2027-11-07T04:30:00Z", "2027-11-07T07:30:00Z")
            }

        val prepared = ScheduleIrMatchInputPolicy.project(input, policy)

        assertEquals(listOf(expected), windows(prepared, pa))
        val actual = windows(prepared, pa).single()
        assertEquals(if (spring) 60L else 180L, Duration.between(actual.startInclusive, actual.endExclusive).toMinutes())
        assertFalse(prepared.evidence.getValue(pa).unresolvedTime)
    }

    /** Invalid input may be rejected, or retained with global uncertainty; accepting a narrower scope is forbidden. */
    private fun assertRejectedOrUnresolved(
        input: ScheduleIrEnvelope,
        policy: IrMatchingPolicy,
        affected: Set<ParticipantId>,
    ) {
        val prepared =
            try {
                ScheduleIrMatchInputPolicy.project(input, policy)
            } catch (_: IllegalArgumentException) {
                return
            }
        assertEquals(setOf(pa, pb), prepared.participants.map { it.participantId }.toSet())
        for (participant in affected) {
            assertTrue(windows(prepared, participant).isEmpty(), "Unsafe scope must not yield a calculated available interval")
            assertTrue(prepared.evidence.getValue(participant).unresolvedTime)
        }
    }

    private fun negativeAlternatives(): List<IrCondition> =
        listOf(
            condition("early", IrEffect.UNAVAILABLE, IrInterpretation.EXPLICIT, scope("2027-01-08T18:00:00", "2027-01-08T19:00:00"))
                .copy(alternativeGroup = "clock", alternative = "EARLY"),
            condition("late", IrEffect.UNAVAILABLE, IrInterpretation.EXPLICIT, scope("2027-01-08T20:00:00", "2027-01-08T21:00:00"))
                .copy(alternativeGroup = "clock", alternative = "LATE"),
        )

    private fun available(): IrCondition = condition("available", IrEffect.AVAILABLE, IrInterpretation.EXPLICIT, friday)

    private fun condition(
        id: String,
        effect: IrEffect,
        label: IrInterpretation,
        scope: IrScope?,
    ): IrCondition =
        IrCondition(
            id,
            effect,
            label,
            scope,
            listOf(IrEvidenceSpan("original", 3, 0, raw.codePointCount(0, raw.length), hash(raw))),
            IrAssertion.ASSERTED,
            emptyList(),
            null,
            null,
        )

    private fun envelope(
        left: List<IrCondition>,
        right: List<IrCondition> = listOf(available()),
    ): ScheduleIrEnvelope {
        val sources =
            listOf(
                IrSourceBinding(
                    "original",
                    IrSourceKind.ORIGINAL,
                    3,
                    raw.codePointCount(0, raw.length),
                    hash(raw),
                    Instant.parse("2018-01-01T00:00:00Z"),
                ),
            )
        val context =
            ScheduleIrContext(
                UUID(0, 811),
                UUID(0, 812),
                UUID(0, 813),
                "Asia/Seoul",
                LocalDate.parse("2027-01-04"),
                LocalDate.parse("2027-01-11"),
                "safety-synthetic-1",
                listOf(
                    IrInputBinding(a, pa.value, 7, 3, hash("synthetic-input-a"), sources),
                    IrInputBinding(b, pb.value, 7, 3, hash("synthetic-input-b"), sources),
                ),
            )
        return ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            context,
            listOf(
                IrResult(a, IrAvailabilityState.KNOWN_WINDOWS, left, emptyList()),
                IrResult(b, IrAvailabilityState.KNOWN_WINDOWS, right, emptyList()),
            ),
        )
    }

    private fun ScheduleIrEnvelope.withSearch(
        zone: String,
        start: String,
    ): ScheduleIrEnvelope =
        copy(context = context.copy(timeZone = zone, searchStart = LocalDate.parse(start), searchEnd = LocalDate.parse(start).plusDays(1)))

    private fun windows(
        prepared: IrPreparedMatchingInputs,
        participant: ParticipantId,
    ): List<InstantTimeRange> = prepared.participants.single { it.participantId == participant }.availableTimes

    private fun scope(
        start: String,
        end: String,
    ): IrScope = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    private fun instant(
        start: String,
        end: String,
    ): InstantTimeRange = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
