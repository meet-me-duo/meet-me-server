package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.shared.adapter.output.aihttp.toProviderEvidence
import com.meetme.server.shared.domain.InputEvidence
import com.meetme.server.shared.domain.InputInterpretation
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Bounded envelope/payload contract; synthetic interceptor responses never reach DNS or a socket. */
class LunaPrecheckResponseBodyBoundaryTest {
    private val mapper = JsonMapper.builder().build()
    private val contract = PrecheckProviderContract(mapper)
    private val anchor = Instant.parse("2027-02-01T00:00:00Z")
    private val request =
        PrecheckProviderRequest(
            checkId = UUID(0, 124),
            rawText = "Synthetic Tuesday availability starts at 8.",
            referenceAt = anchor,
            timeZone = ZoneId.of("Asia/Seoul"),
            locale = "en-US",
            searchStartDate = LocalDate.parse("2027-02-01"),
            searchEndDate = LocalDate.parse("2027-02-08"),
            timeout = Duration.ofSeconds(3),
            evidence = InputEvidence(1, anchor),
        )

    @Test
    fun `LB01 known full HTTP envelope accepts 65536 and rejects 65537`() {
        assertEquals(65_536, check(paddedEnvelope(65_536)).responseBytes)
        providerInvalid(paddedEnvelope(65_537))
    }

    @Test
    fun `LB02 unknown content length retains the same streamed envelope bound`() {
        assertEquals(65_536, check(paddedEnvelope(65_536), unknownLength = true).responseBytes)
        providerInvalid(paddedEnvelope(65_537), unknownLength = true)
    }

    @Test
    fun `LB03 small grounded question with large metadata retains full raw byte accounting`() {
        val bytes = paddedEnvelope(16_447)
        val parsed = contract.parseLuna(bytes, request)
        assertEquals(PrecheckVerdict.NEEDS_CHOICE, parsed.verdict)
        assertEquals("Does 8 mean morning or evening?", parsed.prompt)
        assertEquals("8", parsed.sourceExcerpt)
        assertTrue(parsed.choices.isEmpty())
        assertEquals(16_447, parsed.responseBytes)
        assertEquals(payload().toByteArray().size, parsed.responsePayloadBytes)
        assertEquals(parsed, check(bytes))
    }

    @Test
    fun `LB04 question length and source grounding fail independently below the byte limit`() {
        assertEquals(PrecheckVerdict.NEEDS_CHOICE, check(envelope()).verdict)
        providerInvalid(envelope(payload(prompt = "q".repeat(161))))
        providerInvalid(envelope(payload(excerpt = "9")))
    }

    @Test
    fun `LB05 malformed duplicate and trailing JSON are rejected at both JSON layers`() {
        val normal = envelope()
        val duplicateOuter = normal.toString(Charsets.UTF_8).replaceFirst("{", "{\"status\":\"completed\",")
        val duplicateInner = """{"verdict":"NEEDS_CHOICE","prompt":"Which time?","prompt":"Other time?","sourceExcerpt":"8","choices":[]}"""
        listOf(
            "{".toByteArray(),
            duplicateOuter.toByteArray(),
            envelope(duplicateInner),
            normal + " {}".toByteArray(),
            envelope(payload() + " {}"),
        ).forEach { providerInvalid(it) }
    }

    @Test
    fun `LB06 incomplete response and incomplete structured question are separate failures`() {
        providerInvalid(envelope(status = "incomplete"))
        providerInvalid(envelope("""{"verdict":"NEEDS_CHOICE","prompt":"Which time?","choices":[]}"""))
        providerInvalid(mapper.writeValueAsBytes(mapOf("status" to "completed", "output" to emptyMap<String, String>())))
    }

    @Test
    fun `LB08 application payload accepts 16384 UTF8 bytes and rejects 16385 independently of envelope`() {
        val smallQuestion = payload(prompt = "Does 8 mean morning or evening ☃?")

        fun paddedPayload(size: Int): String = smallQuestion + " ".repeat(size - smallQuestion.toByteArray().size)

        val accepted = envelope(paddedPayload(16_384))
        assertTrue(accepted.size < 65_536)
        val parsed = check(accepted)
        assertEquals(16_384, parsed.responsePayloadBytes)
        assertEquals(accepted.size, parsed.responseBytes)
        val rejectedPayload = paddedPayload(16_385)
        assertTrue(rejectedPayload.length < 16_385, "UTF8 bytes, not character count, define the payload limit")
        providerInvalid(envelope(rejectedPayload))
    }

    @Test
    fun `LB09 duplicate output text and unsupported output content remain invalid`() {
        val message = mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload())))
        providerInvalid(mapper.writeValueAsBytes(mapOf("status" to "completed", "output" to listOf(message, message))))
        providerInvalid(
            mapper.writeValueAsBytes(
                mapOf(
                    "status" to "completed",
                    "output" to listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "refusal", "refusal" to "no")))),
                ),
            ),
        )
    }

    @Test
    fun `LB10 bounded envelope metadata retains explicit depth and number limits`() {
        assertEquals(PrecheckVerdict.NEEDS_CHOICE, check(withOuterField("nested", "[".repeat(8) + "0" + "]".repeat(8))).verdict)
        providerInvalid(withOuterField("nested", "[".repeat(65) + "0" + "]".repeat(65)))
        assertEquals(PrecheckVerdict.NEEDS_CHOICE, check(withOuterField("numeric", "1".repeat(128))).verdict)
        providerInvalid(withOuterField("numeric", "1".repeat(129)))
    }

    @Test
    fun `LB11 bounded bytes do not permit unbounded JSON token overhead`() {
        val manyNodes = withOuterField("nodes", List(17_000) { "[]" }.joinToString(",", "[", "]"))
        assertTrue(manyNodes.size < 65_536)
        providerInvalid(manyNodes)
    }

    @Test
    fun `LB12 request body limit stays 16384 and rejects before the synthetic SDK attempt`() {
        val oversizedRequest = request.copy(locale = "x".repeat(16_384))
        assertTrue(contract.lunaRequestBytes(oversizedRequest).size > 16_384)
        assertEquals(
            "INVALID_REQUEST",
            assertFailsWith<PrecheckProviderException> {
                check(envelope(), providerRequest = oversizedRequest, expectedAttempts = 0)
            }.failureCode,
        )
    }

    @Test
    @EnabledIfSystemProperty(named = "meetme.free.saved-luna-replay", matches = "true")
    fun `LB07 optional private saved response adapter and parser replay has no socket or native admission`() {
        // Paths are supplied only by the execution owner. Never put private text in assertion diagnostics or reports.
        try {
            val wireBytes = Files.readAllBytes(savedPath("requestPath"))
            val responseBytes = Files.readAllBytes(savedPath("responsePath"))
            val wire = mapper.readTree(wireBytes)
            val input = mapper.readTree(wire.path("input").asText())
            val savedEvidence = input.path("evidence")
            check(savedEvidence.isObject && savedEvidence.path("supplement").isNull)
            val revision = savedEvidence.path("original_revision")
            check(revision.isIntegralNumber && revision.canConvertToInt() && revision.intValue() > 0)
            val evidence =
                InputEvidence(
                    originalRevision = revision.intValue(),
                    referenceAt = Instant.parse(savedEvidence.path("reference_at").asText()),
                    interpretation = InputInterpretation.valueOf(savedEvidence.path("interpretation").asText()),
                    unresolvedReason = savedEvidence.path("unresolved_reason").let { if (it.isNull) null else it.asText() },
                    protocol = savedEvidence.path("protocol").asText(),
                )
            val savedRequest =
                request.copy(
                    checkId = UUID.fromString(input.path("input_ref").asText()),
                    rawText = input.path("raw_text").asText(),
                    referenceAt = Instant.parse(input.path("reference_at").asText()),
                    timeZone = ZoneId.of(input.path("time_zone").asText()),
                    locale = input.path("locale").asText(),
                    searchStartDate = LocalDate.parse(input.path("search_start_date").asText()),
                    searchEndDate = LocalDate.parse(input.path("search_end_date").asText()),
                    evidence = evidence,
                )
            evidence.validateAgainst(savedRequest.rawText)
            val regeneratedEvidence = mapper.readTree(mapper.writeValueAsBytes(evidence.toProviderEvidence()))
            check(regeneratedEvidence == savedEvidence)
            check(mapper.readTree(contract.lunaRequestBytes(savedRequest)) == wire)
            val parsed = contract.parseLuna(responseBytes, savedRequest)
            check(parsed.verdict == PrecheckVerdict.NEEDS_CHOICE)
            check(responseBytes.size == 16_447 && parsed.responseBytes == 16_447)
            check(parsed.responsePayloadBytes == 253)
            val adapted = check(responseBytes, providerRequest = savedRequest)
            check(adapted == parsed)
            check(savedPath("outputPath").fileName.toString() != "saved-response-direct-parser-replay.json")
            val report =
                mapper.writeValueAsBytes(
                    mapOf(
                        "stage" to "NO_SOCKET_ADAPTER_AND_PARSER_REPLAY_NOT_NATIVE_OR_E2E",
                        "adapter_parser_equal" to true,
                        "request_bytes" to wireBytes.size,
                        "request_sha256" to sha256(wireBytes),
                        "response_bytes" to responseBytes.size,
                        "response_payload_bytes" to parsed.responsePayloadBytes,
                        "response_sha256" to sha256(responseBytes),
                        "verdict" to parsed.verdict.name,
                    ),
                )
            Files
                .newByteChannel(
                    savedPath("outputPath"),
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                ).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(report)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
        } catch (failure: Throwable) {
            throw AssertionError("SAVED_LUNA_REPLAY_FAILED_${failure.javaClass.simpleName}")
        }
    }

    private fun check(
        bytes: ByteArray,
        unknownLength: Boolean = false,
        providerRequest: PrecheckProviderRequest = request,
        expectedAttempts: Int = 1,
    ): PrecheckProviderResult {
        val intercepted = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .dns(
                    object : okhttp3.Dns {
                        override fun lookup(hostname: String): List<java.net.InetAddress> = error("SYNTHETIC_RESPONSE_MUST_NOT_REACH_DNS")
                    },
                ).addInterceptor { chain ->
                    intercepted.incrementAndGet()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("synthetic")
                        .body(if (unknownLength) unknownLengthBody(bytes) else bytes.toResponseBody(JSON))
                        .build()
                }.build()
        val provider =
            LunaPreSubmissionCheckProvider.runtime(
                OpenAiProperties(apiKey = "synthetic-not-a-credential"),
                mapper,
                PrecheckProviderRuntimeProperties(enabled = true),
                PrecheckProviderHttpTransport(client),
                URI("http://127.0.0.1:1/v1/responses"),
            )
        try {
            return provider.check(providerRequest)
        } finally {
            assertEquals(expectedAttempts, intercepted.get(), "Only the expected synthetic SDK attempt; no retry or fallback")
        }
    }

    private fun providerInvalid(
        bytes: ByteArray,
        unknownLength: Boolean = false,
    ) {
        assertEquals(
            "INVALID_RESPONSE",
            assertFailsWith<PrecheckProviderException> { check(bytes, unknownLength) }.failureCode,
        )
    }

    private fun payload(
        prompt: String = "Does 8 mean morning or evening?",
        excerpt: String = "8",
    ): String =
        mapper.writeValueAsString(
            mapOf("verdict" to "NEEDS_CHOICE", "prompt" to prompt, "sourceExcerpt" to excerpt, "choices" to emptyList<String>()),
        )

    private fun envelope(
        payload: String = payload(),
        status: String = "completed",
        padding: String = "",
    ): ByteArray =
        mapper.writeValueAsBytes(
            mapOf(
                "status" to status,
                "output" to
                    listOf(mapOf("type" to "message", "content" to listOf(mapOf("type" to "output_text", "text" to payload)))),
                "usage" to
                    mapOf("input_tokens" to 7, "output_tokens" to 2, "output_tokens_details" to mapOf("reasoning_tokens" to 1)),
                "metadata" to mapOf("synthetic_padding" to padding),
            ),
        )

    private fun paddedEnvelope(size: Int): ByteArray {
        val bytes = envelope(padding = "x".repeat(size - envelope().size))
        assertEquals(size, bytes.size)
        return bytes
    }

    private fun withOuterField(
        field: String,
        json: String,
    ): ByteArray = (envelope().toString(Charsets.UTF_8).dropLast(1) + ",\"$field\":$json}").toByteArray()

    private fun unknownLengthBody(bytes: ByteArray): ResponseBody =
        object : ResponseBody() {
            private val buffer = Buffer().write(bytes)

            override fun contentType(): MediaType = JSON

            override fun contentLength(): Long = -1

            override fun source(): BufferedSource = buffer
        }

    private fun savedPath(suffix: String): Path =
        Path.of(requireNotNull(System.getProperty("meetme.free.saved-luna-replay.$suffix")) { "MISSING_SAVED_REPLAY_PATH" })

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
