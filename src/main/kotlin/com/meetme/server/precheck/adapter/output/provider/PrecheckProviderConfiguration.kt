package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.shared.adapter.output.budget.DefaultDenyAiCallBudget
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(PrecheckProviderRuntimeProperties::class, PrecheckDecisionRoutingProperties::class)
class PrecheckProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(PrecheckProviderHttpTransport::class)
    fun precheckProviderHttpTransport(): PrecheckProviderHttpTransport = PrecheckProviderHttpTransport()

    @Bean
    @ConditionalOnMissingBean(PreSubmissionCheckProvider::class)
    fun preSubmissionCheckProvider(
        properties: GeminiProperties,
        objectMapper: ObjectMapper,
        activation: PrecheckProviderRuntimeProperties,
        transport: PrecheckProviderHttpTransport,
        meterRegistry: MeterRegistry? = null,
        decisionRouting: PrecheckDecisionRoutingProperties = PrecheckDecisionRoutingProperties(),
        classifier: AmbiguityClassificationPort? = null,
        budget: AiCallBudgetPort? = null,
    ): PreSubmissionCheckProvider {
        val generator = GeminiPreSubmissionCheckProvider.runtime(properties, objectMapper, activation, transport, meterRegistry)
        if (!decisionRouting.enabled || !activation.enabled) return generator
        return TwoStagePreSubmissionCheckProvider(
            classifier ?: AmbiguityClassificationPort { throw AmbiguityClassificationException("CONFIGURATION") },
            generator,
            budget ?: DefaultDenyAiCallBudget(),
        )
    }
}
