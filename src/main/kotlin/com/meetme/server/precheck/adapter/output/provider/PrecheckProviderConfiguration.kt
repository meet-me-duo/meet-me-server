package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
class PrecheckProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(PreSubmissionCheckProvider::class)
    fun preSubmissionCheckProvider(
        properties: GeminiProperties,
        objectMapper: ObjectMapper,
    ): PreSubmissionCheckProvider =
        // No production opt-in until the model's billed reasoning/output cap has been verified.
        GeminiPreSubmissionCheckProvider(properties, objectMapper)
}
