package com.meetme.server.shared.adapter.output.aihttp

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierConfiguration
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderConfiguration
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.shared.application.port.output.AiDispatchModePort
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Separate hardening cycle: real Spring composition; deny probes force every connection to loopback. */
class Issue123ManagedDispatchCompositionTest {
    enum class Route { CLASSIFIER, DIRECT_PERSONAL, TWO_STAGE_PERSONAL, FINAL_GEMINI, FINAL_LUNA }

    private val raw = "managed synthetic original"
    private val date = LocalDate.of(2026, 10, 18)

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["gemini", "luna"])
    fun `AD02 managed final adapter cannot start without its mandatory shared gate`(provider: String) {
        val type =
            if (provider ==
                "gemini"
            ) {
                GeminiNaturalLanguageParserAdapter::class.java
            } else {
                OpenAiLunaNaturalLanguageParserAdapter::class.java
            }
        base().withUserConfiguration(type).run { application -> assertNotNull(application.startupFailure) }
    }

    @Test
    fun `AD02 managed personal factory ON without gate returns configuration failure before any socket`() {
        LoopbackOnlyProxy().use { network ->
            base()
                .withUserConfiguration(PrecheckProviderConfiguration::class.java)
                .withPropertyValues("meetme.ai.budget.enabled=true", "meetme.precheck.provider.enabled=true")
                .run { application ->
                    // Failing startup is also safe; a successfully started ON factory must deny before transport.
                    if (application.startupFailure == null) {
                        val error =
                            assertFailsWith<PrecheckProviderException> {
                                application
                                    .getBean(
                                        PreSubmissionCheckProvider::class.java,
                                    ).check(personal())
                            }
                        assertEquals("CONFIGURATION", error.failureCode)
                    }
                    assertEquals(0, network.connections.get())
                }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(Route::class)
    fun `AD02 every managed route uses the same ON deny gate before connection`(route: Route) {
        val budget = DispatchBudget().apply { denyReserve = true }
        LoopbackOnlyProxy().use { network ->
            managed(budget, route != Route.DIRECT_PERSONAL).run { application ->
                assertNull(application.startupFailure)
                when (route) {
                    Route.CLASSIFIER ->
                        assertEquals(
                            "CONFIGURATION",
                            assertFailsWith<AmbiguityClassificationException> {
                                application.getBean(AmbiguityClassificationPort::class.java).classify(classification())
                            }.failureCode,
                        )
                    Route.DIRECT_PERSONAL, Route.TWO_STAGE_PERSONAL ->
                        assertEquals(
                            "CONFIGURATION",
                            assertFailsWith<PrecheckProviderException> {
                                application.getBean(PreSubmissionCheckProvider::class.java).check(personal())
                            }.failureCode,
                        )
                    Route.FINAL_GEMINI ->
                        assertFinalDenied {
                            application
                                .getBean(
                                    GeminiNaturalLanguageParserAdapter::class.java,
                                ).parse(batch())
                        }
                    Route.FINAL_LUNA ->
                        assertFinalDenied {
                            application
                                .getBean(
                                    OpenAiLunaNaturalLanguageParserAdapter::class.java,
                                ).parse(batch())
                        }
                }
                val operations =
                    when (route) {
                        Route.CLASSIFIER, Route.TWO_STAGE_PERSONAL -> listOf(AiPhysicalOperation.PRECHECK_CLASSIFICATION)
                        Route.DIRECT_PERSONAL -> listOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION)
                        Route.FINAL_GEMINI -> listOf(AiPhysicalOperation.FINAL_PRIMARY)
                        Route.FINAL_LUNA -> listOf(AiPhysicalOperation.FINAL_FALLBACK)
                    }
                assertEquals(
                    operations,
                    budget.reserves
                        .single()
                        .calls
                        .map { it.operation },
                )
                assertTrue(budget.claims.isEmpty())
                assertEquals(0, network.connections.get())
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = ["provider", "routing", "classifier"])
    fun `AD03 managed classifier cannot bypass any original explicit OFF switch`(off: String) {
        val budget = DispatchBudget()
        LoopbackOnlyProxy().use { network ->
            managed(budget, true)
                .withPropertyValues(
                    "meetme.precheck.provider.enabled=${off != "provider"}",
                    "meetme.precheck.decision-routing.enabled=${off != "routing"}",
                    "meetme.precheck.classifier.enabled=${off != "classifier"}",
                ).run { application ->
                    assertNull(application.startupFailure)
                    val error =
                        assertFailsWith<AmbiguityClassificationException> {
                            application.getBean(AmbiguityClassificationPort::class.java).classify(classification())
                        }
                    assertEquals("CONFIGURATION", error.failureCode)
                    assertTrue(budget.reserves.isEmpty())
                    assertEquals(0, network.connections.get())
                }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = [false, true])
    fun `OR01 managed application mode reads the same explicit gate property without financial authority`(enabled: Boolean) {
        base()
            .withUserConfiguration(AiHttpDispatchConfiguration::class.java)
            .withBean(DurableAiBudgetPort::class.java, Supplier { DispatchBudget() })
            .withBean(Clock::class.java, Supplier { DispatchClock() })
            .withPropertyValues("meetme.ai.budget.enabled=$enabled")
            .run { application ->
                assertNull(application.startupFailure)
                assertEquals(enabled, application.getBean(AiDispatchModePort::class.java).isEnabled())
            }
    }

    private fun base() =
        ApplicationContextRunner()
            .withPropertyValues("meetme.typesafe.api-key=synthetic-managed-key")
            .withBean(GeminiProperties::class.java, Supplier { GeminiProperties(apiKey = "synthetic-managed-key") })
            .withBean(OpenAiProperties::class.java, Supplier { OpenAiProperties(apiKey = "synthetic-managed-key") })
            .withBean(ObjectMapper::class.java, Supplier { JsonMapper.builder().build() })

    private fun managed(
        budget: DispatchBudget,
        routing: Boolean,
    ) = base()
        .withUserConfiguration(
            AiHttpDispatchConfiguration::class.java,
            AmbiguityClassifierConfiguration::class.java,
            PrecheckProviderConfiguration::class.java,
            GeminiNaturalLanguageParserAdapter::class.java,
            OpenAiLunaNaturalLanguageParserAdapter::class.java,
        ).withBean(DurableAiBudgetPort::class.java, Supplier { budget })
        .withBean(Clock::class.java, Supplier { DispatchClock() })
        .withPropertyValues(
            "meetme.ai.budget.enabled=true",
            "meetme.precheck.provider.enabled=true",
            "meetme.precheck.decision-routing.enabled=$routing",
            "meetme.precheck.classifier.enabled=true",
            "meetme.precheck.classifier.minimum-confidence=0.8",
        )

    private fun context(final: Boolean): AiProviderDispatchContext {
        val original = dispatchContext(final)
        val source = if (final)dispatchFramedHash("final-source-v1", UUID(0, 888).toString(), raw)else dispatchFramedHash(raw)
        val contextual =
            if (final) {
                dispatchFramedHash(
                    "final-context-v1",
                    "Asia/Seoul",
                    date.toString(),
                    date.plusDays(5).toString(),
                    UUID(0, 888).toString(),
                    "ko-KR",
                    date.toString(),
                )
            } else {
                dispatchFramedHash(dispatchNow.toString(), "Asia/Seoul", "ko-KR", date.toString(), date.plusDays(5).toString())
            }
        return original.copy(execution = original.execution.copy(sourceFingerprint = source, contextFingerprint = contextual))
    }

    private fun personal() =
        PrecheckProviderRequest(
            UUID(0, 124),
            raw,
            dispatchNow,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(5),
            Duration.ofSeconds(2),
            roomId = UUID(0, 123),
            executionGeneration = 7,
            dispatchContext = context(false),
        )

    private fun classification() =
        AmbiguityClassificationRequest(
            raw,
            dispatchNow,
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            date,
            date.plusDays(5),
            Duration.ofSeconds(2),
            dispatchContext = context(false),
        )

    private fun batch() =
        NaturalLanguageBatchRequest(
            ZoneId.of("Asia/Seoul"),
            date,
            date.plusDays(5),
            listOf(NaturalLanguageInput(UUID(0, 888).toString(), raw, Locale.forLanguageTag("ko-KR"), date)),
            callTimeout = Duration.ofSeconds(2),
            dispatchContext = context(true),
        )

    private fun assertFinalDenied(call: () -> Unit) {
        val error = assertFailsWith<NaturalLanguageParserException> { call() }
        assertEquals(ParserFailureKind.CONFIGURATION, error.kind)
        assertTrue(!error.retryable)
    }

    /** Last-resort egress guard for a bad managed wiring: proxy always resolves to this local socket. */
    private class LoopbackOnlyProxy : AutoCloseable {
        val connections = AtomicInteger()
        private val previous = ProxySelector.getDefault()
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "issue123-loopback-proxy").apply { isDaemon = true }
            }

        init {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", socket.localPort))
            ProxySelector.setDefault(
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> = listOf(proxy)

                    override fun connectFailed(
                        uri: URI?,
                        sa: SocketAddress?,
                        ioe: IOException?,
                    ) = Unit
                },
            )
            executor.submit {
                try {
                    while (!socket.isClosed) socket.accept().use { connections.incrementAndGet() }
                } catch (_: IOException) {
                }
            }
        }

        override fun close() {
            ProxySelector.setDefault(previous)
            socket.close()
            executor.shutdownNow()
        }
    }
}
