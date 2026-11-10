package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisInvocationBudget
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import com.meetme.server.coordination.application.port.output.ParserUsage
import com.meetme.server.coordination.application.port.output.ScheduleDslProviderResponse
import com.meetme.server.coordination.application.port.output.SelectedScheduleDslProviderRequest
import com.meetme.server.coordination.domain.analysis.FinalAnalysisRouteChoice
import com.meetme.server.coordination.domain.dsl.SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT
import com.meetme.server.coordination.domain.dsl.ScheduleDslContextModePolicy
import com.meetme.server.coordination.domain.dsl.ScheduleDslStructuralMatchingPolicy
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException
import com.meetme.server.shared.adapter.output.aihttp.GatedAiHttpExchange
import com.meetme.server.shared.adapter.output.aihttp.PreparedAiHttpRequest
import com.meetme.server.shared.adapter.output.aihttp.physicalProfile
import com.meetme.server.shared.application.port.output.AiBudgetExecutionKind
import com.meetme.server.shared.application.port.output.AiBudgetFailure
import com.meetme.server.shared.application.port.output.AiBudgetOutcome
import com.meetme.server.shared.application.port.output.AiBudgetUsage
import com.meetme.server.shared.application.port.output.AiDispatchFingerprints
import com.meetme.server.shared.application.port.output.AiPhysicalOperation
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.dsl.SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT
import com.meetme.server.submission.domain.ir.IrEvidenceBindings
import com.meetme.server.submission.domain.ir.IrInterpretation
import com.meetme.server.submission.domain.ir.IrSourceKind
import com.meetme.server.submission.domain.time.APPROVED_KOREAN_TIME_TERM_VERSION
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** One prepared physical call. Durable/runtime admission and accounting remain in the existing gate. */
internal class SelectedScheduleDslHttpTransport(
    private val mapper: ObjectMapper,
    private val gate: AiHttpDispatchGate,
) {
    private val reader =
        mapper
            .readerFor(Map::class.java)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun generate(
        request: SelectedScheduleDslProviderRequest,
        provider: AnalysisProvider,
        model: String,
        apiKey: String,
        endpoint: URI,
    ): ScheduleDslProviderResponse {
        val target =
            configuration {
                require(gate.properties.enabled)
                require(apiKey.isNotBlank() && apiKey.all { it in '!'..'~' })
                validate(request, provider, model)
                target(endpoint, provider, model)
            }
        val cap =
            if (provider == AnalysisProvider.GEMINI) {
                AnalysisInvocationBudget.GEMINI_CALL_TIMEOUT
            } else {
                AnalysisInvocationBudget.lunaCallTimeout(request.binding.invocation.policyVersion)
            }
        val budget = minOf(request.timeout, cap)
        if (budget.isNegative || budget.isZero) fail(ParserFailureKind.TIMEOUT)
        val now = System.nanoTime()
        val allowed = minOf(budget.toNanos(), request.dispatchContext.deadlineNanos - now)
        if (allowed <= 0) fail(ParserFailureKind.TIMEOUT)
        val deadline = now + allowed
        val operation = if (provider == AnalysisProvider.GEMINI) AiPhysicalOperation.FINAL_PRIMARY else AiPhysicalOperation.FINAL_FALLBACK
        var pending: Call? = null
        var physical: GatedAiHttpExchange? = null
        var usage = AiBudgetUsage()
        var completed = false
        var primaryFailure: NaturalLanguageParserException? = null
        try {
            val bytes = configuration { body(request, provider, model) }
            val profile = physicalProfile(operation, model, target, PROFILE_CAPTURE_LIMIT)
            val prepared =
                PreparedAiHttpRequest.freeze(
                    operation,
                    request.dispatchContext.providerOrdinal,
                    request.attempt.id,
                    profile,
                    bytes,
                )
            val group = gate.prepareGroup(request.dispatchContext, listOf(prepared))
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail(ParserFailureKind.TIMEOUT)
            val client =
                OkHttpClient
                    .Builder()
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .authenticator(Authenticator.NONE)
                    .proxyAuthenticator(Authenticator.NONE)
                    .connectTimeout(Duration.ZERO)
                    .readTimeout(Duration.ZERO)
                    .writeTimeout(Duration.ZERO)
                    .callTimeout(Duration.ofNanos(remaining))
                    .build()
            val exchange = gate.forPreparedCall(client, group, operation)
            physical = exchange
            val http =
                Request
                    .Builder()
                    .url(target.toString())
                    .apply {
                        if (provider ==
                            AnalysisProvider.GEMINI
                        ) {
                            header("x-goog-api-key", apiKey)
                        } else {
                            header("Authorization", "Bearer $apiKey")
                        }
                    }.post(prepared.copyBody().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            if (deadline - System.nanoTime() <= 0) fail(ParserFailureKind.TIMEOUT)
            val call = exchange.client.newCall(http)
            pending = call
            call.timeout().deadlineNanoTime(deadline)
            val responseBytes =
                call.execute().use { response ->
                    val responseBody = response.body ?: fail(ParserFailureKind.INVALID_RESPONSE)
                    val catalogRoute =
                        (request.binding.route.choice as? FinalAnalysisRouteChoice.DslStructuralV5)?.identity?.responseContractVersion in
                            setOf(
                                SERVER_EVIDENCE_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT,
                            )
                    val limit = if (provider == AnalysisProvider.GEMINI || catalogRoute) GEMINI_RESPONSE_LIMIT else DSL_LIMIT
                    if (responseBody.contentLength() > limit) fail(ParserFailureKind.INVALID_RESPONSE)
                    val received = responseBody.byteStream().use { it.readNBytes(limit + 1) }
                    if (received.size > limit) fail(ParserFailureKind.INVALID_RESPONSE)
                    received
                }
            if (deadline - System.nanoTime() <= 0) fail(ParserFailureKind.TIMEOUT)
            val root = reader.readValue<Map<*, *>>(responseBytes)
            usage = knownUsage(root, provider)
            val result =
                if (provider == AnalysisProvider.GEMINI) {
                    ScheduleDslGeminiWire.response(mapper, responseBytes)
                } else {
                    lunaResponse(root, responseBytes.size, model)
                }
            if (deadline - System.nanoTime() <= 0) fail(ParserFailureKind.TIMEOUT)
            completed = true
            return result
        } catch (failure: AiHttpDispatchException) {
            val typed = NaturalLanguageParserException(ParserFailureKind.CONFIGURATION)
            primaryFailure = typed
            throw typed
        } catch (failure: AiHttpProviderStatusException) {
            val errorRoot = runCatching { reader.readValue<Map<*, *>>(failure.responseBytes) }.getOrNull()
            if (errorRoot != null) usage = knownUsage(errorRoot, provider)
            val error = errorRoot?.get("error") as? Map<*, *>
            val code = (error?.get("code") as? String) ?: (error?.get("status") as? String)
            val type = error?.get("type") as? String
            val kind = ProviderFailureClassifier.classifyStatus(failure.status, code, type)
            val typed =
                NaturalLanguageParserException(
                    kind,
                    cause = ProviderRejectionDiagnostics.decode(provider, failure.status, failure.responseBytes),
                    retryAfterMillis =
                        failure.retryAfterMillis.takeIf {
                            kind ==
                                ParserFailureKind.SERVER ||
                                kind == ParserFailureKind.RATE_LIMIT
                        },
                )
            primaryFailure = typed
            throw typed
        } catch (failure: NaturalLanguageParserException) {
            primaryFailure = failure
            throw failure
        } catch (failure: IOException) {
            val typed = NaturalLanguageParserException(ProviderFailureClassifier.classify(failure))
            primaryFailure = typed
            throw typed
        } catch (failure: RuntimeException) {
            val typed = NaturalLanguageParserException(ProviderFailureClassifier.classify(failure))
            primaryFailure = typed
            throw typed
        } finally {
            pending?.cancel()
            try {
                physical?.observe(UUID.randomUUID(), outcome(completed, primaryFailure?.kind), usage, budgetFailure(primaryFailure?.kind))
            } catch (observationFailure: AiHttpDispatchException) {
                val failure = primaryFailure
                if (failure == null) fail(ParserFailureKind.CONFIGURATION)
                failure.addSuppressed(observationFailure)
            }
        }
    }

    private fun validate(
        request: SelectedScheduleDslProviderRequest,
        provider: AnalysisProvider,
        model: String,
    ) {
        val binding = request.binding
        val route = binding.route
        val invocation = binding.invocation
        val context = request.inputCapsule.snapshot.context
        val selectedContext =
            when (val selected = route.choice) {
                is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                    require(
                        selected.identity.responseContractVersion in
                            setOf(
                                SCHEDULE_DSL_STRUCTURAL_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
                                SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT,
                            ),
                    )
                    require(selected.identity.irSemanticVersion == SCHEDULE_DSL_STRUCTURAL_SEMANTIC_VERSION)
                    require(selected.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
                    require(
                        selected.policy.version == selected.identity.policyVersion &&
                            selected.policy.version == selected.context.policyVersion,
                    )
                    require(IrInterpretation.UNRESOLVED !in selected.policy.eligibleInterpretations)
                    require(
                        selected.policy ==
                            ScheduleDslStructuralMatchingPolicy.forVersion(selected.policy.version, selected.policy.alternativeChoices),
                    )
                    selected.context
                }
                is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                    require(selected.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_MODE_RESPONSE_CONTRACT)
                    require(selected.identity.irSemanticVersion == SCHEDULE_DSL_CONTEXT_MODE_SEMANTIC_VERSION)
                    require(selected.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
                    require(
                        selected.policy.version == selected.identity.policyVersion &&
                            selected.policy.version == selected.context.policyVersion,
                    )
                    require(IrInterpretation.UNRESOLVED !in selected.policy.eligibleInterpretations)
                    require(selected.policy == ScheduleDslContextModePolicy.contextual(selected.policy.alternativeChoices))
                    selected.context
                }
                is FinalAnalysisRouteChoice.DslContextV2 -> {
                    require(selected.identity.responseContractVersion == SCHEDULE_DSL_CONTEXT_RESPONSE_CONTRACT)
                    require(selected.identity.irSemanticVersion == SCHEDULE_DSL_SEMANTIC_VERSION)
                    require(
                        selected.policy.version == selected.identity.policyVersion &&
                            selected.policy.version == selected.context.policyVersion,
                    )
                    require(IrInterpretation.UNRESOLVED !in selected.policy.eligibleInterpretations)
                    selected.context
                }
                is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                    require(selected.identity.responseContractVersion == SCHEDULE_DSL_APPROVED_TERMS_RESPONSE_CONTRACT)
                    require(selected.identity.irSemanticVersion == SCHEDULE_DSL_APPROVED_TERMS_SEMANTIC_VERSION)
                    require(selected.vocabularyVersion == APPROVED_KOREAN_TIME_TERM_VERSION)
                    require(
                        selected.policy.version == selected.identity.policyVersion &&
                            selected.policy.version == selected.context.policyVersion,
                    )
                    require(IrInterpretation.UNRESOLVED !in selected.policy.eligibleInterpretations)
                    selected.context
                }
                FinalAnalysisRouteChoice.Legacy -> error("CONFIGURATION")
            }
        require(
            context == selectedContext &&
                context.runId == route.runId &&
                context.batchId == route.batchId &&
                context.roomId == route.roomId,
        )
        require(invocation.runId.value == route.runId && invocation.finishedAt == null && invocation.winnerAttemptId == null)
        require(
            AnalysisInvocationBudget.isAllowedPolicy(invocation.policyVersion, route.choice is FinalAnalysisRouteChoice.DslStructuralV5),
        )
        require(
            invocation.startedAt < invocation.deadlineAt &&
                Duration.between(invocation.startedAt, invocation.deadlineAt) <= Duration.ofSeconds(60),
        )
        val attempt = request.attempt
        val expectedModel = if (provider == AnalysisProvider.GEMINI) ScheduleDslGeminiWire.PINNED_MODEL else LUNA_MODEL
        require(model == expectedModel && attempt.model == expectedModel && attempt.provider == provider)
        require(
            attempt.coordinationRunId == invocation.runId &&
                attempt.invocationId == invocation.id &&
                attempt.policyVersion == invocation.policyVersion,
        )
        require(attempt.finishedAt == null && attempt.failureKind == null && attempt.attemptNumber > 0)
        require(attempt.startedAt >= invocation.startedAt && attempt.startedAt < invocation.deadlineAt)
        val dispatch = request.dispatchContext
        val execution = dispatch.execution
        require(dispatch.finalAttemptId == attempt.id)
        require(if (provider == AnalysisProvider.GEMINI) dispatch.providerOrdinal in 1..4 else dispatch.providerOrdinal == 1)
        // The binding is an immutable admission reference; advancing counters are verified by the DB gate.
        require(
            execution.kind == AiBudgetExecutionKind.FINAL_ANALYSIS &&
                execution.roomId == route.roomId &&
                execution.executionId == invocation.id,
        )
        require(
            execution.ownerToken == invocation.ownerToken &&
                execution.version == invocation.runVersion &&
                execution.generation == invocation.runVersion,
        )
        val inputs = request.dispatchInputs
        val expected = context.inputs.sortedBy { it.participantId }
        require(inputs.size in 2..50 && inputs.size == expected.size && inputs.map { it.inputRef }.distinct().size == inputs.size)
        val zone = ZoneId.of(context.timeZone)
        inputs.zip(expected).forEach { (input, native) ->
            require(input.inputRef == native.submissionVersionId.toString())
            require(Locale.forLanguageTag(input.locale).toLanguageTag() == input.locale)
            require(input.rawText == request.inputCapsule.snapshot.sourceText(native.submissionVersionId, "original"))
            input.evidence?.validateAgainst(input.rawText)
            require(native.originalRevision == input.evidence?.originalRevision)
            val content =
                listOf(input.rawText, input.locale, input.evidence?.canonical(), native.submissionRevision.toString())
                    .joinToString("") { if (it == null) "-1:" else "${it.toByteArray(Charsets.UTF_8).size}:$it" }
            require(IrEvidenceBindings.sha256(content) == native.contentSha256)
            val original = native.sources.single { it.kind == IrSourceKind.ORIGINAL }
            require(original.sourceId == "original" && original.revision == (input.evidence?.originalRevision ?: native.submissionRevision))
            require(original.contentSha256 == IrEvidenceBindings.sha256(input.rawText))
            require(input.referenceDate == original.referenceAt.atZone(zone).toLocalDate())
            input.evidence?.let { require(it.referenceAt == original.referenceAt) }
            val answer = input.evidence?.supplement
            val supplements = native.sources.filter { it.kind == IrSourceKind.SUPPLEMENT }
            require(supplements.size == if (answer == null) 0 else 1)
            if (answer != null) {
                val source = supplements.single()
                require(
                    source.sourceId == "supplement:${answer.questionId}" &&
                        source.questionId == answer.questionId &&
                        source.revision == answer.sourceRevision,
                )
                require(source.referenceAt == answer.referenceAt && source.contentSha256 == IrEvidenceBindings.sha256(answer.answerText))
                require(request.inputCapsule.snapshot.sourceText(native.submissionVersionId, source.sourceId) == answer.answerText)
            }
        }
        require(execution.sourceFingerprint == AiDispatchFingerprints.batchSource(inputs))
        require(execution.contextFingerprint == AiDispatchFingerprints.batchContext(zone, context.searchStart, context.searchEnd, inputs))
    }

    private fun target(
        endpoint: URI,
        provider: AnalysisProvider,
        model: String,
    ): URI {
        val origin = URI(if (provider == AnalysisProvider.GEMINI) "https://generativelanguage.googleapis.com" else "https://api.openai.com")
        val path = if (provider == AnalysisProvider.GEMINI) "/v1beta/models/$model:generateContent" else "/v1/responses"
        val canonical = origin.resolve(path)
        require(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
        if (endpoint == origin || endpoint == canonical) return canonical
        require(endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "::1", "[::1]") && endpoint.port in 1..65535)
        require(endpoint.rawPath in setOf("", "/", path))
        return endpoint.resolve(path)
    }

    private fun body(
        request: SelectedScheduleDslProviderRequest,
        provider: AnalysisProvider,
        model: String,
    ): ByteArray {
        if (provider == AnalysisProvider.GEMINI && request.binding.route.choice is FinalAnalysisRouteChoice.DslContextV2) {
            return ScheduleDslGeminiWire.requestContextBytes(mapper, request.inputCapsule, model)
        }
        val neutral: ByteArray
        val schema: Map<String, Any?>
        when (val selected = request.binding.route.choice) {
            is FinalAnalysisRouteChoice.DslStructuralV5 -> {
                if (selected.identity.responseContractVersion == SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT) {
                    val contract = WeeklyEvidenceProviderContract(mapper, allowPlaceConstraints = true)
                    neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                    schema = contract.responseSchema(request.inputCapsule.snapshot)
                } else if (selected.identity.responseContractVersion == SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT) {
                    val contract = IndexedEvidenceProviderContract(mapper, allowPlaceConstraints = true)
                    neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                    schema = contract.responseSchema(request.inputCapsule.snapshot)
                } else if (selected.identity.responseContractVersion == SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT) {
                    val contract = CalendarFactsEvidenceProviderContract(mapper, allowPlaceConstraints = true)
                    neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                    schema = contract.responseSchema(request.inputCapsule.snapshot)
                } else if (selected.identity.responseContractVersion == SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT) {
                    val contract = SearchScopedEvidenceProviderContract(mapper, allowPlaceConstraints = true)
                    neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                    schema = contract.responseSchema(request.inputCapsule.snapshot)
                } else if (selected.identity.responseContractVersion == SERVER_EVIDENCE_RESPONSE_CONTRACT) {
                    val contract = ServerEvidenceProviderContract(mapper, allowPlaceConstraints = true)
                    neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                    schema = contract.responseSchema()
                } else {
                    val contract = ScheduleDslStructuralProviderContract(mapper)
                    neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                    schema = contract.responseSchema()
                }
            }
            is FinalAnalysisRouteChoice.DslContextModeV4 -> {
                val contract = ScheduleDslContextModeProviderContract(mapper)
                neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                schema = contract.responseSchema()
            }
            is FinalAnalysisRouteChoice.DslContextV2 -> {
                neutral = ScheduleDslContextProviderContract(mapper).requestBytes(request.inputCapsule)
                schema = ScheduleDslProviderContract(mapper).responseSchema()
            }
            is FinalAnalysisRouteChoice.DslApprovedTermsV3 -> {
                val contract = ScheduleDslApprovedTermsProviderContract(mapper)
                neutral = contract.requestBytes(request.inputCapsule, selected.meetingMode)
                schema = contract.responseSchema()
            }
            FinalAnalysisRouteChoice.Legacy -> error("CONFIGURATION")
        }
        if ((request.binding.route.choice as? FinalAnalysisRouteChoice.DslStructuralV5)?.identity?.responseContractVersion in
            setOf(
                SERVER_EVIDENCE_RESPONSE_CONTRACT,
                SERVER_EVIDENCE_SCOPED_RESPONSE_CONTRACT,
                SERVER_EVIDENCE_CALENDAR_RESPONSE_CONTRACT,
                SERVER_EVIDENCE_INDEXED_RESPONSE_CONTRACT,
                SERVER_EVIDENCE_WEEKLY_RESPONSE_CONTRACT,
            )
        ) {
            return ServerEvidenceWire.bytes(mapper, neutral, provider, model)
        }
        return mapper.writeValueAsBytes(
            if (provider == AnalysisProvider.GEMINI) {
                linkedMapOf(
                    "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to neutral.toString(Charsets.UTF_8))))),
                    "generationConfig" to
                        linkedMapOf(
                            "responseMimeType" to "application/json",
                            "responseJsonSchema" to GeminiResponseSchemaProjection.project(schema),
                            "candidateCount" to 1,
                            "maxOutputTokens" to ScheduleDslGeminiWire.MAX_OUTPUT_TOKENS,
                            "thinkingConfig" to mapOf("thinkingLevel" to "LOW"),
                        ).apply {
                            if (request.binding.route.choice is FinalAnalysisRouteChoice.DslStructuralV5 && model == "gemini-3.8-flash") {
                                remove("candidateCount")
                            }
                        },
                )
            } else {
                linkedMapOf(
                    "model" to model,
                    "store" to false,
                    "input" to neutral.toString(Charsets.UTF_8),
                    "max_output_tokens" to ScheduleDslGeminiWire.MAX_OUTPUT_TOKENS,
                    "text" to
                        mapOf(
                            "format" to
                                mapOf(
                                    "type" to "json_schema",
                                    "name" to
                                        if (request.binding.route.choice is FinalAnalysisRouteChoice.DslStructuralV5) {
                                            "meet_me_schedule_dsl_v5"
                                        } else {
                                            "meet_me_schedule_dsl"
                                        },
                                    "strict" to true,
                                    "schema" to schema,
                                ),
                        ),
                ).apply {
                    if (request.binding.route.choice is FinalAnalysisRouteChoice.DslStructuralV5) {
                        val data = reader.readValue<Map<String, Any?>>(neutral).toMutableMap()
                        val instructions = data.remove("prompt")
                        require(instructions is String && instructions.isNotBlank())
                        put("instructions", instructions)
                        put("input", mapper.writeValueAsString(data))
                        put("reasoning", mapOf("effort" to "low"))
                    }
                }
            },
        )
    }

    private fun lunaResponse(
        root: Map<*, *>,
        responseBytes: Int,
        model: String,
    ): ScheduleDslProviderResponse {
        require(root["status"] == "completed" && root["error"] == null && root["incomplete_details"] == null)
        require(root["model"] == null || root["model"] == model)
        val output = root["output"] as? List<*> ?: fail(ParserFailureKind.INVALID_RESPONSE)
        val texts = mutableListOf<String>()
        output.forEach { raw ->
            val item = raw as? Map<*, *> ?: fail(ParserFailureKind.INVALID_RESPONSE)
            when (item["type"]) {
                "reasoning" -> Unit
                "message" -> {
                    require(item["role"] == "assistant")
                    require(item["status"] == null || item["status"] == "completed")
                    val content = item["content"] as? List<*> ?: fail(ParserFailureKind.INVALID_RESPONSE)
                    require(content.size == 1)
                    val part = content.single() as? Map<*, *> ?: fail(ParserFailureKind.INVALID_RESPONSE)
                    require(part["type"] == "output_text" && part["refusal"] == null)
                    texts += part["text"] as? String ?: fail(ParserFailureKind.INVALID_RESPONSE)
                }
                else -> fail(ParserFailureKind.INVALID_RESPONSE)
            }
        }
        require(texts.size == 1)
        val text = texts.single()
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= DSL_LIMIT)
        val usage = root["usage"] as? Map<*, *>
        return ScheduleDslProviderResponse(
            text,
            ParserUsage(token(usage, "input_tokens"), token(usage, "output_tokens"), responseBytes),
            AnalysisProvider.OPENAI,
            model,
        )
    }

    private fun knownUsage(
        root: Map<*, *>,
        provider: AnalysisProvider,
    ): AiBudgetUsage {
        val usage = root[if (provider == AnalysisProvider.GEMINI) "usageMetadata" else "usage"] as? Map<*, *>
        return if (provider == AnalysisProvider.GEMINI) {
            AiBudgetUsage(
                token(usage, "promptTokenCount"),
                token(usage, "candidatesTokenCount"),
                token(usage, "thoughtsTokenCount"),
                token(usage, "cachedContentTokenCount"),
            )
        } else {
            AiBudgetUsage(
                token(usage, "input_tokens"),
                token(usage, "output_tokens"),
                token(usage?.get("output_tokens_details") as? Map<*, *>, "reasoning_tokens"),
                token(usage?.get("input_tokens_details") as? Map<*, *>, "cached_tokens"),
            )
        }
    }

    private fun token(
        usage: Map<*, *>?,
        field: String,
    ): Long? = (usage?.get(field) as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it >= 0 }

    private fun outcome(
        completed: Boolean,
        kind: ParserFailureKind?,
    ): AiBudgetOutcome =
        when {
            completed -> AiBudgetOutcome.SUCCEEDED
            kind == ParserFailureKind.TIMEOUT -> AiBudgetOutcome.TIMED_OUT
            kind == ParserFailureKind.NETWORK -> AiBudgetOutcome.UNKNOWN
            else -> AiBudgetOutcome.FAILED
        }

    private fun budgetFailure(kind: ParserFailureKind?): AiBudgetFailure? =
        when (kind) {
            null -> null
            ParserFailureKind.TIMEOUT -> AiBudgetFailure.TIMEOUT
            ParserFailureKind.NETWORK -> AiBudgetFailure.NETWORK
            ParserFailureKind.INVALID_RESPONSE -> AiBudgetFailure.INVALID_RESPONSE
            ParserFailureKind.CONFIGURATION -> AiBudgetFailure.CONFIGURATION
            ParserFailureKind.AUTHENTICATION -> AiBudgetFailure.AUTHENTICATION
            ParserFailureKind.PERMISSION -> AiBudgetFailure.PERMISSION
            ParserFailureKind.QUOTA -> AiBudgetFailure.QUOTA
            ParserFailureKind.RATE_LIMIT -> AiBudgetFailure.RATE_LIMIT
            else -> AiBudgetFailure.PROVIDER
        }

    private fun <T> configuration(block: () -> T): T =
        try {
            block()
        } catch (_: RuntimeException) {
            fail(ParserFailureKind.CONFIGURATION)
        }

    private fun fail(kind: ParserFailureKind): Nothing = throw NaturalLanguageParserException(kind)

    companion object {
        private const val LUNA_MODEL = "gpt-6-luna"
        private const val PROFILE_CAPTURE_LIMIT = 32 * 1024 * 1024
        private const val DSL_LIMIT = 256 * 1024
        private const val GEMINI_RESPONSE_LIMIT = 1024 * 1024
    }
}
