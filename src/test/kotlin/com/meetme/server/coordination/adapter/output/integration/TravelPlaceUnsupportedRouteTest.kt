package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.FinalAnalysisAttemptBinding
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import com.meetme.server.support.FinalAnalysisRouteTestFixture
import com.meetme.server.support.ScheduleDslQuestionContextFixture
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pure request assembly; a legacy early return must not bypass an explicitly requested unsupported option. */
class TravelPlaceUnsupportedRouteTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `TPW05 Gemini V2 works with flag off but rejects place candidate before legacy early return`() {
        val source = ScheduleDslQuestionContextFixture
        val capsule = ScheduleDslProviderInputCapsule.create(source.snapshot(), source.native())
        val request = Mockito.mock(SelectedScheduleDslProviderRequest::class.java)
        Mockito.`when`(request.binding).thenReturn(
            FinalAnalysisAttemptBinding(FinalAnalysisRouteTestFixture.route(), FinalAnalysisRouteTestFixture.invocation()),
        )
        Mockito.`when`(request.inputCapsule).thenReturn(capsule)
        val budget = Mockito.mock(DurableAiBudgetPort::class.java)
        val gate = AiHttpDispatchGate(AiDispatchGateProperties(enabled = true), budget, Clock.systemUTC())
        val default = C5EvaluationSelectedHttpTransport(mapper, gate)
        val off = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true, placeConstraintsCandidate = false)
        val baseline = default.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash")
        val wire = mapper.readTree(baseline)
        assertTrue(wire.path("contents").isArray)
        assertEquals("application/json", wire.path("generationConfig").path("responseMimeType").asText())
        assertContentEquals(baseline, off.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash"))
        val on = C5EvaluationSelectedHttpTransport(mapper, gate, providerParityCandidate = true, placeConstraintsCandidate = true)
        val failure = assertFailsWith<IllegalArgumentException> { on.body(request, AnalysisProvider.GEMINI, "gemini-3.8-flash") }
        assertEquals("PLACE_CONSTRAINTS_REQUIRE_COMMON_V5", failure.message)
        Mockito.verifyNoInteractions(budget)
    }
}
