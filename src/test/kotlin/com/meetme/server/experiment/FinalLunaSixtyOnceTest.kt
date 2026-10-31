package com.meetme.server.experiment

import com.meetme.server.coordination.adapter.output.integration.NaturalLanguageProviderContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

internal object FinalLunaSixtyManifest {
    const val APPROVAL = "Sentinel_ead1921d8663f419cb06d108e3ff5e91"
    val BASE: Path = Path.of("/workspace/scratch/live-eval")
    val PREP: Path = BASE.resolve("final-luna60-20261030")
    val PAYLOAD: Path = PREP.resolve("payload.json")
    val JSON = FinalLunaSixtyProbe.JSON

    fun validate(
        path: Path,
        expectedSha: String,
    ): JsonNode {
        check(Files.size(path) <= 4_194_304)
        val bytes = Files.readAllBytes(path)
        check(FinalLunaSixtyProbe.sha(bytes) == expectedSha)
        val m = JSON.readTree(bytes)
        check(m.path("approved").asBoolean() && m.path("approvalId").asText() == APPROVAL)
        check(m.path("mode").asText() == "FINAL_LUNA_ONCE60")
        val id = UUID.fromString(m.path("planId").asText()).toString()
        check(id == m.path("planId").asText())
        check(Path.of(m.path("outputDirectory").asText()) == BASE.resolve("final-luna60-$id"))
        check(!Files.exists(BASE.resolve("final-luna60-$id")))
        check(m.path("maximumPhysicalRequests").asInt() == 1 && m.path("timeoutSeconds").asInt() == 60)
        check(m.path("actualJevRequests").asInt() == 0 && m.path("actualGeminiRequests").asInt() == 0)
        check(m.path("automaticRetries").asInt() == 0)
        check(m.path("endpoint").asText() == FinalLunaSixtyProbe.ENDPOINT.toString())
        check(Path.of(m.path("payloadPath").asText()) == PAYLOAD)
        val payload = Files.readAllBytes(PAYLOAD)
        check(FinalLunaSixtyProbe.sha(payload) == m.path("payloadSha256").asText())
        check(payload.contentEquals(FinalLunaSixtyPayload.bytes()))
        val supplied = m.path("files").values().toList()
        val paths = supplied.map { Path.of(it.path("path").asText()).toAbsolutePath().normalize() }
        check(paths.size == paths.toSet().size)
        val root = Path.of("/workspace/meet-me-analysis")
        for (tree in listOf("src/main", "src/test", "build/classes/kotlin/main", "build/classes/kotlin/test")) {
            Files.walk(root.resolve(tree)).use { entries -> entries.filter { Files.isRegularFile(it) }.forEach { check(it in paths) } }
        }
        listOf(
            root.resolve("build.gradle.kts"),
            root.resolve("settings.gradle.kts"),
            BASE.resolve("live-eval.init.gradle"),
            Path.of("/workspace/scratch/analysis-gradle"),
            PREP.resolve("run-once.py"),
            PREP.resolve("build-manifest.py"),
            FinalLunaSixtyPayload.DATABASE,
            PAYLOAD,
        ).forEach {
            check(
                it in paths,
            )
        }
        supplied.forEach {
            check(
                FinalLunaSixtyProbe.sha(Files.readAllBytes(Path.of(it.path("path").asText()))) == it.path("sha256").asText(),
            )
        }
        check(FinalLunaSixtyProbe.sha(canonical(m.path("files")).toByteArray()) == m.path("sourceSha256").asText())
        val actualBase = ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start()
        check(
            actualBase.inputStream
                .bufferedReader()
                .readText()
                .trim() == m.path("baseRevision").asText() &&
                actualBase.waitFor() == 0,
        )
        return m
    }

    fun write(
        path: Path,
        value: Any?,
    ) {
        Files.writeString(path, JSON.writeValueAsString(value), StandardOpenOption.CREATE_NEW)
    }

    private fun canonical(node: JsonNode): String =
        when {
            node.isObject ->
                node.properties().sortedBy { it.key }.joinToString(",", "{", "}") {
                    JSON.writeValueAsString(it.key) + ":" +
                        canonical(it.value)
                }
            node.isArray -> node.values().joinToString(",", "[", "]") { canonical(it) }
            else -> JSON.writeValueAsString(node)
        }
}

@EnabledIfEnvironmentVariable(named = "MEETME_FINAL_LUNA60_MODE", matches = "PREPARE")
class FinalLunaSixtyPrepareTest {
    @Test
    fun `frozen original and supplement generate the unchanged final contract offline`() {
        val request = FinalLunaSixtyPayload.request()
        val payload = FinalLunaSixtyPayload.bytes(request)
        if (Files.exists(FinalLunaSixtyManifest.PAYLOAD)) {
            check(Files.readAllBytes(FinalLunaSixtyManifest.PAYLOAD).contentEquals(payload))
        } else {
            Files.write(FinalLunaSixtyManifest.PAYLOAD, payload, StandardOpenOption.CREATE_NEW)
        }
        val root = FinalLunaSixtyProbe.JSON.readTree(payload)
        check(root.path("model").asText() == "gpt-6-luna" && !root.path("store").asBoolean())
        check(root.path("max_output_tokens").asInt() == 32768)
        check(
            root
                .path("text")
                .path("format")
                .path("strict")
                .asBoolean(),
        )
        check(request.inputs.count { it.evidence?.supplement != null } == 1)
        if (!Files.exists(FinalLunaSixtyManifest.PREP.resolve("payload-metrics.json"))) {
            FinalLunaSixtyManifest.write(
                FinalLunaSixtyManifest.PREP.resolve("payload-metrics.json"),
                mapOf(
                    "requestUtf8Bytes" to payload.size,
                    "promptUtf8Bytes" to
                        root
                            .path("input")
                            .asText()
                            .toByteArray()
                            .size,
                    "schemaUtf8Bytes" to
                        FinalLunaSixtyProbe.JSON
                            .writeValueAsBytes(
                                NaturalLanguageProviderContract.RESPONSE_SCHEMA,
                            ).size,
                    "payloadSha256" to FinalLunaSixtyProbe.sha(payload),
                    "model" to "gpt-6-luna",
                    "schema" to "meet_me_conditions_v3",
                    "maximumOutputTokens" to 32768,
                    "logicalInputs" to request.inputs.size,
                    "supplements" to 1,
                    "inputRefs" to request.inputs.map { it.inputRef },
                    "historicalWireBytesCaptured" to false,
                    "actualProviderRequests" to 0,
                ),
            )
        }
    }
}

@EnabledIfEnvironmentVariable(named = "MEETME_FINAL_LUNA60_MODE", matches = "VALIDATE")
class FinalLunaSixtyPreflightTest {
    @Test
    fun `fixed source and payload validate offline without claiming approval`() {
        val path = Path.of(checkNotNull(System.getenv("MEETME_FINAL_LUNA60_MANIFEST")))
        FinalLunaSixtyManifest.validate(path, checkNotNull(System.getenv("MEETME_FINAL_LUNA60_MANIFEST_SHA")))
        check(!Files.exists(FinalLunaSixtyManifest.BASE.resolve("final-luna60-approval-claims/${FinalLunaSixtyManifest.APPROVAL}.claim")))
    }
}

@EnabledIfEnvironmentVariable(named = "MEETME_FINAL_LUNA60_MODE", matches = "FINAL_LUNA_ONCE60")
class FinalLunaSixtyPaidTest {
    @Test
    fun `fresh approval sends one frozen logical final request with a standalone sixty second deadline`() {
        val manifestPath = Path.of(checkNotNull(System.getenv("MEETME_FINAL_LUNA60_MANIFEST")))
        val manifestSha = checkNotNull(System.getenv("MEETME_FINAL_LUNA60_MANIFEST_SHA"))
        val m = FinalLunaSixtyManifest.validate(manifestPath, manifestSha)
        val key = checkNotNull(System.getenv("OPENAI_API_KEY")).also { check(it.isNotBlank()) }
        val sourceSha = m.path("sourceSha256").asText()
        BlindFlowBudget.claim(
            FinalLunaSixtyManifest.BASE.resolve("final-luna60-approval-claims"),
            FinalLunaSixtyManifest.APPROVAL,
            manifestSha,
            sourceSha,
        )
        val output = Path.of(m.path("outputDirectory").asText())
        Files.createDirectory(output)
        Files.writeString(output.resolve("started"), "FINAL_LUNA_ONCE60\n", StandardOpenOption.CREATE_NEW)
        val payload = Files.readAllBytes(FinalLunaSixtyManifest.PAYLOAD)
        val report = FinalLunaSixtyProbe(output, m.path("payloadSha256").asText()).run(key, payload)
        var valid = false
        var failure: String? = null
        var summary: List<Map<String, Any?>>? = null
        if (report["transportSucceeded"] == true && report["responseExported"] == true) {
            try {
                val response = FinalLunaSixtyProbe.JSON.readTree(Files.readAllBytes(output.resolve("response.json")))
                check(response.path("status").asText() == "completed")
                val texts = mutableListOf<String>()
                response.path("output").values().filter { it.path("type").asText() == "message" }.forEach { message ->
                    message.path("content").values().forEach { part ->
                        check(part.path("type").asText() == "output_text")
                        texts += part.path("text").asText()
                    }
                }
                check(texts.size == 1)
                val parsed =
                    NaturalLanguageProviderContract(
                        FinalLunaSixtyProbe.JSON,
                    ).parseProviderResponse(texts.single(), FinalLunaSixtyPayload.request(), strict = true)
                Files.writeString(output.resolve("structured.json"), texts.single(), StandardOpenOption.CREATE_NEW)
                summary =
                    parsed.map {
                        mapOf(
                            "inputRef" to it.submissionVersionId.value.toString(),
                            "conditionCount" to it.conditions.size,
                            "conditionTypes" to it.conditions.map { condition -> condition.javaClass.simpleName },
                            "effectiveRejectionCode" to it.rejectionCode,
                        )
                    }
                valid = true
            } catch (exception: Exception) {
                failure = exception.javaClass.simpleName
            }
        }
        FinalLunaSixtyManifest.write(
            output.resolve("validation.json"),
            mapOf(
                "strictContractValid" to valid,
                "failureClass" to failure,
                "effectiveResults" to summary,
                "finishedAt" to Instant.now().toString(),
                "actualApplicationE2E" to false,
                "productionTimeoutChanged" to false,
            ),
        )
        check(
            report["transportSucceeded"] == true && valid,
        ) { "Standalone diagnostic did not produce a valid response; never rerun automatically" }
    }
}
