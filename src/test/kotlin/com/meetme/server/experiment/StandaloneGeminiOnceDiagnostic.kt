package com.meetme.server.experiment

import com.google.genai.Client
import com.google.genai.types.ClientOptions
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.ParserTransportObservationSession
import com.meetme.server.coordination.adapter.output.integration.ProviderFailureClassifier
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** Test-only direct SDK probe. No app adapter, source authority, fallback, Spring or database. */
internal class StandaloneGeminiOnceDiagnostic(
    private val output: Path,
    private val planId: UUID,
) {
    private val diagnostics = mutableListOf<ParserTransportDiagnosticSnapshot>()
    private val observations = mutableListOf<ParserTransportSnapshot>()
    private val ledger = OnceLedger(output.resolve("wire-ledger.json"))

    fun run(
        apiKey: String,
        endpoint: URI = ENDPOINT,
        baseClient: OkHttpClient = OkHttpClient(),
        timeout: Duration = TIMEOUT,
    ): Map<String, Any?> {
        require(endpoint == ENDPOINT || endpoint.host in setOf("localhost", "127.0.0.1"))
        require(!timeout.isNegative && !timeout.isZero && timeout <= TIMEOUT)
        val observation =
            ParserTransportObservationSession(
                ParserObservationContext(planId, planId),
                AnalysisProvider.GEMINI,
                ParserTransportObservationPort { observations += it },
                ParserTransportDiagnosticPort { diagnostics += it },
            )
        observation.configureTimeouts(timeout.toNanos(), timeout.toNanos(), null)
        val client =
            baseClient
                .newBuilder()
                .eventListenerFactory(observation.eventListenerFactory)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .connectTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ZERO)
                .callTimeout(timeout)
                .addNetworkInterceptor { chain ->
                    val url = chain.request().url.toUri()
                    if (url.scheme != endpoint.scheme ||
                        url.host != endpoint.host ||
                        url.port != endpoint.port ||
                        url.path != "/v1beta/models/$MODEL:generateContent" ||
                        chain.request().method != "POST"
                    ) {
                        throw LocalOnceDenied()
                    }
                    val body = chain.request().body ?: throw LocalOnceDenied()
                    if (body.contentLength() !in 1..MAX_WIRE_BYTES) throw LocalOnceDenied()
                    ledger.admit()
                    chain.proceed(chain.request())
                }.build()
        val retry =
            HttpRetryOptions
                .builder()
                .attempts(1)
                .initialDelay(0.0)
                .maxDelay(0.0)
                .jitter(0.0)
                .build()
        var success = false
        var failure: String? = null
        var finish: String? = null
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var thoughtTokens: Int? = null
        var totalTokens: Int? = null
        var sdkTimeoutMillis: Long? = null
        val started = System.nanoTime()
        val deadline = started + timeout.toNanos()
        try {
            Client
                .builder()
                .apiKey(apiKey)
                .vertexAI(false)
                .clientOptions(ClientOptions.builder().customHttpClient(client).build())
                .httpOptions(
                    HttpOptions
                        .builder()
                        .baseUrl(endpoint.toString())
                        .timeout(timeout.toMillis().toInt())
                        .retryOptions(retry)
                        .build(),
                ).build()
                .use { sdk ->
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) throw LocalOnceDenied()
                    sdkTimeoutMillis = remaining
                    val config =
                        GenerateContentConfig
                            .builder()
                            .httpOptions(
                                HttpOptions
                                    .builder()
                                    .timeout(remaining.toInt())
                                    .retryOptions(retry)
                                    .build(),
                            ).candidateCount(1)
                            .maxOutputTokens(MAX_OUTPUT_TOKENS)
                            .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.LOW).build())
                            .responseMimeType("application/json")
                            .responseJsonSchema(SCHEMA)
                            .build()
                    val response = sdk.models.generateContent(MODEL, PROMPT, config)
                    val usage = response.usageMetadata().orElse(null)
                    inputTokens = usage?.promptTokenCount()?.orElse(null)
                    outputTokens = usage?.candidatesTokenCount()?.orElse(null)
                    thoughtTokens = usage?.thoughtsTokenCount()?.orElse(null)
                    totalTokens = usage?.totalTokenCount()?.orElse(null)
                    val candidates = response.candidates().orElse(emptyList())
                    finish =
                        candidates
                            .singleOrNull()
                            ?.finishReason()
                            ?.orElse(null)
                            ?.knownEnum()
                            ?.name
                    val text = response.text()
                    success =
                        System.nanoTime() < deadline &&
                        candidates.size == 1 &&
                        finish == "STOP" &&
                        text != null &&
                        text.toByteArray().size <= 1_024 &&
                        strictResponse(text)
                    if (!success) failure = "INVALID_RESPONSE"
                }
        } catch (exception: Exception) {
            failure =
                if (generateSequence<Throwable>(exception) { it.cause }.take(16).any { it is LocalOnceDenied }) {
                    "LOCAL_GUARD"
                } else {
                    ProviderFailureClassifier.classify(exception).name
                }
        } finally {
            observation.finish()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        val elapsed = System.nanoTime() - started
        val calls = diagnostics.flatMap { it.calls }
        val headersStarted = calls.sumOf { call -> call.events.count { it.kind.name == "REQUEST_HEADERS_START" } }
        val headersEmitted = calls.sumOf { call -> call.events.count { it.kind.name == "REQUEST_HEADERS_END" } }
        val httpStatus = observations.flatMap { it.calls }.mapNotNull { it.httpStatus }.singleOrNull()
        // Status itself is captured by the normal observation; request/response bodies and headers are never exported.
        val report =
            mapOf(
                "planId" to planId.toString(),
                "mode" to "STANDALONE_TINY_SDK_ONCE30",
                "model" to MODEL,
                "sdkVersion" to "1.72.0",
                "layer" to "GenAI SDK -> OkHttp; application adapter and gate are not invoked",
                "success" to success,
                "failureKind" to failure,
                "finishReason" to finish,
                "requestedTimeoutNanos" to timeout.toNanos(),
                "sdkRequestTimeoutMillis" to sdkTimeoutMillis,
                "elapsedNanos" to elapsed,
                "maximumOutputTokens" to MAX_OUTPUT_TOKENS,
                "promptUtf8Bytes" to PROMPT.toByteArray().size,
                "maximumWireBytes" to MAX_WIRE_BYTES,
                "inputTokens" to inputTokens,
                "outputTokens" to outputTokens,
                "thoughtTokens" to thoughtTokens,
                "totalTokens" to totalTokens,
                "billedCost" to null,
                "billedCostObserved" to false,
                "admissions" to ledger.admissions,
                "headersStarted" to headersStarted,
                "headersEmitted" to headersEmitted,
                "httpStatus" to httpStatus,
                "httpResponseHeadersObserved" to (httpStatus != null),
                "transport" to diagnostics.toList(),
                "automaticRetries" to 0,
                "fallbackCalls" to 0,
                "jevCalls" to 0,
                "lunaCalls" to 0,
                "limitations" to
                    listOf(
                        "Tiny diagnostic prompt/schema differs from service payload",
                        "Not an application E2E or blind semantic evaluation",
                    ),
            )
        Files.writeString(output.resolve("results.json"), JSON.writeValueAsString(report) + "\n", StandardOpenOption.CREATE_NEW)
        return report
    }

    internal class OnceLedger(
        private val path: Path,
    ) {
        var admissions = 0
            private set

        init {
            Files.createDirectories(path.parent)
            Files.writeString(path, "{\"admissions\":0,\"maximum\":1}\n", StandardOpenOption.CREATE_NEW)
        }

        @Synchronized
        fun admit() {
            if (admissions != 0) throw LocalOnceDenied()
            admissions = 1
            val pending = path.resolveSibling(path.fileName.toString() + ".pending")
            FileChannel.open(pending, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW).use {
                it.write(java.nio.ByteBuffer.wrap("{\"admissions\":1,\"maximum\":1}\n".toByteArray()))
                it.force(true)
            }
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private class LocalOnceDenied : IOException("LOCAL_GUARD")

    companion object {
        val JSON =
            JsonMapper
                .builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build()
        val ENDPOINT: URI = URI.create("https://generativelanguage.googleapis.com")
        val TIMEOUT: Duration = Duration.ofSeconds(30)
        val MODEL: String = GeminiProperties().model
        const val MAX_OUTPUT_TOKENS = 128
        const val MAX_WIRE_BYTES = 4_096L
        const val PROMPT = "Convert synthetic availability 2026-11-13 19:00-20:00 into date, start, end. Times must be HH:mm."
        val SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to
                    mapOf("date" to mapOf("type" to "string"), "start" to mapOf("type" to "string"), "end" to mapOf("type" to "string")),
                "required" to listOf("date", "start", "end"),
            )

        fun strictResponse(text: String): Boolean {
            val root = JSON.readTree(text)
            return root.isObject &&
                root.properties().map { it.key }.toSet() == setOf("date", "start", "end") &&
                root["date"].isString &&
                root["date"].asString() == "2026-11-13" &&
                root["start"].isString &&
                root["start"].asString() == "19:00" &&
                root["end"].isString &&
                root["end"].asString() == "20:00"
        }

        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun claimApproval(
            directory: Path,
            approvalId: String,
            manifestSha: String,
        ): Path {
            require(approvalId.matches(Regex("Sentinel_[a-f0-9]{32}")))
            require(manifestSha.matches(Regex("[a-f0-9]{64}")))
            Files.createDirectories(directory)
            val claim = directory.resolve("$approvalId.claim")
            Files.writeString(claim, "manifestSha256=$manifestSha\n", StandardOpenOption.CREATE_NEW)
            return claim
        }
    }
}
