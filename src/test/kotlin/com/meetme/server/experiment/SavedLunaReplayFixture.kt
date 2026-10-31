package com.meetme.server.experiment

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.PlaceNormalizationResult
import com.meetme.server.coordination.application.port.output.PlaceSearchPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiBudgetReserveRequest
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic by default; explicit local-artifact mode is never selected by ordinary CI. No provider wire is allowed. */
internal object SavedLunaReplayState {
    val json: ObjectMapper = JsonMapper.builder().build()
    private val data: SavedLunaReplayData =
        when (System.getenv("MEETME_SAVED_LUNA_REPLAY_MODE") ?: "SYNTHETIC") {
            "SYNTHETIC" -> SavedLunaReplaySyntheticFixture.data(json)
            "LOCAL_ARTIFACT" -> localArtifactData()
            else -> error("Unsupported replay fixture mode")
        }
    val origin: String = data.origin
    val originalInputs = data.originalInputs
    val question = data.question
    val sourceInput = data.sourceInput
    val responseSourceBytes: ByteArray get() = data.responseBytes.copyOf()
    val answer = checkNotNull(originalInputs.single { it.evidence?.supplement != null }.evidence?.supplement).answerText
    val questionRaw = originalInputs.single { it.evidence?.supplement != null }.rawText
    val output: Path = Files.createTempDirectory("meetme-response-replay-")

    /** Preserves the old local replay path only behind explicit opt-in; no CI/default code reads these files. */
    private fun localArtifactData(): SavedLunaReplayData {
        fun requiredPath(name: String): Path =
            Path.of(
                System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Missing local replay path: $name"),
            )
        return SavedLunaReplayData(
            FinalLunaSixtyPayload.request().inputs,
            json.readTree(Files.readAllBytes(requiredPath("MEETME_SAVED_LUNA_QUESTION_PATH"))).path("question"),
            json.readTree(Files.readAllBytes(requiredPath("MEETME_SAVED_LUNA_INPUT_PATH"))),
            Files.readAllBytes(requiredPath("MEETME_SAVED_LUNA_RESPONSE_PATH")),
            "LOCAL_ARTIFACT",
        )
    }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sourceResults() =
        json
            .readTree(
                json
                    .readTree(responseSourceBytes)
                    .path("output")
                    .values()
                    .single { it.path("type").asText() == "message" }
                    .path("content")
                    .values()
                    .single()
                    .path("text")
                    .asText(),
            ).path("results")

    val sourceChecks = CopyOnWriteArrayList<AiBudgetReserveRequest>()
    val bindings = CopyOnWriteArrayList<Map<String, Any?>>()
    val requests = CopyOnWriteArrayList<NaturalLanguageBatchRequest>()
    val responseCount = AtomicInteger()
    val primaryCount = AtomicInteger()
    val headerAttempts = AtomicInteger()
    val dnsAttempts = AtomicInteger()
    val connectAttempts = AtomicInteger()
    val placeAttempts = AtomicInteger()
    private val immutableSources = mutableMapOf<String, AiBudgetReserveRequest>()

    @Suppress("UNCHECKED_CAST")
    fun remap(mapping: Map<String, String>): ByteArray {
        require(mapping.keys == originalInputs.map { it.inputRef }.toSet())
        require(
            mapping.values.size == mapping.values.toSet().size &&
                mapping.values.all { runCatching { java.util.UUID.fromString(it) }.isSuccess },
        )
        val envelope = json.readValue(responseSourceBytes, Map::class.java) as Map<String, Any?>
        var replaced = 0
        val messages =
            (envelope.getValue("output") as List<Map<String, Any?>>).map { message ->
                if (message["type"] != "message") {
                    message
                } else {
                    message + (
                        "content" to
                            (message.getValue("content") as List<Map<String, Any?>>).map { part ->
                                if (part["type"] != "output_text") {
                                    part
                                } else {
                                    replaced++
                                    val structured = json.readValue(part.getValue("text") as String, Map::class.java) as Map<String, Any?>
                                    val results = structured.getValue("results") as List<Map<String, Any?>>
                                    check(results.map { it["input_ref"] as String }.toSet() == mapping.keys && results.size == 3)
                                    part +
                                        (
                                            "text" to
                                                json.writeValueAsString(
                                                    structured +
                                                        (
                                                            "results" to
                                                                results.map {
                                                                    it + (
                                                                        "input_ref" to
                                                                            mapping.getValue(
                                                                                it["input_ref"] as String,
                                                                            )
                                                                    )
                                                                }
                                                        ),
                                                )
                                        )
                                }
                            }
                    )
                }
            }
        check(replaced == 1)
        return json.writeValueAsBytes(envelope + ("output" to messages))
    }

    @Synchronized
    fun source(
        delegate: JdbcAiBudgetExecutionFence,
        request: AiBudgetReserveRequest,
        at: java.time.Instant,
    ): java.time.Instant? {
        val deadline = delegate.authorize(request, at) ?: return null
        check(request.calls.size == 1 && request.calls.single().operation == AiPhysicalOperation.FINAL_FALLBACK)
        val key = "${request.execution.executionId}:${request.calls.single().attemptId}"
        val previous = immutableSources.putIfAbsent(key, request.copy(calls = request.calls.toList()))
        check(previous == null || previous == request)
        sourceChecks += request
        return deadline
    }

    val hook =
        object : AiHttpExperimentWireHook {
            override fun callStarted(call: AiHttpExperimentCall) = Unit

            override fun beforeHeaders(call: AiHttpExperimentCall) {
                headerAttempts.incrementAndGet()
                error("Replay forbids external headers")
            }

            override fun headersEmitted(call: AiHttpExperimentCall) = error("Replay emitted external headers")

            override fun callFinished(
                call: AiHttpExperimentCall,
                httpStatus: Int?,
                failure: AiHttpExperimentTransportFailure?,
            ) = Unit
        }

    fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        dnsAttempts.incrementAndGet()
                        throw UnknownHostException("REPLAY_NO_NETWORK")
                    }
                },
            ).eventListener(
                object : EventListener() {
                    override fun connectStart(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                    ) {
                        connectAttempts.incrementAndGet()
                        error("REPLAY_NO_CONNECT")
                    }
                },
            ).addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "POST" && request.url.toString() == "https://api.openai.com/v1/responses")
                val bytes = Buffer().also { checkNotNull(request.body).writeTo(it) }.readByteArray()
                val hash = sha(bytes)
                check(sourceChecks.any { it.calls.single().wholePayloadFingerprint == hash })
                val body = json.readTree(bytes)
                val inputs =
                    body
                        .path("input")
                        .asText()
                        .substringAfterLast("Inputs:\n")
                        .lineSequence()
                        .filter {
                            it.isNotBlank()
                        }.map { json.readTree(it) }
                        .toList()
                check(inputs.size == 3)
                val mapping =
                    inputs.associate { current ->
                        val original = originalInputs.single { it.rawText == current.path("raw_text").asText() }
                        val evidence = checkNotNull(original.evidence)
                        check(current.path("evidence").path("interpretation").asText() == evidence.interpretation.name)
                        if (evidence.supplement != null) {
                            check(
                                current
                                    .path("evidence")
                                    .path("supplement")
                                    .path("answer_text")
                                    .asText() == answer,
                            )
                        }
                        original.inputRef to current.path("input_ref").asText()
                    }
                check(responseCount.incrementAndGet() <= 2)
                bindings +=
                    mapOf(
                        "mapping" to mapping,
                        "requestSha256" to hash,
                        "sourceResponseSha256" to sha(responseSourceBytes),
                    )
                Response
                    .Builder()
                    .request(
                        request,
                    ).protocol(
                        Protocol.HTTP_1_1,
                    ).code(
                        200,
                    ).message(
                        "${origin.lowercase()} response replay, no provider wire",
                    ).body(remap(mapping).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    fun properties(registry: DynamicPropertyRegistry) {
        PrimaryGeminiSmokeInfrastructure.properties(registry)
        registry.add("meetme.openai.api-key") { "synthetic-replay-only" }
        registry.add("logging.level.root") { "ERROR" }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class SavedLunaReplayApplication {
    @Bean @Primary
    fun replayTokens(clock: Clock): AccessTokenPort = PrimaryGeminiSmokeApplication().primarySyntheticTokens(clock)

    @Bean @Primary
    fun replayPrechecks(): PreSubmissionCheckProvider =
        PreSubmissionCheckProvider { request ->
            if (request.rawText == SavedLunaReplayState.questionRaw) {
                if (request.evidence?.supplement ==
                    null
                ) {
                    PrecheckProviderResult(
                        PrecheckVerdict.NEEDS_CHOICE,
                        prompt = SavedLunaReplayState.question.path("text").asText(),
                        sourceExcerpt = SavedLunaReplayState.question.path("source_excerpt").asText(),
                    )
                } else {
                    PrecheckProviderResult(PrecheckVerdict.UNRESOLVED, unresolvedReason = originalReviewReason())
                }
            } else {
                check(SavedLunaReplayState.originalInputs.any { it.rawText == request.rawText })
                PrecheckProviderResult(PrecheckVerdict.CLEAR)
            }
        }

    private fun originalReviewReason(): String =
        checkNotNull(
            SavedLunaReplayState.originalInputs
                .single {
                    it.rawText ==
                        SavedLunaReplayState.questionRaw
                }.evidence
                ?.unresolvedReason,
        )

    @Bean @Primary
    fun replaySource(delegate: JdbcAiBudgetExecutionFence): AiRuntimeDispatchGuardPort =
        AiRuntimeDispatchGuardPort { request, at -> SavedLunaReplayState.source(delegate, request, at) }

    @Bean @Primary
    fun replayPlaces(): PlaceSearchPort =
        object : PlaceSearchPort {
            override fun normalize(query: String): PlaceNormalizationResult {
                SavedLunaReplayState.placeAttempts.incrementAndGet()
                error("Replay forbids external place search")
            }
        }

    companion object {
        @Bean @JvmStatic
        fun replayProviders(
            properties: ObjectProvider<OpenAiProperties>,
            mapper: ObjectProvider<ObjectMapper>,
            gate: ObjectProvider<AiHttpDispatchGate>,
            observed: ObjectProvider<ParserTransportObservationPort>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when {
                        bean is AiHttpDispatchGate -> bean.also { it.installExperimentWireHook(SavedLunaReplayState.hook) }
                        bean is AmbiguityClassificationPort -> AmbiguityClassificationPort { error("Replay forbids Jev classification") }
                        bean is NaturalLanguageParserPort && beanName == "geminiNaturalLanguageParserAdapter" ->
                            NaturalLanguageParserPort {
                                check(SavedLunaReplayState.primaryCount.incrementAndGet() <= 2)
                                throw NaturalLanguageParserException(ParserFailureKind.SERVER, retryAfterMillis = 40000)
                            }
                        bean is NaturalLanguageParserPort && beanName == "lunaParser" -> {
                            val adapter =
                                OpenAiLunaNaturalLanguageParserAdapter(
                                    properties.getObject(),
                                    mapper.getObject(),
                                    SavedLunaReplayState.client(),
                                    URI("https://api.openai.com/v1/responses"),
                                    observed.ifAvailable,
                                    gate.getObject(),
                                )
                            NaturalLanguageParserPort { request ->
                                SavedLunaReplayState.requests += request
                                adapter.parse(request)
                            }
                        }
                        bean is NaturalLanguageParserPort -> NaturalLanguageParserPort { error("Replay forbids other parsers") }
                        else -> bean
                    }
            }
    }
}
