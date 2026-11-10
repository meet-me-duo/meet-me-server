package com.meetme.server.experiment

import com.meetme.server.config.GeminiProperties
import com.meetme.server.config.OpenAiProperties
import com.meetme.server.config.SelectedScheduleDslPhysicalAuthority
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationSelectedHttpTransport
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationStrictJsonBoundary
import com.meetme.server.coordination.adapter.output.integration.C5EvaluationStructuralProviderContract
import com.meetme.server.coordination.adapter.output.integration.C5SelectedChoiceContextRun
import com.meetme.server.coordination.adapter.output.integration.ProviderRejectionDiagnostics
import com.meetme.server.coordination.adapter.output.integration.ScheduleDslStructuralProviderContract
import com.meetme.server.coordination.adapter.output.integration.SelectedScheduleDslHttpTransport
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ScheduleDslStructuralResponseParser
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderPort
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Path

/** Differently named test dependencies; ordinary controllers/workers/services and durable guards are unchanged. */
@TestConfiguration(proxyBeanMethods = false)
@Profile("c5-http-evaluation")
internal class C5EvaluationSelectedProviders {
    @Bean
    fun c5SelectedChoiceContextRun(): C5SelectedChoiceContextRun =
        C5SelectedChoiceContextRun(System.getProperty("meetme.experiment.selected-choice-context") == "true")

    @Bean
    fun c5EvaluationStructuralParser(mapper: ObjectMapper): ScheduleDslStructuralResponseParser =
        C5EvaluationStrictJsonBoundary(
            mapper,
            System.getProperty("meetme.experiment.scoped-term-evidence") == "true",
            System.getProperty("meetme.experiment.place-constraints") == "true",
        )

    @Bean("selectedScheduleDslGeminiProvider")
    fun c5EvaluationGeminiProvider(
        mapper: ObjectMapper,
        properties: GeminiProperties,
        authority: SelectedScheduleDslPhysicalAuthority,
        journal: ObjectProvider<C5HttpWireJournal>,
        contexts: C5SelectedChoiceContextRun,
    ): SelectedScheduleDslProviderPort =
        C5EvaluationSelectedProvider(
            C5EvaluationSelectedHttpTransport(
                mapper,
                authority.gate,
                termPrecisionSchemaCandidate = System.getProperty("meetme.experiment.term-precision-schema") == "true",
                schemaCoherenceCandidate = System.getProperty("meetme.experiment.schema-coherence") == "true",
                providerParityCandidate = System.getProperty("meetme.experiment.provider-parity") == "true",
                commonFiniteExpressionSchemaCandidate = System.getProperty("meetme.experiment.common-finite-schema") == "true",
                scopedTermEvidenceCandidate = System.getProperty("meetme.experiment.scoped-term-evidence") == "true",
                placeConstraintsCandidate = System.getProperty("meetme.experiment.place-constraints") == "true",
                relativeDateContextCandidate = System.getProperty("meetme.experiment.relative-date-context") == "true",
                responseDiagnosticsObserver = { diagnostic ->
                    journal.ifAvailable?.let { trace ->
                        runCatching { trace.recordError(trace.currentCall(), diagnostic) }.onFailure { trace.observationFailure() }
                    }
                },
                selectedChoiceContextResolver = contexts::resolve,
            ) { bytes ->
                journal.ifAvailable?.let { trace ->
                    runCatching { trace.captureResponse(trace.currentCall(), bytes) }.onFailure { trace.observationFailure() }
                }
            },
            AnalysisProvider.GEMINI,
            properties.model,
            properties.apiKey,
            URI("https://generativelanguage.googleapis.com"),
            journal.ifAvailable,
        )

    @Bean("selectedScheduleDslLunaProvider")
    fun c5EvaluationLunaProvider(
        mapper: ObjectMapper,
        properties: OpenAiProperties,
        authority: SelectedScheduleDslPhysicalAuthority,
        journal: ObjectProvider<C5HttpWireJournal>,
        contexts: C5SelectedChoiceContextRun,
    ): SelectedScheduleDslProviderPort =
        C5EvaluationSelectedProvider(
            C5EvaluationSelectedHttpTransport(
                mapper,
                authority.gate,
                termPrecisionSchemaCandidate = System.getProperty("meetme.experiment.term-precision-schema") == "true",
                schemaCoherenceCandidate = System.getProperty("meetme.experiment.schema-coherence") == "true",
                providerParityCandidate = System.getProperty("meetme.experiment.provider-parity") == "true",
                commonFiniteExpressionSchemaCandidate = System.getProperty("meetme.experiment.common-finite-schema") == "true",
                scopedTermEvidenceCandidate = System.getProperty("meetme.experiment.scoped-term-evidence") == "true",
                placeConstraintsCandidate = System.getProperty("meetme.experiment.place-constraints") == "true",
                relativeDateContextCandidate = System.getProperty("meetme.experiment.relative-date-context") == "true",
                responseDiagnosticsObserver = { diagnostic ->
                    journal.ifAvailable?.let { trace ->
                        runCatching { trace.recordError(trace.currentCall(), diagnostic) }.onFailure { trace.observationFailure() }
                    }
                },
                selectedChoiceContextResolver = contexts::resolve,
            ) { bytes ->
                journal.ifAvailable?.let { trace ->
                    runCatching { trace.captureResponse(trace.currentCall(), bytes) }.onFailure { trace.observationFailure() }
                }
            },
            AnalysisProvider.OPENAI,
            properties.model,
            properties.apiKey,
            URI("https://api.openai.com/v1/responses"),
            journal.ifAvailable,
            System.getProperty("meetme.experiment.term-schema-baseline")?.let(Path::of),
        )
}

internal class C5EvaluationSelectedProvider(
    private val transport: C5EvaluationSelectedHttpTransport,
    private val provider: AnalysisProvider,
    private val model: String,
    private val apiKey: String,
    private val endpoint: URI,
    private val journal: C5HttpWireJournal? = null,
    private val schemaOnlyBaseline: Path? = null,
) : SelectedScheduleDslProviderPort {
    override fun generate(
        request: SelectedScheduleDslProviderRequest,
    ): com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse {
        schemaOnlyBaseline?.let {
            require(provider == AnalysisProvider.OPENAI)
            val coherent = System.getProperty("meetme.experiment.schema-coherence") == "true"
            require(coherent xor (System.getProperty("meetme.experiment.term-precision-schema") == "true"))
            val mapper =
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .findAndAddModules()
                    .build()
            val baseline =
                java.nio.file.Files
                    .readAllBytes(it)
            val actual = transport.body(request, provider, model)
            if (coherent) {
                val expected =
                    java.nio.file.Files.readAllBytes(
                        Path.of(requireNotNull(System.getProperty("meetme.experiment.schema-coherence-expected"))),
                    )
                val digest =
                    java.security.MessageDigest
                        .getInstance(
                            "SHA-256",
                        ).digest(expected)
                        .joinToString("") { byte -> "%02x".format(byte) }
                require(digest == System.getProperty("meetme.experiment.schema-coherence-expected-sha")) { "PINNED_CANDIDATE_FILE_CHANGED" }
                C5SchemaOnlyRequestCheck.verifyPinnedCandidate(mapper, baseline, expected, actual)
            } else {
                C5SchemaOnlyRequestCheck.verify(mapper, baseline, actual)
            }
        }
        val observation = journal ?: return transport.generate(request, provider, model, apiKey, endpoint)
        val operation =
            if (provider == AnalysisProvider.GEMINI) {
                com.meetme.server.shared.application.port.output.AiPhysicalOperation.FINAL_PRIMARY
            } else {
                com.meetme.server.shared.application.port.output.AiPhysicalOperation.FINAL_FALLBACK
            }
        observation.prepareRequest(
            request.dispatchContext.execution.executionId,
            operation,
            request.dispatchContext.providerOrdinal,
            transport.body(request, provider, model),
        )
        ProviderRejectionDiagnostics
            .observeErrorForCurrentThread(
                java.util.function.Consumer { error ->
                    runCatching { observation.recordError(observation.currentCall(), error) }.onFailure { observation.observationFailure() }
                },
            ).use {
                val result = transport.generate(request, provider, model, apiKey, endpoint)
                runCatching {
                    val call = observation.currentCall()
                    check(call.executionId == request.dispatchContext.execution.executionId && call.operation == operation)
                    observation.captureModel(call, result.responseJson.toByteArray(Charsets.UTF_8))
                    observation.recordUsage(call, result.usage)
                }.onFailure { observation.observationFailure() }
                return result
            }
    }
}

internal object C5SchemaOnlyRequestCheck {
    fun verifyPinnedCandidate(
        mapper: ObjectMapper,
        baseline: ByteArray,
        expected: ByteArray,
        actual: ByteArray,
    ) {
        val original = mapper.readTree(baseline)
        val pinned = mapper.readTree(expected)
        require(pinned == mapper.readTree(actual)) { "SCHEMA_ONLY_PIN_MISMATCH" }
        val originalFormat = original.path("text").path("format") as tools.jackson.databind.node.ObjectNode
        val pinnedFormat = pinned.path("text").path("format") as tools.jackson.databind.node.ObjectNode
        require(originalFormat.path("schema").isObject && pinnedFormat.path("schema").isObject)
        require(originalFormat.path("schema") != pinnedFormat.path("schema")) { "SCHEMA_CANDIDATE_UNCHANGED" }
        originalFormat.remove("schema")
        pinnedFormat.remove("schema")
        require(original == pinned) { "SCHEMA_ONLY_BASELINE_MISMATCH" }
    }

    fun verify(
        mapper: ObjectMapper,
        baseline: ByteArray,
        actual: ByteArray,
    ) {
        val expected = mapper.readTree(baseline)
        val observed = mapper.readTree(actual)
        val format = expected.path("text").path("format") as tools.jackson.databind.node.ObjectNode
        val schema = mapper.readValue(mapper.writeValueAsBytes(format.path("schema")), Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val candidate =
            com.meetme.server.coordination.adapter.output.integration.C5DirectLocatorProducer.termPrecisionSchema(
                schema as Map<String, Any>,
            )
        format.replace("schema", mapper.readTree(mapper.writeValueAsBytes(candidate)))
        require(expected == observed) { "TERM_SCHEMA_ONLY_REQUEST_MISMATCH" }
    }
}

internal object C5EvaluationCandidateProfile {
    /** Free preparation cannot open LIVE; replace only with independently verified real ledger/trace binding. */
    fun requireLiveBindings(journal: C5HttpWireJournal? = null) {
        checkNotNull(journal) { "C5_HTTP_LIVE_NOT_ARMED: actual case journal and both native gates required" }.requireInstalled()
    }

    fun verify(): Map<String, String> {
        val projectRoot = Path.of(System.getProperty("user.dir")).toRealPath()
        val testRoot = projectRoot.resolve("build/classes").toRealPath()
        require(testRoot.startsWith(projectRoot))
        val candidateTypes =
            listOf(
                C5EvaluationStructuralProviderContract::class.java,
                C5EvaluationSelectedHttpTransport::class.java,
                C5EvaluationStrictJsonBoundary::class.java,
                Class.forName("com.meetme.server.coordination.adapter.output.integration.C5DirectLocatorProducer"),
            )
        candidateTypes.forEach {
            val origin =
                Path
                    .of(
                        it.protectionDomain.codeSource.location
                            .toURI(),
                    ).toRealPath()
            require(origin.startsWith(testRoot) && "test" in origin.map(Path::toString))
        }
        listOf(ScheduleDslStructuralProviderContract::class.java, SelectedScheduleDslHttpTransport::class.java).forEach {
            val origin =
                Path
                    .of(
                        it.protectionDomain.codeSource.location
                            .toURI(),
                    ).toRealPath()
            require(origin.startsWith(testRoot) && "main" in origin.map(Path::toString))
        }
        return candidateTypes.associate {
            it.name to
                it.protectionDomain.codeSource.location
                    .toString()
        }
    }

    fun parser(mapper: ObjectMapper): ScheduleDslStructuralResponseParser {
        verify()
        return C5EvaluationStrictJsonBoundary(mapper)
    }
}
