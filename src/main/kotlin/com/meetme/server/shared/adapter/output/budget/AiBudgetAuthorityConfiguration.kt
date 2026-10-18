package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AiBudgetAuthorityConfiguration {
    @Bean
    @ConditionalOnMissingBean(AiBudgetAuthorityRegistryPort::class)
    fun aiBudgetAuthorityRegistry(): AiBudgetAuthorityRegistryPort = DenyAiBudgetAuthorityRegistry()
}
