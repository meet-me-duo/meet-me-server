package com.meetme.server.experiment

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Only the HTTP flow/infrastructure are reused; primary-only parser exclusion fixtures are not imported. */
internal class LunaFallbackOnceSettings private constructor(
    val paid: Boolean,
    val output: Path,
) {
    val wire = StandaloneGeminiOnceDiagnostic.OnceLedger(output.resolve("wire-ledger.json"))
    val primaryCalls = AtomicInteger()
    val lunaCalls = AtomicInteger()
    val sourceChecks = AtomicInteger()
    val syntheticResponses = AtomicInteger()
    val events = CopyOnWriteArrayList<Map<String, Any?>>()
    val timings = CopyOnWriteArrayList<Map<String, Any?>>()
    val parseOutcomes = CopyOnWriteArrayList<Map<String, Any?>>()
    private var admitted: AiBudgetReserveRequest? = null
    private var mockResponse: String? = null
    val endpoint: URI = URI.create("https://api.openai.com/v1/responses")

    fun mockResponse(): String {
        check(!paid)
        return checkNotNull(mockResponse)
    }

    @Synchronized
    fun source(request: AiBudgetReserveRequest): Boolean {
        val call = request.calls.singleOrNull() ?: return false
        if (call.operation != AiPhysicalOperation.FINAL_FALLBACK || call.ordinal != 1 || call.attemptId == null) return false
        val frozen = request.copy(calls = request.calls.toList())
        val previous = admitted
        if (previous != null && previous != frozen) return false
        admitted = frozen
        sourceChecks.incrementAndGet()
        return true
    }

    val hook =
        object : AiHttpExperimentWireHook {
            override fun callStarted(call: AiHttpExperimentCall) = event("CALL_START", call)

            override fun beforeHeaders(call: AiHttpExperimentCall) {
                if (call.operation != AiPhysicalOperation.FINAL_FALLBACK ||
                    call.ordinal != 1 ||
                    call.admissionId == null
                ) {
                    throw AiHttpDispatchException()
                }
                wire.admit()
                event("HEADERS_START", call)
            }

            override fun headersEmitted(call: AiHttpExperimentCall) = event("HEADERS_END", call)

            override fun callFinished(
                call: AiHttpExperimentCall,
                httpStatus: Int?,
                failure: AiHttpExperimentTransportFailure?,
            ) {
                events +=
                    mapOf(
                        "event" to "CALL_FINISHED",
                        "callId" to call.callId,
                        "operation" to call.operation,
                        "httpStatus" to httpStatus,
                        "transportFailure" to failure,
                    )
            }
        }

    private fun event(
        kind: String,
        call: AiHttpExperimentCall,
    ) {
        events += mapOf("event" to kind, "callId" to call.callId, "operation" to call.operation, "ordinal" to call.ordinal)
    }

    fun prepareMock(request: NaturalLanguageBatchRequest) {
        check(!paid)
        val structured =
            mapOf(
                "schema_version" to "3",
                "results" to
                    request.inputs.map { input ->
                        mapOf(
                            "input_ref" to input.inputRef,
                            "rejection_code" to null,
                            "clarification" to null,
                            "conditions" to
                                listOf(
                                    mapOf(
                                        "type" to "TIME_WINDOW",
                                        "polarity" to "AVAILABLE",
                                        "date" to "2026-11-12",
                                        "day_of_week" to null,
                                        "start_time" to "18:00",
                                        "end_time" to "20:00",
                                    ),
                                ),
                        )
                    },
            )
        val envelope =
            mapOf(
                "status" to "completed",
                "output" to
                    listOf(
                        mapOf(
                            "type" to "message",
                            "content" to listOf(mapOf("type" to "output_text", "text" to JSON.writeValueAsString(structured))),
                        ),
                    ),
                "usage" to mapOf("input_tokens" to 100, "output_tokens" to 50),
            )
        mockResponse = JSON.writeValueAsString(envelope)
    }

    fun stop() {
        // No network server is used by the free interceptor fixture.
    }

    companion object {
        val JSON = StandaloneGeminiOnceDiagnostic.JSON
        const val APPROVAL_ID = "Sentinel_cfc3122fe73a6ef1515c5aee945b1001"
        val current: LunaFallbackOnceSettings by lazy { load() }

        internal fun freeForTest(output: Path) = LunaFallbackOnceSettings(false, output)

        internal fun forApplicationContext(): LunaFallbackOnceSettings =
            if (System.getenv("MEETME_LUNA_FALLBACK_MODE") == null) {
                freeForTest(Files.createTempDirectory("luna-fallback-free-"))
            } else {
                current
            }

        private fun load(): LunaFallbackOnceSettings {
            val mode = System.getenv("MEETME_LUNA_FALLBACK_MODE")
            if (mode ==
                null
            ) {
                return LunaFallbackOnceSettings(
                    false,
                    Files.createTempDirectory(Path.of("/workspace/scratch/live-eval"), "luna-fallback-free-"),
                )
            }
            try {
                check(mode == "LUNA_FALLBACK_ONCE")
                val bytes = Files.readAllBytes(Path.of(checkNotNull(System.getenv("MEETME_LUNA_FALLBACK_MANIFEST"))))
                val hash = StandaloneGeminiOnceDiagnostic.sha(bytes)
                check(hash == System.getenv("MEETME_LUNA_FALLBACK_MANIFEST_SHA"))
                val manifest = JSON.readTree(bytes)
                check(manifest["approvalId"].asString() == APPROVAL_ID && manifest["approved"].asBoolean())
                check(manifest["mode"].asString() == "LUNA_FALLBACK_ONCE" && manifest["model"].asString() == "gpt-6-luna")
                check(manifest["maximumPhysicalRequests"].asInt() == 1 && manifest["timeoutMillis"].asInt() == 27_000)
                check(manifest["realGeminiCalls"].asInt() == 0 && manifest["automaticRetries"].asInt() == 0)
                check(manifest["syntheticRetryAfterMillis"].asInt() == 40_000)
                val root = Path.of("/workspace/meet-me-analysis")
                val records = manifest["files"].values().toList()
                check(records.size > 1_000)
                val supplied = records.map { Path.of(it["path"].asString()) }.toSet()
                for (tree in listOf("src/main", "src/test", "build/classes/kotlin/main", "build/classes/kotlin/test")) {
                    Files.walk(root.resolve(tree)).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.forEach { check(it in supplied) }
                    }
                }
                records.forEach {
                    check(
                        StandaloneGeminiOnceDiagnostic.sha(Files.readAllBytes(Path.of(it["path"].asString()))) == it["sha256"].asString(),
                    )
                }
                val git = ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start()
                check(
                    git.inputStream
                        .bufferedReader()
                        .readText()
                        .trim() == manifest["baseRevision"].asString() &&
                        git.waitFor() == 0,
                )
                val planId = manifest["planId"].asString()
                val output = Path.of(manifest["outputDirectory"].asString()).toAbsolutePath().normalize()
                check(output == Path.of("/workspace/scratch/live-eval/luna-fallback-once-$planId"))
                check(!System.getenv("OPENAI_API_KEY").isNullOrBlank())
                StandaloneGeminiOnceDiagnostic.claimApproval(
                    Path.of("/workspace/scratch/live-eval/luna-fallback-approval-claims"),
                    APPROVAL_ID,
                    hash,
                )
                PrimaryGeminiSmokeClaim(
                    Path.of("/workspace/scratch/live-eval/luna-fallback-plan-claims"),
                ).acquire(planId, output, output, hash)
                Files.createDirectories(output)
                Files.writeString(output.resolve("started"), "LUNA_FALLBACK_ONCE\n", StandardOpenOption.CREATE_NEW)
                return LunaFallbackOnceSettings(true, output)
            } catch (_: Throwable) {
                throw IllegalStateException("Luna one-shot preflight refused; no automatic retry")
            }
        }

        fun properties(registry: DynamicPropertyRegistry) {
            // All paid checks/claims precede container and provider startup. Free state is context-owned.
            val settings = if (System.getenv("MEETME_LUNA_FALLBACK_MODE") == null) null else current
            PrimaryGeminiSmokeInfrastructure.properties(registry)
            registry.add("meetme.openai.api-key") {
                if (settings?.paid == true) checkNotNull(System.getenv("OPENAI_API_KEY")) else "synthetic-local-placeholder"
            }
            registry.add("logging.level.root") { "ERROR" }
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class LunaFallbackOnceApplication {
    @Bean
    internal fun onceRunSettings(): LunaFallbackOnceSettings = LunaFallbackOnceSettings.forApplicationContext()

    @Bean @Primary
    fun onceTokens(clock: Clock): AccessTokenPort = PrimaryGeminiSmokeApplication().primarySyntheticTokens(clock)

    @Bean @Primary
    fun onceClear(): PreSubmissionCheckProvider =
        PreSubmissionCheckProvider {
            PrecheckProviderResult(PrecheckVerdict.CLEAR, provider = "TEST_DOUBLE", model = "TEST_DOUBLE")
        }

    @Bean @Primary
    internal fun onceSource(
        delegate: JdbcAiBudgetExecutionFence,
        settings: LunaFallbackOnceSettings,
    ): AiRuntimeDispatchGuardPort =
        AiRuntimeDispatchGuardPort { request, at ->
            delegate.authorize(request, at)?.takeIf { settings.source(request) }
        }

    companion object {
        @Bean @JvmStatic
        internal fun onceProviders(
            props: ObjectProvider<OpenAiProperties>,
            mapper: ObjectProvider<ObjectMapper>,
            gate: ObjectProvider<AiHttpDispatchGate>,
            observed: ObjectProvider<ParserTransportObservationPort>,
            contextSettings: ObjectProvider<LunaFallbackOnceSettings>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when {
                        bean is AiHttpDispatchGate -> bean.also { it.installExperimentWireHook(contextSettings.getObject().hook) }
                        bean is AmbiguityClassificationPort ->
                            AmbiguityClassificationPort {
                                throw AmbiguityClassificationException(
                                    "CONFIGURATION",
                                )
                            }
                        bean is NaturalLanguageParserPort && beanName == "geminiNaturalLanguageParserAdapter" -> {
                            val settings = contextSettings.getObject()
                            NaturalLanguageParserPort {
                                check(settings.primaryCalls.incrementAndGet() == 1)
                                throw NaturalLanguageParserException(ParserFailureKind.SERVER, retryAfterMillis = 40_000)
                            }
                        }
                        bean is NaturalLanguageParserPort && beanName == "lunaParser" -> {
                            val settings = contextSettings.getObject()
                            val client =
                                OkHttpClient
                                    .Builder()
                                    .addInterceptor { chain ->
                                        settings.timings += mapOf("actualCallTimeoutNanos" to chain.call().timeout().timeoutNanos())
                                        if (settings.paid) {
                                            chain.proceed(chain.request())
                                        } else {
                                            check(settings.sourceChecks.get() > 0)
                                            settings.syntheticResponses.incrementAndGet()
                                            Response
                                                .Builder()
                                                .request(chain.request())
                                                .protocol(Protocol.HTTP_1_1)
                                                .code(200)
                                                .message("synthetic application-interceptor response")
                                                .body(settings.mockResponse().toResponseBody("application/json".toMediaType()))
                                                .build()
                                        }
                                    }.build()
                            val adapter =
                                OpenAiLunaNaturalLanguageParserAdapter(
                                    props.getObject(),
                                    mapper.getObject(),
                                    client,
                                    settings.endpoint,
                                    observed.ifAvailable,
                                    gate.getObject(),
                                )
                            NaturalLanguageParserPort { request ->
                                check(settings.lunaCalls.incrementAndGet() == 1)
                                check(request.callTimeout > Duration.ZERO && request.callTimeout <= Duration.ofSeconds(27))
                                if (!settings.paid) settings.prepareMock(request)
                                val started = System.nanoTime()
                                var failure: String? = null
                                var count: Int? = null
                                try {
                                    adapter.parse(request).also { count = it.results.size }
                                } catch (exception: NaturalLanguageParserException) {
                                    failure = exception.kind.name
                                    throw exception
                                } finally {
                                    settings.parseOutcomes +=
                                        mapOf(
                                            "elapsedNanos" to System.nanoTime() - started,
                                            "callerBudgetNanos" to request.callTimeout.toNanos(),
                                            "failureKind" to failure,
                                            "structuredResultCount" to count,
                                        )
                                }
                            }
                        }
                        bean is NaturalLanguageParserPort ->
                            NaturalLanguageParserPort {
                                throw NaturalLanguageParserException(
                                    ParserFailureKind.CONFIGURATION,
                                )
                            }
                        else -> bean
                    }
            }
    }
}
