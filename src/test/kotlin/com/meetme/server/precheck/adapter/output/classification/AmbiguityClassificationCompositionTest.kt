package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.adapter.output.provider.GeminiPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderConfiguration
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DC02,05,11,12: actual existing provider configuration stays OFF; classifier never joins the worker. */
class AmbiguityClassificationCompositionTest {
    @Test
    fun `DCC01 component scan and arbitrary environment style flags cannot wire the standalone classifier`() {
        val calls = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor {
                    calls.incrementAndGet()
                    throw AssertionError("No external socket permitted")
                }.build()
        ApplicationContextRunner()
            .withUserConfiguration(PrecheckProviderConfiguration::class.java, ClassifierPackageScan::class.java)
            .withBean(GeminiProperties::class.java, Supplier { GeminiProperties(apiKey = "synthetic-composition-key") })
            .withBean(ObjectMapper::class.java, Supplier { DecisionClassificationFixtures.mapper })
            .withBean(PrecheckProviderHttpTransport::class.java, Supplier { PrecheckProviderHttpTransport(client) })
            .withPropertyValues(
                "meetme.precheck.classification.enabled=true",
                "meetme.precheck.classification.minimum-confidence=0.8",
                "meetme.openai.api-key=synthetic-unused-key",
            ).run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBeansOfType(AmbiguityClassificationPort::class.java).isEmpty())
                assertFalse(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
                val provider = context.getBean(PreSubmissionCheckProvider::class.java)
                assertIs<GeminiPreSubmissionCheckProvider>(provider)
                val r = DecisionClassificationFixtures.request
                val request =
                    PrecheckProviderRequest(
                        UUID.fromString("00000000-0000-0000-0000-000000000107"),
                        r.rawText,
                        r.referenceAt,
                        r.timeZone,
                        r.locale,
                        r.searchStartDate,
                        r.searchEndDate,
                        r.timeout,
                    )
                assertEquals("CONFIGURATION", assertFailsWith<PrecheckProviderException> { provider.check(request) }.failureCode)
                assertEquals(0, calls.get())
            }
    }

    @Test
    fun `DCC02 classifier port is separate and cannot masquerade as an accepted submission provider`() {
        assertFalse(PreSubmissionCheckProvider::class.java.isAssignableFrom(OpenAiAmbiguityClassifier::class.java))
        assertTrue(AmbiguityClassificationPort::class.java.isAssignableFrom(OpenAiAmbiguityClassifier::class.java))
        assertTrue(
            PreSubmissionCheckWorker::class.java.declaredConstructors.none { constructor ->
                constructor.parameterTypes.any { AmbiguityClassificationPort::class.java.isAssignableFrom(it) }
            },
        )
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = [OpenAiAmbiguityClassifier::class])
    class ClassifierPackageScan
}
