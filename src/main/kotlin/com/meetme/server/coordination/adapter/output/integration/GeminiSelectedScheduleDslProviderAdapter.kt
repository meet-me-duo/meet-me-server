package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import tools.jackson.databind.ObjectMapper
import java.net.URI

/** Explicit selected contract; every physical request requires the existing dispatch gate. */
class GeminiSelectedScheduleDslProviderAdapter(
    private val properties: GeminiProperties,
    mapper: ObjectMapper,
    gate: AiHttpDispatchGate,
    private val endpoint: URI = URI("https://generativelanguage.googleapis.com"),
) : SelectedScheduleDslProviderPort {
    private val transport = SelectedScheduleDslHttpTransport(mapper, gate)

    override fun generate(request: SelectedScheduleDslProviderRequest): ScheduleDslProviderResponse =
        transport.generate(request, AnalysisProvider.GEMINI, properties.model, properties.apiKey, endpoint)
}
