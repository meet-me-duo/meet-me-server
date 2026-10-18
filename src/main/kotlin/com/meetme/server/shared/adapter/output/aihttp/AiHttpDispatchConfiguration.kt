package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(AiDispatchGateProperties::class)
class AiHttpDispatchConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiDispatchModePort::class)
    fun aiDispatchMode(properties: AiDispatchGateProperties): AiDispatchModePort = AiDispatchModePort { properties.enabled }

    @Bean
    @ConditionalOnMissingBean(AiHttpDispatchGate::class)
    fun aiHttpDispatchGate(
        properties: AiDispatchGateProperties,
        budget: DurableAiBudgetPort,
        clock: Clock,
    ): AiHttpDispatchGate = AiHttpDispatchGate(properties, budget, clock)
}
