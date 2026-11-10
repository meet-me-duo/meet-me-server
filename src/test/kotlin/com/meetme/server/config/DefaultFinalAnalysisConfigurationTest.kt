package com.meetme.server.config

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
import com.meetme.server.coordination.domain.analysis.FinalAnalysisContractSelection
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.file.Path
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DFC: approved production default is V5; configuration cannot invent a partial or unreviewed default. */
class DefaultFinalAnalysisConfigurationTest {
    private val flags =
        listOf(
            "meetme.experiment.union-preferred-availability",
            "meetme.experiment.server-evidence-references",
            "meetme.experiment.search-relative-dates",
            "meetme.experiment.calendar-month-facts",
            "meetme.experiment.indexed-evidence-selections",
            "meetme.experiment.weekly-schedule-rules",
        )
    private val runs = mock(CoordinationRunRepository::class.java)
    private val routes = mock(FinalAnalysisRouteRegistry::class.java)

    @ParameterizedTest
    @ValueSource(strings = ["", "local"])
    fun `DFC01 nonproduction actual configuration keeps Legacy and every experiment disabled`(profile: String) {
        runner(profile).run { context ->
            assertNull(context.startupFailure)
            assertEquals(
                FinalAnalysisContractSelection.LEGACY,
                context.environment.getProperty(
                    "meetme.analysis.default-contract",
                    FinalAnalysisContractSelection::class.java,
                    FinalAnalysisContractSelection.LEGACY,
                ),
            )
            flags.forEach { assertFalse(context.environment.getProperty(it, Boolean::class.java, false), it) }
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `DFC02 actual prod profile starts with V5 and all six required capabilities`() {
        runner("prod").run { context ->
            assertNull(context.startupFailure)
            assertEquals(
                FinalAnalysisContractSelection.DSL_STRUCTURAL_V5,
                context.environment.getProperty("meetme.analysis.default-contract", FinalAnalysisContractSelection::class.java),
            )
            flags.forEach { assertTrue(context.environment.getProperty(it, Boolean::class.java, false), it) }
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `DFC03 explicit production environment Legacy override starts without inventing a new frozen run`() {
        runner("prod", environment = mapOf("MEETME_ANALYSIS_DEFAULT_CONTRACT" to "LEGACY")).run { context ->
            assertNull(context.startupFailure)
            assertEquals(
                FinalAnalysisContractSelection.LEGACY,
                context.environment.getProperty("meetme.analysis.default-contract", FinalAnalysisContractSelection::class.java),
            )
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5])
    fun `DFC04 each actual prod capability disabled through environment refuses startup`(missingIndex: Int) {
        val disabledKey = flags[missingIndex].uppercase().replace('.', '_').replace('-', '_')
        runner("prod", environment = mapOf(disabledKey to "false")).run { context ->
            assertNotNull(context.startupFailure, "A prod V5 default without ${flags[missingIndex]} must fail closed")
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `DFC05 explicitly configured V5 with all six capabilities starts outside production`() {
        runner(properties = allFlags(true) + "meetme.analysis.default-contract=DSL_STRUCTURAL_V5").run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5])
    fun `DFC06 explicitly configured V5 refuses each missing capability before creating new runs`(missingIndex: Int) {
        val values = flags.mapIndexed { index, key -> "$key=${index != missingIndex}" }
        runner(properties = values + "meetme.analysis.default-contract=DSL_STRUCTURAL_V5").run { context ->
            assertNotNull(context.startupFailure, "Default V5 requires every capability including ${flags[missingIndex]}")
        }
        verifyNoInteractions(runs, routes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["DSL_APPROVED_TERMS_V3", "DSL_CONTEXT_MODE_V4", "UNREVIEWED_CONTRACT"])
    fun `DFC07 unsupported defaults never silently select a different generation`(contract: String) {
        runner(properties = allFlags(true) + "meetme.analysis.default-contract=$contract").run { context ->
            assertNotNull(context.startupFailure, "Only Legacy and the reviewed full V5 may be runtime defaults")
        }
        verifyNoInteractions(runs, routes)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `DFC08 Legacy default preserves existing explicit selection capability configurations`(enabledIndex: Int) {
        val values = flags.mapIndexed { index, key -> "$key=${enabledIndex == 6 || index == enabledIndex}" }
        runner(properties = values + "meetme.analysis.default-contract=LEGACY").run { context ->
            assertNull(context.startupFailure, "Legacy default does not invalidate prior explicit opt-in configurations")
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    private fun allFlags(enabled: Boolean): List<String> = flags.map { "$it=$enabled" }

    private fun runner(
        profile: String = "",
        environment: Map<String, Any> = emptyMap(),
        properties: List<String> = emptyList(),
    ): ApplicationContextRunner {
        // Test classpath has its own application.yml; load the real production files explicitly.
        val mainConfig = Path.of("src/main/resources/application.yml").toAbsolutePath().toUri()
        return ApplicationContextRunner()
            .withPropertyValues("spring.config.location=$mainConfig", "spring.profiles.active=$profile", *properties.toTypedArray())
            .withBean(CoordinationRunRepository::class.java, Supplier { runs })
            .withBean(FinalAnalysisRouteRegistry::class.java, Supplier { routes })
            .withInitializer { context ->
                context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment),
                )
                ConfigDataApplicationContextInitializer().initialize(context)
                // Exercise the real @Bean method and its @Value arguments without creating provider/DB beans.
                // A singleton factory has no configuration bean definition, so unrelated @Bean methods are not scanned.
                context.beanFactory.registerSingleton("actualSelectedConfigurationFactory", SelectedScheduleDslAutoConfiguration())
                (context.beanFactory as BeanDefinitionRegistry).registerBeanDefinition(
                    "finalAnalysisRouteSelectionService",
                    RootBeanDefinition(FinalAnalysisRouteSelectionService::class.java).apply {
                        factoryBeanName = "actualSelectedConfigurationFactory"
                        factoryMethodName = "finalAnalysisRouteSelectionService"
                        autowireMode = AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR
                    },
                )
            }
    }
}
