package com.meetme.server.experiment

import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Test-only, default OFF. Loading consumes the approval before any container/provider is created. */
internal class BlindFlowSettings private constructor(
    val manifest: JsonNode,
    val output: Path,
    val budget: BlindFlowBudget,
) {
    val paid = true

    companion object {
        const val APPROVAL_ID = "Sentinel_f19d2f59a82e7fd652feb99ee65890ba"
        private val json = StandaloneGeminiOnceDiagnostic.JSON
        private val root = Path.of("/workspace/meet-me-analysis")
        private val base = Path.of("/workspace/scratch/live-eval")
        val current: BlindFlowSettings by lazy {
            try {
                load()
            } catch (failure: BlindFlowSnapshotRejected) {
                throw failure
            } catch (_: Exception) {
                throw IllegalStateException("Blind flow preflight refused; approval is never automatically retried")
            }
        }

        private fun load(): BlindFlowSettings {
            check(System.getenv("MEETME_BLIND20_MODE") == "BLIND20")
            val manifestPath = Path.of(checkNotNull(System.getenv("MEETME_BLIND20_MANIFEST")))
            val hash = checkNotNull(System.getenv("MEETME_BLIND20_MANIFEST_SHA"))
            val manifest = validateSnapshot(manifestPath, hash)
            listOf("OPENAI_API_KEY", "TYPESAFE_API_KEY").forEach { check(!System.getenv(it).isNullOrBlank()) }
            val sourceHash = manifest["sourceSha256"].asString()
            val planId = manifest["planId"].asString()
            val output = Path.of(manifest["outputDirectory"].asString()).toAbsolutePath().normalize()
            BlindFlowBudget.claim(base.resolve("blind20-approval-claims"), APPROVAL_ID, hash, sourceHash)
            PrimaryGeminiSmokeClaim(base.resolve("blind20-plan-claims")).acquire(planId, output, output, hash)
            Files.createDirectories(output)
            Files.writeString(output.resolve("started"), "BLIND20\n", StandardOpenOption.CREATE_NEW)
            return BlindFlowSettings(manifest, output, BlindFlowBudget(output.resolve("wire-ledger.json"), APPROVAL_ID, hash, sourceHash))
        }

        /** Read-only source/config validation: no claim, container, output directory or provider creation. */
        internal fun validateSnapshot(
            manifestPath: Path,
            expectedHash: String,
            validationRoot: Path = root.parent,
        ): JsonNode {
            // Only free contract tests supply a synthetic workspace; load() retains the fixed default.
            val root = validationRoot.resolve("meet-me-analysis").toAbsolutePath().normalize()
            val base = validationRoot.resolve("scratch/live-eval").toAbsolutePath().normalize()
            var stage = "START"
            try {
                stage = "MANIFEST_SIZE"
                check(Files.size(manifestPath) <= 4_194_304)
                val bytes = Files.readAllBytes(manifestPath)
                val hash = StandaloneGeminiOnceDiagnostic.sha(bytes)
                check(hash == expectedHash)
                stage = "MANIFEST_JSON"
                val manifest = json.readTree(bytes)
                stage = "APPROVAL"
                check(manifest["approved"].asBoolean() && manifest["approvalId"].asString() == APPROVAL_ID)
                check(manifest["mode"].asString() == "BLIND20")
                val planId = manifest["planId"].asString()
                check(UUID.fromString(planId).toString() == planId)
                val output = Path.of(manifest["outputDirectory"].asString()).toAbsolutePath().normalize()
                check(output == base.resolve("blind20-$planId") && !Files.exists(output))
                stage = "CONFIG"
                val config = manifest["config"]
                mapOf(
                    "maximumPhysicalRequests" to 20,
                    "jevMaximum" to 12,
                    "personalLunaMaximum" to 6,
                    "finalLunaMaximum" to 2,
                    "realGeminiCalls" to 0,
                    "precheckTimeoutSeconds" to 15,
                    "finalLunaTimeoutSeconds" to 27,
                    "syntheticRetryAfterMillis" to 40_000,
                    "automaticRetries" to 0,
                ).forEach { (name, value) -> check(config[name].asInt() == value) }
                check(config["classifierThreshold"].asDouble() == 0.8)
                check(StandaloneGeminiOnceDiagnostic.sha(canonical(config).toByteArray()) == manifest["configSha256"].asString())
                stage = "SOURCE_PATHS"
                val records = manifest["files"].values().toList()
                val supplied = records.map { Path.of(it["path"].asString()).toAbsolutePath().normalize() }
                check(supplied.size == supplied.toSet().size)
                for (tree in listOf("src/main", "src/test", "build/classes/kotlin/main", "build/classes/kotlin/test")) {
                    Files.walk(root.resolve(tree)).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.forEach { check(it in supplied) }
                    }
                }
                listOf(
                    root.resolve("build.gradle.kts"),
                    root.resolve("settings.gradle.kts"),
                    base.resolve("live-eval.init.gradle"),
                    validationRoot.resolve("scratch/analysis-gradle").toAbsolutePath().normalize(),
                    base.resolve("blind20-20261030/run-blind20.py"),
                ).forEach { check(it in supplied) }
                stage = "SOURCE_FILE_HASHES"
                records.forEach { verifyFile(it) }
                stage = "SOURCE_AGGREGATE_HASH"
                val sourceHash = StandaloneGeminiOnceDiagnostic.sha(canonical(manifest["files"]).toByteArray())
                check(sourceHash == manifest["sourceSha256"].asString())
                stage = "BASE_REVISION"
                val git = ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start()
                check(
                    git.inputStream
                        .bufferedReader()
                        .readText()
                        .trim() == manifest["baseRevision"].asString(),
                )
                check(git.waitFor() == 0)
                stage = "SCENARIO_PATHS"
                val scenarios = Path.of(manifest["scenariosDirectory"].asString()).toAbsolutePath().normalize()
                check(scenarios == base.resolve("blind20-20261030/scenarios"))
                val scenarioRecords = manifest["scenarioFiles"].values().toList()
                val expected =
                    listOf("pilot", "sealed")
                        .flatMap { name ->
                            listOf("input.json", "answers.json", "gold.json").map { scenarios.resolve(name).resolve(it) }
                        }.toSet() + setOf(scenarios.resolve("manifest.json"))
                check(scenarioRecords.map { Path.of(it["path"].asString()) }.toSet() == expected)
                check(scenarioRecords.size == expected.size)
                // Hash bytes only: no sealed scenario or gold is parsed during preparation.
                stage = "SCENARIO_FILE_HASHES"
                scenarioRecords.forEach { verifyFile(it) }
                return manifest
            } catch (_: Exception) {
                // Only an allowlisted local stage is exposed, never parser excerpts, keys or scenario contents.
                throw BlindFlowSnapshotRejected(stage)
            }
        }

        private fun verifyFile(node: JsonNode) {
            val path = Path.of(node["path"].asString())
            check(StandaloneGeminiOnceDiagnostic.sha(Files.readAllBytes(path)) == node["sha256"].asString())
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

        fun properties(registry: DynamicPropertyRegistry) {
            current // Approval/source/claims before the first container and network-capable bean.
            PrimaryGeminiSmokeInfrastructure.properties(registry)
            registry.add("meetme.openai.api-key") { checkNotNull(System.getenv("OPENAI_API_KEY")) }
            registry.add("meetme.typesafe.api-key") { checkNotNull(System.getenv("TYPESAFE_API_KEY")) }
            registry.add("meetme.precheck.classifier.minimum-confidence") { "0.8" }
            registry.add("logging.level.root") { "ERROR" }
        }
    }
}

/** Only fixed local validation-stage names are passed to this exception. */
private class BlindFlowSnapshotRejected(
    stage: String,
) : IllegalStateException("Blind flow snapshot rejected at $stage")
