package com.meetme.server.experiment

import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Test-only, default OFF. Loading consumes the approval before any container/provider is created. */
internal class PilotTenSettings private constructor(
    val manifest: JsonNode,
    val output: Path,
    val budget: BlindFlowBudget,
) {
    val paid = true

    companion object {
        const val APPROVAL_ID = "Sentinel_300cb71b79ff69b2003894c216d6c028"
        val CAPS =
            mapOf(
                com.meetme.server.shared.application.port.output.AiPhysicalOperation.PRECHECK_CLASSIFICATION to 6,
                com.meetme.server.shared.application.port.output.AiPhysicalOperation.PRECHECK_GENERATION to 3,
                com.meetme.server.shared.application.port.output.AiPhysicalOperation.FINAL_FALLBACK to 1,
            )
        private val json = StandaloneGeminiOnceDiagnostic.JSON
        private val root = Path.of("/workspace/meet-me-analysis")
        private val base = Path.of("/workspace/scratch/live-eval")
        val current: PilotTenSettings by lazy {
            try {
                load()
            } catch (failure: PilotTenSnapshotRejected) {
                throw failure
            } catch (_: Exception) {
                throw IllegalStateException("Blind flow preflight refused; approval is never automatically retried")
            }
        }

        private fun load(): PilotTenSettings {
            check(System.getenv("MEETME_PILOT10_MODE") == "PILOT10")
            val manifestPath = Path.of(checkNotNull(System.getenv("MEETME_PILOT10_MANIFEST")))
            val hash = checkNotNull(System.getenv("MEETME_PILOT10_MANIFEST_SHA"))
            val manifest = validateSnapshot(manifestPath, hash)
            listOf("OPENAI_API_KEY", "TYPESAFE_API_KEY").forEach { check(!System.getenv(it).isNullOrBlank()) }
            val sourceHash = manifest["sourceSha256"].asString()
            val planId = manifest["planId"].asString()
            val output = Path.of(manifest["outputDirectory"].asString()).toAbsolutePath().normalize()
            BlindFlowBudget.claim(base.resolve("pilot10-approval-claims"), APPROVAL_ID, hash, sourceHash)
            PrimaryGeminiSmokeClaim(base.resolve("pilot10-plan-claims")).acquire(planId, output, output, hash)
            Files.createDirectories(output)
            Files.writeString(output.resolve("started"), "PILOT10\n", StandardOpenOption.CREATE_NEW)
            return PilotTenSettings(
                manifest,
                output,
                BlindFlowBudget(
                    output.resolve("wire-ledger.json"),
                    APPROVAL_ID,
                    hash,
                    sourceHash,
                    caps = CAPS,
                    maximumPhysicalRequests = 10,
                ),
            )
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
                check(manifest["mode"].asString() == "PILOT10")
                val planId = manifest["planId"].asString()
                check(UUID.fromString(planId).toString() == planId)
                val output = Path.of(manifest["outputDirectory"].asString()).toAbsolutePath().normalize()
                check(output == base.resolve("pilot10-$planId") && !Files.exists(output))
                stage = "CONFIG"
                val config = manifest["config"]
                mapOf(
                    "maximumPhysicalRequests" to 10,
                    "jevMaximum" to 6,
                    "personalLunaMaximum" to 3,
                    "finalLunaMaximum" to 1,
                    "realGeminiCalls" to 0,
                    "precheckTimeoutSeconds" to 15,
                    "finalLunaTimeoutSeconds" to 27,
                    "syntheticRetryAfterMillis" to 40_000,
                    "automaticRetries" to 0,
                ).forEach { (name, value) -> check(config[name].asInt() == value) }
                check(config["classifierThreshold"].asDouble() == 0.8)
                check(config["routingPolicy"].asString() == "LABEL_FIRST_V2")
                check(config["meetingMode"].asString() == "IN_PERSON")
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
                    base.resolve("pilot10-20261030/run-pilot10.py"),
                    base.resolve("pilot10-20261030/build-manifest.py"),
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
                stage = "PILOT_PATHS"
                val pilot = Path.of(manifest["pilotDirectory"].asString()).toAbsolutePath().normalize()
                check(pilot == base.resolve("blind20-20261030/scenarios/pilot"))
                val recordsPilot = manifest["pilotFiles"].values().toList()
                val expected = listOf("input.json", "answers.json", "gold.json").map { pilot.resolve(it) }.toSet()
                check(recordsPilot.map { Path.of(it["path"].asString()) }.toSet() == expected)
                check(recordsPilot.size == expected.size)
                stage = "PILOT_FILE_HASHES"
                // Hash frozen development files only. No sealed scenario is read, hashed or parsed here.
                recordsPilot.forEach { verifyFile(it) }
                return manifest
            } catch (_: Exception) {
                // Only an allowlisted local stage is exposed, never parser excerpts, keys or scenario contents.
                throw PilotTenSnapshotRejected(stage)
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
private class PilotTenSnapshotRejected(
    stage: String,
) : IllegalStateException("Blind flow snapshot rejected at $stage")
