package com.meetme.server.experiment

import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.coordination.adapter.output.integration.OpenAiLunaNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.PlaceNormalizationResult
import com.meetme.server.coordination.application.port.output.PlaceSearchPort
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationResult
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
import tools.jackson.databind.JsonNode
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Fresh synthetic development fixture, not a blind scenario or a paid execution mode. */
internal object FreeLabelFlowState {
    const val QUESTION_RAW = "2026년 11월 12일 퇴근 후 합성 공통 카페에서 가능해요."
    const val LUNA_CLEAR_RAW = "2026년 11월 12일 오후 6시부터 8시까지 합성 공통 카페에서 가능해요."
    const val DIRECT_CLEAR_RAW = "2026년 11월 12일 18시부터 20시까지 합성 공통 카페에서 가능해요."
    const val EXCERPT = "퇴근 후"
    const val PROMPT = "11월 12일 퇴근 후 가능한 시작 시각을 알려주세요."
    const val ANSWER = "아직 퇴근 시각은 정해지지 않았어요."
    val json: ObjectMapper = JsonMapper.builder().build()
    val output: Path = Files.createTempDirectory("free-label-flow-")
    val primaryCalls = AtomicInteger()
    val dnsAttempts = AtomicInteger()
    val connectionAttempts = AtomicInteger()
    val headerAttempts = AtomicInteger()
    val placeSearchAttempts = AtomicInteger()
    val sourceDenials = AtomicInteger()
    val sourceChecks = CopyOnWriteArrayList<AiBudgetReserveRequest>()
    val classifications = CopyOnWriteArrayList<AmbiguityClassificationResult>()
    val captured = CopyOnWriteArrayList<Pair<AiPhysicalOperation, JsonNode>>()
    private val sourceIdentities = ConcurrentHashMap<String, AiBudgetReserveRequest>()

    fun authorized(request: AiBudgetReserveRequest): Boolean {
        val call = request.calls.singleOrNull() ?: return false
        if (call.operation !in EXPECTED_OPERATIONS || call.ordinal != 1) return false
        val frozen = request.copy(calls = request.calls.toList())
        val key = "${request.execution.executionId}:${request.execution.generation}:${call.operation}:${call.ordinal}:${call.attemptId}"
        val previous = sourceIdentities.putIfAbsent(key, frozen)
        if (previous != null && previous != frozen) return false
        sourceChecks += frozen
        return true
    }

    val hook =
        object : AiHttpExperimentWireHook {
            override fun callStarted(call: AiHttpExperimentCall) = Unit

            override fun beforeHeaders(call: AiHttpExperimentCall) {
                headerAttempts.incrementAndGet()
                error("Free fixture forbids provider wire headers")
            }

            override fun headersEmitted(call: AiHttpExperimentCall) {
                error("Free fixture emitted provider wire headers")
            }

            override fun callFinished(
                call: AiHttpExperimentCall,
                httpStatus: Int?,
                failure: AiHttpExperimentTransportFailure?,
            ) = Unit
        }

    /** No branch calls chain.proceed. DNS additionally denies any accidental escape to a real network. */
    fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        dnsAttempts.incrementAndGet()
                        throw UnknownHostException("FREE_LABEL_FLOW_NO_NETWORK")
                    }
                },
            ).eventListener(
                object : EventListener() {
                    override fun connectStart(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                    ) {
                        connectionAttempts.incrementAndGet()
                    }
                },
            ).addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "POST")
                val buffer = Buffer()
                checkNotNull(request.body).writeTo(buffer)
                val bytes = buffer.readByteArray()
                val body = json.readTree(bytes)
                val operation =
                    when {
                        request.url.host == "api.typesafe.ai" -> AiPhysicalOperation.PRECHECK_CLASSIFICATION
                        request.url.host == "api.openai.com" &&
                            body
                                .path("text")
                                .path("format")
                                .path("name")
                                .asText() ==
                            "meet_me_personal_check_v2" -> AiPhysicalOperation.PRECHECK_GENERATION
                        request.url.host == "api.openai.com" -> AiPhysicalOperation.FINAL_FALLBACK
                        else -> error("Unexpected synthetic provider target")
                    }
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(
                    sourceChecks.any { source ->
                        source.calls.any { it.operation == operation && it.wholePayloadFingerprint == digest }
                    },
                ) {
                    "Synthetic response preceded original JDBC source authorization"
                }
                captured += operation to body
                check(captured.count { it.first == operation } <= MAXIMUM_RESPONSES.getValue(operation))
                val response =
                    when (operation) {
                        AiPhysicalOperation.PRECHECK_CLASSIFICATION -> classification(body.path("state"))
                        AiPhysicalOperation.PRECHECK_GENERATION -> personal(body)
                        AiPhysicalOperation.FINAL_FALLBACK -> final(body)
                        else -> error("Unexpected synthetic operation")
                    }
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("synthetic application-interceptor response; no provider wire")
                    .body(json.writeValueAsString(response).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private fun classification(state: JsonNode): Map<String, Any?> {
        val label =
            when (state.path("raw_text").asText()) {
                QUESTION_RAW -> if (state.path("evidence").path("supplement").isObject) "INDETERMINATE" else "NEEDS_REVIEW"
                LUNA_CLEAR_RAW -> "INDETERMINATE"
                DIRECT_CLEAR_RAW -> "CLEAR"
                else -> error("Unexpected synthetic original")
            }
        return mapOf(
            "model" to "jev-1.14.0",
            "answers" to
                mapOf(
                    "material_ambiguity" to
                        mapOf(
                            "type" to "choice",
                            "choice" to label,
                            "probabilities" to
                                listOf("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").associateWith { if (it == label) 0.6 else 0.2 },
                            "confidence" to 0.4,
                        ),
                ),
            "usage" to mapOf("input_tokens" to 100, "output_tokens" to 10),
        )
    }

    private fun personal(body: JsonNode): Map<String, Any?> {
        check(body.path("store").isBoolean && !body.path("store").asBoolean())
        check(
            body
                .path("text")
                .path("format")
                .path("strict")
                .asBoolean(),
        )
        val input = json.readTree(body.path("input").asText())
        check(!input.path("evidence").path("supplement").isObject) { "A second generation followed a supplement" }
        val question = input.path("raw_text").asText() == QUESTION_RAW
        check(question || input.path("raw_text").asText() == LUNA_CLEAR_RAW)
        return envelope(
            mapOf(
                "verdict" to if (question) "NEEDS_CHOICE" else "CLEAR",
                "prompt" to if (question) PROMPT else null,
                "sourceExcerpt" to if (question) EXCERPT else null,
                "choices" to emptyList<Any>(),
            ),
        )
    }

    fun finalInputs(): List<JsonNode> = finalInputs(captured.single { it.first == AiPhysicalOperation.FINAL_FALLBACK }.second)

    private fun finalInputs(body: JsonNode): List<JsonNode> =
        body
            .path("input")
            .asText()
            .substringAfterLast("Inputs:\n")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { json.readTree(it) }
            .toList()

    private fun final(body: JsonNode): Map<String, Any?> {
        val inputs = finalInputs(body)
        check(inputs.size == 3)
        check(inputs.map { it.path("raw_text").asText() }.toSet() == setOf(QUESTION_RAW, LUNA_CLEAR_RAW, DIRECT_CLEAR_RAW))
        return envelope(
            mapOf<String, Any?>(
                "schema_version" to "3",
                "results" to
                    inputs.map { input ->
                        val rejected = input.path("raw_text").asText() == QUESTION_RAW
                        mapOf<String, Any?>(
                            "input_ref" to input.path("input_ref").asText(),
                            "rejection_code" to if (rejected) "AMBIGUOUS_TIME_CONSTRAINT" else null,
                            "clarification" to null,
                            "conditions" to
                                if (rejected) {
                                    emptyList<Map<String, String?>>()
                                } else {
                                    listOf(
                                        mapOf(
                                            "type" to "TIME_WINDOW",
                                            "polarity" to "AVAILABLE",
                                            "date" to "2026-11-12",
                                            "day_of_week" to null,
                                            "start_time" to "18:00",
                                            "end_time" to "20:00",
                                        ),
                                        mapOf(
                                            "type" to "SPECIFIC_PLACE",
                                            "query" to "합성 공통 카페",
                                            "area_key" to "AREA_1",
                                            "area_name" to "합성 공통 카페",
                                        ),
                                    )
                                },
                        )
                    },
            ),
        )
    }

    private fun envelope(payload: Any): Map<String, Any?> =
        mapOf(
            "status" to "completed",
            "output" to
                listOf(
                    mapOf(
                        "type" to "message",
                        "content" to listOf(mapOf("type" to "output_text", "text" to json.writeValueAsString(payload))),
                    ),
                ),
            // Synthetic metadata validates parsing only; it is not measured model usage or billing.
            "usage" to mapOf("input_tokens" to 100, "output_tokens" to 50),
        )

    fun properties(registry: DynamicPropertyRegistry) {
        PrimaryGeminiSmokeInfrastructure.properties(registry)
        registry.add("meetme.openai.api-key") { "synthetic-free-local-placeholder" }
        registry.add("meetme.typesafe.api-key") { "synthetic-free-local-placeholder" }
        registry.add("meetme.precheck.classifier.minimum-confidence") { "0.8" }
        registry.add("logging.level.root") { "ERROR" }
    }

    private val MAXIMUM_RESPONSES =
        mapOf(
            AiPhysicalOperation.PRECHECK_CLASSIFICATION to 4,
            AiPhysicalOperation.PRECHECK_GENERATION to 2,
            AiPhysicalOperation.FINAL_FALLBACK to 1,
        )
    private val EXPECTED_OPERATIONS = MAXIMUM_RESPONSES.keys
}

/** Original provider adapters and router remain real; only external response bytes are synthetic. */
@TestConfiguration(proxyBeanMethods = false)
class FreeLabelFlowApplication {
    @Bean @Primary
    fun freeLabelTokens(clock: Clock): AccessTokenPort = PrimaryGeminiSmokeApplication().primarySyntheticTokens(clock)

    @Bean @Primary
    fun freeLabelTransport(): PrecheckProviderHttpTransport = PrecheckProviderHttpTransport(FreeLabelFlowState.client())

    @Bean @Primary
    fun freeLabelPlaces(): PlaceSearchPort =
        object : PlaceSearchPort {
            override fun normalize(query: String): PlaceNormalizationResult {
                FreeLabelFlowState.placeSearchAttempts.incrementAndGet()
                error("Free fixture forbids external place search; explicit common AREA must suffice")
            }
        }

    @Bean @Primary
    fun freeLabelSource(delegate: JdbcAiBudgetExecutionFence): AiRuntimeDispatchGuardPort =
        AiRuntimeDispatchGuardPort { request, at ->
            val deadline = delegate.authorize(request, at)
            if (deadline == null || !FreeLabelFlowState.authorized(request)) {
                FreeLabelFlowState.sourceDenials.incrementAndGet()
                null
            } else {
                deadline
            }
        }

    companion object {
        @Bean @JvmStatic
        fun freeLabelProviders(
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
                        bean is AiHttpDispatchGate -> bean.also { it.installExperimentWireHook(FreeLabelFlowState.hook) }
                        bean is AmbiguityClassificationPort ->
                            AmbiguityClassificationPort { request ->
                                bean.classify(request).also { FreeLabelFlowState.classifications += it }
                            }
                        bean is NaturalLanguageParserPort && beanName == "geminiNaturalLanguageParserAdapter" ->
                            NaturalLanguageParserPort {
                                check(FreeLabelFlowState.primaryCalls.incrementAndGet() == 1)
                                throw NaturalLanguageParserException(ParserFailureKind.SERVER, retryAfterMillis = 40_000)
                            }
                        bean is NaturalLanguageParserPort && beanName == "lunaParser" ->
                            OpenAiLunaNaturalLanguageParserAdapter(
                                properties.getObject(),
                                mapper.getObject(),
                                FreeLabelFlowState.client(),
                                URI("https://api.openai.com/v1/responses"),
                                observed.ifAvailable,
                                gate.getObject(),
                            )
                        bean is NaturalLanguageParserPort ->
                            NaturalLanguageParserPort { throw NaturalLanguageParserException(ParserFailureKind.CONFIGURATION) }
                        else -> bean
                    }
            }
    }
}
