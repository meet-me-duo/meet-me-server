package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.RecommendationProjector
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ProposedParticipantScheduleProgram
import com.meetme.server.submission.domain.dsl.SCHEDULE_AST_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslNode
import com.meetme.server.submission.domain.dsl.ScheduleDslOperator
import com.meetme.server.submission.domain.dsl.ScheduleDslProgram
import com.meetme.server.submission.domain.dsl.ScheduleDslProposal
import com.meetme.server.submission.domain.dsl.ScheduleDslValidator
import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.ProposedIrEvidence
import com.meetme.server.submission.domain.ir.ProposedIrV2Condition
import com.meetme.server.submission.domain.ir.ProposedIrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrV2Proposal
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.submission.domain.ir.IrEffect as Effect

/** Independent typed weekly support oracle. Sources remain unmodified NON_PRECISE declarations. */
class WeeklyScheduleCalculationTest {
    private val first = ParticipantId(ScheduleIrV2SyntheticFixture.firstParticipant)
    private val peer = ParticipantId(ScheduleIrV2SyntheticFixture.secondParticipant)

    @Test
    fun `WKC01 all known weekly occurrences become precise support without rewriting native scope`() {
        val f = Fixture()
        val envelope = f.valid(listOf(f.weekly()), f.program(listOf(f.ref("wr", "w")), "wr"))
        val actual = project(f, envelope, f.overrides())
        assertEquals(f.support(), person(actual).availableTimes)
        assertEquals(f.support(), actual.evidence.getValue(first).possibleWindows)
        assertFalse(actual.evidence.getValue(first).unresolvedTime)
        val original =
            envelope.claims.results
                .first()
                .conditions
                .single()
        assertNull(original.scope)
        assertEquals(IrTemporalResolution.NON_PRECISE, original.temporalResolution)
        assertEquals(listOf(f.search()), actual.participants.single { it.participantId == peer }.availableTimes)
    }

    @Test
    fun `WKC02 missing weekly override stays unknown but present empty override is resolved empty`() {
        val f = Fixture()
        val envelope = f.valid(listOf(f.weekly()), f.program(listOf(f.ref("wr", "w")), "wr"))
        val unknown = project(f, envelope)
        assertTrue(person(unknown).availableTimes.isEmpty())
        assertTrue(unknown.evidence.getValue(first).unresolvedTime)
        assertEquals(listOf(f.search()), unknown.evidence.getValue(first).possibleWindows)
        val empty = project(f, envelope, mapOf((f.ir.first to "w") to emptyList()))
        assertTrue(person(empty).availableTimes.isEmpty())
        assertTrue(
            empty.evidence
                .getValue(first)
                .possibleWindows
                .isEmpty(),
        )
        assertFalse(empty.evidence.getValue(first).unresolvedTime)
    }

    @Test
    fun `WKC03 ANY OF unions repeated windows and a separate dated alternative`() {
        val f = Fixture()
        val fixed = f.fixed("a", "2056-01-01T10:00", "2056-01-01T11:00")
        val nodes = listOf(f.ref("wr", "w"), f.ref("ar", "a"), f.node("root", ScheduleDslOperator.ANY_OF, "wr", "ar"))
        val actual = project(f, f.valid(listOf(f.weekly(), fixed), f.program(nodes, "root")), f.overrides())
        assertEquals(listOf(f.support()[0], f.dated("2056-01-01T10:00", "2056-01-01T11:00"), f.support()[1]), person(actual).availableTimes)
    }

    @Test
    fun `WKC04 ALL OF intersects each weekly occurrence against its local dated condition`() {
        val f = Fixture()
        val fixed = f.fixed("a", "2055-12-31T20:00", "2055-12-31T22:00")
        val nodes = listOf(f.ref("wr", "w"), f.ref("ar", "a"), f.node("root", ScheduleDslOperator.ALL_OF, "wr", "ar"))
        val actual = project(f, f.valid(listOf(f.weekly(), fixed), f.program(nodes, "root")), f.overrides())
        assertEquals(listOf(f.dated("2055-12-31T20:00", "2055-12-31T21:00")), person(actual).availableTimes)
    }

    @Test
    fun `WKC05 local EXCEPT cannot become a global block on another alternative`() {
        val f = Fixture()
        val claims =
            listOf(
                f.weekly(),
                f.fixed("b", "2055-12-31T20:00", "2055-12-31T21:00", Effect.UNAVAILABLE),
                f.fixed("a", "2055-12-31T20:00", "2055-12-31T22:00"),
            )
        val nodes =
            listOf(
                f.ref("wr", "w"),
                f.ref("br", "b"),
                f.ref("ar", "a"),
                f.node("except", ScheduleDslOperator.EXCEPT, "wr", "br"),
                f.node("root", ScheduleDslOperator.ANY_OF, "except", "ar"),
            )
        val local = project(f, f.valid(claims, f.program(nodes, "root")), f.overrides())
        assertEquals(listOf(f.dated("2055-12-31T19:00", "2055-12-31T22:00"), f.support()[1]), person(local).availableTimes)
        val global = project(f, f.valid(claims, f.program(nodes, "root", "br")), f.overrides())
        assertEquals(
            listOf(f.dated("2055-12-31T19:00", "2055-12-31T20:00"), f.dated("2055-12-31T21:00", "2055-12-31T22:00"), f.support()[1]),
            person(global).availableTimes,
        )
    }

    @Test
    fun `WKC06 global dated negative subtracts only the affected repeated occurrence`() {
        val f = Fixture()
        val b = f.fixed("b", "2056-01-07T20:00", "2056-01-07T21:00", Effect.UNAVAILABLE)
        val envelope = f.valid(listOf(f.weekly(), b), f.program(listOf(f.ref("wr", "w"), f.ref("br", "b")), "wr", "br"))
        assertEquals(
            listOf(f.support()[0], f.dated("2056-01-07T19:00", "2056-01-07T20:00")),
            person(project(f, envelope, f.overrides())).availableTimes,
        )
    }

    @Test
    fun `WKC07 repeated UNAVAILABLE removes only its known windows from a complete base`() {
        val f = Fixture()
        val base = f.fixed("a", "2055-12-25T00:00", "2056-01-09T00:00")
        val envelope =
            f.valid(
                listOf(base, f.weekly(effect = Effect.UNAVAILABLE)),
                f.program(listOf(f.ref("ar", "a"), f.ref("wr", "w")), "ar", "wr"),
            )
        val actual = project(f, envelope, f.overrides())
        assertEquals(
            listOf(
                f.dated("2055-12-25T00:00", "2055-12-31T19:00"),
                f.dated("2055-12-31T21:00", "2056-01-07T19:00"),
                f.dated("2056-01-07T21:00", "2056-01-09T00:00"),
            ),
            person(actual).availableTimes,
        )
        assertFalse(actual.evidence.getValue(first).unresolvedTime)
        val empty = project(f, envelope, mapOf((f.ir.first to "w") to emptyList()))
        assertEquals(listOf(f.search()), person(empty).availableTimes)
        assertFalse(empty.evidence.getValue(first).unresolvedTime)
    }

    @Test
    fun `WKC08 preferred only repeats are eligible with zero preference bonus`() {
        val f = Fixture()
        val envelope = f.valid(listOf(f.weekly(effect = Effect.PREFERRED)), f.program(listOf(f.ref("wr", "w")), null, prefer = "wr"))
        val actual = project(f, envelope, f.overrides())
        assertEquals(f.support(), person(actual).availableTimes)
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
        assertFalse(actual.evidence.getValue(first).unresolvedTime)
    }

    @Test
    fun `WKC09 mixed independent preferred repeats retain every occurrence and one credit per person`() {
        val f = Fixture()
        val base = f.fixed("a", "2055-12-31T10:00", "2055-12-31T11:00")
        val envelope =
            f.valid(
                listOf(base, f.weekly(effect = Effect.PREFERRED)),
                f.program(listOf(f.ref("ar", "a"), f.ref("wr", "w")), "ar", prefer = "wr"),
            )
        val actual = project(f, envelope, f.overrides())
        assertEquals(listOf(f.dated("2055-12-31T10:00", "2055-12-31T11:00")) + f.support(), person(actual).availableTimes)
        assertEquals(f.support(), person(actual).explicitPreferences.timeRanges)
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, actual.participants, ZoneId.of("Asia/Seoul")).options
        assertEquals(listOf(1, 1), options.filter { it.window in f.support() }.map { it.variants.single().preferenceCount })
    }

    @Test
    fun `WKC10 scoped PREFER cannot add support outside a weekly base`() {
        val f = Fixture()
        val p = f.fixed("p", "2056-01-01T10:00", "2056-01-01T11:00", Effect.PREFERRED)
        val nodes = listOf(f.ref("wr", "w"), f.ref("pr", "p"), f.node("root", ScheduleDslOperator.PREFER, "wr", "pr"))
        val actual = project(f, f.valid(listOf(f.weekly(), p), f.program(nodes, "root")), f.overrides())
        assertEquals(f.support(), person(actual).availableTimes)
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
    }

    @Test
    fun `WKC11 CHOICE selects a repeated branch once and never selects an unknown choice implicitly`() {
        val f = Fixture()
        val a = f.weekly("w").copy(alternativeGroup = "option", alternative = "alpha")
        val b = f.weekly("v").copy(alternativeGroup = "option", alternative = "beta")
        val choice =
            f.node("root", ScheduleDslOperator.CHOICE, "wr", "vr").copy(
                choiceGroup = "option",
                branchLabels = listOf("alpha", "beta"),
            )
        val envelope = f.valid(listOf(a, b), f.program(listOf(f.ref("wr", "w"), f.ref("vr", "v"), choice), "root"))
        val second = listOf(f.dated("2056-01-01T11:00", "2056-01-01T12:00"))
        val overrides = f.overrides() + ((f.ir.first to "v") to second)
        val unknown = project(f, envelope, overrides)
        assertTrue(person(unknown).availableTimes.isEmpty())
        assertTrue(unknown.evidence.getValue(first).unresolvedTime)
        val selected =
            ScheduleDslInputPolicy.projectWithUnionPreferredAvailability(
                envelope,
                f.policy.copy(alternativeChoices = listOf(IrAlternativeChoice(f.ir.first, "option", "beta"))),
                overrides,
            )
        assertEquals(second, person(selected).availableTimes)
        assertFalse(selected.evidence.getValue(first).unresolvedTime)
    }

    @Test
    fun `WKC12 explicit global correction retires every occurrence of the original condition ID`() {
        val f = Fixture()
        val replacement = f.fixed("new", "2056-01-01T10:00", "2056-01-01T11:00").copy(supersedes = listOf("w"))
        val envelope = f.valid(listOf(f.weekly(), replacement), f.program(listOf(f.ref("nr", "new")), "nr", history = listOf("w")))
        val actual = project(f, envelope, f.overrides())
        assertEquals(listOf(f.dated("2056-01-01T10:00", "2056-01-01T11:00")), person(actual).availableTimes)
        assertFalse(actual.evidence.getValue(first).unresolvedTime)
        assertEquals(
            IrConditionDisposition.SUPERSEDED,
            actual.evidence
                .getValue(first)
                .conditions
                .single { it.conditionId == "w" }
                .disposition,
        )
    }

    @Test
    fun `WKC13 inferred correction cannot erase resolved weekly negatives or poison independent base`() {
        val f = Fixture()
        val base = f.fixed("a", "2055-12-25T00:00", "2056-01-09T00:00")
        val estimate =
            f.fixed("estimate", "2055-12-31T20:00", "2055-12-31T21:00").copy(
                interpretation = IrInterpretation.INFERRED,
                supersedes = listOf("w"),
            )
        val nodes = listOf(f.ref("ar", "a"), f.ref("er", "estimate"), f.node("root", ScheduleDslOperator.ANY_OF, "ar", "er"))
        val envelope =
            f.valid(
                listOf(base, f.weekly(effect = Effect.UNAVAILABLE), estimate),
                f.program(nodes, "root", history = listOf("w")),
            )
        val actual =
            ScheduleDslInputPolicy.projectWithUnionPreferredAvailability(
                envelope,
                f.policy.copy(eligibleInterpretations = setOf(IrInterpretation.EXPLICIT, IrInterpretation.INFERRED)),
                f.overrides(),
            )
        assertEquals(
            listOf(
                f.dated("2055-12-25T00:00", "2055-12-31T19:00"),
                f.dated("2055-12-31T21:00", "2056-01-07T19:00"),
                f.dated("2056-01-07T21:00", "2056-01-09T00:00"),
            ),
            person(actual).availableTimes,
        )
        assertTrue(actual.evidence.getValue(first).unresolvedTime)
        assertEquals(
            IrConditionDisposition.WITHHELD,
            actual.evidence
                .getValue(first)
                .conditions
                .single { it.conditionId == "estimate" }
                .disposition,
        )
    }

    @Test
    fun `WKC14 mode preparation forwards the same weekly map into structural union projection`() {
        val f = Fixture()
        val envelope = f.valid(listOf(f.weekly()), f.program(listOf(f.ref("wr", "w")), "wr"))
        val actual =
            ScheduleDslContextModeCalculator.prepareProjection(
                envelope,
                envelope,
                emptyList(),
                emptySet(),
                f.snapshot,
                f.policy,
                MeetingMode.REMOTE,
                structuralPolicy = true,
                knownWeeklyWindows = f.overrides(),
            )
        assertEquals(f.support(), person(actual.preparedByMode.getValue(MeetingMode.REMOTE)).availableTimes)
    }

    @Test
    fun `WKC15 numeric 31 day limit cannot be bypassed by a known weekly window map`() {
        val f = Fixture(days = 31)
        val valid = f.valid(listOf(f.weekly()), f.program(listOf(f.ref("wr", "w")), "wr"))
        val extendedContext = valid.context.copy(searchEnd = valid.context.searchStart.plusDays(32))
        val oversized = valid.copy(claims = valid.claims.copy(context = extendedContext))
        assertEquals(
            32L,
            java.time.temporal.ChronoUnit.DAYS
                .between(oversized.context.searchStart, oversized.context.searchEnd),
        )
        assertFailsWith<IllegalArgumentException> { project(f, oversized, f.overrides()) }
    }

    private fun project(
        f: Fixture,
        envelope: ScheduleDslEnvelope,
        windows: Map<Pair<java.util.UUID, String>, List<InstantTimeRange>> = emptyMap(),
    ) = ScheduleDslInputPolicy.projectWithUnionPreferredAvailability(envelope, f.policy, windows)

    private fun person(actual: ScheduleDslPreparedInputs) = actual.participants.single { it.participantId == first }

    private class Fixture(
        date: String = "2055-12-25",
        val days: Long = 15,
        zone: String = "Asia/Seoul",
        source: String = "Synthetic jade provenance.",
    ) {
        val ir = ScheduleIrV2SyntheticFixture
        private val day = LocalDate.parse(date)
        private val timeZone = ZoneId.of(zone)
        private val texts = listOf(source, "Synthetic independent peer provenance.")
        val policy = ScheduleDslStructuralMatchingPolicy.union()
        val snapshot =
            ir.snapshot().let { old ->
                val bindings =
                    old.context.inputs.mapIndexed { index, input ->
                        val text = texts[index]
                        input.copy(
                            originalRevision = if (index == 0) 3 else 5,
                            contentSha256 = IrEvidenceBindings.sha256("frozen synthetic input $index:$text"),
                            sources =
                                listOf(
                                    input.sources.single().copy(
                                        revision =
                                            if (index ==
                                                0
                                            ) {
                                                3
                                            } else {
                                                5
                                            },
                                        codePointLength = text.codePointCount(0, text.length),
                                        contentSha256 = IrEvidenceBindings.sha256(text),
                                    ),
                                ),
                        )
                    }
                ScheduleEvidenceSnapshot(
                    old.context.copy(
                        timeZone = zone,
                        searchStart = day,
                        searchEnd = day.plusDays(days),
                        policyVersion = policy.version,
                        inputs = bindings,
                    ),
                    bindings
                        .mapIndexed { index, input ->
                            input.submissionVersionId to
                                mapOf("original" to texts[index])
                        }.toMap(),
                )
            }

        fun datedScope(
            start: String,
            end: String,
        ) = IrScope(IrDimension.TIME, java.time.LocalDateTime.parse(start), java.time.LocalDateTime.parse(end))

        fun dated(
            start: String,
            end: String,
        ) = InstantTimeRange(
            java.time.LocalDateTime
                .parse(start)
                .atZone(timeZone)
                .toInstant(),
            java.time.LocalDateTime
                .parse(end)
                .atZone(timeZone)
                .toInstant(),
        )

        fun fixed(
            id: String,
            start: String,
            end: String,
            effect: Effect = Effect.AVAILABLE,
        ) = claim(id, "00:00", "01:00", effect).copy(scope = datedScope(start, end))

        fun weekly(
            id: String = "w",
            effect: Effect = Effect.AVAILABLE,
        ) = claim(id, "00:00", "01:00", effect).copy(scope = null, temporalResolution = IrTemporalResolution.NON_PRECISE)

        fun support() =
            listOf(
                dated("2055-12-31T19:00", "2055-12-31T21:00"),
                dated("2056-01-07T19:00", "2056-01-07T21:00"),
            )

        fun overrides(id: String = "w") = mapOf((ir.first to id) to support())

        fun proof(index: Int = 0) =
            ProposedIrEvidence(
                "original",
                if (index ==
                    0
                ) {
                    3
                } else {
                    5
                },
                0,
                texts[index].codePointCount(0, texts[index].length),
                texts[index],
            )

        fun scope(
            start: String,
            end: String,
        ) = IrScope(IrDimension.TIME, day.atTime(LocalTime.parse(start)), day.atTime(LocalTime.parse(end)))

        fun window(
            start: String,
            end: String,
        ) = InstantTimeRange(
            day.atTime(LocalTime.parse(start)).atZone(timeZone).toInstant(),
            day.atTime(LocalTime.parse(end)).atZone(timeZone).toInstant(),
        )

        fun search() = InstantTimeRange(day.atStartOfDay(timeZone).toInstant(), day.plusDays(days).atStartOfDay(timeZone).toInstant())

        fun claim(
            id: String,
            start: String,
            end: String,
            effect: Effect = Effect.AVAILABLE,
        ) = ProposedIrV2Condition(
            id,
            effect,
            IrInterpretation.EXPLICIT,
            IrTemporalResolution.PRECISE_INTERVAL,
            scope(start, end),
            listOf(proof()),
        )

        fun node(
            id: String,
            operator: ScheduleDslOperator,
            vararg children: String,
        ) = ScheduleDslNode(
            id,
            operator,
            null,
            children.toList(),
            null,
            emptyList(),
            IrInterpretation.EXPLICIT,
            IrAssertion.ASSERTED,
            listOf(proof()),
        )

        fun ref(
            id: String,
            claim: String,
        ) = node(id, ScheduleDslOperator.CLAIM_REF).copy(claimId = claim)

        fun program(
            nodes: List<ScheduleDslNode<ProposedIrEvidence>>,
            allow: String?,
            block: String? = null,
            prefer: String? = null,
            history: List<String> = emptyList(),
        ) = ScheduleDslProgram(nodes, allow, block, prefer, history)

        fun simple(
            available: ProposedIrV2Condition,
            preferred: ProposedIrV2Condition,
        ) = valid(listOf(available, preferred), program(listOf(ref("ar", available.id), ref("pr", preferred.id)), "ar", prefer = "pr"))

        fun valid(
            claims: List<ProposedIrV2Condition>,
            program: ScheduleDslProgram<ProposedIrEvidence>,
        ) = decode(claims, program).also {
            assertTrue(it.claims.results.all { result -> result.quarantined.isEmpty() })
            assertTrue(it.programs.all { result -> result.program != null && result.problems.isEmpty() })
        }

        fun decode(
            claims: List<ProposedIrV2Condition>,
            program: ScheduleDslProgram<ProposedIrEvidence>,
        ): ScheduleDslEnvelope {
            val peer =
                ProposedIrV2Condition(
                    "peer",
                    Effect.AVAILABLE,
                    IrInterpretation.EXPLICIT,
                    IrTemporalResolution.PRECISE_INTERVAL,
                    IrScope(IrDimension.TIME, day.atStartOfDay(), day.plusDays(days).atStartOfDay()),
                    listOf(proof(1)),
                )
            return ScheduleDslValidator().validate(
                ScheduleDslProposal(
                    SCHEDULE_DSL_SEMANTIC_VERSION,
                    SCHEDULE_AST_VERSION,
                    ScheduleIrV2Proposal(
                        SCHEDULE_IR_V2_VERSION,
                        listOf(ProposedIrV2Result(ir.first, claims), ProposedIrV2Result(ir.second, listOf(peer))),
                    ),
                    listOf(
                        ProposedParticipantScheduleProgram(ir.first, program),
                        ProposedParticipantScheduleProgram(
                            ir.second,
                            this.program(listOf(ref("peer-r", "peer").copy(evidence = listOf(proof(1)))), "peer-r"),
                        ),
                    ),
                ),
                snapshot,
            )
        }
    }
}
