package com.meetme.server.experiment

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.test.assertEquals

/** New explicit approval only; the stopped cumulative10 ledger is never read or resumed. */
@EnabledIfEnvironmentVariable(named = "MEETME_SDK_ONCE30_MODE", matches = "STANDALONE_TINY_SDK_ONCE30")
class StandaloneGeminiOncePaidTest {
    @Test
    fun `new approved single use plan sends at most one tiny Gemini request and stops`() {
        try {
            val manifestPath = Path.of(required("MEETME_SDK_ONCE30_MANIFEST"))
            val bytes = Files.readAllBytes(manifestPath)
            require(StandaloneGeminiOnceDiagnostic.sha(bytes) == required("MEETME_SDK_ONCE30_MANIFEST_SHA"))
            val manifest = StandaloneGeminiOnceDiagnostic.JSON.readTree(bytes)
            val output = Path.of(manifest["outputDirectory"].asString()).toAbsolutePath().normalize()
            val root = Path.of("/workspace/meet-me-analysis")
            require(output.startsWith(Path.of("/workspace/scratch/live-eval")))
            require(manifest["approved"].asBoolean() && manifest["maximumPhysicalRequests"].asInt() == 1)
            require(manifest["timeoutMillis"].asLong() == 30_000L && manifest["automaticRetries"].asInt() == 0)
            require(manifest["fallbackCalls"].asInt() == 0 && manifest["maximumOutputTokens"].asInt() == 128)
            require(manifest["mode"].asString() == "STANDALONE_TINY_SDK_ONCE30")
            require(manifest["approvalId"].asString() == "Sentinel_ef53e85dfd95c050e2f6c943a94c2179")
            require(manifest["model"].asString() == StandaloneGeminiOnceDiagnostic.MODEL)
            require(
                manifest["promptSha256"].asString() ==
                    StandaloneGeminiOnceDiagnostic.sha(StandaloneGeminiOnceDiagnostic.PROMPT.toByteArray()),
            )
            require(
                manifest["schemaSha256"].asString() ==
                    StandaloneGeminiOnceDiagnostic.sha(
                        StandaloneGeminiOnceDiagnostic.JSON.writeValueAsBytes(StandaloneGeminiOnceDiagnostic.SCHEMA),
                    ),
            )
            val records = manifest["files"].values().toList()
            require(records.size > 1_000)
            val supplied = records.map { Path.of(it["path"].asString()).toAbsolutePath().normalize() }.toSet()
            require(supplied.contains(root.resolve("src/test/kotlin/com/meetme/server/experiment/StandaloneGeminiOnceDiagnostic.kt")))
            require(supplied.contains(root.resolve("src/test/kotlin/com/meetme/server/experiment/StandaloneGeminiOncePaidTest.kt")))
            for (tree in listOf("src/main", "src/test", "build/classes/kotlin/main", "build/classes/kotlin/test")) {
                Files.walk(root.resolve(tree)).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { require(it in supplied) } }
            }
            records.forEach { record ->
                require(
                    StandaloneGeminiOnceDiagnostic.sha(Files.readAllBytes(Path.of(record["path"].asString()))) ==
                        record["sha256"].asString(),
                )
            }
            val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start()
            require(
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim() == manifest["baseRevision"].asString() &&
                    process.waitFor() == 0,
            )
            val key = required("GEMINI_API_KEY")
            require(key.isNotBlank())
            StandaloneGeminiOnceDiagnostic.claimApproval(
                Path.of("/workspace/scratch/live-eval/sdk-once30-approval-claims"),
                manifest["approvalId"].asString(),
                StandaloneGeminiOnceDiagnostic.sha(bytes),
            )
            PrimaryGeminiSmokeClaim(Path.of("/workspace/scratch/live-eval/sdk-once30-claims")).acquire(
                manifest["planId"].asString(),
                output,
                output,
                StandaloneGeminiOnceDiagnostic.sha(bytes),
            )
            Files.createDirectories(output)
            Files.writeString(output.resolve("started"), "STANDALONE_TINY_SDK_ONCE30\n", StandardOpenOption.CREATE_NEW)
            val result = StandaloneGeminiOnceDiagnostic(output, UUID.fromString(manifest["planId"].asString())).run(key)
            assertEquals(true, result["success"], "One-shot stopped; inspect safe results; do not rerun")
            assertEquals(1, result["headersEmitted"])
        } catch (_: Throwable) {
            // Do not pass provider messages, headers, bodies or a private exception cause to JUnit.
            throw AssertionError("Standalone one-shot finished or refused; inspect preserved metadata; no rerun")
        }
    }

    private fun required(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Required one-shot setup missing")
}
