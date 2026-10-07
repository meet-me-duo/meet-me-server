package com.meetme.server.coordination.adapter.output.integration.evaluation

import com.google.genai.Client
import com.google.genai.types.ClientOptions
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.ThinkingLevel
import com.meetme.server.config.GeminiProperties
import com.meetme.server.coordination.adapter.output.integration.GeminiNaturalLanguageParserAdapter
import com.meetme.server.coordination.application.port.output.CoordinationRunRepository
import com.meetme.server.coordination.application.port.output.NaturalLanguageBatchRequest
import com.meetme.server.coordination.application.port.output.NaturalLanguageInput
import com.meetme.server.coordination.application.port.output.NormalizedPlaceRepository
import com.meetme.server.coordination.application.service.MatchingProcessingPersistenceService
import com.meetme.server.coordination.application.service.MatchingProcessor
import com.meetme.server.coordination.application.service.MatchingResultService
import com.meetme.server.coordination.domain.CoordinationRun
import com.meetme.server.coordination.domain.SubmissionBatch
import com.meetme.server.meetingroom.application.port.output.MeetingRoomRepository
import com.meetme.server.meetingroom.domain.ClosurePolicy
import com.meetme.server.meetingroom.domain.InviteCode
import com.meetme.server.meetingroom.domain.MeetingMode
import com.meetme.server.meetingroom.domain.MeetingRoom
import com.meetme.server.participant.application.port.output.GuestCredentialPort
import com.meetme.server.participant.application.port.output.GuestSessionRepository
import com.meetme.server.participant.application.port.output.ParticipantRepository
import com.meetme.server.participant.domain.GuestSession
import com.meetme.server.participant.domain.Participant
import com.meetme.server.participant.domain.ParticipantDisplayName
import com.meetme.server.shared.application.port.output.IdGenerator
import com.meetme.server.shared.domain.CoordinationRunId
import com.meetme.server.shared.domain.GuestSessionId
import com.meetme.server.shared.domain.MeetingRoomId
import com.meetme.server.shared.domain.ParticipantId
import com.meetme.server.shared.domain.SubmissionBatchId
import com.meetme.server.shared.domain.SubmissionId
import com.meetme.server.shared.domain.SubmissionVersionId
import com.meetme.server.shared.domain.time.MeetingTimeZone
import com.meetme.server.shared.domain.time.SearchDateRange
import com.meetme.server.submission.application.port.output.StructuredSubmissionRepository
import com.meetme.server.submission.application.port.output.SubmissionRepository
import com.meetme.server.submission.domain.StructuredCondition
import com.meetme.server.submission.domain.StructuredSubmissionResult
import com.meetme.server.submission.domain.Submission
import okhttp3.OkHttpClient
import org.mockito.Mockito
import org.springframework.context.support.ResourceBundleMessageSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** Standalone opt-in runner. No Spring context, database, production API, or automatic retries. */
object BoundedGeminiEvaluation {
    private val mapper = JsonMapper.builder().build()
    private const val MAX_CALLS = 25
    private const val LIMIT_NANO_USD = 300_000_000L
    private const val INPUT_NANO_USD_PER_TOKEN = 750L
    private const val OUTPUT_NANO_USD_PER_TOKEN = 3_750L
    private const val FROZEN_PLAN_SHA = "44a02c180a852bdd5cb0757c9e13cc1a8d64c5d32642a6dd256de068cbad9fe1"
    private const val FROZEN_EXTENSION_SHA = "8b1133de496a8489b8fe5bd68b522ba8523dd54cdca57e385a77feeb57206a6d"
    private val adapter = GeminiNaturalLanguageParserAdapter(GeminiProperties(), mapper)
    private val schemaJson = mapper.writeValueAsString(GeminiNaturalLanguageParserAdapter.RESPONSE_SCHEMA)

    @JvmStatic
    fun main(args: Array<String>) {
        // Do not print exceptions: HTTP exception messages can contain headers or credential-bearing URLs.
        try {
            run(args)
        } catch (_: Throwable) {
            println("Evaluation stopped: harness preflight or execution failure; no exception contents exported.")
            kotlin.system.exitProcess(2)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(args: Array<String>) {
        require(args.size == 3)
        val planPath = Path.of(args[0])
        val planBytes = Files.readAllBytes(planPath)
        require(sha(planBytes) == FROZEN_PLAN_SHA) // No expectation revisions after observing model output.
        val plan = mapper.readValue(planBytes, Map::class.java) as Map<String, Any?>
        val cases = plan["cases"] as List<Map<String, Any?>>
        require(cases.size == 23 && plan["model"] == "gemini-3.8-flash")
        val extensionFile = System.getenv("MEETME_EVAL_EXTENSION_FILE").orEmpty()
        val extensionBytes = extensionFile.takeIf { it.isNotBlank() }?.let { Files.readAllBytes(Path.of(it)) }
        val additionalCases =
            if (extensionBytes != null) {
                require(sha(extensionBytes) == FROZEN_EXTENSION_SHA)
                val extension = mapper.readValue(extensionBytes, Map::class.java) as Map<String, Any?>
                (extension["additional_cases"] as List<Map<String, Any?>>).also { require(it.size == 5) }
            } else {
                emptyList()
            }
        val filler = cases.single { it["id"] == "exact-user-filler" }
        val ordered =
            listOf(filler, filler + ("id" to "exact-user-filler-repeat-1"), filler + ("id" to "exact-user-filler-repeat-2")) +
                additionalCases +
                cases.filter { it["id"] != "exact-user-filler" }
        require(ordered.size == MAX_CALLS + additionalCases.size)
        require(ordered.map { it["id"] }.distinct().size == ordered.size)
        require(ordered.all { it["id"].toString().matches(Regex("[A-Za-z0-9-]+")) })
        val output = Path.of(args[1])
        val replayDirectory = System.getenv("MEETME_EVAL_REPLAY_DIRECTORY").orEmpty()
        val replayRoot = replayDirectory.takeIf { it.isNotBlank() }?.let { Path.of(it).toRealPath() }
        if (replayRoot != null) {
            require(Files.isDirectory(replayRoot))
            require(!output.toAbsolutePath().normalize().startsWith(replayRoot))
        }
        // Fresh output protects recorded evidence. A separate live approval ledger protects cumulative spending.
        Files.createDirectory(output)
        val armed = System.getenv("MEETME_LIVE_EVAL_AUTHORIZED") == "true"
        val costBoundVerified = System.getenv("MEETME_LIVE_COST_BOUND_VERIFIED") == "true"
        val records = ordered.map { linkedMapOf<String, Any?>("id" to it["id"], "status" to "UNRUN", "expected" to it["expected"]) }
        val ledger =
            linkedMapOf<String, Any?>(
                "utc_started" to Instant.now().toString(),
                "repository_sha" to args[2],
                "plan_sha256" to sha(planBytes),
                "extension_sha256" to extensionBytes?.let(::sha),
                "planned_evaluations" to ordered.size,
                "expected_unchanged" to true,
                "mode" to if (replayRoot != null) "REPLAY_POSTPROCESSING" else "DISARMED_DRY_RUN",
                "schema_sha256" to sha(schemaJson.toByteArray()),
                "model" to "gemini-3.8-flash",
                "sdk_version" to "1.72.0",
                "limits" to
                    mapOf(
                        "calls" to MAX_CALLS,
                        "nano_usd" to LIMIT_NANO_USD,
                        "attempts" to 1,
                        "timeout_ms" to 15_000,
                        "max_output_tokens" to 32_768,
                        "max_response_bytes" to 262_144,
                        "thinking" to "LOW",
                    ),
                "provider_calls" to 0,
                "accounted_nano_usd" to 0L,
                "verified_usage_nano_usd" to 0L,
                "input_token_bound" to
                    "100000 minus prior verified input reserved each time; UTF8 estimate separately recorded; " +
                    "text/schema only; no tools/media/cache",
                "cost_bound_limitations" to
                    listOf(
                        "Input reservation is conservative, not a vendor hard cap.",
                        "Parent evaluation task reports official verification of a combined thinking/final output cap; " +
                            "input100k reservation remains unverified.",
                    ),
                "results" to records,
            )

        fun save() = write(output.resolve("report.json"), ledger)
        write(output.resolve("fixed-expectations.json"), ordered)
        if (replayRoot != null) {
            ledger["new_prompt_model_accuracy_evidence"] = false
            ledger["replay_limitation"] =
                "Recorded wire is postprocessed only; no new model output or current-prompt evaluation is implied."
            save()
            var replayed = 0
            for ((index, case) in ordered.withIndex()) {
                val record = records[index]
                val source = replayRoot.resolve("${case["id"]}.wire.json")
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    record["reason"] = "NO_RECORDED_PROVIDER_WIRE"
                    continue
                }
                try {
                    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                    require(Files.size(source) <= 262_144)
                    val bytes = Files.readAllBytes(source)
                    require(bytes.size <= 262_144)
                    record["source_wire_sha256"] = sha(bytes)
                    val fixture = Fixture(case)
                    record["current_prompt_sha256"] = sha(adapter.prompt(fixture.request).toByteArray())
                    compareWire(bytes.toString(Charsets.UTF_8), case, fixture, record)
                    replayed += 1
                } catch (_: Throwable) {
                    record["status"] = "FAIL"
                    record["reason"] = "REPLAY_WIRE_NOT_READABLE_OR_OVERSIZED"
                }
                save()
            }
            ledger["replayed_cases"] = replayed
            ledger["utc_finished"] = Instant.now().toString()
            ledger["stop_reason"] = "REPLAY_COMPLETE_NO_PROVIDER_CALLS"
            save()
            println("Replay only: provider calls 0; $replayed recorded responses compared; missing responses remain UNRUN.")
            return // No key read, SDK client, proxy setup, budget reservation or paid request in replay mode.
        }
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        if (!armed || key.isBlank() || !costBoundVerified) {
            ledger["stop_reason"] =
                when {
                    !armed -> "NOT_EXPLICITLY_ARMED"
                    key.isBlank() -> "CREDENTIAL_MISSING"
                    else -> "VENDOR_COST_BOUND_UNVERIFIED"
                }
            save()
            println("Provider calls: 0; all ${ordered.size} planned evaluations UNRUN.")
            return
        }
        val approvalId = System.getenv("MEETME_EVAL_APPROVAL_ID").orEmpty()
        val approvalDirectory = System.getenv("MEETME_EVAL_LEDGER_DIRECTORY").orEmpty()
        if (approvalId.isBlank() || approvalDirectory.isBlank()) {
            ledger["stop_reason"] = "SHARED_APPROVAL_LEDGER_REQUIRED"
            save()
            println("Provider calls: 0; explicit approval ID and shared ledger directory are required.")
            return
        }
        ledger["mode"] = "LIVE_EVALUATION"
        val policySha =
            sha(
                (
                    "gemini-3.8-flash|LOW|1|32768|15000|attempts1|750|3750|25|300000000|100000|40000|" +
                        sha(schemaJson.toByteArray())
                ).toByteArray(),
            )
        val sharedApproval =
            try {
                ApprovalBudgetLedger.open(approvalId, approvalDirectory, policySha)
            } catch (_: Throwable) {
                ledger["stop_reason"] = "SHARED_APPROVAL_LOCK_OR_STATE_PREFLIGHT_FAILED"
                save()
                println("Provider calls this run: 0; shared approval lock or state preflight failed.")
                return
            }
        sharedApproval.use { approval ->
            ledger["approval_id"] = approvalId
            ledger["approval_policy_sha256"] = policySha

            fun syncApproval() {
                for (field in listOf(
                    "provider_calls",
                    "accounted_nano_usd",
                    "verified_usage_nano_usd",
                    "total_input_tokens",
                    "total_billed_output_including_thinking_tokens",
                )) {
                    ledger[field] = approval.amount(field)
                }
            }
            syncApproval()
            ledger["this_run_provider_calls"] = 0
            if (approval.hasPending()) {
                ledger["stop_reason"] = "PRIOR_PENDING_RESERVATION_RETAINED_MANUAL_HANDOFF_REQUIRED"
                save()
                println("Provider calls this run: 0; prior unresolved reservation requires owner handoff.")
                return
            }
            // Uses the already configured proxy and default certificate trust. Never fall back to a direct connection.
            val proxyUri = URI(System.getenv("HTTPS_PROXY") ?: error("Configured proxy missing"))
            require(proxyUri.scheme == "http" && proxyUri.userInfo == null && !proxyUri.host.isNullOrBlank())
            require(System.getenv("GOOGLE_GEMINI_BASE_URL").isNullOrBlank())
            require(System.getenv("GOOGLE_GENAI_USE_VERTEXAI").isNullOrBlank())
            val transport =
                OkHttpClient
                    .Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyUri.host, if (proxyUri.port < 0) 80 else proxyUri.port)))
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(Duration.ofMillis(15_000))
                    .build()
            val config =
                GenerateContentConfig
                    .builder()
                    .responseMimeType("application/json")
                    .responseJsonSchema(GeminiNaturalLanguageParserAdapter.RESPONSE_SCHEMA)
                    .candidateCount(1)
                    .maxOutputTokens(32_768)
                    .thinkingConfig(ThinkingConfig.builder().thinkingLevel(ThinkingLevel.Known.LOW))
                    .build()
            var accounted = approval.amount("accounted_nano_usd")
            var verified = approval.amount("verified_usage_nano_usd")
            var totalInput = approval.amount("total_input_tokens")
            var totalOutput = approval.amount("total_billed_output_including_thinking_tokens")
            var calls = approval.amount("provider_calls").toInt()
            var thisRunCalls = 0
            Client
                .builder()
                .apiKey(key)
                .vertexAI(false)
                .httpOptions(
                    HttpOptions
                        .builder()
                        .baseUrl("https://generativelanguage.googleapis.com")
                        .apiVersion("v1beta")
                        .timeout(15_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1))
                        .build(),
                ).clientOptions(ClientOptions.builder().customHttpClient(transport).build())
                .build()
                .use { client ->
                    for ((index, case) in ordered.withIndex()) {
                        val record = records[index]
                        val fixture = Fixture(case)
                        val prompt = adapter.prompt(fixture.request)
                        val promptSha = sha(prompt.toByteArray())
                        if (approval.hasDuplicate(case["id"].toString(), promptSha)) {
                            record["reason"] = "CASE_PROMPT_POLICY_ALREADY_CALLED"
                            save()
                            continue
                        }
                        require(fixture.request.inputs.size in 2..3 && fixture.request.inputs.all { it.rawText.length <= 500 })
                        require(prompt.toByteArray().size <= 16_384 && schemaJson.toByteArray().size <= 16_384)
                        val inputEstimate = 4L * (prompt.toByteArray().size + schemaJson.toByteArray().size) + 16_384L
                        val inputBound = 100_000 - totalInput
                        val reservation = inputBound * INPUT_NANO_USD_PER_TOKEN + 32_768L * OUTPUT_NANO_USD_PER_TOKEN
                        record["prompt_sha256"] = promptSha
                        record["input_token_upper_estimate"] = inputEstimate
                        record["reserved_input_tokens"] = inputBound
                        record["reservation_nano_usd"] = reservation
                        if (calls >= MAX_CALLS ||
                            accounted + reservation > LIMIT_NANO_USD ||
                            totalInput + inputBound > 100_000 ||
                            totalOutput + 32_768 > 40_000
                        ) {
                            ledger["stop_reason"] = "NEXT_CALL_WORST_CASE_EXCEEDS_BUDGET"
                            break
                        }
                        // Durable reservation BEFORE the SDK can issue the request.
                        approval.reserve(case["id"].toString(), promptSha, inputEstimate, inputBound, reservation)
                        accounted += reservation
                        calls += 1
                        thisRunCalls += 1
                        ledger["this_run_provider_calls"] = thisRunCalls
                        record["status"] = "RESERVED_PENDING_RESPONSE"
                        ledger["provider_calls"] = calls
                        ledger["accounted_nano_usd"] = accounted
                        save()
                        val started = System.nanoTime()
                        val response =
                            try {
                                client.models.generateContent("gemini-3.8-flash", prompt, config)
                            } catch (_: Throwable) {
                                record["status"] = "FAIL"
                                record["reason"] = "PROVIDER_CALL_FAILED_USAGE_UNKNOWN_FULL_RESERVATION_RETAINED"
                                record["latency_ms"] = (System.nanoTime() - started) / 1_000_000
                                ledger["stop_reason"] = "PROVIDER_FAILURE_NO_AUTOMATIC_RETRY"
                                save()
                                break
                            }
                        record["latency_ms"] = (System.nanoTime() - started) / 1_000_000
                        record["model_version"] = response.modelVersion().orElse(null)
                        val usage = response.usageMetadata().orElse(null)
                        val input = usage?.promptTokenCount()?.orElse(null)?.toLong()
                        val candidates = usage?.candidatesTokenCount()?.orElse(null)?.toLong()
                        val thoughts = usage?.thoughtsTokenCount()?.orElse(null)?.toLong()
                        val total = usage?.totalTokenCount()?.orElse(null)?.toLong()
                        val tools = usage?.toolUsePromptTokenCount()?.orElse(null)?.toLong()
                        record["usage"] =
                            mapOf(
                                "prompt" to input,
                                "candidates" to candidates,
                                "thoughts" to thoughts,
                                "total" to total,
                                "cached_input" to usage?.cachedContentTokenCount()?.orElse(null),
                                "tool_prompt" to tools,
                            )
                        if (input == null ||
                            candidates == null ||
                            total == null ||
                            input < 0 ||
                            candidates < 0 ||
                            (thoughts != null && thoughts < 0) ||
                            (tools != null && tools != 0L) ||
                            total < input + candidates + (thoughts ?: 0L) ||
                            input > inputBound ||
                            total - input > 32_768
                        ) {
                            record["status"] = "FAIL"
                            record["reason"] = "USAGE_MISSING_INCONSISTENT_OR_EXCEEDS_RESERVATION"
                            ledger["stop_reason"] = "USAGE_UNVERIFIED_FULL_RESERVATION_RETAINED"
                            save()
                            break
                        }
                        val billedOutput = total - input // Includes reasoning even when thoughtsTokenCount is absent.
                        val cost = input * INPUT_NANO_USD_PER_TOKEN + billedOutput * OUTPUT_NANO_USD_PER_TOKEN
                        approval.complete(cost, input, billedOutput)
                        accounted += cost - reservation
                        verified += cost
                        totalInput += input
                        totalOutput += billedOutput
                        record["verified_nano_usd"] = cost
                        ledger["accounted_nano_usd"] = accounted
                        ledger["verified_usage_nano_usd"] = verified
                        ledger["total_input_tokens"] = totalInput
                        ledger["total_billed_output_including_thinking_tokens"] = totalOutput
                        val wire = response.text()
                        if (wire == null || wire.toByteArray().size > 262_144) {
                            record["status"] = "FAIL"
                            record["reason"] = "PROVIDER_TEXT_MISSING_OR_OVERSIZED"
                        } else {
                            // Wire JSON contains synthetic input interpretation only. Do not serialize sdkHttpResponse or exceptions.
                            Files.writeString(output.resolve("${case["id"]}.wire.json"), wire, StandardOpenOption.CREATE_NEW)
                            compareWire(wire, case, fixture, record)
                        }
                        save()
                    }
                }
            ledger["utc_finished"] = Instant.now().toString()
            save()
            println("Provider calls: $calls approval-total, $thisRunCalls this run; PASS/FAIL/UNRUN retained separately.")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareWire(
        wire: String,
        case: Map<String, Any?>,
        fixture: Fixture,
        record: MutableMap<String, Any?>,
    ) {
        try {
            validateWire(wire, fixture.request)
            val parsed = adapter.parseProviderResponse(wire, fixture.request)
            record["normalized"] = fixture.normalized(parsed)
            val actual = fixture.evaluate(parsed)
            record["actual"] = actual
            val expected = case["expected"] as Map<String, Any?>
            require((expected["participant_count"] as Number).toInt() == fixture.request.inputs.size)
            val mismatches =
                expected.keys.filter {
                    it != "participant_count" &&
                        mapper.writeValueAsString(expected[it]) != mapper.writeValueAsString(actual[it])
                }
            record["mismatches"] = mismatches
            record["status"] = if (mismatches.isEmpty()) "PASS" else "FAIL"
        } catch (_: Throwable) {
            record["status"] = "FAIL"
            record["reason"] = "ADAPTER_OR_MATCHING_REJECTED_RESPONSE"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateWire(
        wire: String,
        request: NaturalLanguageBatchRequest,
    ) {
        val root = mapper.readValue(wire, Map::class.java) as Map<String, Any?>
        require(root.keys == setOf("schema_version", "results") && root["schema_version"] == "2")
        val results = root["results"] as List<Map<String, Any?>>
        require(results.size == request.inputs.size)
        require(results.map { it["input_ref"] }.toSet() == request.inputs.map { it.inputRef }.toSet())
        for (result in results) {
            require(result.keys.containsAll(setOf("input_ref", "conditions")))
            require(result.keys.all { it in setOf("input_ref", "conditions", "rejection_code") })
            require(result["rejection_code"] == null || result["rejection_code"] is String)
            val conditions = result["conditions"] as List<Map<String, Any?>>
            require(conditions.size <= 32)
            for (condition in conditions) {
                when (condition["type"]) {
                    "TIME_WINDOW" -> {
                        require(condition.keys == setOf("type", "polarity", "date", "day_of_week", "start_time", "end_time"))
                        require(condition["polarity"] in setOf("AVAILABLE", "UNAVAILABLE"))
                        // Match adapter compatibility: a dated scope may redundantly carry its matching weekday.
                        // Both absent, conflicting redundant scopes, and out-of-range dates remain invalid.
                        val date = condition["date"]?.let { LocalDate.parse(it as String) }
                        val day = condition["day_of_week"]?.let { DayOfWeek.valueOf(it as String) }
                        require(date != null || day != null)
                        if (date != null) {
                            require(date >= request.searchStartDate && date < request.searchEndDate)
                        }
                        if (date != null && day != null) require(date.dayOfWeek == day)
                        val start = LocalTime.parse(condition["start_time"] as String)
                        val end = condition["end_time"] as String
                        require(end == "24:00" || start < LocalTime.parse(end))
                    }
                    "SPECIFIC_PLACE" -> {
                        require(condition.keys == setOf("type", "query", "area_key", "area_name"))
                        require((condition["query"] as String).isNotBlank())
                        require((condition["area_key"] as String).matches(Regex("AREA_[1-9][0-9]*")))
                        require((condition["area_name"] as String).isNotBlank())
                    }
                    "TRAVEL_CONSTRAINT" -> {
                        require(condition.keys == setOf("type", "expression"))
                        require((condition["expression"] as String).isNotBlank())
                    }
                    "UNRESOLVED_PLACE" -> {
                        require(condition.keys == setOf("type", "query"))
                        require((condition["query"] as String).isNotBlank())
                    }
                    else -> error("Invalid wire condition")
                }
            }
        }
    }

    private class Fixture(
        case: Map<String, Any?>,
    ) {
        private val locale = Locale.forLanguageTag(case["locale"].toString())
        private val referenceDate = LocalDate.parse(case["reference_date"].toString())
        private val zone = MeetingTimeZone.of(case["room_timezone"].toString())
        private val range =
            SearchDateRange.explicit(
                LocalDate.parse(case["search_start_inclusive"].toString()),
                LocalDate.parse(case["search_end_exclusive"].toString()),
            )
        private val created = referenceDate.atStartOfDay(zone.value).toInstant()
        private val room =
            MeetingRoom.create(
                MeetingRoomId(uuid(1)),
                InviteCode.of("abcdefghijklmnopqrstuv"),
                "synthetic evaluation",
                MeetingMode.REMOTE,
                zone,
                range,
                ClosurePolicy.of(expectedParticipants = 2),
                created,
            )
        private val participants = (case["participants"] as List<*>).mapIndexed { i, _ -> ParticipantId(uuid(100 + i)) }
        private val submissions =
            (case["participants"] as List<*>).mapIndexed { i, text ->
                Submission.start(
                    SubmissionId(uuid(200 + i)),
                    room.id,
                    participants[i],
                    SubmissionVersionId(uuid(300 + i)),
                    text.toString(),
                    emptyList(),
                    locale,
                    created,
                )
            }
        val request =
            NaturalLanguageBatchRequest(
                zone.value,
                range.startInclusive,
                range.endExclusive,
                submissions.map {
                    NaturalLanguageInput(
                        it.latest.id.value
                            .toString(),
                        requireNotNull(it.latest.rawText),
                        locale,
                        referenceDate,
                    )
                },
            )

        fun normalized(parsed: List<StructuredSubmissionResult>) =
            parsed.map { result ->
                mapOf(
                    "participant_index" to submissions.indexOfFirst { it.latest.id == result.submissionVersionId },
                    "rejection_code" to result.rejectionCode,
                    "conditions" to
                        result.conditions.map { c ->
                            when (c) {
                                is StructuredCondition.TimeWindow ->
                                    mapOf(
                                        "type" to "TIME_WINDOW",
                                        "polarity" to c.polarity.name,
                                        "date" to c.date?.toString(),
                                        "day_of_week" to c.dayOfWeek?.name,
                                        "start_time" to c.startTime.toString(),
                                        "end_time" to if (c.endsAtNextDayStart) "24:00" else c.endTime.toString(),
                                    )
                                is StructuredCondition.SpecificPlace ->
                                    mapOf(
                                        "type" to "SPECIFIC_PLACE",
                                        "query" to c.query,
                                        "area_key" to c.areaKey,
                                        "area_name" to c.areaName,
                                    )
                                is StructuredCondition.TravelConstraint ->
                                    mapOf(
                                        "type" to "TRAVEL_CONSTRAINT",
                                        "expression" to c.expression,
                                    )
                                is StructuredCondition.UnresolvedPlace -> mapOf("type" to "UNRESOLVED_PLACE", "query" to c.query)
                            }
                        },
                )
            }

        fun evaluate(parsed: List<StructuredSubmissionResult>): Map<String, Any?> {
            val batch = SubmissionBatch(SubmissionBatchId(uuid(2)), room.id, submissions.map { it.latest.id }, created)
            var run = CoordinationRun.queued(CoordinationRunId(uuid(3)), batch).startMatching()
            val rooms = Mockito.mock(MeetingRoomRepository::class.java)
            val runs = Mockito.mock(CoordinationRunRepository::class.java)
            val inputs = Mockito.mock(SubmissionRepository::class.java)
            val structured = Mockito.mock(StructuredSubmissionRepository::class.java)
            val places = Mockito.mock(NormalizedPlaceRepository::class.java)
            Mockito.`when`(rooms.findById(room.id)).thenReturn(room)
            Mockito.`when`(rooms.findByInviteCode(room.inviteCode)).thenReturn(room)
            Mockito.`when`(runs.findByBatchId(batch.id)).thenAnswer { run }
            Mockito.`when`(runs.findLatestByRoom(room.id)).thenAnswer { run }
            Mockito.`when`(inputs.findLatestByRoom(room.id)).thenReturn(submissions)
            Mockito.`when`(structured.findByBatch(batch.id)).thenReturn(parsed)
            Mockito.`when`(places.findByBatch(batch.id)).thenReturn(emptyList())
            Mockito
                .doAnswer {
                    run = it.getArgument(0)
                    null
                }.`when`(runs)
                .update(anyValue())
            var nextId = 500
            MatchingProcessor(
                runs,
                inputs,
                structured,
                places,
                IdGenerator { uuid(nextId++) },
                MatchingProcessingPersistenceService(rooms, runs, places),
                clock = Clock.fixed(created, ZoneOffset.UTC),
            ).process(batch.id)
            val sessions = Mockito.mock(GuestSessionRepository::class.java)
            val members = Mockito.mock(ParticipantRepository::class.java)
            val credentials = Mockito.mock(GuestCredentialPort::class.java)
            val session = GuestSession(GuestSessionId(uuid(4)), "synthetic-digest", created.plusSeconds(3600), null, created)
            val host = Participant.host(participants.first(), room.id, session.id, ParticipantDisplayName.of("synthetic host"), created)
            Mockito.`when`(credentials.digest("synthetic-credential")).thenReturn(session.credentialDigest)
            Mockito.`when`(sessions.findByCredentialDigest(session.credentialDigest)).thenReturn(session)
            Mockito.`when`(members.findByRoomAndGuestSession(room.id, session.id)).thenReturn(host)
            val messages =
                ResourceBundleMessageSource().apply {
                    setBasename("messages")
                    setDefaultEncoding("UTF-8")
                }
            val view =
                MatchingResultService(
                    rooms,
                    sessions,
                    members,
                    inputs,
                    structured,
                    places,
                    runs,
                    credentials,
                    messages,
                    Clock.fixed(created, ZoneOffset.UTC),
                ).getCandidates(room.inviteCode.value, "synthetic-credential", locale)
            val candidate = view.candidates.firstOrNull()
            return mapOf(
                "exact_utc_intervals" to
                    candidate?.timeRanges.orEmpty().map { listOf(it.startInclusive.toString(), it.endExclusive.toString()) },
                "rejection_code" to parsed.firstOrNull { it.submissionVersionId == submissions.first().latest.id }?.rejectionCode,
                "all_rejection_codes" to parsed.map { it.rejectionCode },
                "quality" to view.quality.name,
                "candidate_count" to view.candidates.size,
                "candidate_participant_indices" to
                    run.candidates
                        .firstOrNull()
                        ?.participantIds
                        .orEmpty()
                        .map { participants.indexOf(it) }
                        .sorted(),
                "candidate_attendance_count" to (candidate?.attendanceCount ?: 0),
                "total_participants" to participants.size,
                "applied_submissions" to view.appliedSubmissions,
                "unapplied_inputs" to view.unappliedInputs,
                "candidate_plan_type" to candidate?.planType?.name,
                "meeting_mode" to (candidate?.meetingMode ?: room.mode).name,
                "summary_ko_KR" to candidate?.summary,
                "all_candidates" to
                    view.candidates.map {
                        mapOf(
                            "attendance" to it.attendanceCount,
                            "summary" to it.summary,
                            "ranges" to it.timeRanges.map { r -> listOf(r.startInclusive.toString(), r.endExclusive.toString()) },
                        )
                    },
                "per_participant_availability" to
                    submissions.map { submission ->
                        val result = parsed.single { it.submissionVersionId == submission.latest.id }
                        val availability =
                            if (result.rejectionCode in
                                setOf("AMBIGUOUS_TIME_CONSTRAINT", "UNSUPPORTED_CONDITIONAL_CONSTRAINT")
                            ) {
                                emptyList()
                            } else {
                                com.meetme.server.coordination.domain.matching.TimeRangeMatcher.calculateAvailability(
                                    result.conditions.filterIsInstance<StructuredCondition.TimeWindow>(),
                                    emptyList(),
                                    emptyList(),
                                    emptyList(),
                                    range,
                                    zone,
                                )
                            }
                        availability.map { listOf(it.startInclusive.toString(), it.endExclusive.toString()) }
                    },
            )
        }
    }

    private fun uuid(n: Int) = UUID(0L, n.toLong())

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        Mockito.any<T>()
        return null as T
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(
        path: Path,
        content: Any,
    ) {
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content))
        Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
