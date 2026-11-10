package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException
import com.meetme.server.coordination.application.port.output.ParserFailureKind
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Non-sensitive provider-rejection adapter contract. All bodies and secrets are synthetic; no HTTP or model calls. */
class ProviderRejectionDiagnosticsContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `PRD01 known unsupported parameter retains only closed diagnostic facts`() {
        val failure = decode(error("INVALID_ARGUMENT", "Unsupported parameter: candidateCount", "generation_config.candidate_count"))
        val diagnostic = failure.diagnostic
        assertEquals(AnalysisProvider.GEMINI, diagnostic.provider)
        assertEquals(400, diagnostic.httpStatus)
        assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNSUPPORTED_PARAMETER, diagnostic.reason)
        assertEquals(setOf(ProviderRejectedField.CANDIDATE_COUNT), diagnostic.fields)
        assertFalse(diagnostic.malformed)
        assertFalse(diagnostic.bodyLimitReached)
        assertSafe(failure)
    }

    @Test
    fun `PRD02 known schema complexity is a fixed reason without copying its description`() {
        val failure =
            decode(
                error(
                    "INVALID_ARGUMENT",
                    "The specified schema has too many states for serving.",
                    "generation_config.response_json_schema",
                ),
            )
        assertEquals(ProviderRejectionReason.SCHEMA_COMPLEXITY, failure.diagnostic.reason)
        assertEquals(setOf(ProviderRejectedField.RESPONSE_JSON_SCHEMA), failure.diagnostic.fields)
        assertFalse(failure.message.orEmpty().contains("too many states"))
        assertSafe(failure)
    }

    @Test
    fun `PRD03 provider statuses are exact finite values and do not override actual HTTP status`() {
        val names =
            listOf(
                "INVALID_ARGUMENT",
                "PERMISSION_DENIED",
                "UNAUTHENTICATED",
                "NOT_FOUND",
                "RESOURCE_EXHAUSTED",
                "UNAVAILABLE",
                "INTERNAL",
                "DEADLINE_EXCEEDED",
            )
        for (name in names) {
            val failure = decode(error(name, "Unlisted diagnostic description"), httpStatus = 400)
            assertEquals(ProviderRejectionStatus.valueOf(name), failure.diagnostic.providerStatus)
            assertEquals(400, failure.diagnostic.httpStatus)
            assertSafe(failure)
        }
        val openAi =
            ProviderRejectionDiagnostics.decode(
                AnalysisProvider.OPENAI,
                400,
                mapper.writeValueAsBytes(mapOf("error" to mapOf("type" to "invalid_request_error", "message" to PRIVATE))),
            )
        assertEquals(ProviderRejectionStatus.INVALID_REQUEST_ERROR, openAi.diagnostic.providerStatus)
        assertSafe(openAi)
    }

    @Test
    fun `PRD04 arbitrary status code type and path cannot become diagnostic strings`() {
        val body =
            mapper.writeValueAsBytes(
                mapOf(
                    "error" to
                        mapOf(
                            "status" to "INVALID_ARGUMENT_$PRIVATE",
                            "code" to PRIVATE,
                            "type" to "https://example.invalid/$PRIVATE?key=$SECRET",
                            "message" to PRIVATE,
                            "details" to
                                listOf(
                                    mapOf(
                                        "fieldViolations" to
                                            listOf(
                                                mapOf("field" to "generation_config.candidate_count_$PRIVATE", "description" to PRIVATE),
                                            ),
                                    ),
                                ),
                        ),
                ),
            )
        val failure = decode(body)
        assertEquals(ProviderRejectionStatus.UNKNOWN, failure.diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
        assertTrue(failure.diagnostic.fields.isEmpty())
        assertSafe(failure)
    }

    @Test
    fun `PRD05 request text credentials endpoint and echoed body are absent from all exposed exception surfaces`() {
        val bytes =
            error(
                "INVALID_ARGUMENT",
                "$PRIVATE $SECRET Authorization: Bearer $SECRET https://example.invalid/$PRIVATE?key=$SECRET",
                "generation_config.response_json_schema",
            )
        val failure = decode(bytes)
        bytes.fill(0)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertSafe(failure)
    }

    @Test
    fun `PRD06 malformed bodies remain bounded unknown diagnostics instead of throwing or exposing parser text`() {
        val bodies = listOf(byteArrayOf(), PRIVATE.toByteArray(), "{\"error\":".toByteArray(), byteArrayOf(0xff.toByte()))
        for (body in bodies) {
            val failure = decode(body)
            assertEquals(400, failure.diagnostic.httpStatus)
            assertEquals(ProviderRejectionStatus.UNKNOWN, failure.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
            assertTrue(failure.diagnostic.fields.isEmpty())
            assertTrue(failure.diagnostic.malformed)
            assertSafe(failure)
        }
    }

    @Test
    fun `PRD07 duplicate JSON and trailing JSON never supply partially trusted diagnosis`() {
        val bodies =
            listOf(
                """{"error":{"status":"INVALID_ARGUMENT","status":"UNAVAILABLE"}}""",
                """{"error":{"status":"INVALID_ARGUMENT"}} {"error":{"status":"UNAVAILABLE"}}""",
            )
        for (body in bodies) {
            val failure = decode(body.toByteArray())
            assertTrue(failure.diagnostic.malformed)
            assertEquals(ProviderRejectionStatus.UNKNOWN, failure.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
            assertTrue(failure.diagnostic.fields.isEmpty())
            assertSafe(failure)
        }
    }

    @Test
    fun `PRD08 oversized body is not parsed and its exposed size remains bounded`() {
        val bytes = error("INVALID_ARGUMENT", PRIVATE.repeat(2_000), "generation_config.candidate_count")
        assertTrue(bytes.size > 16_384)
        val failure = decode(bytes)
        assertEquals(16_385, failure.diagnostic.bodyBytes)
        assertTrue(failure.diagnostic.bodyLimitReached)
        assertEquals(ProviderRejectionStatus.UNKNOWN, failure.diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
        assertTrue(failure.diagnostic.fields.isEmpty())
        assertSafe(failure)
    }

    @Test
    fun `PRD09 complete JSON at and below capture boundary preserves exact bounded size and limit signal`() {
        val prefix = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\""
        val suffix = "\"}}"
        for (size in listOf(16_383, 16_384)) {
            val bytes = (prefix + "x".repeat(size - prefix.length - suffix.length) + suffix).toByteArray()
            assertEquals(size, bytes.size)
            val failure = decode(bytes)
            assertEquals(size, failure.diagnostic.bodyBytes)
            assertEquals(size >= 16_384, failure.diagnostic.bodyLimitReached)
            assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, failure.diagnostic.providerStatus)
            assertFalse(failure.diagnostic.malformed)
            assertSafe(failure)
        }
    }

    @Test
    fun `PRD10 only recognized API field aliases become closed field enums`() {
        val aliases =
            mapOf(
                "generation_config.candidate_count" to ProviderRejectedField.CANDIDATE_COUNT,
                "generationConfig.responseJsonSchema" to ProviderRejectedField.RESPONSE_JSON_SCHEMA,
                "generation_config.response_mime_type" to ProviderRejectedField.RESPONSE_MIME_TYPE,
                "generationConfig.responseFormat" to ProviderRejectedField.RESPONSE_FORMAT,
                "generation_config.max_output_tokens" to ProviderRejectedField.MAX_OUTPUT_TOKENS,
                "generationConfig.thinkingConfig.thinkingLevel" to ProviderRejectedField.THINKING_LEVEL,
                "generation_config.temperature" to ProviderRejectedField.TEMPERATURE,
                "generation_config.top_p" to ProviderRejectedField.TOP_P,
                "generationConfig.topK" to ProviderRejectedField.TOP_K,
                "model" to ProviderRejectedField.MODEL,
                "contents" to ProviderRejectedField.CONTENTS,
            )
        for ((path, field) in aliases) {
            val failure = decode(error("INVALID_ARGUMENT", "Unsupported parameter.", path))
            assertEquals(setOf(field), failure.diagnostic.fields, path)
            assertEquals(ProviderRejectionReason.UNSUPPORTED_PARAMETER, failure.diagnostic.reason, path)
            assertSafe(failure)
        }
    }

    @Test
    fun `PRD11 unsupported wording without an identified approved parameter stays unknown`() {
        val failure = decode(error("INVALID_ARGUMENT", "Unsupported parameter: $PRIVATE", "unknown.$PRIVATE"))
        assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
        assertTrue(failure.diagnostic.fields.isEmpty())
        assertSafe(failure)
    }

    @Test
    fun `PRD12 safe diagnosis never changes typed HTTP failure priority or retry policy`() {
        for ((status, kind) in listOf(
            400 to ParserFailureKind.INVALID_REQUEST,
            503 to ParserFailureKind.SERVER,
            529 to ParserFailureKind.SERVER,
        )) {
            val safeCause = decode(error("INVALID_ARGUMENT", PRIVATE), httpStatus = status)
            val classified = ProviderFailureClassifier.classifyStatus(status, null)
            val failure = NaturalLanguageParserException(classified, cause = safeCause)
            assertEquals(kind, classified)
            assertEquals(kind, ProviderFailureClassifier.classify(failure))
            assertEquals(status != 400, failure.retryable)
            assertEquals(kind.name, failure.message)
            assertFalse(failure.stackTraceToString().contains(PRIVATE))
        }
    }

    @Test
    fun `PRD13 gzip provider errors expose the same closed facts without retaining compressed or decoded bodies`() {
        val plain = error("INVALID_ARGUMENT", "Unsupported parameter: candidateCount", "generation_config.candidate_count")
        val compressed = gzip(plain)
        assertTrue(compressed.size < 16_384)
        val failure = decode(compressed)
        val expected = decode(plain).diagnostic.copy(bodyBytes = compressed.size)
        assertEquals(expected, failure.diagnostic)
        assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, failure.diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNSUPPORTED_PARAMETER, failure.diagnostic.reason)
        assertEquals(setOf(ProviderRejectedField.CANDIDATE_COUNT), failure.diagnostic.fields)
        assertFalse(failure.diagnostic.bodyLimitReached)
        assertFalse(failure.diagnostic.malformed)
        assertSafe(failure)
    }

    @Test
    fun `PRD14 gzip expansion raw limits and broken trailers remain bounded unknown diagnoses`() {
        val valid = gzip(error("INVALID_ARGUMENT", PRIVATE, "generation_config.candidate_count"))
        val expanded = gzip(error("INVALID_ARGUMENT", PRIVATE.repeat(2_000), "generation_config.candidate_count"))
        assertTrue(expanded.size < 16_384)
        val expansionFailure = decode(expanded)
        assertEquals(expanded.size, expansionFailure.diagnostic.bodyBytes)
        assertTrue(expansionFailure.diagnostic.bodyLimitReached)
        assertEquals(ProviderRejectionStatus.UNKNOWN, expansionFailure.diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNKNOWN, expansionFailure.diagnostic.reason)
        assertTrue(expansionFailure.diagnostic.fields.isEmpty())
        assertSafe(expansionFailure)

        val oversizedRaw = valid + ByteArray(16_385 - valid.size)
        val rawFailure = decode(oversizedRaw)
        assertEquals(16_385, rawFailure.diagnostic.bodyBytes)
        assertTrue(rawFailure.diagnostic.bodyLimitReached)
        assertEquals(ProviderRejectionStatus.UNKNOWN, rawFailure.diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNKNOWN, rawFailure.diagnostic.reason)
        assertTrue(rawFailure.diagnostic.fields.isEmpty())
        assertSafe(rawFailure)

        val damagedTrailer = valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for (broken in listOf(valid.copyOf(valid.size - 4), damagedTrailer, byteArrayOf(0x1f, 0x8b.toByte(), 0x08))) {
            val failure = decode(broken)
            assertEquals(broken.size, failure.diagnostic.bodyBytes)
            assertFalse(failure.diagnostic.bodyLimitReached)
            assertTrue(failure.diagnostic.malformed)
            assertEquals(ProviderRejectionStatus.UNKNOWN, failure.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
            assertTrue(failure.diagnostic.fields.isEmpty())
            assertSafe(failure)
        }
    }

    @Test
    fun `PRD15 exact decoded gzip limit is signaled while complete known facts remain available`() {
        val prefix = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\""
        val suffix = "\"}}"
        val plain = (prefix + "x".repeat(16_384 - prefix.length - suffix.length) + suffix).toByteArray()
        assertEquals(16_384, plain.size)
        val compressed = gzip(plain)
        assertTrue(compressed.size < 16_384)
        val failure = decode(compressed)
        assertEquals(compressed.size, failure.diagnostic.bodyBytes)
        assertTrue(failure.diagnostic.bodyLimitReached)
        assertFalse(failure.diagnostic.malformed)
        assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, failure.diagnostic.providerStatus)
        assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
        assertTrue(failure.diagnostic.fields.isEmpty())
        assertSafe(failure)
    }

    @Test
    fun `PRD16 only complete supported aliases identify fields and invalid path fragments remain unknown`() {
        for (message in listOf(
            "Unsupported parameter: candidateCount",
            "Unknown name \"candidate_count\" at 'generation_config': Cannot find field.",
        )) {
            val known = decode(error("INVALID_ARGUMENT", message))
            assertEquals(setOf(ProviderRejectedField.CANDIDATE_COUNT), known.diagnostic.fields)
            assertEquals(ProviderRejectionReason.UNSUPPORTED_PARAMETER, known.diagnostic.reason)
            assertSafe(known)
        }
        for (path in listOf(
            "_m_o_d_e_l_",
            "generation_config.__candidate_count",
            "generationConfig.candidateCount.$PRIVATE",
        )) {
            val failure = decode(error("INVALID_ARGUMENT", "Unsupported parameter.", path))
            assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, failure.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason, path)
            assertTrue(failure.diagnostic.fields.isEmpty(), path)
            assertSafe(failure)
        }
        for (message in listOf(
            "Unsupported parameter: generation_config.candidate_count.$PRIVATE",
            "Unsupported parameter: private_namespace.candidateCount",
            "Unsupported parameter: candidateCount[$PRIVATE]",
        )) {
            val failure = decode(error("INVALID_ARGUMENT", message))
            assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, failure.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
            assertTrue(failure.diagnostic.fields.isEmpty())
            assertSafe(failure)
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun decode(
        body: ByteArray,
        httpStatus: Int = 400,
    ) = ProviderRejectionDiagnostics.decode(AnalysisProvider.GEMINI, httpStatus, body)

    private fun error(
        status: String,
        message: String,
        field: String? = null,
    ): ByteArray =
        mapper.writeValueAsBytes(
            mapOf(
                "error" to
                    mapOf(
                        "status" to status,
                        "message" to message,
                        "details" to
                            field
                                ?.let {
                                    listOf(
                                        mapOf(
                                            "@type" to "type.googleapis.com/google.rpc.BadRequest",
                                            "fieldViolations" to listOf(mapOf("field" to it, "description" to PRIVATE)),
                                        ),
                                    )
                                }.orEmpty(),
                    ),
            ),
        )

    private fun assertSafe(failure: ProviderRejectionDiagnosticException) {
        val exposed =
            listOf(
                failure.toString(),
                failure.message.orEmpty(),
                failure.diagnostic.toString(),
                mapper.writeValueAsString(failure.diagnostic),
                failure.stackTraceToString(),
            ).joinToString()
        assertFalse(exposed.contains(PRIVATE))
        assertFalse(exposed.contains(SECRET))
        assertFalse(exposed.contains("example.invalid"))
        assertFalse(exposed.contains("Authorization:"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val PRIVATE = "OFFLINE_PRIVATE_REJECTION_TEXT_1938"
        private const val SECRET = "sk-offline-test-secret-1938"
    }
}
