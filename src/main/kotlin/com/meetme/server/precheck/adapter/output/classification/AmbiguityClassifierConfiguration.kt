package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.adapter.output.provider.PrecheckDecisionRoutingProperties
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(
    AmbiguityClassifierRuntimeProperties::class,
    PrecheckProviderRuntimeProperties::class,
    PrecheckDecisionRoutingProperties::class,
    TypesafeProperties::class,
)
class AmbiguityClassifierConfiguration {
    @Bean
    @ConditionalOnMissingBean(AmbiguityClassificationPort::class)
    @ConditionalOnExpression("\${meetme.ai.budget.enabled:false} || \${meetme.precheck.provider.enabled:false}")
    fun ambiguityClassifier(
        properties: TypesafeProperties,
        objectMapper: ObjectMapper,
        activation: AmbiguityClassifierRuntimeProperties,
        dispatchGate: AiHttpDispatchGate,
        providerActivation: PrecheckProviderRuntimeProperties = PrecheckProviderRuntimeProperties(),
        decisionRouting: PrecheckDecisionRoutingProperties = PrecheckDecisionRoutingProperties(),
        transport: PrecheckProviderHttpTransport = PrecheckProviderHttpTransport(),
    ): AmbiguityClassificationPort =
        JevAmbiguityClassifier(
            properties.apiKey,
            objectMapper,
            JevClassifierOptions(
                enabled = activation.enabled && providerActivation.enabled && decisionRouting.enabled,
                minimumConfidence = activation.minimumConfidence,
                requireMinimumConfidence = dispatchGate.enforceBudget,
            ),
            client = transport.client,
            dispatchGate = dispatchGate,
        )
}
