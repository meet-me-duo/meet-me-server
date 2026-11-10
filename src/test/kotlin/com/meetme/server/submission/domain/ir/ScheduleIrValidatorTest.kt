package com.meetme.server.submission.domain.ir

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IR-01..16: labels and scoped evidence survive, while invalid authority cannot become availability. */
class ScheduleIrValidatorTest {
    private val validator = ScheduleIrValidator()
    private val fixture = ScheduleIrTestFixture

    @Test
    fun `valid result retains exact independent evidence spans and context without provider quotes`() {
        val actual = validator.validate(fixture.proposal(), fixture.snapshot())

        assertEquals(fixture.envelope(), actual)
        assertNotEquals(
            actual.context.inputs
                .first()
                .sources
                .single()
                .contentSha256,
            actual.results
                .first()
                .conditions
                .single()
                .evidence
                .single()
                .contentSha256,
        )
        assertFalse(actual.toString().contains(fixture.evidence().quote))
        assertFalse(fixture.snapshot().toString().contains(ScheduleIrTestFixture.FIRST_TEXT))
    }

    @ParameterizedTest
    @EnumSource(IrInterpretation::class)
    fun `interpretation label is retained without making a participant confirmation`(interpretation: IrInterpretation) {
        val proposed = fixture.condition().copy(interpretation = interpretation)
        val result = first(validator.validate(fixture.proposal(listOf(proposed), IrAvailabilityState.UNKNOWN), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(proposed)), result.conditions)
        assertTrue(result.quarantined.isEmpty())
        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong_quote", "wrong_revision", "unknown_source", "negative_start", "past_end", "empty_span", "utf16_offset"])
    fun `invalid evidence quarantines only its condition without trusting the claimed scope`(defect: String) {
        val good = fixture.condition()
        val original = fixture.evidence()
        val invalid =
            when (defect) {
                "wrong_quote" -> original.copy(quote = "provider invented private fact")
                "wrong_revision" -> original.copy(revision = 7)
                "unknown_source" -> original.copy(sourceId = "supplement:${UUID(0, 999)}")
                "negative_start" -> original.copy(startCodePoint = -1)
                "past_end" -> original.copy(endCodePoint = 999)
                "empty_span" -> original.copy(endCodePoint = original.startCodePoint)
                else -> original.copy(startCodePoint = original.startCodePoint + 1, endCodePoint = original.endCodePoint + 1)
            }
        val bad = good.copy(id = "bad", effect = IrEffect.UNAVAILABLE, evidence = listOf(invalid))
        val result = first(validator.validate(fixture.proposal(listOf(good, bad)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(good)), result.conditions)
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, result.availability)
        val rejected = result.quarantined.single { it.conditionId == "bad" }
        assertEquals(IrProblem.EVIDENCE, rejected.problem)
        assertNull(rejected.scope, "Unverified evidence cannot authorize a narrow exclusion scope")
        assertFalse(result.toString().contains(invalid.quote))
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing_end", "reversed", "empty"])
    fun `invalid interval is quarantined without deleting an independent valid window`(defect: String) {
        val valid = fixture.condition()
        val badScope =
            when (defect) {
                "missing_end" -> fixture.window.copy(end = null)
                "reversed" -> fixture.window.copy(start = fixture.window.end, end = fixture.window.start)
                else -> fixture.window.copy(end = fixture.window.start)
            }
        val result =
            first(validator.validate(fixture.proposal(listOf(valid, valid.copy(id = "broken", scope = badScope))), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(valid)), result.conditions)
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, result.availability)
        val rejected = result.quarantined.single { it.conditionId == "broken" }
        assertEquals(IrProblem.SCOPE, rejected.problem)
        assertNull(rejected.scope)
    }

    @Test
    fun `bounded unresolved condition and independent valid time remain separate`() {
        val unknown =
            fixture.condition("friday").copy(
                effect = IrEffect.UNRESOLVED,
                interpretation = IrInterpretation.UNRESOLVED,
                scope = IrScope(IrDimension.TIME, LocalDateTime.parse("2027-01-08T00:00:00"), LocalDateTime.parse("2027-01-09T00:00:00")),
                evidence = listOf(fixture.evidence("금요일 7시는 미정")),
            )
        val result = first(validator.validate(fixture.proposal(listOf(fixture.condition(), unknown)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(), fixture.expectedCondition(unknown)), result.conditions)
        assertTrue(result.quarantined.isEmpty())
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, result.availability)
    }

    @Test
    fun `valid outside search window is not removed as unknown or widened to full range`() {
        val outside =
            fixture.condition().copy(
                scope = IrScope(IrDimension.TIME, LocalDateTime.parse("2027-02-04T18:00:00"), LocalDateTime.parse("2027-02-04T20:00:00")),
            )
        val result = first(validator.validate(fixture.proposal(listOf(outside)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(outside)), result.conditions)
        assertEquals(IrAvailabilityState.KNOWN_WINDOWS, result.availability)
        assertTrue(result.quarantined.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(IrAvailabilityState::class)
    fun `empty provider list never proves known empty windows or unrestricted availability`(state: IrAvailabilityState) {
        val result = first(validator.validate(fixture.proposal(emptyList(), state), fixture.snapshot()))

        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertTrue(result.conditions.isEmpty())
        if (state == IrAvailabilityState.UNKNOWN) {
            assertTrue(result.quarantined.isEmpty())
        } else {
            assertEquals(listOf(IrQuarantine(null, IrProblem.AVAILABILITY, null)), result.quarantined)
        }
    }

    @Test
    fun `unconstrained and explicitly excluded entire search remain distinct states with evidence`() {
        val unrestricted = fixture.condition().copy(effect = IrEffect.UNCONSTRAINED, scope = fixture.fullSearch)
        val excluded = fixture.condition().copy(effect = IrEffect.UNAVAILABLE, scope = fixture.fullSearch)
        val unrestrictedResult =
            first(validator.validate(fixture.proposal(listOf(unrestricted), IrAvailabilityState.UNCONSTRAINED), fixture.snapshot()))
        val emptyResult = first(validator.validate(fixture.proposal(listOf(excluded), IrAvailabilityState.EMPTY), fixture.snapshot()))

        assertEquals(IrAvailabilityState.UNCONSTRAINED, unrestrictedResult.availability)
        assertEquals(IrAvailabilityState.EMPTY, emptyResult.availability)
        assertTrue(unrestrictedResult.quarantined.isEmpty())
        assertTrue(emptyResult.quarantined.isEmpty())
        assertEquals(IrEffect.UNCONSTRAINED, unrestrictedResult.conditions.single().effect)
        assertEquals(IrEffect.UNAVAILABLE, emptyResult.conditions.single().effect)
    }

    @Test
    fun `a narrow exclusion cannot certify that the entire search is empty`() {
        val excluded = fixture.condition().copy(effect = IrEffect.UNAVAILABLE)
        val result = first(validator.validate(fixture.proposal(listOf(excluded), IrAvailabilityState.EMPTY), fixture.snapshot()))

        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertEquals(listOf(fixture.expectedCondition(excluded)), result.conditions)
        assertTrue(result.quarantined.contains(IrQuarantine(null, IrProblem.AVAILABILITY, null)))
    }

    @ParameterizedTest
    @EnumSource(value = IrAssertion::class, names = ["RETRACTED", "QUOTED_OTHER"])
    fun `quoted other or retracted availability is retained but cannot certify known windows`(assertion: IrAssertion) {
        val condition = fixture.condition().copy(assertion = assertion)
        val result = first(validator.validate(fixture.proposal(listOf(condition)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(condition)), result.conditions)
        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertTrue(result.quarantined.contains(IrQuarantine(null, IrProblem.AVAILABILITY, null)))
    }

    @Test
    fun `superseded available claim cannot certify known windows while both source facts remain`() {
        val old = fixture.condition("old")
        val correction = fixture.condition("correction").copy(effect = IrEffect.UNAVAILABLE, supersedes = listOf("old"))
        val result = first(validator.validate(fixture.proposal(listOf(old, correction)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(old), fixture.expectedCondition(correction)), result.conditions)
        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertTrue(result.quarantined.contains(IrQuarantine(null, IrProblem.AVAILABILITY, null)))
    }

    @Test
    fun `exclusive morning and evening interpretations retain their relationship without flattening`() {
        val am =
            fixture.condition("am").copy(
                interpretation = IrInterpretation.INFERRED,
                scope = IrScope(IrDimension.TIME, LocalDateTime.parse("2027-01-08T07:00:00"), LocalDateTime.parse("2027-01-08T08:00:00")),
                evidence = listOf(fixture.evidence("금요일 7시는 미정")),
                alternativeGroup = "friday-clock",
                alternative = "AM",
            )
        val pm =
            am.copy(
                id = "pm",
                scope = am.scope!!.copy(start = am.scope.start!!.plusHours(12), end = am.scope.end!!.plusHours(12)),
                alternative = "PM",
            )
        val result = first(validator.validate(fixture.proposal(listOf(am, pm), IrAvailabilityState.UNKNOWN), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(am), fixture.expectedCondition(pm)), result.conditions)
        assertEquals(IrAvailabilityState.UNKNOWN, result.availability)
        assertEquals(setOf("AM", "PM"), result.conditions.map { it.alternative }.toSet())
        assertTrue(result.quarantined.isEmpty())
    }

    @Test
    fun `incomplete alternative and unknown superseded reference are quarantined separately`() {
        val valid = fixture.condition()
        val lonely = fixture.condition("lonely").copy(alternativeGroup = "missing-peer", alternative = "A")
        val foreign = fixture.condition("foreign").copy(supersedes = listOf("not-in-this-input"))
        val result = first(validator.validate(fixture.proposal(listOf(valid, lonely, foreign)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition(valid)), result.conditions)
        assertEquals(setOf("lonely", "foreign"), result.quarantined.mapNotNull { it.conditionId }.toSet())
        assertTrue(result.quarantined.all { it.problem == IrProblem.RELATION })
    }

    @Test
    fun `cyclic corrections cannot invalidate independent conditions or survive as valid relations`() {
        val a = fixture.condition("a").copy(supersedes = listOf("b"))
        val b = fixture.condition("b").copy(supersedes = listOf("a"))
        val result = first(validator.validate(fixture.proposal(listOf(fixture.condition(), a, b)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition()), result.conditions)
        assertEquals(setOf("a", "b"), result.quarantined.mapNotNull { it.conditionId }.toSet())
        assertTrue(result.quarantined.all { it.problem == IrProblem.RELATION })
    }

    @Test
    fun `strict parser rejections survive validation alongside valid conditions`() {
        val rejected = IrQuarantine("bad-shape", IrProblem.SHAPE, null)
        val result = first(validator.validate(fixture.proposal(rejected = listOf(rejected)), fixture.snapshot()))

        assertEquals(listOf(fixture.expectedCondition()), result.conditions)
        assertEquals(listOf(rejected), result.quarantined)
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "missing_input", "extra_input", "duplicate_input", "duplicate_condition"])
    fun `document identity corruption rejects the whole proposal`(defect: String) {
        val good = fixture.proposal()
        val changed =
            when (defect) {
                "version" -> good.copy(semanticVersion = "unknown-future-schema")
                "missing_input" -> good.copy(results = good.results.take(1))
                "extra_input" -> good.copy(results = good.results + good.results.first().copy(submissionVersionId = UUID(0, 999)))
                "duplicate_input" -> good.copy(results = listOf(good.results.first(), good.results.first()))
                else -> fixture.proposal(listOf(fixture.condition(), fixture.condition()))
            }

        assertFailsWith<IllegalArgumentException> { validator.validate(changed, fixture.snapshot()) }
    }

    @Test
    fun `restored envelope preserves exact known context and evidence`() {
        val expected = fixture.envelope()
        assertEquals(expected, validator.validateRestored(expected, fixture.snapshot()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "run", "batch", "room", "policy", "zone", "search",
            "input_digest", "input_revision", "source_digest", "source_reference",
        ],
    )
    fun `restored envelope cannot be replayed against a different immutable context`(field: String) {
        val context = fixture.context()
        val binding = context.inputs.first()
        val changed =
            when (field) {
                "run" -> context.copy(runId = UUID(0, 800))
                "batch" -> context.copy(batchId = UUID(0, 801))
                "room" -> context.copy(roomId = UUID(0, 802))
                "policy" -> context.copy(policyVersion = "different-policy")
                "zone" -> context.copy(timeZone = "UTC")
                "search" -> context.copy(searchEnd = context.searchEnd.plusDays(1))
                "input_digest" -> context.copy(inputs = listOf(binding.copy(contentSha256 = "0".repeat(64)), context.inputs.last()))
                "input_revision" -> context.copy(inputs = listOf(binding.copy(submissionRevision = 8), context.inputs.last()))
                "source_digest" ->
                    context.copy(
                        inputs =
                            listOf(
                                binding.copy(sources = listOf(binding.sources.single().copy(contentSha256 = "0".repeat(64)))),
                                context.inputs.last(),
                            ),
                    )
                else ->
                    context.copy(
                        inputs =
                            listOf(
                                binding.copy(
                                    sources = listOf(binding.sources.single().copy(referenceAt = fixture.reference.plusSeconds(1))),
                                ),
                                context.inputs.last(),
                            ),
                    )
            }

        assertFailsWith<IllegalArgumentException> { validator.validateRestored(fixture.envelope(changed), fixture.snapshot()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["version", "span_digest", "span_end", "source", "revision", "missing_result"])
    fun `restored data corruption is rejected instead of being read as legacy or empty availability`(field: String) {
        val envelope = fixture.envelope()
        val first = first(envelope)
        val condition = first.conditions.single()
        val evidence = condition.evidence.single()
        val changedEvidence =
            when (field) {
                "span_digest" -> evidence.copy(contentSha256 = "0".repeat(64))
                "span_end" -> evidence.copy(endCodePoint = 999)
                "source" -> evidence.copy(sourceId = "absent")
                "revision" -> evidence.copy(revision = 99)
                else -> evidence
            }
        val changed =
            when (field) {
                "version" -> envelope.copy(semanticVersion = "future-ir")
                "missing_result" -> envelope.copy(results = listOf(first))
                else ->
                    envelope.copy(
                        results =
                            listOf(
                                first.copy(conditions = listOf(condition.copy(evidence = listOf(changedEvidence)))),
                                envelope.results.last(),
                            ),
                    )
            }

        assertFailsWith<IllegalArgumentException> { validator.validateRestored(changed, fixture.snapshot()) }
    }

    private fun first(envelope: ScheduleIrEnvelope): IrResult = envelope.results.single { it.submissionVersionId == fixture.firstId }
}
