package com.meetme.server.coordination.domain.dsl

import com.meetme.server.coordination.domain.matching.IrAlternativeChoice
import com.meetme.server.coordination.domain.matching.IrConditionDisposition
import com.meetme.server.coordination.domain.matching.IrMatchingPolicy
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
import com.meetme.server.submission.domain.ir.IrProblem
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.meetme.server.submission.domain.ir.IrEffect as Effect

/** Independent typed semantics, including held-out date/time/source variants; no language/model accuracy oracle. */
class ScheduleDslUnionPreferredAvailabilityTest {
    private val first = ParticipantId(ScheduleIrV2SyntheticFixture.firstParticipant)
    private val peer = ParticipantId(ScheduleIrV2SyntheticFixture.secondParticipant)
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @ParameterizedTest
    @CsvSource(
        "2034-05-09,UTC,07:20,08:05,12:10,13:40,Synthetic azure provenance.",
        "2034-05-10,Asia/Tokyo,20:15,22:45,02:05,04:25,Synthetic bronze provenance.",
        "2034-05-11,UTC,00:10,00:55,23:00,23:50,🙂 Synthetic quartz provenance.",
    )
    fun `UPA01 held out independent precise preferences add eligibility and mixed credit without changing old policy`(
        date: String,
        zone: String,
        aStart: String,
        aEnd: String,
        pStart: String,
        pEnd: String,
        source: String,
    ) {
        val f = Fixture(date, zone, source)
        val envelope = f.simple(f.claim("a", aStart, aEnd), f.claim("p", pStart, pEnd, Effect.PREFERRED))
        val frozen = mapper.writeValueAsString(envelope)
        val old = ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy)
        val actual = project(envelope, f.policy)
        val expected = listOf(f.window(aStart, aEnd), f.window(pStart, pEnd)).sortedBy { it.startInclusive }
        assertEquals(expected, person(actual).availableTimes)
        assertEquals(expected, actual.evidence.getValue(first).possibleWindows)
        assertEquals(listOf(f.window(pStart, pEnd)), person(actual).explicitPreferences.timeRanges)
        assertEquals(listOf(f.window(aStart, aEnd)), person(old).availableTimes)
        assertTrue(person(old).explicitPreferences.timeRanges.isEmpty())
        assertEquals(old, ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy))
        assertEquals(frozen, mapper.writeValueAsString(envelope))
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, actual.participants, ZoneId.of(zone)).options
        assertEquals(
            mapOf(f.window(aStart, aEnd) to 0, f.window(pStart, pEnd) to 1),
            options.associate { it.window to it.variants.single().preferenceCount },
        )
        assertPeer(actual, f)
    }

    @Test
    fun `UPA02 overlap is normalized and global hard negatives subtract from both supports and all credit`() {
        val f = Fixture()
        val claims =
            listOf(
                f.claim("a", "10:00", "13:00"),
                f.claim("p", "12:00", "15:00", Effect.PREFERRED),
                f.claim("b", "12:30", "13:30", Effect.UNAVAILABLE),
            )
        val envelope = f.valid(claims, f.program(listOf(f.ref("ar", "a"), f.ref("pr", "p"), f.ref("br", "b")), "ar", "br", "pr"))
        val actual = project(envelope, f.policy)
        assertEquals(listOf(f.window("10:00", "12:30"), f.window("13:30", "15:00")), person(actual).availableTimes)
        assertEquals(listOf(f.window("12:00", "12:30"), f.window("13:30", "15:00")), person(actual).explicitPreferences.timeRanges)
        assertEquals(person(actual).availableTimes, actual.evidence.getValue(first).possibleWindows)
    }

    @Test
    fun `UPA03 preference only remains eligible with zero bonus and preserves declared preferred meaning`() {
        val f = Fixture()
        val envelope =
            f.valid(
                listOf(f.claim("p", "08:10", "09:40", Effect.PREFERRED)),
                f.program(listOf(f.ref("pr", "p")), null, prefer = "pr"),
            )
        val actual = project(envelope, f.policy)
        assertEquals(listOf(f.window("08:10", "09:40")), person(actual).availableTimes)
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
        assertEquals(
            Effect.PREFERRED,
            envelope.claims.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertTrue(
            RecommendationProjector
                .generate(
                    MeetingMode.REMOTE,
                    actual.participants,
                    ZoneId.of("UTC"),
                ).options
                .flatMap { it.variants }
                .all {
                    it.preferenceCount ==
                        0
                },
        )
    }

    @Test
    fun `UPA04 unknown availability cannot expand known preference or manufacture a mixed bonus`() {
        val f = Fixture()
        val unknown = f.claim("a", "10:00", "12:00").copy(scope = null, temporalResolution = IrTemporalResolution.NON_PRECISE)
        val actual = project(f.simple(unknown, f.claim("p", "15:10", "16:20", Effect.PREFERRED)), f.policy)
        assertEquals(listOf(f.window("15:10", "16:20")), person(actual).availableTimes)
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
        assertTrue(actual.evidence.getValue(first).unresolvedTime)
        assertEquals(listOf(f.search()), actual.evidence.getValue(first).possibleWindows)
    }

    @Test
    fun `UPA05 unknown preference cannot provide definite availability or credit outside a known allow`() {
        val f = Fixture()
        val unknown = f.claim("p", "13:00", "16:00", Effect.PREFERRED).copy(temporalResolution = IrTemporalResolution.NON_PRECISE)
        val actual = project(f.simple(f.claim("a", "06:10", "07:20"), unknown), f.policy)
        assertEquals(listOf(f.window("06:10", "07:20")), person(actual).availableTimes)
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(f.window("06:10", "07:20"), f.window("13:00", "16:00")), actual.evidence.getValue(first).possibleWindows)
        assertTrue(actual.evidence.getValue(first).unresolvedTime)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `UPA06 unknown global negative upper bound protects either a bounded scope or the entire search`(unbounded: Boolean) {
        val f = Fixture()
        val negative =
            f.claim("b", "11:00", "12:00", Effect.UNRESOLVED).copy(
                temporalResolution = IrTemporalResolution.NON_PRECISE,
                scope = if (unbounded) null else f.scope("11:00", "12:00"),
            )
        val envelope =
            f.valid(
                listOf(f.claim("a", "06:00", "07:00"), f.claim("p", "10:00", "13:00", Effect.PREFERRED), negative),
                f.program(listOf(f.ref("ar", "a"), f.ref("pr", "p"), f.ref("br", "b")), "ar", "br", "pr"),
            )
        val actual = project(envelope, f.policy)
        val windows =
            if (unbounded) {
                emptyList()
            } else {
                listOf(
                    f.window("06:00", "07:00"),
                    f.window("10:00", "11:00"),
                    f.window("12:00", "13:00"),
                )
            }
        assertEquals(windows, person(actual).availableTimes)
        assertEquals(
            if (unbounded) emptyList() else listOf(f.window("10:00", "11:00"), f.window("12:00", "13:00")),
            person(actual).explicitPreferences.timeRanges,
        )
        assertEquals(listOf(f.window("06:00", "07:00"), f.window("10:00", "13:00")), actual.evidence.getValue(first).possibleWindows)
        assertTrue(actual.evidence.getValue(first).unresolvedTime)
    }

    @Test
    fun `UPA07 mixed bonus depends on definite allow before global exclusion rather than a surviving allow window`() {
        val f = Fixture()
        val claims =
            listOf(
                f.claim("a", "06:00", "07:00"),
                f.claim("p", "17:00", "18:00", Effect.PREFERRED),
                f.claim("b", "06:00", "07:00", Effect.UNAVAILABLE),
            )
        val actual =
            project(f.valid(claims, f.program(listOf(f.ref("ar", "a"), f.ref("pr", "p"), f.ref("br", "b")), "ar", "br", "pr")), f.policy)
        assertEquals(listOf(f.window("17:00", "18:00")), person(actual).availableTimes)
        assertEquals(listOf(f.window("17:00", "18:00")), person(actual).explicitPreferences.timeRanges)
    }

    @Test
    fun `UPA08 scoped PREFER remains base limited and is never flattened into an independent preference root`() {
        val f = Fixture()
        val claims = listOf(f.claim("a", "09:00", "11:00"), f.claim("p", "10:00", "14:00", Effect.PREFERRED))
        val program =
            f.program(
                listOf(f.ref("ar", "a"), f.ref("pr", "p"), f.node("scoped", ScheduleDslOperator.PREFER, "ar", "pr")),
                "scoped",
            )
        val envelope = f.valid(claims, program)
        val actual = project(envelope, f.policy)
        assertEquals(listOf(f.window("09:00", "11:00")), person(actual).availableTimes)
        assertEquals(listOf(f.window("10:00", "11:00")), person(actual).explicitPreferences.timeRanges)
        assertEquals(ScheduleDslInputPolicy.projectWithPreferredAvailability(envelope, f.policy), actual)
    }

    @Test
    fun `UPA09 independent preference ALL OF remains an intersection rather than a flat claim union`() {
        val f = Fixture()
        val claims =
            listOf(
                f.claim("a", "06:00", "07:00"),
                f.claim("p1", "10:00", "14:00", Effect.PREFERRED),
                f.claim("p2", "12:00", "16:00", Effect.PREFERRED),
            )
        val program =
            f.program(
                listOf(f.ref("ar", "a"), f.ref("r1", "p1"), f.ref("r2", "p2"), f.node("both", ScheduleDslOperator.ALL_OF, "r1", "r2")),
                "ar",
                prefer = "both",
            )
        val actual = project(f.valid(claims, program), f.policy)
        assertEquals(listOf(f.window("06:00", "07:00"), f.window("12:00", "14:00")), person(actual).availableTimes)
        assertEquals(listOf(f.window("12:00", "14:00")), person(actual).explicitPreferences.timeRanges)
    }

    @Test
    fun `UPA10 local EXCEPT keeps local meaning while an exclusion root remains globally authoritative`() {
        val f = Fixture()
        val claims =
            listOf(
                f.claim("a", "09:00", "17:00"),
                f.claim("p", "12:00", "14:00", Effect.PREFERRED),
                f.claim("b", "11:00", "13:00", Effect.UNAVAILABLE),
            )
        val nodes = listOf(f.ref("ar", "a"), f.ref("pr", "p"), f.ref("br", "b"), f.node("except", ScheduleDslOperator.EXCEPT, "ar", "br"))
        val local = project(f.valid(claims, f.program(nodes, "except", prefer = "pr")), f.policy)
        assertEquals(listOf(f.window("09:00", "11:00"), f.window("12:00", "17:00")), person(local).availableTimes)
        assertEquals(listOf(f.window("12:00", "14:00")), person(local).explicitPreferences.timeRanges)
        val global = project(f.valid(claims, f.program(nodes, "except", "br", "pr")), f.policy)
        assertEquals(listOf(f.window("09:00", "11:00"), f.window("13:00", "17:00")), person(global).availableTimes)
        assertEquals(listOf(f.window("13:00", "14:00")), person(global).explicitPreferences.timeRanges)
    }

    @Test
    fun `UPA11 a preference choice adds only its selected branch and unresolved choice adds no definite support`() {
        val f = Fixture()
        val p1 = f.claim("p1", "10:00", "11:00", Effect.PREFERRED).copy(alternativeGroup = "option", alternative = "alpha")
        val p2 = f.claim("p2", "15:00", "16:00", Effect.PREFERRED).copy(alternativeGroup = "option", alternative = "beta")
        val choice =
            f
                .node(
                    "choice",
                    ScheduleDslOperator.CHOICE,
                    "r1",
                    "r2",
                ).copy(choiceGroup = "option", branchLabels = listOf("alpha", "beta"))
        val envelope =
            f.valid(
                listOf(f.claim("a", "06:00", "07:00"), p1, p2),
                f.program(listOf(f.ref("ar", "a"), f.ref("r1", "p1"), f.ref("r2", "p2"), choice), "ar", prefer = "choice"),
            )
        val unknown = project(envelope, f.policy)
        assertEquals(listOf(f.window("06:00", "07:00")), person(unknown).availableTimes)
        assertTrue(person(unknown).explicitPreferences.timeRanges.isEmpty())
        val selected = project(envelope, f.policy.copy(alternativeChoices = listOf(IrAlternativeChoice(f.ir.first, "option", "beta"))))
        assertEquals(listOf(f.window("06:00", "07:00"), f.window("15:00", "16:00")), person(selected).availableTimes)
        assertEquals(listOf(f.window("15:00", "16:00")), person(selected).explicitPreferences.timeRanges)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `UPA12 retracted or explicitly superseded preference history cannot regain eligibility`(correction: Boolean) {
        val f = Fixture()
        val old =
            f
                .claim(
                    "old",
                    "10:00",
                    "11:00",
                    Effect.PREFERRED,
                ).let { if (correction) it else it.copy(assertion = IrAssertion.RETRACTED) }
        val replacement =
            f
                .claim(
                    "new",
                    "15:00",
                    "16:00",
                    Effect.PREFERRED,
                ).let { if (correction) it.copy(supersedes = listOf("old")) else it }
        val program = f.program(listOf(f.ref("ar", "a"), f.ref("pr", "new")), "ar", prefer = "pr", history = listOf("old"))
        val actual = project(f.valid(listOf(f.claim("a", "06:00", "07:00"), old, replacement), program), f.policy)
        assertEquals(listOf(f.window("06:00", "07:00"), f.window("15:00", "16:00")), person(actual).availableTimes)
        assertEquals(listOf(f.window("15:00", "16:00")), person(actual).explicitPreferences.timeRanges)
        assertEquals(
            if (correction) IrConditionDisposition.SUPERSEDED else IrConditionDisposition.EXCLUDED_ASSERTION,
            actual.evidence
                .getValue(first)
                .conditions
                .single {
                    it.conditionId ==
                        "old"
                }.disposition,
        )
    }

    @Test
    fun `UPA13 claim and graph permutations and duplicate window declarations do not change eligibility or multiply bonus`() {
        val f = Fixture()
        val claims =
            listOf(
                f.claim("a1", "06:00", "07:00"),
                f.claim("a2", "06:00", "07:00"),
                f.claim("p1", "12:00", "14:00", Effect.PREFERRED),
                f.claim("p2", "13:00", "15:00", Effect.PREFERRED),
            )
        val nodes =
            listOf(
                f.ref("a1r", "a1"),
                f.ref("a2r", "a2"),
                f.ref("p1r", "p1"),
                f.ref("p2r", "p2"),
                f.node("allow", ScheduleDslOperator.ANY_OF, "a1r", "a2r"),
                f.node("prefer", ScheduleDslOperator.ANY_OF, "p1r", "p2r"),
            )
        val first = project(f.valid(claims, f.program(nodes, "allow", prefer = "prefer")), f.policy)
        val reordered =
            nodes.reversed().map {
                if (it.operator ==
                    ScheduleDslOperator.ANY_OF
                ) {
                    it.copy(children = it.children.reversed())
                } else {
                    it
                }
            }
        val second = project(f.valid(claims.reversed(), f.program(reordered, "allow", prefer = "prefer")), f.policy)
        assertEquals(first.participants, second.participants)
        assertEquals(listOf(f.window("06:00", "07:00"), f.window("12:00", "15:00")), person(first).availableTimes)
        assertEquals(listOf(f.window("12:00", "15:00")), person(first).explicitPreferences.timeRanges)
        val options = RecommendationProjector.generate(MeetingMode.REMOTE, first.participants, ZoneId.of("UTC")).options
        assertEquals(
            1,
            options
                .single { it.window == f.window("12:00", "15:00") }
                .variants
                .single()
                .preferenceCount,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["root", "owner", "revision"])
    fun `UPA14 malformed graph foreign evidence and stale revision cannot trigger a full search fallback`(fault: String) {
        val f = Fixture()
        val preference =
            f.claim("p", "10:00", "11:00", Effect.PREFERRED).let {
                when (fault) {
                    "owner" -> it.copy(evidence = listOf(f.proof(1)))
                    "revision" -> it.copy(evidence = listOf(f.proof(0).copy(revision = 9)))
                    else -> it
                }
            }
        val envelope =
            f.decode(
                listOf(preference),
                f.program(
                    listOf(f.ref("pr", "p")),
                    if (fault ==
                        "root"
                    ) {
                        "missing"
                    } else {
                        null
                    },
                    prefer = "pr",
                ),
            )
        assertTrue(envelope.programs.first().program == null)
        if (fault != "root") {
            assertEquals(
                IrProblem.EVIDENCE,
                envelope.claims.results
                    .first()
                    .quarantined
                    .single()
                    .problem,
            )
        }
        val actual = project(envelope, f.policy)
        assertTrue(person(actual).availableTimes.isEmpty())
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
        assertPeer(actual, f)
    }

    @Test
    fun `UPA15 present but entirely unknown roots never become default full search availability`() {
        val f = Fixture()
        val available = f.claim("a", "06:00", "07:00").copy(scope = null, temporalResolution = IrTemporalResolution.NON_PRECISE)
        val preferred =
            f
                .claim(
                    "p",
                    "12:00",
                    "13:00",
                    Effect.PREFERRED,
                ).copy(scope = null, temporalResolution = IrTemporalResolution.NON_PRECISE)
        val actual = project(f.simple(available, preferred), f.policy)
        assertTrue(person(actual).availableTimes.isEmpty())
        assertTrue(person(actual).explicitPreferences.timeRanges.isEmpty())
        assertEquals(listOf(f.search()), actual.evidence.getValue(first).possibleWindows)
        assertTrue(actual.evidence.getValue(first).unresolvedTime)
    }

    private fun project(
        envelope: ScheduleDslEnvelope,
        policy: IrMatchingPolicy,
    ) = ScheduleDslInputPolicy.projectWithUnionPreferredAvailability(envelope, policy)

    private fun person(prepared: ScheduleDslPreparedInputs) = prepared.participants.single { it.participantId == first }

    private fun assertPeer(
        prepared: ScheduleDslPreparedInputs,
        fixture: Fixture,
    ) {
        val actual = prepared.participants.single { it.participantId == peer }
        assertEquals(listOf(fixture.search()), actual.availableTimes)
        assertTrue(actual.explicitPreferences.timeRanges.isEmpty())
    }

    private class Fixture(
        date: String = "2034-05-09",
        zone: String = "UTC",
        source: String = "Synthetic jade provenance.",
    ) {
        val ir = ScheduleIrV2SyntheticFixture
        private val day = LocalDate.parse(date)
        private val timeZone = ZoneId.of(zone)
        private val texts = listOf(source, "Synthetic independent peer provenance.")
        val policy = IrMatchingPolicy("union-support-synthetic-1", setOf(IrInterpretation.EXPLICIT), emptyList(), true)
        private val snapshot =
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
                        searchEnd = day.plusDays(3),
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

        fun search() = InstantTimeRange(day.atStartOfDay(timeZone).toInstant(), day.plusDays(3).atStartOfDay(timeZone).toInstant())

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
                    IrScope(IrDimension.TIME, day.atStartOfDay(), day.plusDays(3).atStartOfDay()),
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
