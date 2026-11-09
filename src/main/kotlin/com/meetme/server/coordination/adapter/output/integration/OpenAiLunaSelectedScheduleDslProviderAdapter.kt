package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import tools.jackson.databind.ObjectMapper
import java.net.URI

/** Explicit selected contract; the provider envelope yields raw DSL for the selected strict parser. */
class OpenAiLunaSelectedScheduleDslProviderAdapter(
    private val properties: OpenAiProperties,
    mapper: ObjectMapper,
    gate: AiHttpDispatchGate,
    private val endpoint: URI = URI("https://api.openai.com/v1/responses"),
) : SelectedScheduleDslProviderPort {
    private val transport = SelectedScheduleDslHttpTransport(mapper, gate)

    override fun generate(request: SelectedScheduleDslProviderRequest): ScheduleDslProviderResponse =
        transport.generate(request, AnalysisProvider.OPENAI, properties.model, properties.apiKey, endpoint)
}
