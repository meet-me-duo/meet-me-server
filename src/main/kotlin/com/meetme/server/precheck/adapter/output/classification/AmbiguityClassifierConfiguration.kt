package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.adapter.output.provider.PrecheckDecisionRoutingProperties
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(
    AmbiguityClassifierRuntimeProperties::class,
    PrecheckProviderRuntimeProperties::class,
    PrecheckDecisionRoutingProperties::class,
)
class AmbiguityClassifierConfiguration {
    @Bean
    @ConditionalOnMissingBean(AmbiguityClassificationPort::class)
    @ConditionalOnProperty(prefix = "meetme.ai.budget", name = ["enabled"], havingValue = "true")
    fun ambiguityClassifier(
        properties: OpenAiProperties,
        objectMapper: ObjectMapper,
        activation: AmbiguityClassifierRuntimeProperties,
        dispatchGate: AiHttpDispatchGate,
        meterRegistry: MeterRegistry? = null,
        providerActivation: PrecheckProviderRuntimeProperties = PrecheckProviderRuntimeProperties(),
        decisionRouting: PrecheckDecisionRoutingProperties = PrecheckDecisionRoutingProperties(),
        transport: PrecheckProviderHttpTransport = PrecheckProviderHttpTransport(),
    ): AmbiguityClassificationPort =
        OpenAiAmbiguityClassifier(
            properties.apiKey,
            objectMapper,
            AmbiguityClassifierOptions(
                enabled = activation.enabled && providerActivation.enabled && decisionRouting.enabled,
                minimumConfidence = activation.minimumConfidence,
            ),
            client = transport.client,
            meterRegistry = meterRegistry,
            dispatchGate = dispatchGate,
        )
}
