package com.meetme.server.submission.domain.dsl

import com.meetme.server.submission.domain.ir.IrAssertion
import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrEvidenceSpan
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrSourceBinding
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.IrV2Condition
import com.meetme.server.submission.domain.ir.IrV2Result
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrV2Envelope
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Independent date-authority rules, not a natural-language interpretation or model-output oracle. */
class MeetingDateScopePolicyTest {
    private val native = ScheduleIrV2SyntheticFixture
    private val start = LocalDate.of(2038, 5, 15)
    private val end = LocalDate.of(2038, 5, 18)
    private val search = setOf(start, start.plusDays(1), start.plusDays(2))

    @Test
    fun `MDS01 relative and bare weekday wording authorize only the inclusive exclusive meeting search dates`() {
        listOf("다음주 수욜은 오후 가능", "금요일은 가능", "둘째 주 일요일에 가능").forEach { text ->
            val f = Fixture(first = text)
            val actual = MeetingDateScopePolicy.create(f.snapshot)
            assertEquals(search, actual.allowedDates(native.first))
            assertEquals(search, actual.allDates)
            assertFalse(end in actual.allDates)
        }
        assertEquals("meeting-date-scope-1", MeetingDateScopePolicy.VERSION)
    }

    @Test
    fun `MDS02 each supported explicit numeric date form authorizes its own outside date for positive and negative scopes`() {
        val examples =
            listOf(
                "날짜 2052-07-19 가능" to LocalDate.of(2052, 7, 19),
                "날짜 2052년 7월 19일 불가" to LocalDate.of(2052, 7, 19),
                "날짜 7월 19일 가능" to LocalDate.of(2038, 7, 19),
                "날짜 7/19 불가" to LocalDate.of(2038, 7, 19),
            )
        examples.forEach { (text, outside) ->
            val f = Fixture(first = text)
            val policy = MeetingDateScopePolicy.create(f.snapshot)
            assertEquals(search + outside, policy.allowedDates(native.first))
            listOf(IrEffect.AVAILABLE, IrEffect.UNAVAILABLE).forEach { effect ->
                policy.validate(f.envelope(window(outside), effect))
            }
        }
    }

    @Test
    fun `MDS03 missing years use original reference year and all years actually inside the search interval`() {
        val f =
            Fixture(
                first = "날짜 1월 15일 또는 12/03 가능",
                start = LocalDate.of(2038, 12, 30),
                end = LocalDate.of(2039, 1, 2),
                referenceAt = Instant.parse("2041-02-02T00:00:00Z"),
            )
        val explicit =
            setOf(2038, 2039, 2041)
                .flatMap { year ->
                    listOf(LocalDate.of(year, 1, 15), LocalDate.of(year, 12, 3))
                }.toSet()
        val expectedSearch = setOf(LocalDate.of(2038, 12, 30), LocalDate.of(2038, 12, 31), LocalDate.of(2039, 1, 1))
        assertEquals(expectedSearch + explicit, MeetingDateScopePolicy.create(f.snapshot).allowedDates(native.first))
    }

    @Test
    fun `MDS04 another participant's absolute date is in the global candidate set but grants no local authority`() {
        val own = LocalDate.of(2052, 7, 19)
        val peer = LocalDate.of(2053, 8, 22)
        val f = Fixture(first = "2052-07-19 가능", second = "2053-08-22 불가")
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        assertEquals(search + own, policy.allowedDates(native.first))
        assertEquals(search + peer, policy.allowedDates(native.second))
        assertEquals(search + own + peer, policy.allDates)
        assertFailsWith<IllegalArgumentException> { policy.validate(f.envelope(window(peer))) }
    }

    @Test
    fun `MDS05 an actual linked supplemental answer may authorize an explicit date without rewriting the original`() {
        val f = Fixture(first = "수욜 오후 가능", supplement = "추가 답변 날짜 2052-07-19 불가")
        val outside = LocalDate.of(2052, 7, 19)
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        assertEquals(search + outside, policy.allowedDates(native.first))
        assertEquals(search, policy.allowedDates(native.second))
        assertEquals("수욜 오후 가능", f.snapshot.sourceText(native.first, "original"))
        assertEquals("추가 답변 날짜 2052-07-19 불가", f.snapshot.sourceText(native.first, f.supplementId))
    }

    @Test
    fun `MDS06 calendar-invalid numeric literals add no date authority and never become corrected dates`() {
        val f = Fixture(first = "2052-02-30 또는 13/40 또는 2월 30일 가능")
        assertEquals(search, MeetingDateScopePolicy.create(f.snapshot).allowedDates(native.first))
        assertEquals("2052-02-30 또는 13/40 또는 2월 30일 가능", f.snapshot.sourceText(native.first, "original"))
    }

    @Test
    fun `MDS07 native digest corruption and forged original or supplemental source identities are rejected`() {
        listOf("digest", "original-id", "supplement-id").forEach { corruption ->
            val f = Fixture(supplement = "추가 날짜 2052-07-19 가능")
            val badId = "question:${UUID(0, 808)}"
            val badInputs =
                f.snapshot.context.inputs.map { input ->
                    if (input.submissionVersionId != native.first) {
                        input
                    } else {
                        input.copy(
                            sources =
                                input.sources.map { source ->
                                    when {
                                        corruption == "digest" && source.sourceId == "original" ->
                                            source.copy(contentSha256 = IrEvidenceBindings.sha256("unrelated bytes"))
                                        corruption == "original-id" && source.sourceId == "original" -> source.copy(sourceId = badId)
                                        corruption == "supplement-id" && source.sourceId == f.supplementId -> source.copy(sourceId = badId)
                                        else -> source
                                    }
                                },
                        )
                    }
                }
            val badTexts =
                f.texts.mapValues { (input, sources) ->
                    if (input != native.first) {
                        sources
                    } else {
                        sources.entries.associate { (id, text) ->
                            val replaced =
                                (corruption == "original-id" && id == "original") ||
                                    (corruption == "supplement-id" && id == f.supplementId)
                            (if (replaced) badId else id) to text
                        }
                    }
                }
            assertFailsWith<IllegalArgumentException> {
                MeetingDateScopePolicy.create(ScheduleEvidenceSnapshot(f.snapshot.context.copy(inputs = badInputs), badTexts))
            }
        }
    }

    @Test
    fun `MDS08 relative outside known TIME declarations are rejected for both availability and unavailability`() {
        val f = Fixture(first = "다음주 수욜 오후 가능")
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        listOf(IrEffect.AVAILABLE, IrEffect.UNAVAILABLE).forEach { effect ->
            val envelope = f.envelope(window(start.minusDays(7)), effect)
            assertFailsWith<IllegalArgumentException> { policy.validate(envelope) }
            assertEquals(
                start.minusDays(7),
                envelope.calculationProjection.claims.results
                    .first()
                    .conditions
                    .single()
                    .scope
                    ?.start
                    ?.toLocalDate(),
            )
        }
    }

    @Test
    fun `MDS09 known inside times pass while unknown null and unknown date scopes remain unchanged`() {
        val f = Fixture()
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        policy.validate(f.envelope(window(start.plusDays(1))))
        listOf(null, IrScope(IrDimension.TIME)).forEach { unknown ->
            val envelope = f.envelope(unknown)
            policy.validate(envelope)
            assertEquals(
                unknown,
                envelope.calculationProjection.claims.results
                    .first()
                    .conditions
                    .single()
                    .scope,
            )
            if (unknown == null) {
                assertNull(
                    envelope.base.base.declaration.claims.results
                        .first()
                        .conditions
                        .single()
                        .scope,
                )
            }
        }
    }

    @Test
    fun `MDS10 overnight end dates may extend one calendar day without authorizing that day as a start`() {
        val f = Fixture()
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        val last = end.minusDays(1)
        assertFalse(end in policy.allowedDates(native.first))
        policy.validate(f.envelope(IrScope(IrDimension.TIME, last.atTime(23, 0), end.atTime(3, 0))))
        assertFailsWith<IllegalArgumentException> { policy.validate(f.envelope(window(end))) }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(f.envelope(IrScope(IrDimension.TIME, last.atTime(23, 0), end.plusDays(1).atStartOfDay())))
        }
    }

    @Test
    fun `MDS11 a daylight saving civil day is checked by its meeting dates rather than a fixed 24 hour duration`() {
        val day = LocalDate.of(2036, 3, 9)
        val f = Fixture(start = day, end = day.plusDays(1), zone = "America/New_York")
        val scope = IrScope(IrDimension.TIME, day.atStartOfDay(), day.plusDays(1).atStartOfDay())
        assertEquals(23L, Duration.between(scope.start!!.atZone(ZoneId.of(f.zone)), scope.end!!.atZone(ZoneId.of(f.zone))).toHours())
        MeetingDateScopePolicy.create(f.snapshot).validate(f.envelope(scope))
    }

    @Test
    fun `MDS12 changing any declaration or calculation layer cannot hide an unauthorized RAW_SCOPE date`() {
        val f = Fixture()
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        val outside = window(start.minusDays(7))
        listOf("declaration", "approved-projection", "context-projection", "final-projection").forEach { layer ->
            val envelope = f.envelope()
            val modified =
                when (layer) {
                    "declaration" ->
                        envelope.copy(
                            base =
                                envelope.base.copy(
                                    base =
                                        envelope.base.base.copy(
                                            declaration = replaceFirstScope(envelope.base.base.declaration, outside),
                                        ),
                                ),
                        )
                    "approved-projection" ->
                        envelope.copy(
                            base =
                                envelope.base.copy(
                                    base =
                                        envelope.base.base.copy(
                                            calculationProjection = replaceFirstScope(envelope.base.base.calculationProjection, outside),
                                        ),
                                ),
                        )
                    "context-projection" ->
                        envelope.copy(
                            base =
                                envelope.base.copy(
                                    calculationProjection = replaceFirstScope(envelope.base.calculationProjection, outside),
                                ),
                        )
                    else -> envelope.copy(calculationProjection = replaceFirstScope(envelope.calculationProjection, outside))
                }
            assertFailsWith<IllegalArgumentException> { policy.validate(modified) }
        }
    }

    @Test
    fun `MDS13 unauthorized directional and approved term dates cannot bypass date authority through derived scopes`() {
        val f = Fixture()
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        val envelope = f.envelope()
        val outside = start.minusDays(7)
        val proof = f.proof(native.first)
        val directional =
            DirectionalTimeApplication(
                native.first,
                DirectionalTimeDeclaration(
                    "c0",
                    TemporalDirection.AFTER,
                    outside,
                    LocalTime.of(17, 0),
                    listOf(LocatedLiteralEvidence(proof)),
                    listOf(LocatedLiteralEvidence(proof)),
                ),
                window(outside),
                null,
            )
        assertFailsWith<IllegalArgumentException> { policy.validate(envelope.copy(directionalApplications = listOf(directional))) }
        val term =
            ApprovedTimeTermApplication(
                native.first,
                "c0",
                ApprovedKoreanTimeTerm.PM,
                outside,
                proof,
                listOf(proof),
                null,
                window(outside),
            )
        assertFailsWith<IllegalArgumentException> {
            policy.validate(envelope.copy(base = envelope.base.copy(base = envelope.base.base.copy(termApplications = listOf(term)))))
        }
    }

    @Test
    fun `MDS14 366 search days and 400 total candidates are accepted and larger bounds fail without truncation`() {
        val leap = LocalDate.of(2036, 1, 1)
        val inside = Fixture(start = leap, end = leap.plusDays(366))
        assertEquals(366, MeetingDateScopePolicy.create(inside.snapshot).allDates.size)
        assertFailsWith<IllegalArgumentException> {
            MeetingDateScopePolicy.create(
                Fixture(start = leap, end = leap.plusDays(367)).snapshot,
            )
        }

        fun explicit(count: Int) = (0 until count).joinToString(" ") { LocalDate.of(2055, 1, 1).plusDays(it.toLong()).toString() }
        val atLimit = Fixture(first = explicit(34), start = leap, end = leap.plusDays(366))
        assertEquals(400, MeetingDateScopePolicy.create(atLimit.snapshot).allDates.size)
        val tooMany = Fixture(first = explicit(35), start = leap, end = leap.plusDays(366))
        assertFailsWith<IllegalArgumentException> { MeetingDateScopePolicy.create(tooMany.snapshot) }
    }

    @Test
    fun `MDS15 foreign input lookups and inverted or empty search intervals are rejected`() {
        val f = Fixture()
        val policy = MeetingDateScopePolicy.create(f.snapshot)
        assertFailsWith<IllegalArgumentException> { policy.allowedDates(UUID(0, 909)) }
        assertFailsWith<IllegalArgumentException> { MeetingDateScopePolicy.create(Fixture(start = end, end = start).snapshot) }
        assertFailsWith<IllegalArgumentException> { MeetingDateScopePolicy.create(Fixture(start = start, end = start).snapshot) }
    }

    private fun window(day: LocalDate) = IrScope(IrDimension.TIME, day.atTime(12, 0), day.atTime(13, 0))

    private fun replaceFirstScope(
        view: ScheduleDslEnvelope,
        scope: IrScope,
    ): ScheduleDslEnvelope =
        view.copy(
            claims =
                view.claims.copy(
                    results =
                        view.claims.results.map { result ->
                            if (result.submissionVersionId ==
                                native.first
                            ) {
                                result.copy(conditions = result.conditions.map { it.copy(scope = scope) })
                            } else {
                                result
                            }
                        },
                ),
        )

    private inner class Fixture(
        first: String = "다음주 수욜은 오후 가능",
        second: String = "금요일 가능",
        supplement: String? = null,
        val start: LocalDate = this@MeetingDateScopePolicyTest.start,
        end: LocalDate = this@MeetingDateScopePolicyTest.end,
        referenceAt: Instant = Instant.parse("2038-02-01T00:00:00Z"),
        val zone: String = "Asia/Seoul",
    ) {
        val supplementId = "supplement:${UUID(0, 808)}"
        val texts =
            mapOf(
                native.first to (mapOf("original" to first) + if (supplement == null) emptyMap() else mapOf(supplementId to supplement)),
                native.second to mapOf("original" to second),
            )
        val snapshot: ScheduleEvidenceSnapshot

        init {
            val old = native.snapshot()
            val inputs =
                old.context.inputs.map { input ->
                    input.copy(
                        sources =
                            texts.getValue(input.submissionVersionId).map { (id, text) ->
                                IrSourceBinding(
                                    id,
                                    if (id == "original") IrSourceKind.ORIGINAL else IrSourceKind.SUPPLEMENT,
                                    3,
                                    text.codePointCount(0, text.length),
                                    IrEvidenceBindings.sha256(text),
                                    referenceAt,
                                    if (id == "original") null else UUID(0, 808),
                                    if (id == "original") null else 0,
                                    if (id == "original") null else first.codePointCount(0, first.length),
                                )
                            },
                    )
                }
            snapshot =
                ScheduleEvidenceSnapshot(
                    old.context.copy(
                        timeZone = zone,
                        searchStart = start,
                        searchEnd = end,
                        policyVersion = SCHEDULE_CONTEXT_MODE_POLICY_VERSION,
                        inputs = inputs,
                    ),
                    texts,
                )
        }

        fun proof(input: UUID): IrEvidenceSpan {
            val text = texts.getValue(input).getValue("original")
            return IrEvidenceSpan("original", 3, 0, text.codePointCount(0, text.length), IrEvidenceBindings.sha256(text))
        }

        fun envelope(
            firstScope: IrScope? = window(start),
            firstEffect: IrEffect = IrEffect.AVAILABLE,
        ): ScheduleDslStructuralEnvelope {
            val results =
                snapshot.context.inputs.mapIndexed { index, input ->
                    val scope = if (index == 0) firstScope else window(start)
                    IrV2Result(
                        input.submissionVersionId,
                        listOf(
                            IrV2Condition(
                                "c$index",
                                if (index == 0) firstEffect else IrEffect.AVAILABLE,
                                IrInterpretation.EXPLICIT,
                                if (scope?.start == null) IrTemporalResolution.NON_PRECISE else IrTemporalResolution.PRECISE_INTERVAL,
                                scope,
                                listOf(proof(input.submissionVersionId)),
                                IrAssertion.ASSERTED,
                                emptyList(),
                                null,
                                null,
                            ),
                        ),
                        emptyList(),
                    )
                }
            val programs =
                results.mapIndexed { index, result ->
                    val blocked = result.conditions.single().effect == IrEffect.UNAVAILABLE
                    ParticipantScheduleProgram(
                        result.submissionVersionId,
                        ScheduleDslProgram(
                            listOf(
                                ScheduleDslNode(
                                    "r$index",
                                    ScheduleDslOperator.CLAIM_REF,
                                    "c$index",
                                    emptyList(),
                                    null,
                                    emptyList(),
                                    IrInterpretation.EXPLICIT,
                                    IrAssertion.ASSERTED,
                                    listOf(proof(result.submissionVersionId)),
                                ),
                            ),
                            if (blocked) null else "r$index",
                            if (blocked) "r$index" else null,
                            null,
                            emptyList(),
                        ),
                        emptySet(),
                    )
                }
            val dsl =
                ScheduleDslEnvelope(
                    SCHEDULE_DSL_SEMANTIC_VERSION,
                    SCHEDULE_AST_VERSION,
                    ScheduleIrV2Envelope(SCHEDULE_IR_V2_VERSION, snapshot.context, results),
                    programs,
                )
            val approved =
                ScheduleDslApprovedTermsEnvelope(
                    SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION,
                    SCHEDULE_AST_VERSION,
                    APPROVED_KOREAN_TIME_TERM_VERSION,
                    dsl,
                    dsl,
                    emptyList(),
                )
            val contextual =
                ScheduleDslContextModeEnvelope(
                    SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION,
                    SCHEDULE_AST_VERSION,
                    approved,
                    dsl,
                    emptyList(),
                    emptyList(),
                )
            return ScheduleDslStructuralEnvelope(
                SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION,
                SCHEDULE_AST_VERSION,
                snapshot.context,
                contextual,
                snapshot.context.inputs.map {
                    ScheduleDslLocatedEvidence(
                        it.submissionVersionId,
                        LocatedLiteralEvidence(proof(it.submissionVersionId)),
                    )
                },
                emptyList(),
                dsl,
            )
        }
    }
}
