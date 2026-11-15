package com.meetme.server.config

import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.FinalAnalysisRouteRegistry
import com.meetme.server.coordination.application.service.FinalAnalysisRouteSelectionService
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TRC: the reviewed generation is enabled for production closures, not historical reads or local defaults. */
class TemporalRangeConfigurationTest {
    private val runs = mock(CoordinationRunRepository::class.java)
    private val routes = mock(FinalAnalysisRouteRegistry::class.java)

    @ParameterizedTest
    @ValueSource(strings = ["", "local"])
    fun `TRC01 new admission is disabled outside the actual production profile`(profile: String) {
        runner(profile).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.environment.getProperty(FLAG, Boolean::class.java, false))
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `TRC02 production enables the new generation capability from the actual configuration`() {
        runner("prod").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.environment.getProperty(FLAG, Boolean::class.java, false))
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `TRC03 production has an explicit false environment override without changing frozen routes`() {
        runner("prod", environment = mapOf(ENVIRONMENT_FLAG to "false")).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.environment.getProperty(FLAG, Boolean::class.java, true))
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `TRC04 enabling admission without weekly capability refuses startup before creating runs`() {
        runner(properties = listOf("$FLAG=true", "meetme.experiment.weekly-schedule-rules=false")).run { context ->
            assertNotNull(context.startupFailure)
        }
        verifyNoInteractions(runs, routes)
    }

    @Test
    fun `TRC05 explicit full predecessor capabilities can enable admission outside production`() {
        runner(
            properties =
                listOf(
                    "$FLAG=true",
                    "meetme.experiment.union-preferred-availability=true",
                    "meetme.experiment.server-evidence-references=true",
                    "meetme.experiment.search-relative-dates=true",
                    "meetme.experiment.calendar-month-facts=true",
                    "meetme.experiment.indexed-evidence-selections=true",
                    "meetme.experiment.weekly-schedule-rules=true",
                ),
        ).run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.environment.getProperty(FLAG, Boolean::class.java, false))
            assertNotNull(context.getBean(FinalAnalysisRouteSelectionService::class.java))
        }
        verifyNoInteractions(runs, routes)
    }

    private fun runner(
        profile: String = "",
        environment: Map<String, Any> = emptyMap(),
        properties: List<String> = emptyList(),
    ): ApplicationContextRunner {
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

    companion object {
        private const val FLAG = "meetme.experiment.temporal-range-admission"
        private const val ENVIRONMENT_FLAG = "MEETME_EXPERIMENT_TEMPORAL_RANGE_ADMISSION"
    }
}
