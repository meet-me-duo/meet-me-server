package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.function.Supplier
import kotlin.test.assertTrue

/** Registration binds a context to one local infrastructure generation; it must not follow restarts. */
class InfrastructureEndpointSnapshotContractTest {
    @Test
    fun `registered connection settings remain readable and unchanged after infrastructure stop and restart`() {
        val first = CapturingConnectionRegistry()
        val second = CapturingConnectionRegistry()
        try {
            PrimaryGeminiSmokeInfrastructure.properties(first)
            val firstLive = first.snapshot()
            assertValidConnectionSettings(firstLive)

            PrimaryGeminiSmokeInfrastructure.stop()

            assertTrue(firstLive == first.snapshot(), "registered connection settings changed after stop")

            PrimaryGeminiSmokeInfrastructure.properties(second)
            val secondLive = second.snapshot()
            assertValidConnectionSettings(secondLive)
            assertTrue(firstLive == first.snapshot(), "registered connection settings changed after restart")
            assertTrue(secondLive == second.snapshot(), "registered connection settings changed while running")
        } finally {
            PrimaryGeminiSmokeInfrastructure.stop()
        }
    }

    private fun assertValidConnectionSettings(settings: Map<String, Any>) {
        assertTrue(settings.keys == CONNECTION_PROPERTIES, "registered connection property set is incomplete")
        assertTrue(
            (settings.getValue("spring.datasource.url") as? String)?.startsWith("jdbc:postgresql://") == true,
            "registered database endpoint is invalid",
        )
        for (name in listOf("spring.datasource.username", "spring.datasource.password", "spring.data.redis.host")) {
            assertTrue((settings.getValue(name) as? String)?.isNotBlank() == true, "registered connection setting is empty")
        }
        val redisPort = settings.getValue("spring.data.redis.port") as? Number
        assertTrue(redisPort != null && redisPort.toInt() in 1..65535, "registered Redis endpoint is invalid")
    }

    private class CapturingConnectionRegistry : DynamicPropertyRegistry {
        private val suppliers = mutableMapOf<String, Supplier<Any>>()

        override fun add(
            name: String,
            valueSupplier: Supplier<Any>,
        ) {
            // Never evaluate or retain provider-key suppliers or unrelated application configuration.
            if (name in CONNECTION_PROPERTIES) suppliers[name] = valueSupplier
        }

        fun snapshot(): Map<String, Any> = CONNECTION_PROPERTIES.associateWith { name -> suppliers.getValue(name).get() }
    }

    companion object {
        private val CONNECTION_PROPERTIES =
            setOf(
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.data.redis.host",
                "spring.data.redis.port",
            )
    }
}
