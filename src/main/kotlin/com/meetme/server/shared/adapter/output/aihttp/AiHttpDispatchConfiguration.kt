package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
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
    fun aiDispatchMode(
        properties: AiDispatchGateProperties,
        provider: PrecheckProviderRuntimeProperties? = null,
    ): AiDispatchModePort = AiDispatchModePort { properties.enabled || provider?.enabled == true }

    @Bean
    @ConditionalOnMissingBean(AiHttpDispatchGate::class)
    fun aiHttpDispatchGate(
        properties: AiDispatchGateProperties,
        budget: DurableAiBudgetPort,
        clock: Clock,
        provider: PrecheckProviderRuntimeProperties? = null,
        runtimeGuard: AiRuntimeDispatchGuardPort? = null,
        selection: ManagedAiRuntimeSelection? = null,
    ): AiHttpDispatchGate =
        AiHttpDispatchGate(
            AiDispatchGateProperties(properties.enabled || provider?.enabled == true),
            budget,
            clock,
            runtimeGuard = runtimeGuard,
            enforceBudget = selection?.enabled ?: properties.enabled,
        )
}
