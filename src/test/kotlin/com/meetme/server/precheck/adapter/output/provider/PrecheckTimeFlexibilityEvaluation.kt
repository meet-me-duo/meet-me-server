package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import okhttp3.OkHttpClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Explicit standalone tool, never a JUnit test. Default execution exports synthetic requests only. */
object PrecheckTimeFlexibilityEvaluation {
    private val mapper = JsonMapper.builder().build()
    private val defaultIds = listOf("TF02", "TF05", "TF06", "TF08", "TF11", "TF12")
    private val sourcePaths =
        listOf(
            "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt",
            "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/LunaPreSubmissionCheckProvider.kt",
            "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderRuntimeProperties.kt",
            "src/test/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckTimeFlexibilityEvaluation.kt",
        )

    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            run(arguments.toList())
        } catch (_: Exception) {
            // Neither exception messages nor causes are safe output (proxy/key/provider diagnostics).
            System.err.println("Evaluation stopped; no automatic retry. Inspect non-secret claim/result files.")
            kotlin.system.exitProcess(2)
        }
    }

    private fun run(arguments: List<String>) {
        require(arguments.count { it.startsWith("--out=") } == 1)
        require(arguments.all { it.startsWith("--out=") || it in listOf("--live", "--weekday-scope", "--edit-guidance") })
        require(arguments.count { it == "--live" } <= 1)
        require(arguments.count { it == "--weekday-scope" } <= 1)
        require(arguments.count { it == "--edit-guidance" } <= 1)
        val live = "--live" in arguments
        val weekdayScope = "--weekday-scope" in arguments
        val editGuidance = "--edit-guidance" in arguments
        require(!weekdayScope || !editGuidance)
        val allowedIds =
            when {
                editGuidance -> listOf("TF14")
                weekdayScope -> listOf("TF13")
                else -> defaultIds
            }
        val maximumCalls = if (weekdayScope || editGuidance) 1 else 6
        val oraclePath =
            Path.of(
                when {
                    editGuidance -> "docs/evaluation/precheck-edit-guidance-oracle.json"
                    weekdayScope -> "docs/evaluation/precheck-weekday-scope-oracle.json"
                    else -> "docs/evaluation/precheck-time-flexibility-oracle.json"
                },
            )
        val output = Path.of(arguments.single { it.startsWith("--out=") }.removePrefix("--out=")).toAbsolutePath().normalize()
        require(output.fileName != null)
        val oracleBytes = Files.readAllBytes(oraclePath)
        val oracle = mapper.readTree(oracleBytes)
        val cases = oracle.path("cases").values().associateBy { it.path("id").asText() }
        require(cases.keys.containsAll(allowedIds) && allowedIds.size == maximumCalls)
        val sourceHashes = sourcePaths.associateWith { sha(Files.readAllBytes(Path.of(it))) }
        val sourceFingerprint = sha(mapper.writeValueAsBytes(sourceHashes))
        val oracleFingerprint = sha(oracleBytes)
        val compiledHashes =
            listOf(PrecheckProviderContract::class.java, LunaPreSubmissionCheckProvider::class.java).associate { type ->
                type.name to sha(type.getResourceAsStream("${type.simpleName}.class")!!.use { it.readBytes() })
            }
        val context = oracle.path("context")
        val requests = allowedIds.associateWith { request(cases.getValue(it), context) }
        val contract = PrecheckProviderContract(mapper)
        val payloads = requests.mapValues { (_, request) -> contract.lunaRequestBytes(request) }
        require(payloads.values.all { it.size <= 16384 })
        // Creating the run directory and manifest is atomic with respect to an accidental rerun.
        Files.createDirectory(output)
        writeNew(
            output.resolve("manifest.json"),
            mapOf(
                "mode" to if (live) "LIVE_EXPLICIT_OPT_IN" else "DRY_RUN_EXPORT_ONLY",
                "evaluation" to
                    when {
                        editGuidance -> "EDIT_GUIDANCE_ONE_CASE"
                        weekdayScope -> "WEEKDAY_SCOPE_ONE_CASE"
                        else -> "TIME_FLEXIBILITY_SIX_CASES"
                    },
                "createdAt" to Instant.now().toString(),
                "oracleSha256" to oracleFingerprint,
                "sourceFingerprint" to sourceFingerprint,
                "sourceFiles" to sourceHashes,
                "compiledClasses" to compiledHashes,
                "fixedIds" to allowedIds,
                "maximumCalls" to if (live) maximumCalls else 0,
                "retry" to 0,
                "endpoint" to "https://api.openai.com/v1/responses",
                "payloadSha256" to payloads.mapValues { (_, bytes) -> sha(bytes) },
                "limitations" to
                    "Exposed synthetic development set; standalone generation only, not Jev or live-room behavior; usage is not billing proof.",
            ),
        )
        payloads.forEach { (id, bytes) -> Files.write(output.resolve("$id.request.json"), bytes, CREATE_NEW, WRITE) }
        if (!live) {
            println("DRY_RUN_EXPORT_ONLY: ${allowedIds.size} synthetic requests, 0 network calls")
            return
        }

        // The only key access is this explicit opt-in runtime consumption. Never serialize it.
        val properties = OpenAiProperties(apiKey = System.getenv("OPENAI_API_KEY") ?: error("UNCONFIGURED"))
        require(properties.apiKey.isNotBlank())
        val client = proxyClient()
        val provider =
            LunaPreSubmissionCheckProvider.runtime(
                properties,
                mapper,
                PrecheckProviderRuntimeProperties(enabled = true),
                PrecheckProviderHttpTransport(client),
            )
        val claimBase = Path.of(".codex/evaluation/claims")
        Files.createDirectories(claimBase)
        // Existing source-specific claims also block replay after the prompt or harness changes.
        Files.walk(claimBase).use { paths ->
            require(paths.noneMatch { path -> allowedIds.any { path.fileName.toString() == "$it.claim.json" } })
        }
        val claimRoot = claimBase.resolve("$sourceFingerprint-$oracleFingerprint")
        Files.createDirectories(claimRoot)
        val results = mutableListOf<Map<String, Any?>>()
        for (id in allowedIds) {
            require(sourcePaths.all { sha(Files.readAllBytes(Path.of(it))) == sourceHashes.getValue(it) })
            require(sha(Files.readAllBytes(oraclePath)) == oracleFingerprint)
            val expected = cases.getValue(id).path("expectedVerdict").asText()
            val claim =
                mapOf(
                    "id" to id,
                    "expected" to expected,
                    "claimedAt" to Instant.now().toString(),
                    "status" to "RESERVED_BEFORE_DISPATCH_OUTCOME_UNKNOWN_UNTIL_RESULT",
                    "oracleSha256" to oracleFingerprint,
                    "sourceFingerprint" to sourceFingerprint,
                    "payloadSha256" to sha(payloads.getValue(id)),
                    "outputDirectory" to output.toString(),
                )
            // A shared ID claim prevents competing invocations with distinct source fingerprints.
            writeNew(claimBase.resolve("$id.claim.json"), claim)
            writeNew(claimRoot.resolve("$id.claim.json"), claim)
            writeNew(output.resolve("$id.claim.json"), claim)
            val started = System.nanoTime()
            val result =
                try {
                    val actual = provider.check(requests.getValue(id))
                    mapOf(
                        "id" to id,
                        "expected" to expected,
                        "actual" to actual.verdict.name,
                        "matchesExpected" to (actual.verdict.name == expected),
                        "latencyMs" to (System.nanoTime() - started) / 1_000_000,
                        "inputTokens" to actual.inputTokens,
                        "outputTokens" to actual.outputTokens,
                        "question" to actual.prompt,
                        "sourceExcerpt" to actual.sourceExcerpt,
                        "choices" to actual.choices.map { mapOf("id" to it.id, "label" to it.label, "proposedText" to it.proposedText) },
                    )
                } catch (failure: PrecheckProviderException) {
                    mapOf(
                        "id" to id,
                        "expected" to expected,
                        "actual" to "FAILED",
                        "failureCode" to failure.failureCode,
                        "latencyMs" to (System.nanoTime() - started) / 1_000_000,
                        "dispatchOutcome" to "UNKNOWN_DO_NOT_RETRY",
                    )
                }
            writeNew(output.resolve("$id.result.json"), result)
            results.add(result)
            println("$id: ${result["actual"]}")
            if (result["actual"] == "FAILED") break
        }
        writeNew(
            output.resolve("results.json"),
            mapOf(
                "oracleSha256" to oracleFingerprint,
                "sourceFingerprint" to sourceFingerprint,
                "attempted" to results.size,
                "maximumCalls" to maximumCalls,
                "results" to results,
            ),
        )
    }

    private fun request(
        case: JsonNode,
        context: JsonNode,
    ): PrecheckProviderRequest =
        PrecheckProviderRequest(
            UUID.nameUUIDFromBytes(case.path("id").asText().toByteArray(Charsets.UTF_8)),
            case.path("rawText").asText(),
            Instant.parse(context.path("reference_at").asText()),
            ZoneId.of(context.path("time_zone").asText()),
            context.path("locale").asText(),
            LocalDate.parse(context.path("search_start_date").asText()),
            LocalDate.parse(context.path("search_end_date").asText()),
            Duration.ofSeconds(15),
        )

    private fun proxyClient(): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
        val configured = System.getenv("HTTPS_PROXY")?.takeIf { it.isNotBlank() }
        if (configured != null) {
            val proxy = URI(configured)
            require(proxy.scheme == "http" && proxy.host != null && proxy.rawUserInfo == null)
            require(proxy.rawQuery == null && proxy.rawFragment == null && proxy.rawPath in listOf("", "/"))
            val port = if (proxy.port < 0) 80 else proxy.port
            require(port in 1..65535)
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, port)))
        }
        // Use OkHttp/JVM default trust validation. No permissive trust manager or hostname verifier.
        return builder.build()
    }

    private fun writeNew(
        path: Path,
        value: Any,
    ) {
        Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), CREATE_NEW, WRITE)
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
