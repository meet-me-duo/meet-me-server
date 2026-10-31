package com.meetme.server.experiment

import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Failure artifacts and one-shot admission only. No provider, process, database or network call. */
class PrimaryGeminiSmokeFailureEvidenceContractTest {
    @TempDir lateinit var temporary: Path
    private val json = JsonMapper.builder().build()
    private val planId = "00000000-0000-0000-0000-000000000123"

    @Test
    fun `flow failure always leaves one safe report and propagates the exact primary exception`() {
        val observations = PrimaryGeminiSmokeObservations()
        val safety = LiveEvaluationSafety { observations.signals() }
        val failure = NaturalLanguageParserException(ParserFailureKind.SERVER, cause = IOException("PRIVATE_PROVIDER_MESSAGE"))
        val output = temporary.resolve("primary-smoke-results.json")
        var flows = 0
        var snapshots = 0
        val diagnostic =
            PrimaryGeminiSmokeDiagnostic(output, json, safety, observations) {
                snapshots++
                mapOf("attempts" to listOf(mapOf("provider" to "GEMINI", "failure_code" to "SERVER")))
            }

        val propagated =
            assertFailsWith<NaturalLanguageParserException> {
                diagnostic.execute {
                    flows++
                    observations.record(failure)
                    throw failure
                }
            }

        assertSame(failure, propagated)
        assertEquals(1, flows)
        assertEquals(1, snapshots)
        assertTrue(Files.exists(output), "The flow must leave its safe report even when it throws")
        val text = Files.readString(output)
        val report = json.readTree(text)
        assertEquals("PRIMARY_ONLY", report.path("mode").asText())
        assertEquals("SERVER", report.path("failureCode").asText())
        assertEquals(failure.javaClass.name, report.path("failureClass").asText())
        assertFalse(report.path("analysisCompleted").asBoolean())
        assertEquals(
            "SERVER",
            report
                .path("partialSnapshot")
                .path("attempts")
                .path(0)
                .path("failure_code")
                .asText(),
        )
        assertTrue(report.path("observations").size() > 0)
        assertFalse(text.contains("PRIVATE_PROVIDER_MESSAGE"))
        assertFalse(text.contains("stackTrace"))
        Files.list(temporary).use { assertEquals(1, it.count()) }
    }

    @Test
    fun `snapshot failure still leaves a report and cannot replace a stopped flow`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")) }
        val failure = assertFailsWith<LiveEvaluationTechnicalStop> { safety.checkpoint() }
        val output = temporary.resolve("primary-smoke-results.json")
        val diagnostic =
            PrimaryGeminiSmokeDiagnostic(output, json, safety, PrimaryGeminiSmokeObservations()) {
                throw IllegalStateException("PRIVATE_DB_MESSAGE")
            }

        assertSame(failure, assertFailsWith<LiveEvaluationTechnicalStop> { diagnostic.execute { throw failure } })
        assertTrue(Files.exists(output), "The flow must leave its safe report even when it throws")
        val text = Files.readString(output)
        val report = json.readTree(text)
        assertEquals("SERVER", report.path("failureCode").asText())
        assertEquals(IllegalStateException::class.java.name, report.path("snapshotFailureClass").asText())
        assertFalse(text.contains("PRIVATE_DB_MESSAGE"))
        Files.list(temporary).use { assertEquals(1, it.count()) }
    }

    @Test
    fun `successful flow writes one safe completed result without copying raw flow payloads`() {
        val output = temporary.resolve("primary-smoke-results.json")
        val diagnostic =
            PrimaryGeminiSmokeDiagnostic(
                output,
                json,
                LiveEvaluationSafety { emptyList() },
                PrimaryGeminiSmokeObservations(),
            ) { emptyMap() }
        var flows = 0

        val result =
            diagnostic.execute {
                flows++
                mapOf("analysisCompleted" to true, "rawBody" to "PRIVATE_RESPONSE_BODY", "room" to mapOf("secret" to "PRIVATE_ROOM_VALUE"))
            }

        assertEquals(1, flows)
        assertEquals(true, result["analysisCompleted"])
        assertTrue(Files.exists(output), "The flow must leave its safe report even when it throws")
        val text = Files.readString(output)
        assertTrue(json.readTree(text).path("analysisCompleted").asBoolean())
        assertFalse(text.contains("PRIVATE_RESPONSE_BODY"))
        assertFalse(text.contains("PRIVATE_ROOM_VALUE"))
        Files.list(temporary).use { assertEquals(1, it.count()) }
    }

    @Test
    fun `a known shared failure stops polling before reading or waiting`() {
        val safety = LiveEvaluationSafety { listOf(LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")) }
        var reads = 0
        var pauses = 0
        var ticks = 0L
        val polling =
            PrimaryGeminiSmokePolling(safety, {
                pauses++
                ticks++
            }, { ticks })

        assertFailsWith<LiveEvaluationTechnicalStop> {
            polling.until(Duration.ofNanos(5), {
                reads++
                "ANALYZING"
            }, { false })
        }

        assertEquals(0, reads)
        assertEquals(0, pauses)
    }

    @Test
    fun `a failure observed by the first poll prevents a second read and any wait`() {
        val signals = mutableListOf<LiveEvaluationSignal>()
        val safety = LiveEvaluationSafety { signals.toList() }
        var reads = 0
        var pauses = 0
        var ticks = 0L
        val polling =
            PrimaryGeminiSmokePolling(safety, {
                pauses++
                ticks++
            }, { ticks })

        assertFailsWith<LiveEvaluationTechnicalStop> {
            polling.until(Duration.ofNanos(5), {
                reads++
                signals += LiveEvaluationSignal("FINAL_PRIMARY", parserFailureCode = "SERVER")
                "ANALYZING"
            }, { false })
        }

        assertEquals(1, reads)
        assertEquals(0, pauses)
    }

    @Test
    fun `output mismatch rejects the actual claim before any flow can start`() {
        val claims = PrimaryGeminiSmokeClaim(temporary.resolve("claims"))
        var flows = 0

        assertFailsWith<IllegalArgumentException> {
            claims.acquire(planId, temporary.resolve("approved-output"), temporary.resolve("other-output"), "a".repeat(64))
            flows++
        }

        assertEquals(0, flows)
    }

    @Test
    fun `a failed first flow permanently consumes its approved manifest claim`() {
        val output = temporary.resolve("approved-output")
        val claims = PrimaryGeminiSmokeClaim(temporary.resolve("claims"))
        val claim = claims.acquire(planId, output, output, "a".repeat(64))
        Files.createDirectories(output)
        val failure = NaturalLanguageParserException(ParserFailureKind.SERVER)
        val diagnostic =
            PrimaryGeminiSmokeDiagnostic(
                output.resolve("primary-smoke-results.json"),
                json,
                LiveEvaluationSafety { emptyList() },
                PrimaryGeminiSmokeObservations(),
            ) { emptyMap() }
        var flows = 0
        assertSame(
            failure,
            assertFailsWith<NaturalLanguageParserException> {
                diagnostic.execute {
                    flows++
                    throw failure
                }
            },
        )

        assertFailsWith<FileAlreadyExistsException> {
            claims.acquire(planId, output, output, "a".repeat(64))
            flows++
        }

        assertTrue(Files.exists(claim))
        assertTrue(Files.exists(output.resolve("primary-smoke-results.json")))
        assertEquals(1, flows)
    }
}
