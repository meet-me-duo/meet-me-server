package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.AiRuntimePolicyProperties
import com.meetme.server.config.ManagedAiRuntimeConfiguration
import com.meetme.server.config.ManagedAiRuntimeSelection
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierRuntimeProperties
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.budget.AiBudgetAuthorityConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual Boot binding from renderer-facing environment names; these arbitrary values are synthetic, never operational policy. */
class Issue131ManagedRuntimeEnvironmentBindingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `MB03 actual system-environment properties bind the complete bundle including dash-removed names`() {
        runner(environment()).run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
            assertTrue(context.getBean(PrecheckDecisionRoutingProperties::class.java).enabled)
            assertTrue(context.getBean(AmbiguityClassifierRuntimeProperties::class.java).enabled)
            assertTrue(context.getBean(AiDispatchGateProperties::class.java).enabled)
            assertEquals(0.73, context.getBean(AmbiguityClassifierRuntimeProperties::class.java).minimumConfidence)
            val properties = context.getBean(AiRuntimePolicyProperties::class.java)
            assertEquals(directory.resolve("policy.json").toString(), properties.policyPath)
            assertEquals(hash(Files.readString(Path.of(properties.policyPath))), properties.policySha256)
            val selection = context.getBean(ManagedAiRuntimeSelection::class.java)
            assertTrue(selection.enabled)
            assertEquals(properties.policySha256, assertNotNull(selection.policy).fingerprint)
        }
    }

    @Test
    fun `MB02 provider-only environment cannot activate an unmanaged generator`() {
        runner(mapOf("MEETME_PRECHECK_PROVIDER_ENABLED" to "true")).run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `MB04 environment cannot activate with missing artifact hash`() {
        runner(environment() - "MEETME_AI_BUDGET_POLICYSHA256").run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `MB03 environment threshold conflict does not silently override immutable policy`() {
        runner(environment() + ("MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE" to "0.74"))
            .run { context -> assertNotNull(context.startupFailure) }
    }

    private fun runner(environment: Map<String, Any>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ManagedAiRuntimeConfiguration::class.java, AiBudgetAuthorityConfiguration::class.java)
            .withBean(Clock::class.java, Supplier { Clock.fixed(NOW, ZoneOffset.UTC) })
            .withInitializer { context ->
                context.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", environment))
            }

    private fun environment(): Map<String, Any> {
        val names = listOf("PRECHECK_CLASSIFICATION", "PRECHECK_COUNT", "PRECHECK_GENERATION", "FINAL_PRIMARY", "FINAL_FALLBACK")
        val profiles =
            listOf(
                "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
                "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
                "b9ce9802d9dc3f4396f49c796cfe173fda708c6a35ac40c79d5bdc5091419363",
                "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
                "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
            )
        val rows =
            names
                .mapIndexed { index, name ->
                    val evidence = hash("synthetic:$name")
                    """{"operation":"$name","profileFingerprint":"${profiles[index]}","evidenceFingerprint":"$evidence",""" +
                        """"maximumAmount":"${index + 3}","validUntil":"2026-10-07T01:00:00Z"}"""
                }.joinToString(",")
        val json =
            """{"protocol":"meetme-managed-ai-policy-v1","state":"CONFIGURED",""" +
                """"unit":{"code":"TEST_SUBUNIT","scale":0},"roomCap":"1000","minimumConfidence":0.73,"operations":[$rows]}"""
        val path = Files.writeString(directory.resolve("policy.json"), json)
        return mapOf(
            "MEETME_PRECHECK_PROVIDER_ENABLED" to "true",
            "MEETME_PRECHECK_DECISIONROUTING_ENABLED" to "true",
            "MEETME_PRECHECK_CLASSIFIER_ENABLED" to "true",
            "MEETME_AI_BUDGET_ENABLED" to "true",
            "MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE" to "0.73",
            "MEETME_AI_BUDGET_POLICYPATH" to path.toString(),
            "MEETME_AI_BUDGET_POLICYSHA256" to hash(json),
        )
    }

    companion object {
        private val NOW = Instant.parse("2026-10-07T00:00:00Z")

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
    }
}
