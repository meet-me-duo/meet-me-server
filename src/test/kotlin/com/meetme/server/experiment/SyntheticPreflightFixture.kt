package com.meetme.server.experiment

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Independent opaque test bytes only. Never opens private experiments, real scenarios, answers or gold. */
internal class SyntheticPreflightFixture(
    val workspace: Path,
    private val pilotOnly: Boolean,
) {
    private val json = StandaloneGeminiOnceDiagnostic.JSON
    val sourceRoot: Path = workspace.resolve("meet-me-analysis")
    val base: Path = workspace.resolve("scratch/live-eval")
    val scenarios: Path = base.resolve("blind20-20261030/scenarios")
    val planId: String = UUID.randomUUID().toString()
    private val prefix = if (pilotOnly) "pilot10" else "blind20"
    val output: Path = base.resolve("$prefix-$planId")
    val claim: Path =
        base.resolve("$prefix-approval-claims").resolve(
            "${if (pilotOnly) PilotTenSettings.APPROVAL_ID else BlindFlowSettings.APPROVAL_ID}.claim",
        )
    val config: MutableMap<String, Any> =
        linkedMapOf<String, Any>(
            "maximumPhysicalRequests" to if (pilotOnly) 10 else 20,
            "jevMaximum" to if (pilotOnly) 6 else 12,
            "personalLunaMaximum" to if (pilotOnly) 3 else 6,
            "finalLunaMaximum" to if (pilotOnly) 1 else 2,
            "realGeminiCalls" to 0,
            "precheckTimeoutSeconds" to 15,
            "finalLunaTimeoutSeconds" to 27,
            "syntheticRetryAfterMillis" to 40_000,
            "automaticRetries" to 0,
            "classifierThreshold" to 0.8,
        ).apply {
            if (pilotOnly) {
                put("routingPolicy", "LABEL_FIRST_V2")
                put("meetingMode", "IN_PERSON")
            }
        }
    val sourceFiles: MutableList<Map<String, String>> = mutableListOf()
    val scenarioFiles: MutableList<Map<String, String>> = mutableListOf()
    val manifest: MutableMap<String, Any> = linkedMapOf()
    val manifestPath: Path = workspace.resolve("manifest.json")

    init {
        val sources =
            listOf(
                sourceRoot.resolve("src/main/synthetic-source.txt"),
                sourceRoot.resolve("src/test/synthetic-test.txt"),
                sourceRoot.resolve("build/classes/kotlin/main/synthetic-class.txt"),
                sourceRoot.resolve("build/classes/kotlin/test/synthetic-class.txt"),
                sourceRoot.resolve("build.gradle.kts"),
                sourceRoot.resolve("settings.gradle.kts"),
                base.resolve("live-eval.init.gradle"),
                workspace.resolve("scratch/analysis-gradle"),
                base.resolve("$prefix-20261030/run-$prefix.py"),
            ) + if (pilotOnly) listOf(base.resolve("pilot10-20261030/build-manifest.py")) else emptyList()
        sources.forEachIndexed { index, path ->
            write(path, "synthetic source $index\n")
            sourceFiles.add(record(path))
        }
        val rooms = if (pilotOnly) listOf("pilot") else listOf("pilot", "sealed")
        rooms.forEach { room ->
            listOf("input.json", "answers.json", "gold.json").forEach { name ->
                val path = scenarios.resolve(room).resolve(name)
                // Invalid JSON is intentional: a preflight may hash bytes, never parse scenario meaning.
                write(path, "opaque synthetic $room $name, deliberately not JSON\n")
                scenarioFiles.add(record(path))
            }
        }
        if (!pilotOnly) {
            val path = scenarios.resolve("manifest.json")
            write(path, "opaque synthetic scenario inventory, deliberately not JSON\n")
            scenarioFiles.add(record(path))
        }
        write(claim, "synthetic already-existing claim; free validation cannot alter this\n")
        git("init", "--quiet")
        git("add", "src", "build.gradle.kts", "settings.gradle.kts")
        git(
            "-c",
            "user.name=Synthetic Preflight",
            "-c",
            "user.email=synthetic@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "commit",
            "--quiet",
            "-m",
            "test: synthetic source revision",
        )
        manifest.putAll(
            linkedMapOf(
                "approved" to true,
                "approvalId" to if (pilotOnly) PilotTenSettings.APPROVAL_ID else BlindFlowSettings.APPROVAL_ID,
                "mode" to if (pilotOnly) "PILOT10" else "BLIND20",
                "planId" to planId,
                "outputDirectory" to output.toString(),
                "config" to config,
                "files" to sourceFiles,
                "baseRevision" to git("rev-parse", "HEAD"),
            ),
        )
        manifest[if (pilotOnly) "pilotDirectory" else "scenariosDirectory"] =
            (if (pilotOnly) scenarios.resolve("pilot") else scenarios).toString()
        manifest[if (pilotOnly) "pilotFiles" else "scenarioFiles"] = scenarioFiles
        save()
    }

    fun save(): Path {
        manifest["configSha256"] = StandaloneGeminiOnceDiagnostic.sha(canonical(config).toByteArray())
        manifest["sourceSha256"] = StandaloneGeminiOnceDiagnostic.sha(canonical(sourceFiles).toByteArray())
        Files.writeString(manifestPath, json.writeValueAsString(manifest))
        return manifestPath
    }

    fun manifestHash(): String = StandaloneGeminiOnceDiagnostic.sha(Files.readAllBytes(manifestPath))

    fun treeSnapshot(): Map<String, String> =
        Files.walk(workspace).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate {
                workspace.relativize(it).toString() to StandaloneGeminiOnceDiagnostic.sha(Files.readAllBytes(it))
            }
        }

    private fun record(path: Path): Map<String, String> =
        mapOf(
            "path" to path.toAbsolutePath().normalize().toString(),
            "sha256" to StandaloneGeminiOnceDiagnostic.sha(Files.readAllBytes(path)),
        )

    private fun write(
        path: Path,
        value: String,
    ) {
        Files.createDirectories(path.parent)
        Files.writeString(path, value)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(sourceRoot.toFile()).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "Synthetic source revision preparation failed" }
        return output
    }

    private fun canonical(value: Any?): String =
        when (value) {
            is Map<*, *> ->
                value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") {
                    json.writeValueAsString(it.key) + ":" + canonical(it.value)
                }
            is Iterable<*> -> value.joinToString(",", "[", "]") { canonical(it) }
            else -> json.writeValueAsString(value)
        }
}
