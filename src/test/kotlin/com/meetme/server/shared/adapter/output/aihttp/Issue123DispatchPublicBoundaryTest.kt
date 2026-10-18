package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierConfiguration
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderConfiguration
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiDispatchInputSnapshot
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Issue123DispatchPublicBoundaryTest {
    @Test
    fun `AD02 gate denial has bounded typed CONFIGURATION failure without cause or input`() {
        val budget = DispatchBudget().apply { denyReserve = true }
        DispatchEndpoint().use { server ->
            val error =
                assertFailsWith<AiHttpDispatchException> {
                    val exchange =
                        gate(budget).forLazySingleCall(
                            OkHttpClient(),
                            dispatchContext(true),
                            AiPhysicalOperation.FINAL_PRIMARY,
                            AiPhysicalWireProfile("synthetic-profile", "POST", server.uri(), 16384),
                        )
                    exchange.client
                        .newCall(
                            Request
                                .Builder()
                                .url(server.uri().toString())
                                .post("{}".toRequestBody("application/json".toMediaType()))
                                .build(),
                        ).execute()
                        .close()
                }
            assertEquals("CONFIGURATION", error.message)
            assertNull(error.cause)
            assertEquals(1, budget.reserves.size)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `ID02 fingerprints bind exact Unicode source and ordered frozen final identities`() {
        val raw = " 😀 exact source\n"
        assertEquals(dispatchFramedHash(raw), AiDispatchFingerprints.personalSource(raw))
        assertFalse(AiDispatchFingerprints.personalSource(raw) == AiDispatchFingerprints.personalSource(raw.trim()))
        val date = LocalDate.of(2026, 10, 18)
        val first = AiDispatchInputSnapshot("frozen-a", raw, "ko-KR", date)
        val second = AiDispatchInputSnapshot("frozen-b", "second", "en-US", date.plusDays(1))
        assertEquals(
            dispatchFramedHash("final-source-v1", first.inputRef, raw, second.inputRef, second.rawText),
            AiDispatchFingerprints.batchSource(listOf(first, second)),
        )
        assertFalse(AiDispatchFingerprints.batchSource(listOf(first, second)) == AiDispatchFingerprints.batchSource(listOf(second, first)))
        assertEquals(
            dispatchFramedHash(dispatchNow.toString(), "Asia/Seoul", "ko-KR", date.toString(), date.plusDays(5).toString()),
            AiDispatchFingerprints.personalContext(dispatchNow, ZoneId.of("Asia/Seoul"), "ko-KR", date, date.plusDays(5)),
        )
        assertEquals(
            dispatchFramedHash(
                "final-context-v1",
                "Asia/Seoul",
                date.toString(),
                date.plusDays(5).toString(),
                first.inputRef,
                first.locale,
                first.referenceDate.toString(),
            ),
            AiDispatchFingerprints.batchContext(ZoneId.of("Asia/Seoul"), date, date.plusDays(5), listOf(first)),
        )
    }

    @Test
    fun `AD01 managed OFF configuration preserves absent classifier and disabled provider`() {
        context().run { application ->
            assertNull(application.startupFailure)
            assertFalse(application.containsBean("ambiguityClassifier"))
            assertFalse(application.getBean(AiHttpDispatchGate::class.java).properties.enabled)
        }
    }

    @Test
    fun `AD08 managed ON creates actual classifier only with explicit route and keeps threshold absent by default`() {
        context().withPropertyValues("meetme.ai.budget.enabled=true").run { application ->
            assertNull(application.startupFailure)
            assertTrue(application.getBean(AiHttpDispatchGate::class.java).properties.enabled)
            val classifier = application.getBean(AmbiguityClassificationPort::class.java)
            val request =
                com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest(
                    "synthetic input",
                    dispatchNow,
                    ZoneId.of("Asia/Seoul"),
                    "ko-KR",
                    LocalDate.of(2026, 10, 18),
                    LocalDate.of(2026, 10, 20),
                    java.time.Duration.ofSeconds(1),
                )
            val error =
                assertFailsWith<com.meetme.server.precheck.application.port.output.AmbiguityClassificationException> {
                    classifier.classify(
                        request,
                    )
                }
            assertEquals("CONFIGURATION", error.failureCode)
        }
    }

    private fun context() =
        ApplicationContextRunner()
            .withUserConfiguration(
                AiHttpDispatchConfiguration::class.java,
                AmbiguityClassifierConfiguration::class.java,
                PrecheckProviderConfiguration::class.java,
            ).withBean(DurableAiBudgetPort::class.java, Supplier { DispatchBudget().apply { denyReserve = true } })
            .withBean(Clock::class.java, Supplier { DispatchClock() })
            .withBean(GeminiProperties::class.java, Supplier { GeminiProperties(apiKey = "synthetic-key") })
            .withBean(OpenAiProperties::class.java, Supplier { OpenAiProperties(apiKey = "synthetic-key") })
            .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })
}
