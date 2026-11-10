package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModeCalculator
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.time.InstantTimeRange
import com.meetme.server.submission.domain.dsl.ApprovedTimeTermDerivation
import com.meetme.server.submission.domain.dsl.ScheduleDslContextModeEnvelope
import com.meetme.server.submission.domain.dsl.ScheduleDslProblem
import com.meetme.server.submission.domain.ir.IrEffect
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrProblem
import com.meetme.server.submission.domain.ir.IrTemporalResolution
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.meetme.server.support.ApprovedHalfDayTestFixture as F
import com.meetme.server.support.ScheduleDslContextModeNativeFixture as N

/** Proposed option A: a wire offset corroborates the frozen room-local boundary; it never changes its local date. */
class ScheduleDslContextModeOffsetContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val parser = StrictScheduleDslContextModeResponseParser(mapper)
    private val first = ParticipantId(F.ir.firstParticipant)
    private val peer = ParticipantId(F.ir.secondParticipant)

    @Test
    fun `CMO01 matching room offsets preserve native declaration evidence and actual hard-mode calculation`() {
        val input = N.input()
        val document = N.document(input)
        addSeoulOffsets(document)
        val before = F.json(document)
        val envelope = parse(document, input)
        assertEquals(before, F.json(document))
        assertEquals(N.expectedEnvelope(input), envelope)
        val declared =
            envelope.base.declaration.claims.results
                .first()
                .conditions
                .single { it.id == "context" }
        assertEquals(IrInterpretation.INFERRED, declared.interpretation)
        assertEquals(IrTemporalResolution.NON_PRECISE, declared.temporalResolution)
        assertEquals(ApprovedTimeTermDerivation.MODEL_CONTEXT, envelope.contextApplications.single().derivation)
        val result = ScheduleDslContextModeCalculator.calculate(envelope, N.snapshot(input), N.policy, MeetingMode.EITHER)
        val thursday = window("2027-01-07T09:00:00Z", "2027-01-07T13:00:00Z")
        val friday = window("2027-01-08T09:30:00Z", "2027-01-08T13:00:00Z")
        val remote = result.preparedByMode.getValue(MeetingMode.REMOTE)
        val inPerson = result.preparedByMode.getValue(MeetingMode.IN_PERSON)
        assertEquals(listOf(thursday, friday), remote.participants.single { it.participantId == first }.availableTimes)
        assertEquals(listOf(friday), inPerson.participants.single { it.participantId == first }.availableTimes)
        assertEquals(
            listOf(thursday, friday),
            result.candidates.candidates
                .single { it.meetingMode == MeetingMode.REMOTE }
                .timeRanges,
        )
        assertEquals(
            listOf(friday),
            result.candidates.candidates
                .single { it.meetingMode == MeetingMode.IN_PERSON }
                .timeRanges,
        )
    }

    @Test
    fun `CMO02 local midnight keeps its local date and the earlier UTC calendar date`() {
        val (input, document) = range("2027-01-09T00:00", "2027-01-09T03:00", "Asia/Seoul")
        addSeoulOffsets(document)
        val envelope = parse(document, input)
        assertEquals(
            F.scope("2027-01-09T00:00", "2027-01-09T03:00"),
            envelope.base.declaration.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        val result = ScheduleDslContextModeCalculator.calculate(envelope, F.snapshot(input), N.policy, MeetingMode.REMOTE)
        val expected = window("2027-01-08T15:00:00Z", "2027-01-08T18:00:00Z")
        assertEquals(
            listOf(expected),
            result.candidates.candidates
                .single()
                .timeRanges,
        )
        assertTrue(
            result.preparedByMode
                .getValue(MeetingMode.REMOTE)
                .participants
                .all { it.availableTimes == listOf(expected) },
        )
    }

    @Test
    fun `CMO03 unique room offsets on opposite sides of DST preserve the one-hour instant interval`() {
        val (input, document) = range("2027-03-14T01:30", "2027-03-14T03:30", "America/New_York")
        results(document).forEach { result ->
            claims(result).forEach { claim ->
                claim["scope"] = scope("2027-03-14T01:30-05:00", "2027-03-14T03:30-04:00")
            }
        }
        val envelope = parse(document, input)
        assertEquals(
            F.scope("2027-03-14T01:30", "2027-03-14T03:30"),
            envelope.base.declaration.claims.results
                .first()
                .conditions
                .single()
                .scope,
        )
        val result = ScheduleDslContextModeCalculator.calculate(envelope, F.snapshot(input), N.policy, MeetingMode.REMOTE)
        assertEquals(
            listOf(window("2027-03-14T06:30:00Z", "2027-03-14T07:30:00Z")),
            result.candidates.candidates
                .single()
                .timeRanges,
        )
    }

    @Test
    fun `CMO04 invalid or ambiguous hard-mode offsets never become an unrestricted or repaired time scope`() {
        val cases =
            listOf(
                listOf("Asia/Seoul", "2027-01-08T18:00", "2027-01-08T22:00", "2027-01-08T18:00+25:00", "2027-01-08T22:00+09:00"),
                listOf("Asia/Seoul", "2027-01-08T18:00", "2027-01-08T22:00", "2027-01-08T18:00+08:00", "2027-01-08T22:00+09:00"),
                listOf("Asia/Seoul", "2027-01-08T18:00", "2027-01-08T22:00", "2027-01-08T09:00Z", "2027-01-08T13:00Z"),
                listOf(
                    "Asia/Seoul",
                    "2027-01-08T18:00",
                    "2027-01-08T22:00",
                    "2027-01-08T18:00+09:00[Asia/Seoul]",
                    "2027-01-08T22:00+09:00",
                ),
                listOf("America/New_York", "2027-03-14T02:30", "2027-03-14T03:30", "2027-03-14T02:30-05:00", "2027-03-14T03:30-04:00"),
                listOf("America/New_York", "2027-11-07T01:30", "2027-11-07T02:30", "2027-11-07T01:30-04:00", "2027-11-07T02:30-05:00"),
                listOf("America/New_York", "2027-11-07T01:30", "2027-11-07T02:30", "2027-11-07T01:30-05:00", "2027-11-07T02:30-05:00"),
            )
        cases.forEach { case ->
            val (input, document) = range(case[1], case[2], case[0], withMode = true)
            firstMode(document)["scope"] = scope(case[3], case[4])
            val failure = assertFailsWith<NaturalLanguageParserException> { parse(document, input) }
            assertEquals(ParserFailureKind.INVALID_RESPONSE, failure.kind)
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains(input.inputs.first().rawText))
        }
    }

    @Test
    fun `CMO05 wrong condition offset remains quarantined with its independent valid condition and peer retained`() {
        val input = N.input()
        val document = N.document(input)
        addSeoulOffsets(document)
        claims(F.firstResult(document)).first()["scope"] = scope("2027-01-07T18:00+08:00", "2027-01-07T22:00+09:00")
        val envelope = parse(document, input)
        val result =
            envelope.base.declaration.claims.results
                .first()
        assertEquals(listOf("context"), result.conditions.map { it.id })
        assertEquals("thu", result.quarantined.single().conditionId)
        assertEquals(IrProblem.SHAPE, result.quarantined.single().problem)
        assertNull(result.quarantined.single().scope)
        assertNull(
            envelope.base.declaration.programs
                .first()
                .program,
        )
        assertPeerRetained(envelope, input)
    }

    @Test
    fun `CMO06 valid numeric offsets never repair an incorrect quote or Unicode codepoint span`() {
        for (damage in listOf("quote", "span")) {
            val input = N.input()
            val document = N.document(input)
            addSeoulOffsets(document)
            val evidence = fields(list(claims(F.firstResult(document)).first()["evidence"]).first())
            if (damage == "quote") evidence["quote"] = "SYNTHETIC_OFFSET_EVIDENCE_MISMATCH" else evidence["start_code_point"] = 1
            val envelope = parse(document, input)
            val result =
                envelope.base.declaration.claims.results
                    .first()
            assertEquals("thu", result.quarantined.single().conditionId)
            assertEquals(IrProblem.EVIDENCE, result.quarantined.single().problem)
            assertNull(result.quarantined.single().scope)
            assertEquals(listOf("context"), result.conditions.map { it.id })
            assertNull(
                envelope.base.declaration.programs
                    .first()
                    .program,
            )
            assertPeerRetained(envelope, input)
        }
    }

    @Test
    fun `CMO07 normalization never changes an unavailable claim into a valid availability-role claim`() {
        val input = N.input()
        val document = N.document(input)
        addSeoulOffsets(document)
        claims(F.firstResult(document)).first()["effect"] = "UNAVAILABLE"
        val envelope = parse(document, input)
        val condition =
            envelope.base.declaration.claims.results
                .first()
                .conditions
                .single { it.id == "thu" }
        assertEquals(IrEffect.UNAVAILABLE, condition.effect)
        assertEquals(IrInterpretation.EXPLICIT, condition.interpretation)
        assertEquals(IrTemporalResolution.PRECISE_INTERVAL, condition.temporalResolution)
        assertEquals(F.scope("2027-01-07T18:00", "2027-01-07T22:00"), condition.scope)
        val program =
            envelope.base.declaration.programs
                .first()
        assertNull(program.program)
        assertTrue(ScheduleDslProblem.ROLE in program.problems)
        assertPeerRetained(envelope, input)
    }

    @Test
    fun `CMO08 local C3 and legacy declarations stay valid while their offset scope rejection stays unchanged`() {
        val (input, local) = range("2027-01-08T14:00", "2027-01-08T18:00", "Asia/Seoul")

        fun oldDocument(offset: Boolean): MutableMap<String, Any?> =
            F.json(local).let { json ->
                @Suppress("UNCHECKED_CAST")
                val copy = mapper.readValue(json, Map::class.java) as MutableMap<String, Any?>
                copy["semantic_version"] = "schedule-ir-product-4"
                results(copy).forEach { it.remove("mode_restrictions") }
                if (offset) claims(F.firstResult(copy)).first()["scope"] = scope("2027-01-08T14:00+09:00", "2027-01-08T18:00+09:00")
                copy
            }
        val snapshot = F.snapshot(input)
        for (offset in listOf(false, true)) {
            val approvedDocument = oldDocument(offset)
            val approved =
                StrictScheduleDslApprovedTermsResponseParser(mapper).parse(
                    F.json(approvedDocument),
                    snapshot,
                    "schedule-dsl-response-3",
                    "schedule-ir-product-4",
                    "schedule-ast-1",
                    "ko-time-terms-1",
                )
            val legacyDocument =
                oldDocument(offset).apply {
                    this["semantic_version"] = "schedule-ir-product-3"
                    remove("vocabulary_version")
                    results(this).forEach { it.remove("approved_terms") }
                }
            val legacy =
                ScheduleDslProviderContract(mapper).parseProviderResponse(
                    F.json(legacyDocument),
                    snapshot,
                    "schedule-ir-product-3",
                    "schedule-ast-1",
                )
            for (envelope in listOf(approved.declaration, legacy)) {
                val result = envelope.claims.results.first()
                if (offset) {
                    assertTrue(result.conditions.isEmpty())
                    assertEquals(IrProblem.SHAPE, result.quarantined.single().problem)
                    assertNull(envelope.programs.first().program)
                } else {
                    assertTrue(result.quarantined.isEmpty())
                    assertEquals(F.scope("2027-01-08T14:00", "2027-01-08T18:00"), result.conditions.single().scope)
                    assertTrue(
                        envelope.programs
                            .first()
                            .problems
                            .isEmpty(),
                    )
                }
            }
        }
    }

    private fun parse(
        document: MutableMap<String, Any?>,
        input: ScheduleDslOfflineInput,
    ) = parser.parse(
        F.json(document),
        F.snapshot(input),
        "schedule-dsl-response-4",
        "schedule-ir-product-5",
        "schedule-ast-1",
        "ko-time-terms-1",
    )

    private fun assertPeerRetained(
        envelope: ScheduleDslContextModeEnvelope,
        input: ScheduleDslOfflineInput,
    ) {
        val calculation = ScheduleDslContextModeCalculator.calculate(envelope, N.snapshot(input), N.policy, MeetingMode.EITHER)
        val remote = calculation.preparedByMode.getValue(MeetingMode.REMOTE)
        assertEquals(
            listOf(N.thursday, window("2027-01-08T09:00:00Z", "2027-01-08T13:00:00Z")),
            remote.participants.single { it.participantId == peer }.availableTimes,
        )
        assertTrue(
            remote.participants
                .single { it.participantId == first }
                .availableTimes
                .isEmpty(),
        )
    }

    private fun range(
        start: String,
        end: String,
        zone: String,
        withMode: Boolean = false,
    ): Pair<ScheduleDslOfflineInput, MutableMap<String, Any?>> {
        val text = "🙂 $start 부터 $end 까지 가능. 그 시간은 비대면만 가능."
        val input =
            F.input(text).let { original ->
                original.copy(
                    policy = N.policy,
                    timeZone = zone,
                    searchStart = LocalDate.parse(start.substringBefore('T')).minusDays(1),
                    searchEnd = LocalDate.parse(end.substringBefore('T')).plusDays(1),
                    meetingMode = MeetingMode.REMOTE,
                    inputs = original.inputs.map { it.copy(rawText = text) },
                )
            }
        val interval = F.scope(start, end)
        val proposal =
            F.proposal(
                input,
                listOf(F.claim(input, "range", scope = interval)),
                F.program(listOf(F.ref(input, "range-ref", "range")), "range-ref"),
                emptyList(),
            )
        val paired =
            proposal.copy(
                base =
                    proposal.base.copy(
                        claims =
                            proposal.base.claims.copy(
                                results =
                                    proposal.base.claims.results.map { result ->
                                        if (result.submissionVersionId == F.ir.second) {
                                            result.copy(conditions = result.conditions.map { it.copy(scope = interval) })
                                        } else {
                                            result
                                        }
                                    },
                            ),
                    ),
            )
        val document =
            F.document(paired).apply {
                this["semantic_version"] = "schedule-ir-product-5"
                results(this).forEach { it["mode_restrictions"] = mutableListOf<Any>() }
            }
        if (withMode) {
            F.firstResult(document)["mode_restrictions"] =
                mutableListOf(
                    linkedMapOf<String, Any?>(
                        "id" to "remote-range",
                        "scope" to scope(start, end),
                        "permitted_modes" to listOf("REMOTE"),
                        "mode_interpretation" to "EXPLICIT",
                        "time_interpretation" to "EXPLICIT",
                        "temporal_resolution" to "PRECISE_INTERVAL",
                        "evidence" to listOf(F.evidenceMap(F.evidence(input, text))),
                        "assertion" to "ASSERTED",
                        "supersedes" to emptyList<String>(),
                        "alternative_group" to null,
                        "alternative" to null,
                    ),
                )
        }
        return input to document
    }

    private fun addSeoulOffsets(document: MutableMap<String, Any?>) {
        results(document).forEach { result ->
            (claims(result) + list(result["mode_restrictions"]).map(::fields)).forEach declarationLoop@{ declaration ->
                val scope = declaration["scope"]?.let(::fields) ?: return@declarationLoop
                for (endpoint in listOf("start", "end")) scope[endpoint]?.let { scope[endpoint] = "$it+09:00" }
            }
        }
    }

    private fun scope(
        start: String,
        end: String,
    ) = linkedMapOf<String, Any?>("dimension" to "TIME", "start" to start, "end" to end)

    private fun window(
        start: String,
        end: String,
    ) = InstantTimeRange(Instant.parse(start), Instant.parse(end))

    private fun list(value: Any?) = requireNotNull(value as? List<*>)

    @Suppress("UNCHECKED_CAST")
    private fun fields(value: Any?) = requireNotNull(value as? MutableMap<String, Any?>)

    private fun results(document: MutableMap<String, Any?>) = list(document["results"]).map(::fields)

    private fun claims(result: MutableMap<String, Any?>) = list(result["conditions"]).map(::fields)

    private fun firstMode(document: MutableMap<String, Any?>) = fields(list(F.firstResult(document)["mode_restrictions"]).first())
}
