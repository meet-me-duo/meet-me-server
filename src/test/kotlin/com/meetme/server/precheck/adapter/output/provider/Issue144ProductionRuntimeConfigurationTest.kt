package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.ManagedAiRuntimeConfiguration
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.shared.adapter.output.budget.AiBudgetAuthorityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.util.function.Supplier
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** OA01: provider activation and monetary approval are separate accepted decisions. */
class Issue144ProductionRuntimeConfigurationTest {
    @Test
    fun `OA01 all three provider flags with budget OFF need neither policy nor confidence threshold`() {
        runner(7, false).run { context ->
            assertNull(context.startupFailure)
            val selection = context.getBean(ManagedAiRuntimeSelection::class.java)
            assertFalse(selection.enabled, "Monetary selection remains OFF")
            assertNull(selection.policy)
        }
    }

    @Test
    fun `OA01 provider flags still require all ON or all OFF`() {
        for (mask in 1..6) {
            runner(mask, false).run { assertNotNull(it.startupFailure, "Incomplete provider mask $mask must deny startup") }
        }
        runner(0, false).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.getBean(ManagedAiRuntimeSelection::class.java).enabled)
        }
    }

    @Test
    fun `OA01 monetary enforcement still requires its existing explicit configured policy`() {
        runner(7, true).run { assertNotNull(it.startupFailure) }
        runner(0, true).run { assertNotNull(it.startupFailure) }
    }

    private fun runner(mask: Int, budget: Boolean): ApplicationContextRunner {
        val flags = listOf(
            "meetme.precheck.provider.enabled", "meetme.precheck.decision-routing.enabled", "meetme.precheck.classifier.enabled",
        )
        val values = flags.mapIndexed { index, key -> "$key=${mask and (1 shl index) != 0}" } +
            "meetme.ai.budget.enabled=$budget"
        return ApplicationContextRunner()
            .withUserConfiguration(ManagedAiRuntimeConfiguration::class.java, AiBudgetAuthorityConfiguration::class.java)
            .withBean(Clock::class.java, Supplier { Clock.systemUTC() })
            .withPropertyValues(*values.toTypedArray())
    }
}
