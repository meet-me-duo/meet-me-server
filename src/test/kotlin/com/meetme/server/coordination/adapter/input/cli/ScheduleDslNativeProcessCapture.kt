package com.meetme.server.coordination.adapter.input.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

/** Captures the real offline executable's complete merged output, never host-JVM output or selected threads. */
internal object ScheduleDslNativeProcessCapture {
    private const val MAIN = "com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineEntryPoint"
    private const val OUTPUT_LIMIT = 262_144

    // Resource watchdog for each cold real CLI process, not a product latency or privacy assertion.
    private val processLimit = Duration.ofMinutes(1)

    private data class RuntimeJar(
        val name: String,
        val sha256: String,
    )

    private class OutputBoundExceeded : IllegalStateException()

    // Same nine immutable runtime artifacts already verified for the product's DB-free offline package.
    private val runtime =
        listOf(
            RuntimeJar("annotations-23.0.0.jar", "7b0f19724082cbfcbc66e5abea2b9bc92cf08a1ea11e191933ed43801eb3cd05"),
            RuntimeJar("jackson-annotations-2.21.jar", "53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b"),
            RuntimeJar("jackson-core-3.1.5.jar", "9431b7fa2673bbb618c11d865fe15e13222fd182a214ff998cb7e56afd8f35d2"),
            RuntimeJar("jackson-databind-3.1.5.jar", "3a2338d996fd3056791df8d335fa9ba8a62a706ed4245ecf81b3e583df37d08a"),
            RuntimeJar("jackson-module-kotlin-3.1.5.jar", "e1dedb52b0f41a014056a40c8e91e27c46b38f867681d2d42f58880d206c7055"),
            RuntimeJar("kotlin-reflect-2.3.21.jar", "33e37d9dfa86c7a29ab34e975b5f4a1287b458878611051f3485652220c1a2cb"),
            RuntimeJar("kotlin-stdlib-2.3.21.jar", "6f64eac736db9434dd6925b4a518b9d1d17177652320c37916cf9ba3ce7d7d7a"),
            RuntimeJar("kotlin-stdlib-jdk7-2.3.21.jar", "e2243abac2bed6e0b47be18b2b7fe1c1b4cf6a431480353dcd49d1ed35f31199"),
            RuntimeJar("kotlin-stdlib-jdk8-2.3.21.jar", "63091929246beba86b5fb2b020a6f5d06e01693a960b58a59959c8713667991c"),
        )
    private val anchors =
        listOf(
            MAIN,
            "com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflinePipeline",
            "com.meetme.server.coordination.adapter.input.cli.ScheduleDslOfflineEntryPointKt",
            "com.meetme.server.coordination.adapter.output.integration.ScheduleDslProviderContract",
            "com.meetme.server.coordination.adapter.output.integration.ScheduleDslContextProviderContract",
            "com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslResponseParser",
            "com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslContextResponseParser",
            "com.meetme.server.coordination.adapter.output.integration.ScheduleDslGeminiWire",
            "com.meetme.server.coordination.domain.dsl.ScheduleDslProviderInputCapsule",
            "com.meetme.server.coordination.domain.dsl.ScheduleDslInputPolicy",
            "com.meetme.server.coordination.domain.matching.DeterministicCandidateMatcher",
            "com.meetme.server.coordination.domain.matching.RecommendationProjector",
            "com.meetme.server.coordination.domain.matching.OfflinePlacePolicy",
            "com.meetme.server.submission.domain.dsl.ScheduleDslEnvelope",
            "com.meetme.server.submission.domain.dsl.ScheduleDslValidator",
            "com.meetme.server.submission.domain.dsl.RejectedPreferenceWitness",
            "com.meetme.server.submission.domain.ir.ScheduleIrV2Validator",
            "com.meetme.server.submission.domain.ir.IrEvidenceBindings",
        )

    fun run(args: Array<String>): Pair<Int, String> {
        val fixtureStarted = System.nanoTime()
        var phase = "JAVA_BINARY"
        val observedBytes = AtomicInteger()
        var child: Process? = null
        var reader: Future<ByteArray>? = null
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java")
            check(Files.isRegularFile(java) && Files.isExecutable(java))
            phase = "CLASSPATH"
            val classpath = childClasspath()
            val command =
                listOf(
                    java.toString(),
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    classpath,
                    MAIN,
                ) + args.toList()
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment()["LANG"] = "C.UTF-8"
            builder.environment()["LC_ALL"] = "C.UTF-8"
            val deadline = System.nanoTime() + processLimit.toNanos()
            phase = "START"
            val process = builder.start()
            child = process
            process.outputStream.close()
            val capturedOutput =
                executor.submit<ByteArray> {
                    process.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            observedBytes.addAndGet(count)
                            if (output.size() + count > OUTPUT_LIMIT) {
                                process.destroyForcibly()
                                throw OutputBoundExceeded()
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            reader = capturedOutput
            phase = "WAIT_EXIT"
            check(process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS))
            phase = "READ_OUTPUT"
            val bytes = capturedOutput.get(remaining(deadline), TimeUnit.NANOSECONDS)
            phase = "UTF8"
            val text = bytes.toString(Charsets.UTF_8)
            check(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            return process.exitValue() to text
        } catch (exception: Exception) {
            // Child output, command, input paths and underlying exception messages never enter fixture failure text.
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fixtureStarted)
            val kind =
                when (exception) {
                    is TimeoutException -> "LIFECYCLE_BUDGET"
                    is InterruptedException -> "INTERRUPTED"
                    is ExecutionException -> if (exception.cause is OutputBoundExceeded) "OUTPUT_BOUND" else "CAPTURE_IO"
                    else ->
                        when (phase) {
                            "JAVA_BINARY", "CLASSPATH" -> "PREPARATION"
                            "START" -> "START"
                            "WAIT_EXIT", "READ_OUTPUT" -> "LIFECYCLE"
                            "UTF8" -> "INVALID_UTF8"
                            else -> "FIXTURE_CHECK"
                        }
                }
            throw IllegalStateException(
                "Native offline CLI capture failed: phase=$phase kind=$kind " +
                    "elapsedMs=$elapsed bytes=${observedBytes.get()} childAlive=${child?.isAlive}",
            )
        } finally {
            reader?.cancel(true)
            val cleaned =
                runCatching {
                    child?.let { process ->
                        if (process.isAlive) {
                            process.destroy()
                            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                                process.destroyForcibly()
                                check(process.waitFor(2, TimeUnit.SECONDS))
                            }
                        }
                    }
                }
            if (cleaned.isFailure) child?.destroyForcibly()
            val streamsClosed =
                child?.let { process ->
                    listOf<java.io.Closeable>(process.inputStream, process.errorStream, process.outputStream)
                        .map { stream ->
                            runCatching { stream.close() }.isSuccess
                        }.all { it }
                } ?: true
            executor.shutdownNow()
            val readerStopped = runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }.getOrDefault(false)
            check(cleaned.isSuccess && streamsClosed && readerStopped) { "Native offline CLI fixture cleanup failed: kind=CLEANUP" }
        }
    }

    private fun remaining(deadline: Long): Long =
        (deadline - System.nanoTime()).also { check(it > 0) { "Offline CLI fixture deadline expired" } }

    private fun childClasspath(): String {
        val loader = ScheduleDslOfflineEntryPoint::class.java.classLoader
        val origins =
            anchors.associateWith { name ->
                val type = Class.forName(name, false, loader)
                localPath(
                    type.protectionDomain.codeSource.location
                        .toURI(),
                )
            }
        val roots = origins.values.distinct()
        val discovered = linkedSetOf<Path>()
        for (start in listOf(loader, javaClass.classLoader, Thread.currentThread().contextClassLoader)) {
            val hierarchy = generateSequence(start) { it.parent }.toList().asReversed()
            for (parent in hierarchy) {
                if (parent is URLClassLoader) {
                    for (url in parent.urLs) {
                        if (url.protocol == "file") discovered.add(localPath(url.toURI()))
                    }
                }
            }
        }
        for (entry in System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }) {
            val path = Path.of(entry)
            if (Files.exists(path)) discovered.add(path.toRealPath())
        }
        discovered.addAll(roots)
        // Origin constraints preserve active old-CLI/fault overlays even when a parent loader is not URL-based.
        val before = roots.associateWith { mutableSetOf<Path>() }
        for ((name, actual) in origins) {
            val resource = name.replace('.', '/') + ".class"
            for (other in roots) {
                if (other != actual && contains(other, resource)) before.getValue(other).add(actual)
            }
        }
        val pending = roots.toMutableSet()
        val ordered = mutableListOf<Path>()
        while (pending.isNotEmpty()) {
            val next =
                pending.firstOrNull { candidate -> before.getValue(candidate).none { it in pending } }
                    ?: error("Active product class origins cannot be reproduced")
            ordered.add(next)
            pending.remove(next)
        }
        for ((name, actual) in origins) {
            val resource = name.replace('.', '/') + ".class"
            check(ordered.firstOrNull { contains(it, resource) } == actual)
        }
        val jars =
            runtime.map { pin ->
                discovered.firstOrNull { Files.isRegularFile(it) && it.fileName.toString() == pin.name && sha256(it) == pin.sha256 }
                    ?: error("Verified offline runtime is unavailable")
            }
        return (ordered + jars).joinToString(File.pathSeparator)
    }

    private fun localPath(uri: java.net.URI): Path {
        check(uri.scheme == "file") { "Only local offline class origins are supported" }
        return Path.of(uri).toRealPath()
    }

    private fun contains(
        root: Path,
        resource: String,
    ): Boolean =
        if (Files.isDirectory(root)) {
            Files.isRegularFile(root.resolve(resource))
        } else {
            JarFile(root.toFile()).use { it.getJarEntry(resource) != null }
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
