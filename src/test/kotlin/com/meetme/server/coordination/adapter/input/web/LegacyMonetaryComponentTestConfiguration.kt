package com.meetme.server.coordination.adapter.input.web

import com.meetme.server.config.AiRuntimePolicyProperties
import com.meetme.server.config.ManagedAiRuntimePolicyLoader
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock

/**
 * Explicit legacy financial-component fixture only, never a production runtime mode.
 * It preserves receipt, savepoint, quote and accounting defenses after operational
 * analysis stops depending on monetary authorization. Current production behavior
 * is verified independently by Issue144 native runtime graphs.
 */
@TestConfiguration
class LegacyMonetaryComponentTestConfiguration {
    @Bean
    @Primary
    fun legacyMonetarySelection(
        properties: AiRuntimePolicyProperties,
        clock: Clock,
    ): ManagedAiRuntimeSelection =
        ManagedAiRuntimeSelection(
            true,
            requireNotNull(ManagedAiRuntimePolicyLoader.load(properties, clock)),
        )

    @Bean
    @Primary
    fun legacyMonetaryGate(
        budgets: DurableAiBudgetPort,
        clock: Clock,
    ): AiHttpDispatchGate = AiHttpDispatchGate(AiDispatchGateProperties(true), budgets, clock, enforceBudget = true)
}
