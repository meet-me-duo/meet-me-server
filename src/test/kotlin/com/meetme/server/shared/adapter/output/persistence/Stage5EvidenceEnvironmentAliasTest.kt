package com.meetme.server.shared.adapter.output.persistence

import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.assertEquals

/** Independent S5-F02: the emitted name must resolve the actual @Value expression. */
class Stage5EvidenceEnvironmentAliasTest {
    @Test
    fun `S5-F02 exact environment alias resolves the hyphenated Value property`() {
        assertEquals("true", resolve(mapOf("MEETME_PRECHECK_EVIDENCE_ENABLED" to "true")))
        assertEquals("false", resolve(mapOf("MEETME_PRECHECK_EVIDENCE_ENABLED" to "false")))
    }

    @Test
    fun `S5-F01 absent evidence flag and independent provider flag remain false`() {
        assertEquals("false", resolve(emptyMap()))
        assertEquals("false", resolve(mapOf("MEETME_PRECHECK_PROVIDER_ENABLED" to "true")))
    }

    private fun resolve(values: Map<String, Any>): String {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, values),
        )
        return environment.resolveRequiredPlaceholders("\${meetme.precheck.evidence-enabled:false}")
    }
}
