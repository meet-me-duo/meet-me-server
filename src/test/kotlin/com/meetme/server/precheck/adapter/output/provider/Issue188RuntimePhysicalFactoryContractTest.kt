package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.AmbiguityAssessment
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.application.port.output.AiBudgetExecution
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiCallBudgetPort
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** ADR-061 runtime factory contract; direct financial constructors retain their separate tests. */
class Issue188RuntimePhysicalFactoryContractTest {
    private val classifier = Mockito.mock(AmbiguityClassificationPort::class.java)
    private val legacyBudget = Mockito.mock(AiCallBudgetPort::class.java)
    private val durableBudget = Mockito.mock(DurableAiBudgetPort::class.java)
    private val transportCalls = AtomicInteger()
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), java.time.ZoneOffset.UTC)
    private val transport =
        PrecheckProviderHttpTransport(
            OkHttpClient
                .Builder()
                .addInterceptor {
                    transportCalls.incrementAndGet()
                    throw AssertionError("Factory contract must never reach generation transport")
                }.build(),
        )

    @ParameterizedTest
    @ValueSource(strings = ["ABSENT", "DISABLED"])
    fun `ADR061 managed routing rejects absent or disabled physical gate without legacy financial fallback`(mode: String) {
        var runner = base().withBean(AiCallBudgetPort::class.java, Supplier { legacyBudget })
        if (mode == "DISABLED") {
            runner = runner.withBean(AiHttpDispatchGate::class.java, Supplier { gate(false) })
        }
        runner.run { context ->
            assertNull(context.startupFailure)
            val error =
                assertFailsWith<PrecheckProviderException> {
                    context.getBean(PreSubmissionCheckProvider::class.java).check(request())
                }
            assertEquals("CONFIGURATION", error.failureCode)
            Mockito.verifyNoInteractions(classifier, legacyBudget, durableBudget)
            assertEquals(0, transportCalls.get())
        }
    }

    @Test
    fun `ADR061 activated runtime factory does not resolve an available legacy financial bean`() {
        val resolutions = AtomicInteger()
        Mockito
            .`when`(
                classifier.classify(anyValue()),
            ).thenReturn(AmbiguityClassificationResult(AmbiguityAssessment.CLEAR, confidence = 0.99))
        base()
            .withBean(AiHttpDispatchGate::class.java, Supplier { gate(true) })
            .withBean(
                AiCallBudgetPort::class.java,
                Supplier {
                    resolutions.incrementAndGet()
                    throw IllegalStateException("Legacy monetary bean must not be resolved by runtime factory")
                },
                BeanDefinitionCustomizer { it.isLazyInit = true },
            ).run { context ->
                assertNull(context.startupFailure, "Runtime construction must be independent of legacy monetary dependency resolution")
                val result = context.getBean(PreSubmissionCheckProvider::class.java).check(request())
                assertEquals(PrecheckVerdict.CLEAR, result.verdict)
                assertEquals(0, resolutions.get())
                Mockito.verify(classifier).classify(anyValue())
                Mockito.verifyNoInteractions(durableBudget)
                assertEquals(0, transportCalls.get())
            }
    }

    private fun base() =
        ApplicationContextRunner()
            .withUserConfiguration(PrecheckProviderConfiguration::class.java)
            .withPropertyValues(
                "meetme.precheck.provider.enabled=true",
                "meetme.precheck.decision-routing.enabled=true",
                "meetme.precheck.classifier.enabled=true",
                "meetme.ai.budget.enabled=false",
            ).withBean(OpenAiProperties::class.java, Supplier { OpenAiProperties(apiKey = "synthetic-not-real") })
            .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })
            .withBean(PrecheckProviderHttpTransport::class.java, Supplier { transport })
            .withBean(AmbiguityClassificationPort::class.java, Supplier { classifier })

    private fun gate(enabled: Boolean) = AiHttpDispatchGate(AiDispatchGateProperties(enabled), durableBudget, clock, enforceBudget = false)

    private fun request(): PrecheckProviderRequest {
        val room = UUID(0, 1881)
        val check = UUID(0, 1882)
        val execution = AiBudgetExecution(room, AiBudgetExecutionKind.PRECHECK, check, 1, UUID(0, 1883), 1, "a".repeat(64), "b".repeat(64))
        return PrecheckProviderRequest(
            check,
            "synthetic original",
            clock.instant(),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 7),
            Duration.ofSeconds(2),
            roomId = room,
            executionGeneration = 1,
            dispatchContext = AiProviderDispatchContext(execution, System.nanoTime() + Duration.ofSeconds(2).toNanos()),
        )
    }

    private fun <T> anyValue(): T = Mockito.any<T>()
}
