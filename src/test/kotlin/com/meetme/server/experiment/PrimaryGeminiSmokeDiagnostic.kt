package com.meetme.server.experiment

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import tools.jackson.databind.ObjectMapper
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID

/** The paid entry uses this boundary so exceptions cannot skip its one safe diagnostic artifact. */
class PrimaryGeminiSmokeDiagnostic(
    private val outputFile: Path,
    private val json: ObjectMapper,
    private val safety: LiveEvaluationSafety,
    private val observations: PrimaryGeminiSmokeObservations,
    private val partialSnapshot: () -> Map<String, Any?>,
) {
    fun execute(flow: () -> Map<String, Any?>): Map<String, Any?> {
        if (Files.exists(outputFile)) throw FileAlreadyExistsException(outputFile.toString())
        Files.createDirectories(outputFile.toAbsolutePath().parent)
        var failure: Throwable? = null
        var completed = false
        var report: Map<String, Any?> = emptyMap()
        try {
            completed = flow()["analysisCompleted"] == true
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            var snapshotFailureClass: String? = null
            val snapshot =
                try {
                    safeSnapshot(partialSnapshot())
                } catch (caught: Exception) {
                    snapshotFailureClass = caught.javaClass.name
                    emptyMap<String, Any?>()
                }
            val barrier = safety.barrier
            report =
                mapOf(
                    "mode" to "PRIMARY_ONLY",
                    "analysisCompleted" to (completed && failure == null && barrier == null),
                    "failureClass" to failure?.javaClass?.name,
                    "failureCode" to
                        when (val caught = failure) {
                            is NaturalLanguageParserException -> caught.kind.name
                            is LiveEvaluationTechnicalStop -> caught.barrier.failureCode ?: caught.barrier.category
                            else -> barrier?.failureCode ?: barrier?.category
                        },
                    "observations" to observations.events(),
                    "partialSnapshot" to snapshot,
                    "snapshotFailureClass" to snapshotFailureClass,
                    "barrier" to barrier,
                    "limitations" to listOf("Synthetic CLEAR prechecks; no Jev/Luna or semantic quality claim"),
                )
            try {
                Files.writeString(
                    outputFile,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardOpenOption.CREATE_NEW,
                )
            } catch (writeFailure: Exception) {
                // Never replace the original provider/flow exception or attach its private cause to another exception.
                if (failure == null) throw writeFailure
            }
        }
        return report
    }

    private fun safeSnapshot(snapshot: Map<String, Any?>): Map<String, Any?> =
        snapshot.filterKeys { it in SNAPSHOT_SECTIONS }.mapValues { (_, value) -> safeValue(value) }

    private fun safeValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.filter { it.key in SAFE_FIELDS }.associate { it.key.toString() to safeValue(it.value) }
            is Iterable<*> -> value.map(::safeValue)
            is String, is Number, is Boolean, null -> value
            else -> null
        }

    companion object {
        private val SNAPSHOT_SECTIONS = setOf("attempts", "precheckExecutions", "clientCalls", "frozenVersionCount", "protocolCounts")
        private val SAFE_FIELDS =
            setOf(
                "provider",
                "model",
                "failure_code",
                "attempt_number",
                "call_number",
                "http_status",
                "outcome",
                "elapsed_nanos",
                "input_tokens",
                "output_tokens",
                "count",
                "protocol",
                "interpretation",
                "failureClass",
            )
    }
}

class PrimaryGeminiSmokePolling(
    private val safety: LiveEvaluationSafety,
    private val pause: () -> Unit = { Thread.sleep(100) },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun <T> until(
        timeout: Duration,
        read: () -> T,
        done: (T) -> Boolean,
    ): T {
        require(!timeout.isNegative && !timeout.isZero)
        val started = nanoTime()
        do {
            safety.checkpoint()
            val value = read()
            safety.checkpoint()
            if (done(value)) return value
            if (nanoTime() - started >= timeout.toNanos()) break
            pause()
        } while (nanoTime() - started < timeout.toNanos())
        error("Primary diagnostic local HTTP completion deadline")
    }
}

class PrimaryGeminiSmokeClaim(
    private val claimsDirectory: Path,
) {
    fun acquire(
        planId: String,
        approvedOutput: Path,
        selectedOutput: Path,
        approvedManifestSha256: String,
    ): Path {
        val id = UUID.fromString(planId).toString()
        require(id == planId)
        require(approvedManifestSha256.matches(Regex("[a-f0-9]{64}")))
        val approved = approvedOutput.toAbsolutePath().normalize()
        require(approved == selectedOutput.toAbsolutePath().normalize()) { "Approved output directory mismatch" }
        Files.createDirectories(claimsDirectory)
        val claim = claimsDirectory.resolve("$id.claim")
        Files.writeString(
            claim,
            "planId=$id\nmanifestSha256=$approvedManifestSha256\noutputDirectory=$approved\n",
            StandardOpenOption.CREATE_NEW,
        )
        return claim
    }
}
