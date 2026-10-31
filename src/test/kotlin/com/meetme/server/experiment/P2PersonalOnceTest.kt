package com.meetme.server.experiment

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.adapter.output.provider.LunaPreSubmissionCheckProvider
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderContract
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderRuntimeProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.shared.domain.InputEvidence
import com.sun.net.httpserver.HttpServer
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Explicitly approved adapter-only counterfactual, never a production routing or source-authority test. */
internal class P2PersonalProbe(
    val output: Path,
    private val endpoint: URI,
    private val expectedPayloadSha: String,
) {
    val ledger = StandaloneGeminiOnceDiagnostic.OnceLedger(output.resolve("wire-ledger.json"))
    val events = CopyOnWriteArrayList<Map<String, Any?>>()
    var result: PrecheckProviderResult? = null
        private set

    fun run(key: String): Map<String, Any?> {
        val request = request()
        val client =
            OkHttpClient
                .Builder()
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .eventListenerFactory {
                    object : EventListener() {
                        private var started = 0L

                        override fun callStart(call: Call) {
                            started = System.nanoTime()
                            events += mapOf("event" to "CALL_START", "timeoutNanos" to call.timeout().timeoutNanos())
                        }

                        override fun requestHeadersStart(call: Call) {
                            ledger.admit()
                            events +=
                                mapOf(
                                    "event" to "HEADERS_START",
                                    "remainingDeadlineNanos" to (call.timeout().deadlineNanoTime() - System.nanoTime()).coerceAtLeast(0),
                                )
                        }

                        override fun requestHeadersEnd(
                            call: Call,
                            request: okhttp3.Request,
                        ) {
                            events += mapOf("event" to "HEADERS_END")
                        }

                        override fun responseHeadersEnd(
                            call: Call,
                            response: Response,
                        ) {
                            events += mapOf("event" to "RESPONSE_HEADERS", "httpStatus" to response.code)
                        }

                        override fun callEnd(call: Call) {
                            events += mapOf("event" to "CALL_END", "elapsedNanos" to System.nanoTime() - started)
                        }

                        override fun callFailed(
                            call: Call,
                            ioe: IOException,
                        ) {
                            events +=
                                mapOf(
                                    "event" to "CALL_FAILED",
                                    "elapsedNanos" to System.nanoTime() - started,
                                    "failure" to
                                        if (generateSequence<Throwable>(
                                                ioe,
                                            ) { it.cause }.any { it is InterruptedIOException }
                                        ) {
                                            "TIMEOUT"
                                        } else {
                                            "NETWORK"
                                        },
                                    "causeClass" to ioe.javaClass.simpleName,
                                )
                        }
                    }
                }.addNetworkInterceptor { chain ->
                    val outgoing = chain.request()
                    check(outgoing.method == "POST" && outgoing.url.toUri() == endpoint)
                    val bytes = Buffer().also { checkNotNull(outgoing.body).writeTo(it) }.readByteArray()
                    check(sha(bytes) == expectedPayloadSha && bytes.size <= 16384)
                    val response = chain.proceed(outgoing)
                    if (response.code == 200) {
                        val captured = response.peekBody(16385).bytes()
                        if (captured.size <= 16384 && !secretPresent(captured)) {
                            Files.write(output.resolve("response.json"), captured, StandardOpenOption.CREATE_NEW)
                        }
                    }
                    response
                }.build()
        var failure: String? = null
        val start = System.nanoTime()
        try {
            result =
                LunaPreSubmissionCheckProvider
                    .runtime(
                        OpenAiProperties(apiKey = key),
                        JSON,
                        PrecheckProviderRuntimeProperties(enabled = true),
                        PrecheckProviderHttpTransport(client),
                        endpoint,
                    ).check(request)
        } catch (exception: PrecheckProviderException) {
            failure = exception.failureCode
        } catch (exception: Exception) {
            failure = exception.javaClass.simpleName
        }
        val report =
            mapOf(
                "counterfactual" to true,
                "actualPilotConfidence" to 0.25,
                "actualPilotPersonalLunaCalls" to 0,
                "scope" to "DIRECT_CURRENT_V2_PERSONAL_ADAPTER_NOT_ROUTER_OR_JDBC_AUTHORITY",
                "maximumPhysicalRequests" to 1,
                "physicalAdmissions" to ledger.admissions,
                "model" to "gpt-6-luna",
                "configuredMaximumTimeoutMillis" to 15000,
                "automaticRetries" to 0,
                "jevCalls" to 0,
                "geminiCalls" to 0,
                "finalAnalysisCalls" to 0,
                "elapsedNanos" to System.nanoTime() - start,
                "events" to events.toList(),
                "failureCode" to failure,
                "verdict" to result?.verdict,
                "prompt" to result?.prompt,
                "sourceExcerpt" to result?.sourceExcerpt,
                "choices" to result?.choices,
                "inputTokens" to result?.inputTokens,
                "outputTokens" to result?.outputTokens,
                "billedCostObserved" to false,
                "billedCost" to null,
            )
        val export = JSON.writeValueAsBytes(report)
        check(!secretPresent(export))
        Files.write(output.resolve("results.json"), export, StandardOpenOption.CREATE_NEW)
        return report
    }

    companion object {
        val JSON = StandaloneGeminiOnceDiagnostic.JSON
        const val APPROVAL_ID = "Sentinel_bf0363f8c63841a9811d742bb5755e30"
        val ENDPOINT: URI = URI.create("https://api.openai.com/v1/responses")
        val INPUT: Path = Path.of("/workspace/scratch/live-eval/blind20-c87e564a-1753-4d81-8650-8638d84b1435/pilot/participant-p2.json")

        fun sha(bytes: ByteArray) = StandaloneGeminiOnceDiagnostic.sha(bytes)

        fun request(): PrecheckProviderRequest {
            val saved = JSON.readTree(Files.readAllBytes(INPUT))
            val candidate = saved["current"]["candidate"]
            val reference = Instant.parse(candidate["interpretation_reference_at"].asString())
            check(candidate["evidence"]["interpretation"].asString() == "PENDING")
            val raw = saved["rawInput"].asString()
            check(raw == candidate["raw_text"].asString())
            return PrecheckProviderRequest(
                UUID.fromString(saved["current"]["check"]["id"].asString()),
                raw,
                reference,
                ZoneId.of("Asia/Seoul"),
                "ko-KR",
                LocalDate.of(2026, 11, 12),
                LocalDate.of(2026, 11, 16),
                Duration.ofSeconds(15),
                evidence = InputEvidence(candidate["revision"].asInt(), reference),
            )
        }

        fun payload(): ByteArray = PrecheckProviderContract(JSON).lunaRequestBytes(request())

        fun secretPresent(bytes: ByteArray): Boolean =
            listOf("GEMINI_API_KEY", "OPENAI_API_KEY", "TYPESAFE_API_KEY").any {
                System.getenv(it)?.takeIf(String::isNotBlank)?.let { value -> bytes.toString(Charsets.UTF_8).contains(value) } == true
            }

        fun validateManifest(
            path: Path,
            expected: String,
        ): JsonNode {
            val bytes = Files.readAllBytes(path)
            check(sha(bytes) == expected)
            val m = JSON.readTree(bytes)
            check(m["approved"].asBoolean() && m["approvalId"].asString() == APPROVAL_ID)
            check(m["mode"].asString() == "P2_PERSONAL_ONCE")
            check(m["maximumPhysicalRequests"].asInt() == 1 && m["timeoutMillis"].asInt() == 15000)
            check(m["automaticRetries"].asInt() == 0)
            check(m["jevCalls"].asInt() == 0 && m["geminiCalls"].asInt() == 0 && m["finalAnalysisCalls"].asInt() == 0)
            val records = m["files"].values().toList()
            val supplied = records.map { Path.of(it["path"].asString()) }.toSet()
            for (tree in listOf("src/main", "src/test", "build/classes/kotlin/main", "build/classes/kotlin/test")) {
                Files.walk(Path.of("/workspace/meet-me-analysis").resolve(tree)).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.forEach { check(it in supplied) }
                }
            }
            check(INPUT in supplied)
            records.forEach { check(sha(Files.readAllBytes(Path.of(it["path"].asString()))) == it["sha256"].asString()) }
            check(sha(payload()) == m["payloadSha256"].asString())
            val p = JSON.readTree(payload())
            check(p["model"].asString() == "gpt-6-luna" && !p["store"].asBoolean())
            check(p["service_tier"].asString() == "default" && p["reasoning"]["effort"].asString() == "low")
            check(p["max_output_tokens"].asInt() == 2048 && p["text"]["format"]["strict"].asBoolean())
            val values = p["text"]["format"]["schema"]["properties"]["verdict"]["enum"].values().map { it.asString() }.toSet()
            check(values == setOf("CLEAR", "NEEDS_CHOICE"))
            val id = m["planId"].asString()
            check(UUID.fromString(id).toString() == id)
            check(Path.of(m["outputDirectory"].asString()) == Path.of("/workspace/scratch/live-eval/p2-personal-once-$id"))
            check(!Files.exists(Path.of(m["outputDirectory"].asString())))
            return m
        }
    }
}

@EnabledIfEnvironmentVariable(named = "MEETME_P2_PERSONAL_MODE", matches = "FREE")
class P2PersonalOnceContractTest {
    @Test
    fun `current V2 p2 payload and original adapter use one bounded request with no replay`() {
        val dir = Files.createTempDirectory(Path.of("/workspace/scratch/live-eval"), "p2-personal-free-")
        val payload = P2PersonalProbe.payload()
        val root = P2PersonalProbe.JSON.readTree(payload)
        assertEquals("meet_me_personal_check_v2", root["text"]["format"]["name"].asString())
        assertEquals(
            setOf("CLEAR", "NEEDS_CHOICE"),
            root["text"]["format"]["schema"]["properties"]["verdict"]["enum"]
                .values()
                .map {
                    it.asString()
                }.toSet(),
        )
        val payloadPath = Path.of("/workspace/scratch/live-eval/p2-personal-once-preparation-20261030/payload.json")
        if (Files.exists(payloadPath)) {
            assertEquals(P2PersonalProbe.sha(payload), P2PersonalProbe.sha(Files.readAllBytes(payloadPath)))
        } else {
            Files.write(payloadPath, payload, StandardOpenOption.CREATE_NEW)
        }
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val response =
            P2PersonalProbe.JSON.writeValueAsBytes(
                mapOf(
                    "status" to "completed",
                    "output" to
                        listOf(
                            mapOf(
                                "type" to "message",
                                "content" to
                                    listOf(
                                        mapOf(
                                            "type" to "output_text",
                                            "text" to "{\"verdict\":\"CLEAR\",\"prompt\":null,\"sourceExcerpt\":null,\"choices\":[]}",
                                        ),
                                    ),
                            ),
                        ),
                    "usage" to
                        mapOf(
                            "input_tokens" to 100,
                            "output_tokens" to 10,
                            "output_tokens_details" to mapOf("reasoning_tokens" to 0),
                        ),
                ),
            )
        server.createContext("/v1/responses") { exchange ->
            count.incrementAndGet()
            assertEquals(P2PersonalProbe.sha(payload), P2PersonalProbe.sha(exchange.requestBody.readAllBytes()))
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val probe =
                P2PersonalProbe(dir, URI.create("http://127.0.0.1:${server.address.port}/v1/responses"), P2PersonalProbe.sha(payload))
            val r = probe.run("synthetic-unused-placeholder")
            assertEquals("CLEAR", r["verdict"].toString())
            assertEquals(1, count.get())
            assertEquals(1, probe.ledger.admissions)
            assertTrue((probe.events.first()["timeoutNanos"] as Long) in 1..Duration.ofSeconds(15).toNanos())
            assertFailsWith<IOException> { probe.ledger.admit() }
            assertEquals(1, count.get())
        } finally {
            server.stop(0)
        }
    }
}

@EnabledIfEnvironmentVariable(named = "MEETME_P2_PERSONAL_MODE", matches = "P2_PERSONAL_ONCE")
class P2PersonalOncePaidTest {
    @Test
    fun `explicit approved counterfactual sends at most one current personal Luna request`() {
        val path = Path.of(checkNotNull(System.getenv("MEETME_P2_PERSONAL_MANIFEST")))
        val hash = checkNotNull(System.getenv("MEETME_P2_PERSONAL_MANIFEST_SHA"))
        val m = P2PersonalProbe.validateManifest(path, hash)
        val key = checkNotNull(System.getenv("OPENAI_API_KEY")).also { check(it.isNotBlank()) }
        val base = Path.of("/workspace/scratch/live-eval")
        StandaloneGeminiOnceDiagnostic.claimApproval(base.resolve("p2-personal-once-approval-claims"), P2PersonalProbe.APPROVAL_ID, hash)
        val output = Path.of(m["outputDirectory"].asString())
        Files.createDirectory(output)
        Files.writeString(output.resolve("started"), "P2_PERSONAL_ONCE\n", StandardOpenOption.CREATE_NEW)
        val probe = P2PersonalProbe(output, P2PersonalProbe.ENDPOINT, m["payloadSha256"].asString())
        val report = probe.run(key)
        check(report["failureCode"] == null && probe.ledger.admissions == 1)
        check(report["verdict"].toString() in setOf("CLEAR", "NEEDS_CHOICE"))
    }
}

@EnabledIfEnvironmentVariable(named = "MEETME_P2_PERSONAL_MODE", matches = "VALIDATE")
class P2PersonalOnceValidationTest {
    @Test
    fun `frozen paid manifest validates without consuming approval or issuing HTTP`() {
        val claim = Path.of("/workspace/scratch/live-eval/p2-personal-once-approval-claims/${P2PersonalProbe.APPROVAL_ID}.claim")
        val before = if (Files.exists(claim)) Files.readString(claim) else null
        val m =
            P2PersonalProbe.validateManifest(
                Path.of(checkNotNull(System.getenv("MEETME_P2_PERSONAL_MANIFEST"))),
                checkNotNull(System.getenv("MEETME_P2_PERSONAL_MANIFEST_SHA")),
            )
        assertEquals(before, if (Files.exists(claim)) Files.readString(claim) else null)
        assertTrue(!Files.exists(Path.of(m["outputDirectory"].asString())))
    }
}
