package com.meetme.server.shared.operations

import com.meetme.server.config.OperatorQueryConfiguration
import com.meetme.server.config.OperatorQueryProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic binding checks only; no operating account identifier is stored here. */
class OperatorRuntimeEnvironmentBindingTest {
    @Test
    fun `absent runtime settings preserve disabled and empty allowlist defaults`() {
        runner(emptyMap()).run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(OperatorQueryProperties::class.java)
            assertFalse(properties.enabled)
            assertTrue(properties.operatorUserIds.isEmpty())
        }
    }

    @Test
    fun `renderer environment names bind the single synthetic account exactly`() {
        val synthetic = "11111111-2222-3333-4444-555555555555"
        runner(
            mapOf(
                "MEETME_OPERATIONS_ENABLED" to "true",
                "MEETME_OPERATIONS_OPERATORUSERIDS" to synthetic,
            ),
        ).run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(OperatorQueryProperties::class.java)
            assertTrue(properties.enabled)
            assertEquals(setOf(UUID.fromString(synthetic)), properties.operatorUserIds)
        }
    }

    @Test
    fun `enable flag alone never supplies an operator account`() {
        runner(mapOf("MEETME_OPERATIONS_ENABLED" to "true")).run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(OperatorQueryProperties::class.java).operatorUserIds.isEmpty())
        }
    }

    @Test
    fun `invalid UUID fails actual Spring binding`() {
        runner(
            mapOf(
                "MEETME_OPERATIONS_ENABLED" to "true",
                "MEETME_OPERATIONS_OPERATORUSERIDS" to "synthetic-invalid-account",
            ),
        ).run { context -> assertNotNull(context.startupFailure) }
    }

    private fun runner(environment: Map<String, Any>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(OperatorQueryConfiguration::class.java)
            .withInitializer { context ->
                context.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", environment))
            }
}
