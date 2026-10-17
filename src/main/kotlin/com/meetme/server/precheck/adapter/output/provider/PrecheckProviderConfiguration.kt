package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableConfigurationProperties(PrecheckProviderRuntimeProperties::class)
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
    ): PreSubmissionCheckProvider = GeminiPreSubmissionCheckProvider.runtime(properties, objectMapper, activation, transport, meterRegistry)
}
