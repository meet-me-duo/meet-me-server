package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(
    PrecheckProviderRuntimeProperties::class,
    PrecheckDecisionRoutingProperties::class,
    AiDispatchGateProperties::class,
)
class PrecheckProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(PrecheckProviderHttpTransport::class)
    fun precheckProviderHttpTransport(): PrecheckProviderHttpTransport = PrecheckProviderHttpTransport()

    @Bean
    @ConditionalOnMissingBean(PreSubmissionCheckProvider::class)
    fun preSubmissionCheckProvider(
        properties: OpenAiProperties,
        objectMapper: ObjectMapper,
        activation: PrecheckProviderRuntimeProperties,
        transport: PrecheckProviderHttpTransport,
        meterRegistry: MeterRegistry? = null,
        decisionRouting: PrecheckDecisionRoutingProperties = PrecheckDecisionRoutingProperties(),
        classifier: AmbiguityClassificationPort? = null,
        dispatchGate: AiHttpDispatchGate? = null,
        dispatchProperties: AiDispatchGateProperties = AiDispatchGateProperties(),
    ): PreSubmissionCheckProvider {
        if (dispatchProperties.enabled && dispatchGate == null) {
            return PreSubmissionCheckProvider { throw PrecheckProviderException("CONFIGURATION") }
        }
        val generator =
            LunaPreSubmissionCheckProvider.runtime(
                properties,
                objectMapper,
                activation,
                transport,
                meterRegistry = meterRegistry,
                dispatchGate = dispatchGate,
            )
        if (!decisionRouting.enabled || !activation.enabled) return generator
        val physical =
            dispatchGate?.takeIf { it.properties.enabled }
                ?: return PreSubmissionCheckProvider { throw PrecheckProviderException("CONFIGURATION") }
        return TwoStagePreSubmissionCheckProvider.runtime(
            classifier ?: AmbiguityClassificationPort { throw AmbiguityClassificationException("CONFIGURATION") },
            generator,
            physical,
        )
    }
}
