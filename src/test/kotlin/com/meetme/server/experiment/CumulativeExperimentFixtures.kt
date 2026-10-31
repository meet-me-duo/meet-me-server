package com.meetme.server.experiment

import com.google.genai.errors.GenAiIOException
import com.meetme.server.account.adapter.output.security.Rs256AccessTokenAdapter
import com.meetme.server.account.application.port.output.AccessTokenPort
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserPort
import com.meetme.server.coordination.application.port.output.ParserDiagnosticCause
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationReason
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentCall
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentTransportFailure
import com.meetme.server.shared.adapter.output.aihttp.AiHttpExperimentWireHook
import com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.shared.application.port.output.AiProviderDispatchContext
import com.meetme.server.shared.application.port.output.AiRuntimeDispatchGuardPort
import okhttp3.internal.http2.StreamResetException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** The actual entry loads this before containers, output creation, beans or any provider work. */
data class CumulativeExperimentSettings(
    val manifest: JsonNode,
    val payload: JsonNode,
    val answers: JsonNode,
    val diagnostic: JsonNode,
    val outputDirectory: Path,
    val ledger: ExperimentCallLedger,
) {
    override fun toString(): String = "CumulativeExperimentSettings(<redacted>)"

    companion object {
        private val json = JsonMapper.builder().build()
        private val project = Path.of("/workspace/meet-me-analysis")
        private val base = Path.of("/workspace/scratch/live-eval")
        private val runtime =
            listOf(
                "src/test/kotlin/com/meetme/server/experiment/ExperimentCallLedger.kt",
                "src/test/kotlin/com/meetme/server/experiment/CumulativeProviderEvaluationTest.kt",
                "src/test/kotlin/com/meetme/server/experiment/CumulativeExperimentFixtures.kt",
                "src/test/kotlin/com/meetme/server/experiment/CumulativeExperimentHttpFlow.kt",
                "src/test/kotlin/com/meetme/server/experiment/CumulativeExperimentDriver.kt",
            )
        private val buildFiles =
            listOf(
                "build.gradle.kts",
                "settings.gradle.kts",
                "gradlew",
                "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties",
            )
        private val external =
            listOf(
                Path.of("/workspace/scratch/analysis-gradle"),
                base.resolve("live-eval.init.gradle"),
                base.resolve("cumulative10-preparation-20261029/run-cumulative10.py"),
            )

        fun load(): CumulativeExperimentSettings =
            try {
                loadChecked()
            } catch (_: Exception) {
                // Context startup must not print JSON parser excerpts, fixture content or credentials.
                throw IllegalStateException("Capped experiment approval or source preflight rejected")
            }

        private fun loadChecked(): CumulativeExperimentSettings {
            check(required("MEETME_CAPPED_EVAL_MODE") == "CUMULATIVE10")
            listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "TYPESAFE_API_KEY").forEach { required(it) }
            val manifestBytes = bounded(Path.of(required("MEETME_CAPPED_EVAL_MANIFEST")), 2_097_152)
            val manifest = json.readTree(manifestBytes)
            check(manifest.path("approved").asBoolean(false) && manifest.path("sourceTreeClean").asBoolean(false))
            check(manifest.path("schemaVersion").asText() == "meetme-cumulative10-run-plan/v1")
            check(manifest.path("maximumPhysicalCalls").asInt() == 10)
            val config = manifest.path("config")
            check(config.path("maximumPhysicalCalls").asInt() == 10 && config.path("conservativeAdmissionCap").asInt() == 10)
            check(config.path("sameNormalizedTechnicalErrorLimit").asInt() == 2)
            check(config.path("maximumConnectivityAttempts").asInt() == 2 && config.path("maximumRealE2ERooms").asInt() == 1)
            check(config.path("geminiCallMaximumSeconds").asInt() == 15 && config.path("classifierThreshold").asDouble() == 0.8)
            check(!config.path("promptsAndProductionRetryPolicyChanged").asBoolean(true))
            check(!config.path("sdkAutomaticRetries").asBoolean(true))
            check(hash(canonical(config).toByteArray()) == manifest.path("configSha256").asText())
            val expected = mutableSetOf<Path>()
            listOf("src/main", "src/test/resources").forEach { directory ->
                Files.walk(project.resolve(directory)).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.forEach { expected.add(it) }
                }
            }
            (runtime + buildFiles).forEach { expected.add(project.resolve(it)) }
            expected.addAll(external)
            val files = manifest.path("files")
            check(files.isArray)
            val supplied =
                files.values().map { item ->
                    Path.of(item.path("path").asText()).let { if (it.isAbsolute) it else project.resolve(it) }.normalize()
                }
            check(supplied.size == supplied.toSet().size && supplied.toSet() == expected)
            files.values().zip(supplied).forEach { (item, path) -> check(hash(Files.readAllBytes(path)) == item.path("sha256").asText()) }
            check(hash(canonical(files).toByteArray()) == manifest.path("sourceSha256").asText())
            check(git("rev-parse", "HEAD").trim() == manifest.path("sourceBaseRevision").asText())
            check(git("status", "--porcelain").isBlank())
            val payload = fixture(manifest, "payloadPath", "payloadSha256")
            val answers = fixture(manifest, "answerOraclePath", "answerOracleSha256")
            val diagnostic = fixture(manifest, "diagnosticFixturePath", "diagnosticFixtureSha256")
            check(payload.path("rooms").size() == 1 && answers.path("rooms").size() == 1)
            check(payload.path("rooms").get(0).path("roomId") == answers.path("rooms").get(0).path("roomId"))
            check(
                payload
                    .path("rooms")
                    .get(0)
                    .path("participants")
                    .size() == 3,
            )
            check(diagnostic.path("precheck").asText() == "CLEAR_TEST_DOUBLE_DIAGNOSTIC_ONLY")
            check(diagnostic.path("submittedInputs").size() == 3)
            check(payload.path("rooms").get(0).path("referenceAt") == diagnostic.path("referenceAt"))
            val runText = manifest.path("runId").asText()
            val run = UUID.fromString(runText)
            check(run.toString() == runText)
            val output = Path.of(required("MEETME_CAPPED_EVAL_OUTPUT")).toAbsolutePath().normalize()
            check(output.startsWith(base) && output != base && !Files.exists(output))
            check(output == Path.of(manifest.path("outputDirectory").asText()).toAbsolutePath().normalize())
            check(Path.of(manifest.path("ledgerPath").asText()) == output.resolve("call-ledger.json"))
            check(Path.of(manifest.path("questionRequestDirectory").asText()) == output.resolve("questions"))
            val decisions = output.resolve("answer-decisions")
            check(Path.of(manifest.path("answerDecisionDirectory").asText()) == decisions)
            System.getenv("MEETME_CAPPED_EVAL_ANSWER_DECISIONS")?.let { check(Path.of(it).toAbsolutePath().normalize() == decisions) }
            val claim = base.resolve("cumulative10-claims").resolve("$runText.claim")
            check(Path.of(manifest.path("claimPath").asText()) == claim && !Files.exists(claim))
            // One-use approval survives every later failure. Never delete or refund this marker.
            Files.createDirectories(claim.parent)
            Files.writeString(claim, hash(manifestBytes), StandardOpenOption.CREATE_NEW)
            Files.createDirectories(output.parent)
            Files.createDirectory(output)
            Files.writeString(output.resolve("started"), "CUMULATIVE10", StandardOpenOption.CREATE_NEW)
            val ledger =
                ExperimentCallLedger(
                    output.resolve("call-ledger.json"),
                    run,
                    manifest.path("sourceSha256").asText(),
                    manifest.path("configSha256").asText(),
                    hash(manifestBytes),
                    10,
                )
            return CumulativeExperimentSettings(manifest, payload, answers, diagnostic, output, ledger)
        }

        private fun fixture(
            manifest: JsonNode,
            path: String,
            expectedHash: String,
        ): JsonNode {
            val bytes = bounded(Path.of(manifest.path(path).asText()), 262_144)
            check(hash(bytes) == manifest.path(expectedHash).asText())
            return json.readTree(bytes)
        }

        private fun bounded(
            path: Path,
            maximum: Int,
        ): ByteArray {
            check(Files.size(path) <= maximum)
            return Files.readAllBytes(path).also { check(it.size <= maximum) }
        }

        private fun canonical(node: JsonNode): String =
            when {
                node.isObject ->
                    node.properties().sortedBy { it.key }.joinToString(",", "{", "}") {
                        json.writeValueAsString(it.key) + ":" + canonical(it.value)
                    }
                node.isArray -> node.values().joinToString(",", "[", "]") { canonical(it) }
                else -> json.writeValueAsString(node)
            }

        private fun git(vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git") + arguments).directory(project.toFile()).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0)
            return result
        }

        private fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun required(name: String): String =
            checkNotNull(
                System.getenv(name)?.takeIf {
                    it.isNotBlank()
                },
            ) { "Missing runtime setting: $name" }
    }
}

/** Imported only by the explicitly activated real-provider entry, not by ordinary application contract tests. */
@TestConfiguration(proxyBeanMethods = false)
class CumulativeExperimentRuntime {
    @Bean fun cumulativeSettings(): CumulativeExperimentSettings = CumulativeExperimentInfrastructure.settings
}

object CumulativeExperimentInfrastructure {
    val settings: CumulativeExperimentSettings by lazy { CumulativeExperimentSettings.load() }
    private val postgresState =
        lazy { PostgreSQLContainer("postgres:18-alpine").withLabel("meetme.experiment", "cumulative10").apply { start() } }
    private val postgres by postgresState
    private val redisState =
        lazy {
            GenericContainer(
                DockerImageName.parse("redis:8-alpine"),
            ).withExposedPorts(6379).withLabel("meetme.experiment", "cumulative10").apply {
                start()
            }
        }

    private val redis by redisState

    fun properties(registry: DynamicPropertyRegistry) {
        settings // Complete approval/source/claim checks before creating infrastructure or a network-capable app.
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.data.redis.host", redis::getHost)
        registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        mapOf(
            "server.address" to "127.0.0.1",
            "spring.flyway.enabled" to "true",
            "meetme.reliability.enabled" to "true",
            "meetme.rate-limit.enabled" to "false",
            "meetme.data-retention.enabled" to "false",
            "meetme.precheck.provider.enabled" to "true",
            "meetme.precheck.decision-routing.enabled" to "true",
            "meetme.precheck.classifier.enabled" to "true",
            "meetme.precheck.classifier.minimum-confidence" to "0.8",
            "meetme.precheck.evidence-enabled" to "true",
            "meetme.ai.budget.enabled" to "false",
            "meetme.anonymous.allowed-origins" to "http://localhost:18931",
            "meetme.anonymous.cookie-secure" to "false",
            "logging.level.root" to "ERROR",
        ).forEach { (key, value) -> registry.add(key) { value } }
        listOf("gemini" to "GEMINI_API_KEY", "openai" to "OPENAI_API_KEY", "typesafe" to "TYPESAFE_API_KEY").forEach { (provider, env) ->
            registry.add("meetme.$provider.api-key") { checkNotNull(System.getenv(env)?.takeIf { it.isNotBlank() }) }
        }
    }

    fun stop() {
        if (redisState.isInitialized()) redisState.value.stop()
        if (postgresState.isInitialized()) postgresState.value.stop()
    }
}

/** Immutable call facts contain no request, response or exception objects. */
data class CumulativeCallFact(
    val call: AiHttpExperimentCall,
    val httpStatus: Int?,
    val failure: AiHttpExperimentTransportFailure?,
)

class CumulativeExperimentObservations : ParserTransportDiagnosticPort {
    private val diagnosticSnapshots = CopyOnWriteArrayList<ParserTransportDiagnosticSnapshot>()
    private val observedEvents = CopyOnWriteArrayList<Map<String, Any?>>()
    private val finishedCalls = CopyOnWriteArrayList<CumulativeCallFact>()
    private val handledClassifierFailures = ConcurrentHashMap.newKeySet<UUID>()
    private val overflow = AtomicBoolean()
    private val started = System.nanoTime()

    @Synchronized override fun record(snapshot: ParserTransportDiagnosticSnapshot) {
        if (diagnosticSnapshots.size < 256) diagnosticSnapshots += snapshot else overflow.set(true)
    }

    fun snapshots(): List<ParserTransportDiagnosticSnapshot> = diagnosticSnapshots.toList()

    fun providerEvents(): List<Map<String, Any?>> = observedEvents.toList()

    fun callFacts(): List<CumulativeCallFact> = finishedCalls.toList()

    fun coverageKnown(): Boolean = !overflow.get()

    @Synchronized internal fun event(
        stage: String,
        values: Map<String, Any?>,
    ) {
        if (observedEvents.size <
            256
        ) {
            observedEvents += mapOf("stage" to stage, "elapsedNanos" to (System.nanoTime() - started).coerceAtLeast(0)) + values
        } else {
            overflow.set(true)
        }
    }

    @Synchronized internal fun finished(
        call: AiHttpExperimentCall,
        status: Int?,
        failure: AiHttpExperimentTransportFailure?,
    ) {
        if (finishedCalls.size < 256) finishedCalls += CumulativeCallFact(call, status, failure) else overflow.set(true)
    }

    internal fun classifierFailed(execution: UUID) {
        handledClassifierFailures += execution
    }

    internal fun classifierFailureHandled(execution: UUID): Boolean = execution in handledClassifierFailures

    internal fun failedGenerator(execution: UUID): AiHttpExperimentCall? =
        finishedCalls
            .filter {
                it.call.executionId == execution &&
                    it.call.operation in setOf(AiPhysicalOperation.PRECHECK_COUNT, AiPhysicalOperation.PRECHECK_GENERATION) &&
                    (it.failure != null || it.httpStatus?.let { code -> code !in 200..299 } == true)
            }.map { it.call }
            .distinctBy { it.callId }
            .singleOrNull()

    internal fun failure(
        stage: String,
        code: String?,
        failure: Exception,
    ) {
        val kinds = mutableListOf<ParserDiagnosticCause>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        var unavailable = false
        try {
            while (current != null && kinds.size < 8 && seen.add(current)) {
                kinds +=
                    when (current.javaClass) {
                        GenAiIOException::class.java -> ParserDiagnosticCause.GENAI_IO
                        InterruptedIOException::class.java -> ParserDiagnosticCause.INTERRUPTED_IO
                        SocketTimeoutException::class.java -> ParserDiagnosticCause.SOCKET_TIMEOUT
                        HttpTimeoutException::class.java -> ParserDiagnosticCause.HTTP_TIMEOUT
                        StreamResetException::class.java -> ParserDiagnosticCause.STREAM_RESET
                        IOException::class.java -> ParserDiagnosticCause.IO
                        AiHttpDispatchException::class.java -> ParserDiagnosticCause.DISPATCH_DENIED
                        else -> ParserDiagnosticCause.OTHER
                    }
                current = current.cause
            }
        } catch (_: Exception) {
            unavailable = true
        }
        event(
            stage,
            mapOf(
                "failureCode" to (ExperimentFailureNormalization.normalizeKnownFailure(code)?.name ?: "UNKNOWN"),
                "causes" to kinds.toList(),
                "causesTruncated" to (current != null),
                "metadataUnavailable" to unavailable,
            ),
        )
    }
}

/** Real source authority remains first. This configuration is never imported into the production application. */
@TestConfiguration(proxyBeanMethods = false)
class CumulativeExperimentFixtures {
    @Bean fun cumulativeLedger(settings: CumulativeExperimentSettings): ExperimentCallLedger = settings.ledger

    @Bean fun cumulativeObservations(): CumulativeExperimentObservations = CumulativeExperimentObservations()

    @Bean @Primary
    fun cumulativeClock(settings: CumulativeExperimentSettings): Clock =
        Clock.fixed(
            OffsetDateTime
                .parse(
                    settings.payload
                        .path("rooms")
                        .get(0)
                        .path("referenceAt")
                        .asText(),
                ).toInstant(),
            ZoneOffset.UTC,
        )

    @Bean @Primary
    fun cumulativeTokens(clock: Clock): AccessTokenPort {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Rs256AccessTokenAdapter(
            keys.private,
            mapOf("cumulative-local" to keys.public),
            "cumulative-local",
            clock,
            "https://synthetic.invalid",
            "meetme-web",
        )
    }

    @Bean @Primary
    fun cumulativeSource(
        delegate: JdbcAiBudgetExecutionFence,
        settings: CumulativeExperimentSettings,
    ): AiRuntimeDispatchGuardPort {
        val admission = ExperimentCounterAdmission(settings.ledger)
        return AiRuntimeDispatchGuardPort { request, at -> admission.authorize({ delegate.authorize(request, at) }, request) }
    }

    companion object {
        @Bean @JvmStatic
        fun cumulativeProviderObservers(
            configured: ObjectProvider<CumulativeExperimentSettings>,
            observed: ObjectProvider<CumulativeExperimentObservations>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    when (bean) {
                        is AiHttpDispatchGate ->
                            bean.also {
                                val settings = configured.getObject()
                                val observations = observed.getObject()
                                it.installExperimentWireHook(
                                    object : AiHttpExperimentWireHook {
                                        override fun callStarted(call: AiHttpExperimentCall) = settings.ledger.callStarted(call)

                                        override fun beforeHeaders(call: AiHttpExperimentCall) = settings.ledger.beforeHeaders(call)

                                        override fun headersEmitted(call: AiHttpExperimentCall) = settings.ledger.headersEmitted(call)

                                        override fun callFinished(
                                            call: AiHttpExperimentCall,
                                            httpStatus: Int?,
                                            failure: AiHttpExperimentTransportFailure?,
                                        ) {
                                            observations.finished(call, httpStatus, failure)
                                            settings.ledger.callFinished(call, httpStatus, failure)
                                        }
                                    },
                                )
                            }
                        is AmbiguityClassificationPort ->
                            AmbiguityClassificationPort { request ->
                                val settings = configured.getObject()
                                val observations = observed.getObject()
                                checkpoint(settings, request.dispatchContext)
                                if (settings.ledger.snapshot().phase == ExperimentPhase.CONNECTIVITY) throw ExperimentCallStop()
                                try {
                                    bean.classify(request).also { result ->
                                        observations.event(
                                            "CLASSIFICATION",
                                            mapOf(
                                                "assessment" to result.assessment,
                                                "semanticAssessment" to result.semanticAssessment,
                                                "reason" to result.reason,
                                                "confidence" to result.confidence?.takeIf { it.isFinite() && it in 0.0..1.0 },
                                                "probabilities" to result.probabilities.filterValues { it.isFinite() && it in 0.0..1.0 },
                                                "inputTokens" to result.inputTokens,
                                                "outputTokens" to result.outputTokens,
                                                "usageComplete" to result.usageComplete,
                                            ),
                                        )
                                        if (result.reason == AmbiguityClassificationReason.REFUSED) {
                                            request.dispatchContext?.let { observations.classifierFailed(it.execution.executionId) }
                                            finalize(
                                                settings,
                                                request.dispatchContext,
                                                AiPhysicalOperation.PRECHECK_CLASSIFICATION,
                                                "REFUSED",
                                            )
                                        }
                                    }
                                } catch (failure: Exception) {
                                    val code = (failure as? AmbiguityClassificationException)?.failureCode
                                    observations.failure("CLASSIFICATION", code, failure)
                                    request.dispatchContext?.let { observations.classifierFailed(it.execution.executionId) }
                                    finalize(settings, request.dispatchContext, AiPhysicalOperation.PRECHECK_CLASSIFICATION, code)
                                    throw failure
                                }
                            }
                        is PreSubmissionCheckProvider ->
                            PreSubmissionCheckProvider { request ->
                                val settings = configured.getObject()
                                val observations = observed.getObject()
                                checkpoint(settings, request.dispatchContext)
                                if (settings.ledger.snapshot().phase == ExperimentPhase.CONNECTIVITY) {
                                    observations.event(
                                        "CONNECTIVITY_PRECHECK",
                                        mapOf(
                                            "provider" to "CLEAR_TEST_DOUBLE",
                                            "semanticQualityEvidence" to false,
                                        ),
                                    )
                                    PrecheckProviderResult(PrecheckVerdict.CLEAR, provider = "TEST_DOUBLE", model = "TEST_DOUBLE")
                                } else {
                                    try {
                                        bean.check(request).also { result ->
                                            observations.event(
                                                "PRECHECK",
                                                mapOf(
                                                    "verdict" to result.verdict,
                                                    "choiceCount" to result.choices.size,
                                                    "questionOffered" to (result.prompt != null),
                                                    "inputTokens" to result.inputTokens,
                                                    "outputTokens" to result.outputTokens,
                                                ),
                                            )
                                        }
                                    } catch (failure: Exception) {
                                        val code = (failure as? PrecheckProviderException)?.failureCode
                                        observations.failure("PRECHECK", code, failure)
                                        val context = request.dispatchContext
                                        if (context ==
                                            null
                                        ) {
                                            stopUnknown(settings)
                                        } else if (!observations.classifierFailureHandled(context.execution.executionId)) {
                                            val candidate = observations.failedGenerator(context.execution.executionId)
                                            if (candidate == null) stopUnknown(settings) else finalizeExact(settings, candidate, code)
                                        }
                                        throw failure
                                    }
                                }
                            }
                        is NaturalLanguageParserPort ->
                            NaturalLanguageParserPort { request ->
                                val settings = configured.getObject()
                                val observations = observed.getObject()
                                checkpoint(settings, request.dispatchContext)
                                val operation =
                                    if (beanName ==
                                        "lunaParser"
                                    ) {
                                        AiPhysicalOperation.FINAL_FALLBACK
                                    } else {
                                        AiPhysicalOperation.FINAL_PRIMARY
                                    }
                                if (settings.ledger.snapshot().phase == ExperimentPhase.CONNECTIVITY &&
                                    operation != AiPhysicalOperation.FINAL_PRIMARY
                                ) {
                                    throw ExperimentCallStop()
                                }
                                try {
                                    bean.parse(request).also { result ->
                                        observations.event(
                                            operation.name,
                                            mapOf(
                                                "resultCount" to result.results.size,
                                                "inputTokens" to result.usage.inputTokens,
                                                "outputTokens" to result.usage.outputTokens,
                                            ),
                                        )
                                    }
                                } catch (failure: Exception) {
                                    val code = (failure as? NaturalLanguageParserException)?.kind?.name
                                    observations.failure(operation.name, code, failure)
                                    finalize(settings, request.dispatchContext, operation, code)
                                    throw failure
                                }
                            }
                        else -> bean
                    }
            }

        private fun checkpoint(
            settings: CumulativeExperimentSettings,
            context: AiProviderDispatchContext?,
        ) {
            if (context == null) settings.ledger.observationUnavailable()
            settings.ledger.checkpointForRoom(checkNotNull(context).execution.roomId)
        }

        private fun finalize(
            settings: CumulativeExperimentSettings,
            context: AiProviderDispatchContext?,
            operation: AiPhysicalOperation,
            code: String?,
        ) {
            try {
                if (context == null) {
                    settings.ledger.observationUnavailable()
                } else {
                    settings.ledger.providerFailureForExecution(
                        context.execution.executionId,
                        operation,
                        context.finalAttemptId,
                        context.providerOrdinal,
                        code,
                    )
                }
            } catch (_: Exception) {
                // The sticky ledger stop remains; the original provider failure must survive.
            }
        }

        private fun finalizeExact(
            settings: CumulativeExperimentSettings,
            call: AiHttpExperimentCall,
            code: String?,
        ) {
            try {
                settings.ledger.providerFailureForExecution(call.executionId, call.operation, call.attemptId, call.ordinal, code)
            } catch (
                _: Exception,
            ) {
                // Keep the existing provider failure and the durable stop.
            }
        }

        private fun stopUnknown(settings: CumulativeExperimentSettings) {
            try {
                settings.ledger.observationUnavailable()
            } catch (_: Exception) {
                // Preserve original provider failure.
            }
        }
    }
}
