package com.meetme.server.shared.adapter.output.budget

import com.meetme.server.shared.application.port.output.AiBudgetAuthorityRegistryPort
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** DB01/24: scanning production adapters must supply default DENY without a test factory or live profile. */
class Issue123DefaultBudgetAuthorityCompositionTest {
    @Test
    fun `DB01 production budget adapter scan supplies exactly one default deny authority`() {
        ApplicationContextRunner()
            .withUserConfiguration(ProductionBudgetAdapters::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                val authorities = context.getBeansOfType(AiBudgetAuthorityRegistryPort::class.java)
                assertEquals(
                    1,
                    authorities.size,
                    "Default production DI must boot with a DENY authority; the absence of prices cannot remove the required port",
                )
                assertIs<DenyAiBudgetAuthorityRegistry>(authorities.values.single())
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = [DenyAiBudgetAuthorityRegistry::class])
    class ProductionBudgetAdapters
}
