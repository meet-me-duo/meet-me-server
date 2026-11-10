package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.coordination.domain.ir.IrExecutionIdentity
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.ScheduleDslContextModeNativeFixture
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Request facts only; no live call, availability oracle, interpreted relative date, or rewritten source. */
class C5RelativeDateContextTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()
    private val budget = Mockito.mock(DurableAiBudgetPort::class.java)
    private val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
    private val native = ScheduleDslContextModeNativeFixture
    private val q = ScheduleDslQuestionContextFixture

    @Test
    fun `RDC01 per source frozen reference dates remain distinct across Seoul midnight and a month crossing range`() {
        val capsule = capsule()
        val data = requestData(capsule)
        val frozen = mapper.writeValueAsBytes(data)
        val actual = C5RelativeDateContext.enrich(capsule, data)
        assertNotSame(data, actual)
        assertEquals(
            expected("Asia/Seoul", "2027-01-30", "2027-02-03", "2027-02-02", "2027-01-31", "2027-02-01", "2027-01-31"),
            actual["relative_date_context"],
        )
        assertEquals(data, actual - "relative_date_context")
        assertContentEquals(frozen, mapper.writeValueAsBytes(data))
        assertFalse(mapper.writeValueAsString(actual["relative_date_context"]).contains("합성"))
    }

    @Test
    fun `RDC02 each source uses room timezone rather than UTC and a one day range excludes its end`() {
        val capsule =
            capsule(
                zone = "America/Los_Angeles",
                start = "2027-01-31",
                end = "2027-02-01",
                originalAt = "2027-02-01T00:30:00Z",
                answerAt = "2027-02-01T08:30:00Z",
                secondAt = "2027-02-01T06:00:00Z",
            )
        val actual = C5RelativeDateContext.enrich(capsule, requestData(capsule))
        assertEquals(
            expected(
                "America/Los_Angeles",
                "2027-01-31",
                "2027-02-01",
                "2027-01-31",
                "2027-01-31",
                "2027-02-01",
                "2027-01-31",
                "2027-02-01T00:30:00Z",
                "2027-02-01T08:30:00Z",
                "2027-02-01T06:00:00Z",
            ),
            actual["relative_date_context"],
        )
    }

    @Test
    fun `RDC03 source facts sort by input reference and source id independently of native registry order`() {
        val original = capsule()
        val snapshot = original.snapshot
        val reversed =
            ScheduleEvidenceSnapshot(
                snapshot.context.copy(inputs = snapshot.context.inputs.reversed()),
                snapshot.context.inputs.associate { input ->
                    input.submissionVersionId to
                        input.sources.associate { source ->
                            source.sourceId to requireNotNull(snapshot.sourceText(input.submissionVersionId, source.sourceId))
                        }
                },
            )
        val changed = ScheduleDslProviderInputCapsule.create(reversed, q.native(input()))
        val data = requestData(original)
        assertEquals(
            C5RelativeDateContext.enrich(original, data),
            C5RelativeDateContext.enrich(changed, data),
        )
        assertEquals(data, C5RelativeDateContext.enrich(original, data) - "relative_date_context")
    }

    @Test
    fun `RDC04 default false preserves both provider bodies byte for byte under both parity settings`() {
        val request = request(native.capsule())
        for (parity in listOf(false, true)) {
            val baseline = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = parity)
            val explicit =
                C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = parity, relativeDateContextCandidate = false)
            for ((provider, model) in providers()) {
                val bytes = baseline.body(request, provider, model)
                assertContentEquals(bytes, explicit.body(request, provider, model))
                assertFalse(bytes.toString(Charsets.UTF_8).contains("relative_date_context"))
            }
        }
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `RDC05 common V5 adds equal facts and instructions while preserving schema and native input`() {
        val capsule = native.capsule()
        val request = request(capsule)
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true)
        val candidate = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true, relativeDateContextCandidate = true)
        val oldOpenAi = mapper.readTree(baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        val oldGemini = mapper.readTree(baseline.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        val openAi = mapper.readTree(candidate.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"))
        val gemini = mapper.readTree(candidate.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        val oldData = mapper.readValue(oldOpenAi.path("input").asText(), Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val expectedData = mapper.valueToTree<JsonNode>(C5RelativeDateContext.enrich(capsule, oldData as Map<String, Any?>))
        assertEquals(expectedData, mapper.readTree(openAi.path("input").asText()))
        assertEquals(
            expectedData,
            mapper.readTree(
                gemini
                    .path("contents")
                    .path(0)
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(),
            ),
        )
        assertEquals(oldOpenAi.path("text"), openAi.path("text"))
        assertEquals(oldGemini.path("generationConfig"), gemini.path("generationConfig"))
        assertEquals(oldOpenAi.path("reasoning"), openAi.path("reasoning"))
        assertEquals(oldOpenAi.path("max_output_tokens"), openAi.path("max_output_tokens"))
        val instructions = openAi.path("instructions").asText()
        assertEquals(
            instructions,
            gemini
                .path("systemInstruction")
                .path("parts")
                .path(0)
                .path("text")
                .asText(),
        )
        assertTrue(instructions.startsWith(oldOpenAi.path("instructions").asText()))
        assertTrue(instructions.contains("Relative date context (relative-date-context-1)"))
        assertContentEquals(
            baseline.body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
            C5EvaluationSelectedHttpTransport(
                mapper,
                gate,
                providerParityCandidate = true,
            ).body(request, AnalysisProvider.OPENAI, "gpt-6-luna"),
        )
        Mockito.verifyNoInteractions(budget)
    }

    @Test
    fun `RDC06 opt in requires common V5 parity and cannot bypass the Gemini V2 early return`() {
        val v5 = request(native.capsule())
        val noParity = C5EvaluationSelectedHttpTransport(mapper, gate, relativeDateContextCandidate = true)
        for ((provider, model) in providers()) {
            assertFailsWith<IllegalArgumentException> { noParity.body(v5, provider, model) }
        }
        val capsule = ScheduleDslProviderInputCapsule.create(q.snapshot(), q.native())
        val v2 = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(v2.inputCapsule).thenReturn(capsule)
        Mockito
            .`when`(
                v2.binding,
            ).thenReturn(FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(), FinalAnalysisRouteTestFixture.invocation()))
        val baseline = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true)
        assertTrue(mapper.readTree(baseline.body(v2, AnalysisProvider.GEMINI, "gemini-3.8-flash")).path("contents").isArray)
        val candidate = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true, relativeDateContextCandidate = true)
        for ((provider, model) in providers()) {
            assertFailsWith<IllegalArgumentException> { candidate.body(v2, provider, model) }
        }
        Mockito.verifyNoInteractions(budget)
    }

    private fun providers() = listOf(AnalysisProvider.OPENAI to "gpt-6-luna", AnalysisProvider.GEMINI to "gemini-3.8-flash")

    private fun request(capsule: ScheduleDslProviderInputCapsule): SelectedScheduleDslProviderRequest {
        val choice =
            FinalAnalysisRouteChoice.DslStructuralV5(
                IrExecutionIdentity("schedule-dsl-response-5", "schedule-ir-product-6", native.policy.version),
                native.policy,
                capsule.snapshot.context,
                MeetingMode.EITHER,
                "ko-time-terms-1",
            )
        return Mockito.mock(SelectedScheduleDslProviderRequest::class.java).also {
            Mockito.`when`(it.inputCapsule).thenReturn(capsule)
            Mockito
                .`when`(
                    it.binding,
                ).thenReturn(
                    FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(choice), FinalAnalysisRouteTestFixture.invocation()),
                )
        }
    }

    private fun input(
        zone: String = "Asia/Seoul",
        start: String = "2027-01-30",
        end: String = "2027-02-03",
        originalAt: String = "2027-01-31T14:59:59Z",
        answerAt: String = "2027-01-31T15:00:00Z",
        secondAt: String = "2027-01-30T20:00:00Z",
    ) = native.input().let { old ->
        old.copy(
            timeZone = zone,
            searchStart = LocalDate.parse(start),
            searchEnd = LocalDate.parse(end),
            batchFixedAt = Instant.parse("2027-02-01T12:00:00Z"),
            inputs =
                old.inputs.mapIndexed { index, item ->
                    item.copy(
                        rawText = if (index == 0) "🙂 합성 원문." else "합성 다른 원문.",
                        acceptedAt = Instant.parse("2027-02-01T11:00:00Z"),
                        evidence =
                            if (index == 0) {
                                InputEvidence(
                                    3,
                                    Instant.parse(originalAt),
                                    q.answer.copy(
                                        answerText = "합성 보완 응답.",
                                        choiceId = null,
                                        answeredAt = Instant.parse(answerAt),
                                        referenceAt = Instant.parse(answerAt),
                                    ),
                                )
                            } else {
                                InputEvidence(5, Instant.parse(secondAt))
                            },
                    )
                },
        )
    }

    private fun capsule(
        zone: String = "Asia/Seoul",
        start: String = "2027-01-30",
        end: String = "2027-02-03",
        originalAt: String = "2027-01-31T14:59:59Z",
        answerAt: String = "2027-01-31T15:00:00Z",
        secondAt: String = "2027-01-30T20:00:00Z",
    ) = native.capsule(input(zone, start, end, originalAt, answerAt, secondAt))

    @Suppress("UNCHECKED_CAST")
    private fun requestData(capsule: ScheduleDslProviderInputCapsule) =
        mapper.readValue(
            C5EvaluationStructuralProviderContract(mapper).requestBytes(capsule, MeetingMode.EITHER),
            Map::class.java,
        ) as Map<String, Any?>

    private fun expected(
        zone: String,
        start: String,
        end: String,
        last: String,
        originalDate: String,
        answerDate: String,
        secondDate: String,
        originalAt: String = "2027-01-31T14:59:59Z",
        answerAt: String = "2027-01-31T15:00:00Z",
        secondAt: String = "2027-01-30T20:00:00Z",
    ): Map<String, Any?> {
        fun source(
            input: String,
            id: String,
            at: String,
            date: String,
            revision: Int = 3,
        ) = mapOf("input_ref" to input, "source_id" to id, "revision" to revision, "reference_at" to at, "input_local_date" to date)
        return mapOf(
            "version" to "relative-date-context-1",
            "time_zone" to zone,
            "search_range" to mapOf("start_inclusive" to start, "end_exclusive" to end, "last_included_date" to last),
            "sources" to
                listOf(
                    source(q.ir.first.toString(), "original", originalAt, originalDate),
                    source(q.ir.first.toString(), "supplement:${q.questionId}", answerAt, answerDate),
                    source(q.ir.second.toString(), "original", secondAt, secondDate, revision = 5),
                ),
        )
    }
}
