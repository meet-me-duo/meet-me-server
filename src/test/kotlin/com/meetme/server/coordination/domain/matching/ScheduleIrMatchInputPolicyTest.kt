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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Independent inactive technical-policy tests; no publication/confirmation policy is selected. */
class ScheduleIrMatchInputPolicyTest {
    private val a = UUID(0, 101)
    private val b = UUID(0, 102)
    private val pa = ParticipantId(UUID(0, 301))
    private val pb = ParticipantId(UUID(0, 302))
    private val friday = scope("2027-01-08T18:00:00", "2027-01-08T21:00:00")
    private val peerFriday = scope("2027-01-08T19:00:00", "2027-01-08T22:00:00")
    private val saturday = scope("2027-01-09T00:00:00", "2027-01-10T00:00:00")
    private val entireSearch = scope("2027-01-04T00:00:00", "2027-01-11T00:00:00")
    private val raw = "금요일 18시부터 21시 가능. 토요일 미정. 오전 또는 오후. 정정 및 인용은 별도로 유지."
    private val instantFriday = instant("2027-01-08T09:00:00Z", "2027-01-08T12:00:00Z")
    private val commonFriday = instant("2027-01-08T10:00:00Z", "2027-01-08T12:00:00Z")

    @Test
    fun `I01 explicit remote intervals reach both unchanged deterministic matchers without invented duration`() {
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(), policy())

        assertEquals(setOf(pa, pb), prepared.participants.map { it.participantId }.toSet())
        assertEquals(listOf(instantFriday), prepared.participants.single { it.participantId == pa }.availableTimes)
        val candidates = DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants)
        assertEquals(MatchOutcome.READY, candidates.outcome)
        assertEquals(1, candidates.candidates.size)
        val candidate = candidates.candidates.single()
        assertEquals(PlanType.B, candidate.planType)
        assertEquals(MeetingMode.REMOTE, candidate.meetingMode)
        assertEquals(listOf(pa, pb), candidate.participantIds)
        assertEquals(listOf(commonFriday), candidate.timeRanges)
        val recommendations = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul"))
        assertEquals(listOf(0), recommendations.primaryOptionIndexes)
        assertEquals(1, recommendations.options.size)
        assertEquals(commonFriday, recommendations.options.single().window)
        val variant =
            recommendations.options
                .single()
                .variants
                .single()
        assertEquals(MeetingMode.REMOTE, variant.meetingMode)
        assertEquals(listOf(pa, pb), variant.participantIds)
        assertTrue(variant.unverifiedPlaceParticipantIds.isEmpty())
    }

    @Test
    fun `I05 bounded saturday uncertainty preserves independent friday and cannot certify saturday`() {
        val unresolved = condition("sat-unknown", IrEffect.UNRESOLVED, saturday, IrInterpretation.UNRESOLVED)
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(condition(), unresolved)), policy())

        assertEquals(listOf(instantFriday), windows(prepared, pa))
        assertEquals(
            listOf(
                commonFriday,
            ),
            RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul")).options.map {
                it.window
            },
        )
    }

    @Test
    fun `I06 untrusted exception scope has global influence even with independent valid friday evidence`() {
        val bad = IrQuarantine("corrupt-negative", IrProblem.EVIDENCE, null)
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(quarantine = listOf(bad)), policy())

        assertTrue(windows(prepared, pa).isEmpty())
        assertEquals(listOf(instant("2027-01-08T10:00:00Z", "2027-01-08T13:00:00Z")), windows(prepared, pb))
        assertEquals(MatchOutcome.NO_MATCH, DeterministicCandidateMatcher.generate(MeetingMode.REMOTE, prepared.participants).outcome)
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `I11 eligibility is an explicit choice and never rewrites interpretation or declares confirmation`(label: IrInterpretation) {
        val original = envelope(listOf(condition(label = label)))
        val before = original.copy()
        val restrictive = ScheduleIrMatchInputPolicy.project(original, policy())
        val eligible = ScheduleIrMatchInputPolicy.project(original, policy(labels = setOf(IrInterpretation.EXPLICIT, label)))

        assertEquals(if (label == IrInterpretation.EXPLICIT) listOf(instantFriday) else emptyList(), windows(restrictive, pa))
        assertEquals(listOf(instantFriday), windows(eligible, pa))
        assertEquals(before, original)
        assertEquals(
            label,
            original.results
                .first()
                .conditions
                .single()
                .interpretation,
        )
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, original.results.first().availability)
    }

    @ParameterizedTest
    @ValueSource(strings = ["AM", "PM"])
    fun `I12 exclusive branch selection certifies only the chosen branch and never their union`(choice: String) {
        val am = scope("2027-01-08T07:00:00", "2027-01-08T08:00:00")
        val pm = scope("2027-01-08T19:00:00", "2027-01-08T20:00:00")
        val left =
            listOf(
                condition("am", window = am).copy(alternativeGroup = "clock", alternative = "AM"),
                condition("pm", window = pm).copy(alternativeGroup = "clock", alternative = "PM"),
            )
        val selected =
            ScheduleIrMatchInputPolicy.project(
                envelope(left, state = IrAvailabilityState.UNKNOWN),
                policy(choices = listOf(IrAlternativeChoice(a, "clock", choice))),
            )
        val expected =
            if (choice ==
                "AM"
            ) {
                instant("2027-01-07T22:00:00Z", "2027-01-07T23:00:00Z")
            } else {
                instant("2027-01-08T10:00:00Z", "2027-01-08T11:00:00Z")
            }

        assertEquals(listOf(expected), windows(selected, pa))
    }

    @Test
    fun `I13 unselected exclusive group stays unresolved without throwing or deleting independent friday`() {
        val am =
            condition(
                "am",
                window = scope("2027-01-09T07:00:00", "2027-01-09T08:00:00"),
            ).copy(alternativeGroup = "clock", alternative = "AM")
        val pm =
            condition(
                "pm",
                window = scope("2027-01-09T19:00:00", "2027-01-09T20:00:00"),
            ).copy(alternativeGroup = "clock", alternative = "PM")
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(condition(), am, pm)), policy())

        assertEquals(listOf(instantFriday), windows(prepared, pa))
    }

    @ParameterizedTest
    @ValueSource(strings = ["foreign_input", "unknown_group", "unknown_alternative", "duplicate_key"])
    fun `I30 foreign unknown and duplicate explicit choices are rejected instead of silently ignored`(defect: String) {
        val left =
            listOf(
                condition("am").copy(alternativeGroup = "clock", alternative = "AM"),
                condition("pm").copy(alternativeGroup = "clock", alternative = "PM"),
            )
        val good = IrAlternativeChoice(a, "clock", "AM")
        val choices =
            when (defect) {
                "foreign_input" -> listOf(IrAlternativeChoice(UUID(0, 999), "clock", "AM"))
                "unknown_group" -> listOf(IrAlternativeChoice(a, "missing", "AM"))
                "unknown_alternative" -> listOf(IrAlternativeChoice(a, "clock", "NIGHT"))
                else -> listOf(good, good)
            }

        assertFailsWith<RuntimeException> { ScheduleIrMatchInputPolicy.project(envelope(left), policy(choices = choices)) }
    }

    @ParameterizedTest
    @EnumSource(IrAvailabilityState::class)
    fun `I15 I16 I17 all availability states remain structurally distinct rather than sharing an empty-list shortcut`(
        state: IrAvailabilityState,
    ) {
        val conditions =
            when (state) {
                IrAvailabilityState.EMPTY -> listOf(condition(effect = IrEffect.UNAVAILABLE, window = entireSearch))
                IrAvailabilityState.UNCONSTRAINED -> listOf(condition(effect = IrEffect.UNCONSTRAINED, window = entireSearch))
                IrAvailabilityState.KNOWN_WINDOWS -> listOf(condition())
                IrAvailabilityState.UNKNOWN -> emptyList()
            }
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(conditions, state), policy(unconstrained = true))
        val expected =
            when (state) {
                IrAvailabilityState.KNOWN_WINDOWS -> listOf(instantFriday)
                IrAvailabilityState.UNCONSTRAINED -> listOf(instant("2027-01-03T15:00:00Z", "2027-01-10T15:00:00Z"))
                else -> emptyList()
            }

        assertEquals(expected, windows(prepared, pa))
        assertEquals(setOf(pa, pb), prepared.participants.map { it.participantId }.toSet())
    }

    @Test
    fun `I17 explicit unconstrained evaluation requires its own explicit permission`() {
        val source = envelope(listOf(condition(effect = IrEffect.UNCONSTRAINED, window = entireSearch)), IrAvailabilityState.UNCONSTRAINED)
        assertTrue(windows(ScheduleIrMatchInputPolicy.project(source, policy(unconstrained = false)), pa).isEmpty())
    }

    @Test
    fun `I18 outside search interval is clipped without making the participant unrestricted`() {
        val outside = condition(window = scope("2027-02-01T18:00:00", "2027-02-01T20:00:00"))
        assertTrue(windows(ScheduleIrMatchInputPolicy.project(envelope(listOf(outside)), policy()), pa).isEmpty())
    }

    @Test
    fun `I20 asserted correction removes old authority while retaining the corrected range`() {
        val old = condition("old", window = scope("2027-01-08T18:00:00", "2027-01-08T20:00:00"))
        val corrected =
            condition(
                "corrected",
                window = scope("2027-01-08T20:00:00", "2027-01-08T22:00:00"),
            ).copy(supersedes = listOf("old"))
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(old, corrected)), policy())
        assertEquals(listOf(instant("2027-01-08T11:00:00Z", "2027-01-08T13:00:00Z")), windows(prepared, pa))
    }

    @ParameterizedTest
    @EnumSource(value = IrAssertion::class, names = ["RETRACTED", "QUOTED_OTHER"])
    fun `I21 I22 nonasserted availability is retained history rather than participant authority`(assertion: IrAssertion) {
        val original = envelope(listOf(condition().copy(assertion = assertion)), IrAvailabilityState.UNKNOWN)
        assertTrue(windows(ScheduleIrMatchInputPolicy.project(original, policy()), pa).isEmpty())
        assertEquals(
            assertion,
            original.results
                .first()
                .conditions
                .single()
                .assertion,
        )
    }

    @Test
    fun `I05 I06 evidence distinguishes bounded unresolved condition from global untrusted quarantine`() {
        val unresolved = condition("sat-unknown", IrEffect.UNRESOLVED, saturday, IrInterpretation.UNRESOLVED)
        val bounded = ScheduleIrMatchInputPolicy.project(envelope(listOf(condition(), unresolved)), policy()).evidence.getValue(pa)
        val global =
            ScheduleIrMatchInputPolicy
                .project(
                    envelope(quarantine = listOf(IrQuarantine("bad", IrProblem.EVIDENCE, null))),
                    policy(),
                ).evidence
                .getValue(pa)

        assertTrue(bounded.unresolvedTime)
        assertEquals(0, bounded.quarantineCount)
        assertEquals(saturday, bounded.conditions.single { it.conditionId == "sat-unknown" }.scope)
        assertEquals(IrInterpretation.UNRESOLVED, bounded.conditions.single { it.conditionId == "sat-unknown" }.interpretation)
        assertTrue(global.unresolvedTime)
        assertEquals(1, global.quarantineCount)
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, global.declaredAvailability)
        assertEquals(a, global.submissionVersionId)
        assertEquals(pa, global.participantId)
    }

    @Test
    fun `I20 an asserted correction withheld by label never resurrects superseded explicit authority`() {
        val old = condition("old")
        val correction =
            condition(
                "new",
                window = scope("2027-01-08T20:00:00", "2027-01-08T22:00:00"),
                label = IrInterpretation.INFERRED,
            ).copy(supersedes = listOf("old"))
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(old, correction)), policy())

        assertTrue(windows(prepared, pa).isEmpty())
        val dispositions =
            prepared.evidence
                .getValue(pa)
                .conditions
                .associate { it.conditionId to it.disposition }
        assertEquals(IrConditionDisposition.SUPERSEDED, dispositions.getValue("old"))
        assertEquals(IrConditionDisposition.WITHHELD, dispositions.getValue("new"))
    }

    @ParameterizedTest
    @EnumSource(value = IrAssertion::class, names = ["RETRACTED", "QUOTED_OTHER"])
    fun `I23 nonasserted correction cannot remove independent original asserted availability`(assertion: IrAssertion) {
        val correction = condition("nonasserted", effect = IrEffect.UNAVAILABLE).copy(assertion = assertion, supersedes = listOf("fri"))
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(condition(), correction)), policy())

        assertEquals(listOf(instantFriday), windows(prepared, pa))
        assertEquals(
            IrConditionDisposition.EXCLUDED_ASSERTION,
            prepared.evidence
                .getValue(pa)
                .conditions
                .single {
                    it.conditionId ==
                        "nonasserted"
                }.disposition,
        )
    }

    @Test
    fun `I13 an unselected branch correction cannot supersede an independent selected fact`() {
        val am =
            condition(
                "am",
                window = scope("2027-01-09T07:00:00", "2027-01-09T08:00:00"),
            ).copy(alternativeGroup = "clock", alternative = "AM")
        val pm =
            condition(
                "pm",
                window = scope("2027-01-09T19:00:00", "2027-01-09T20:00:00"),
            ).copy(alternativeGroup = "clock", alternative = "PM", supersedes = listOf("fri"))
        val prepared =
            ScheduleIrMatchInputPolicy.project(
                envelope(listOf(condition(), am, pm)),
                policy(choices = listOf(IrAlternativeChoice(a, "clock", "AM"))),
            )

        assertEquals(listOf(instantFriday, instant("2027-01-08T22:00:00Z", "2027-01-08T23:00:00Z")), windows(prepared, pa))
        assertEquals(
            IrConditionDisposition.UNSELECTED_BRANCH,
            prepared.evidence
                .getValue(pa)
                .conditions
                .single {
                    it.conditionId == "pm"
                }.disposition,
        )
    }

    @Test
    fun `declared UNKNOWN does not erase positive evidence but remains unresolved in calculation evidence`() {
        val original = envelope(state = IrAvailabilityState.UNKNOWN)
        val prepared = ScheduleIrMatchInputPolicy.project(original, policy())
        assertEquals(listOf(instantFriday), windows(prepared, pa))
        assertEquals(IrAvailabilityState.UNKNOWN, prepared.evidence.getValue(pa).declaredAvailability)
        assertTrue(prepared.evidence.getValue(pa).unresolvedTime)
    }

    @Test
    fun `withheld preference evidence cannot erase independent available windows or award preference credit`() {
        val preference = condition("preference", effect = IrEffect.PREFERRED, label = IrInterpretation.INFERRED)
        val prepared = ScheduleIrMatchInputPolicy.project(envelope(listOf(condition(), preference)), policy())
        assertEquals(listOf(instantFriday), windows(prepared, pa))
        assertEquals(
            IrConditionDisposition.WITHHELD,
            prepared.evidence
                .getValue(pa)
                .conditions
                .single {
                    it.conditionId == "preference"
                }.disposition,
        )
        val projection = RecommendationProjector.generate(MeetingMode.REMOTE, prepared.participants, ZoneId.of("Asia/Seoul"))
        assertEquals(
            0,
            projection.options
                .single()
                .variants
                .single()
                .preferenceCount,
        )
    }

    private fun policy(
        labels: Set<IrInterpretation> = setOf(IrInterpretation.EXPLICIT),
        choices: List<IrAlternativeChoice> = emptyList(),
        unconstrained: Boolean = false,
    ) = IrMatchingPolicy("synthetic-policy-1", labels, choices, unconstrained)

    private fun windows(
        prepared: IrPreparedMatchingInputs,
        participant: ParticipantId,
    ) = prepared.participants
        .single {
            it.participantId ==
                participant
        }.availableTimes

    private fun condition(
        id: String = "fri",
        effect: IrEffect = IrEffect.AVAILABLE,
        window: IrScope? = friday,
        label: IrInterpretation = IrInterpretation.EXPLICIT,
    ) = IrCondition(
        id,
        effect,
        label,
        window,
        listOf(IrEvidenceSpan("original", 3, 0, raw.codePointCount(0, raw.length), hash(raw))),
        IrAssertion.ASSERTED,
        emptyList(),
        null,
        null,
    )

    private fun envelope(
        left: List<IrCondition> = listOf(condition()),
        state: IrAvailabilityState = IrAvailabilityState.KNOWN_WINDOWS,
        quarantine: List<IrQuarantine> = emptyList(),
    ): ScheduleIrEnvelope {
        val sources =
            listOf(
                IrSourceBinding(
                    "original",
                    IrSourceKind.ORIGINAL,
                    3,
                    raw.codePointCount(0, raw.length),
                    hash(raw),
                    Instant.parse("2027-01-04T00:00:00Z"),
                ),
            )
        val context =
            ScheduleIrContext(
                UUID(0, 201),
                UUID(0, 202),
                UUID(0, 203),
                "Asia/Seoul",
                LocalDate.parse("2027-01-04"),
                LocalDate.parse("2027-01-11"),
                "synthetic-policy-1",
                listOf(
                    IrInputBinding(a, pa.value, 7, 3, hash("synthetic-a"), sources),
                    IrInputBinding(b, pb.value, 7, 3, hash("synthetic-b"), sources),
                ),
            )
        return ScheduleIrEnvelope(
            SCHEDULE_IR_VERSION,
            context,
            listOf(
                IrResult(a, state, left, quarantine),
                IrResult(b, IrAvailabilityState.KNOWN_WINDOWS, listOf(condition(window = peerFriday)), emptyList()),
            ),
        )
    }

    private fun scope(
        start: String,
        end: String,
    ) = IrScope(IrDimension.TIME, LocalDateTime.parse(start), LocalDateTime.parse(end))

    private fun instant(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
