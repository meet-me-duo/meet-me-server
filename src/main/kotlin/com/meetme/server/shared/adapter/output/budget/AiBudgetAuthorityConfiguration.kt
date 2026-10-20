package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class AiBudgetAuthorityConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiBudgetAuthorityRegistryPort::class)
    fun aiBudgetAuthorityRegistry(
        selection: ManagedAiRuntimeSelection? = null,
        clock: Clock? = null,
    ): AiBudgetAuthorityRegistryPort =
        if (selection?.enabled ==
            true
        ) {
            ConfiguredAiBudgetAuthorityRegistry(selection, clock ?: Clock.systemUTC())
        } else {
            DenyAiBudgetAuthorityRegistry()
        }
}
