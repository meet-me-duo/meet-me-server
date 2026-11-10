package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.submission.domain.ir.IrDimension
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrScope
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_V2_VERSION
import com.meetme.server.submission.domain.ir.SCHEDULE_IR_VERSION
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.submission.domain.ir.ScheduleIrV2Validator
import com.meetme.server.support.ScheduleIrV2SyntheticFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Strict synthetic wire fixtures, not a test of model semantic accuracy or word classification. */
class ScheduleIrV2MeaningContractTest {
    private val f = ScheduleIrV2SyntheticFixture
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val parser = ScheduleIrV2ProviderContract(mapper)
    private val validator = ScheduleIrV2Validator()

    @ParameterizedTest
    @ValueSource(strings = ["AVAILABLE", "PREFERRED"])
    fun `V2P01 lack of precise endpoints is valid known meaning through decoding validation and restoration`(name: String) {
        val condition = f.condition(effect = IrEffect.valueOf(name))
        val expected = f.envelope(listOf(condition))
        assertEquals(expected, validator.validate(f.proposal(listOf(condition)), f.snapshot()))
        val actual = parser.parseProviderResponse(f.json(listOf(condition)), f.snapshot(), SCHEDULE_IR_V2_VERSION)

        assertEquals(expected, actual)
        assertEquals(expected, validator.validateRestored(expected, f.snapshot()))
        val storedShape = mapper.readTree(mapper.writeValueAsString(actual))
        assertTrue(storedShape.path("results").all { !it.has("availability") })
        assertTrue(
            actual.results
                .first()
                .quarantined
                .isEmpty(),
        )
        assertEquals(
            IrEffect.valueOf(name),
            actual.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            IrTemporalResolution.NON_PRECISE,
            actual.results
                .first()
                .conditions
                .single()
                .temporalResolution,
        )
        assertTrue(!actual.toString().contains(f.FIRST_TEXT))
    }

    @ParameterizedTest
    @ValueSource(strings = ["aggregate_availability", "extra_top", "extra_result"])
    fun `V2P02 old aggregate or extra document fields are rejected without decoder fallback`(defect: String) {
        val root = f.document(listOf(f.condition()))
        when (defect) {
            "aggregate_availability" -> result(root)["availability"] = "UNKNOWN"
            "extra_top" -> root["provider_source_registry"] = emptyMap<String, String>()
            else -> result(root)["available_times"] = emptyList<String>()
        }
        assertFailsWith<RuntimeException> { parser.parseProviderResponse(f.encode(root), f.snapshot(), SCHEDULE_IR_V2_VERSION) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing_resolution", "null_resolution", "unknown_resolution", "unknown_condition_field"])
    fun `V2P03 malformed condition shape is isolated while independent precise evidence survives`(defect: String) {
        val clear = f.precise()
        val root = f.document(listOf(clear, f.condition("bad")))
        val bad = conditions(root).last()
        when (defect) {
            "missing_resolution" -> bad.remove("temporal_resolution")
            "null_resolution" -> bad["temporal_resolution"] = null
            "unknown_resolution" -> bad["temporal_resolution"] = "GUESS_A_NUMERIC_DEFAULT"
            else -> bad["availability"] = "KNOWN_WINDOWS"
        }
        val actual = parser.parseProviderResponse(f.encode(root), f.snapshot(), SCHEDULE_IR_V2_VERSION)
        assertEquals(listOf(f.verified(clear)), actual.results.first().conditions)
        assertEquals(
            listOf("bad"),
            actual.results
                .first()
                .quarantined
                .map { it.conditionId },
        )
        assertEquals(
            IrProblem.SHAPE,
            actual.results
                .first()
                .quarantined
                .single()
                .problem,
        )
        assertTrue(
            actual.results
                .first()
                .quarantined
                .single()
                .scope == null,
        )
        assertTrue(
            actual.results
                .first()
                .quarantined
                .none { it.problem == IrProblem.AVAILABILITY },
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "precise_missing_both", "precise_unresolved_missing_both", "nonprecise_one_endpoint",
            "nonprecise_reversed", "nonprecise_dst_ambiguous",
        ],
    )
    fun `V2P04 malformed numeric scope is not confused with valid nonprecision`(defect: String) {
        val base = f.condition("bad")
        val bad =
            when (defect) {
                "precise_missing_both" ->
                    base.copy(
                        temporalResolution = IrTemporalResolution.PRECISE_INTERVAL,
                        scope = IrScope(IrDimension.TIME),
                    )
                "precise_unresolved_missing_both" ->
                    base.copy(
                        effect = IrEffect.UNRESOLVED,
                        temporalResolution = IrTemporalResolution.PRECISE_INTERVAL,
                        scope = IrScope(IrDimension.TIME),
                    )
                "nonprecise_one_endpoint" -> base.copy(scope = IrScope(IrDimension.TIME, f.friday.start, null))
                "nonprecise_reversed" -> base.copy(scope = IrScope(IrDimension.TIME, f.friday.end, f.friday.start))
                else ->
                    base.copy(
                        scope =
                            IrScope(
                                IrDimension.TIME,
                                LocalDateTime.parse("2027-11-07T01:30:00"),
                                LocalDateTime.parse("2027-11-07T02:30:00"),
                            ),
                    )
            }
        val snapshot =
            if (defect ==
                "nonprecise_dst_ambiguous"
            ) {
                ScheduleEvidenceSnapshot(
                    f.snapshot().context.copy(timeZone = "America/New_York"),
                    mapOf(
                        f.first to mapOf("original" to f.FIRST_TEXT),
                        f.second to mapOf("original" to f.SECOND_TEXT),
                    ),
                )
            } else {
                f.snapshot()
            }
        val actual = parser.parseProviderResponse(f.json(listOf(f.precise(), bad)), snapshot, SCHEDULE_IR_V2_VERSION)
        assertEquals(listOf(f.verified(f.precise())), actual.results.first().conditions)
        assertEquals(
            IrProblem.SCOPE,
            actual.results
                .first()
                .quarantined
                .single()
                .problem,
        )
        assertTrue(
            actual.results
                .first()
                .quarantined
                .single()
                .scope == null,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["without_bounds", "with_full_bounds"])
    fun `V2P05 nonprecise unconstrained claim cannot acquire precise windows even with search bounds`(bound: String) {
        val bad = f.condition("bad", IrEffect.UNCONSTRAINED, scope = if (bound == "with_full_bounds") f.fullSearch else null)
        val actual = parser.parseProviderResponse(f.json(listOf(f.precise(), bad)), f.snapshot(), SCHEDULE_IR_V2_VERSION)
        assertEquals(listOf(f.verified(f.precise())), actual.results.first().conditions)
        assertEquals(
            "bad",
            actual.results
                .first()
                .quarantined
                .single()
                .conditionId,
        )
        assertTrue(
            actual.results
                .first()
                .quarantined
                .single()
                .problem in setOf(IrProblem.SHAPE, IrProblem.SCOPE),
        )
        assertTrue(
            actual.results
                .first()
                .quarantined
                .single()
                .scope == null,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["quote", "revision", "utf16_end"])
    fun `V2P06 known nonprecise semantics never exempts source evidence checks or retains corrupt quotes`(defect: String) {
        val original = f.evidence()
        val evidence =
            when (defect) {
                "quote" -> original.copy(quote = "synthetic private quote must not be retained")
                "revision" -> original.copy(revision = 4)
                else -> original.copy(endCodePoint = f.FIRST_TEXT.length)
            }
        val bad = f.condition("bad").copy(evidence = listOf(evidence))
        val actual = parser.parseProviderResponse(f.json(listOf(f.precise(), bad)), f.snapshot(), SCHEDULE_IR_V2_VERSION)
        assertEquals(listOf(f.verified(f.precise())), actual.results.first().conditions)
        assertEquals(
            IrProblem.EVIDENCE,
            actual.results
                .first()
                .quarantined
                .single()
                .problem,
        )
        assertTrue(
            actual.results
                .first()
                .quarantined
                .single()
                .scope == null,
        )
        assertTrue(!actual.toString().contains("synthetic private quote"))
    }

    @Test
    fun `V2P07 unchanged V1 wire remains readable only by its selected V1 decoder`() {
        val first = f.wire(f.precise()).apply { remove("temporal_resolution") }
        val second = f.wire(f.precise(firstSource = false)).apply { remove("temporal_resolution") }
        val legacy =
            f.encode(
                mapOf(
                    "semantic_version" to SCHEDULE_IR_VERSION,
                    "results" to
                        listOf(
                            mapOf("input_ref" to f.first.toString(), "availability" to "KNOWN_WINDOWS", "conditions" to listOf(first)),
                            mapOf(
                                "input_ref" to f.second.toString(),
                                "availability" to "KNOWN_WINDOWS",
                                "conditions" to listOf(second),
                            ),
                        ),
                ),
            )
        val v1 = ScheduleIrProviderContract(mapper).parseProviderResponse(legacy, f.snapshot(), SCHEDULE_IR_VERSION)
        assertEquals(SCHEDULE_IR_VERSION, v1.semanticVersion)
        assertEquals(
            IrEffect.AVAILABLE,
            v1.results
                .first()
                .conditions
                .single()
                .effect,
        )
        assertEquals(
            f.friday,
            v1.results
                .first()
                .conditions
                .single()
                .scope,
        )
        assertFailsWith<RuntimeException> { parser.parseProviderResponse(legacy, f.snapshot(), SCHEDULE_IR_V2_VERSION) }
    }

    @Test
    fun `V2P08 response contract selection is explicit and independent of semantic version`() {
        val adapter = StrictScheduleIrV2ResponseParser(mapper, "synthetic-response-v2")
        val json = f.json(listOf(f.condition()))
        assertEquals(f.envelope(listOf(f.condition())), adapter.parse(json, f.snapshot(), "synthetic-response-v2", SCHEDULE_IR_V2_VERSION))
        assertFailsWith<RuntimeException> { adapter.parse(json, f.snapshot(), "synthetic-response-v1", SCHEDULE_IR_V2_VERSION) }
        assertFailsWith<RuntimeException> { adapter.parse(json, f.snapshot(), "synthetic-response-v2", SCHEDULE_IR_VERSION) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun result(root: MutableMap<String, Any?>) = (root.getValue("results") as MutableList<MutableMap<String, Any?>>).first()

    @Suppress("UNCHECKED_CAST")
    private fun conditions(root: MutableMap<String, Any?>) = result(root).getValue("conditions") as MutableList<MutableMap<String, Any?>>
}
