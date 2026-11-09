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

/** Standard google.rpc.ErrorInfo contract, with synthetic data only. No HTTP, model, or original-family replay. */
class ProviderErrorInfoDiagnosticsContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `EIR01 exact Google ErrorInfo exposes each documented reason as a closed enum only`() {
        for (name in documentedReasons) {
            val body = envelope(listOf(detail(name)))
            val failure = decode(body)
            assertEquals(ProviderErrorInfoReason.valueOf(name), failure.diagnostic.errorInfoReason)
            assertEquals(AnalysisProvider.GEMINI, failure.diagnostic.provider)
            assertEquals(400, failure.diagnostic.httpStatus)
            assertEquals(ProviderRejectionStatus.INVALID_ARGUMENT, failure.diagnostic.providerStatus)
            assertEquals(ProviderRejectionReason.UNKNOWN, failure.diagnostic.reason)
            assertTrue(failure.diagnostic.fields.isEmpty())
            assertFalse(failure.diagnostic.malformed)
            assertEquals(body.size, failure.diagnostic.bodyBytes)
            assertSafe(failure)
        }
    }

    @Test
    fun `EIR02 type domain and provider must match exactly before a reason is trusted`() {
        val mismatches =
            listOf(
                detail("API_KEY_INVALID", type = null),
                detail("API_KEY_INVALID", type = "google.rpc.ErrorInfo"),
                detail("API_KEY_INVALID", type = "$ERROR_INFO_TYPE.$PRIVATE"),
                detail("API_KEY_INVALID", type = "https://$ERROR_INFO_TYPE"),
                detail("API_KEY_INVALID", domain = null),
                detail("API_KEY_INVALID", domain = "GOOGLEAPIS.COM"),
                detail("API_KEY_INVALID", domain = "googleapis.com.$PRIVATE"),
                detail("API_KEY_INVALID", domain = " googleapis.com"),
                detail("API_KEY_INVALID", domain = "googleapis.com "),
            )
        for (entry in mismatches) {
            val failure = decode(envelope(listOf(entry)))
            assertEquals(ProviderErrorInfoReason.UNKNOWN, failure.diagnostic.errorInfoReason)
            assertSafe(failure)
        }
        val otherProvider =
            ProviderRejectionDiagnostics.decode(
                AnalysisProvider.OPENAI,
                400,
                envelope(listOf(detail("API_KEY_INVALID"))),
            )
        assertEquals(ProviderErrorInfoReason.UNKNOWN, otherProvider.diagnostic.errorInfoReason)
        assertSafe(otherProvider)
    }

    @Test
    fun `EIR03 unknown or decorated reason values never become diagnostic strings or partial matches`() {
        val unknowns =
            listOf(
                "$PRIVATE-API_KEY_INVALID",
                "API_KEY_INVALID_$PRIVATE",
                "api_key_invalid",
                " API_KEY_INVALID",
                "API_KEY_INVALID ",
                "UNKNOWN",
                PRIVATE,
                400,
                listOf("API_KEY_INVALID"),
                null,
            )
        for (reason in unknowns) {
            val failure = decode(envelope(listOf(detail(reason))))
            assertEquals(ProviderErrorInfoReason.UNKNOWN, failure.diagnostic.errorInfoReason)
            assertSafe(failure)
        }
    }

    @Test
    fun `EIR04 equal duplicate reasons are stable but distinct supported reasons are unresolved in every order`() {
        val same = decode(envelope(listOf(detail("API_KEY_INVALID"), detail("API_KEY_INVALID"))))
        assertEquals(ProviderErrorInfoReason.API_KEY_INVALID, same.diagnostic.errorInfoReason)
        assertSafe(same)
        for (reasons in listOf(
            listOf("API_KEY_INVALID", "SERVICE_DISABLED"),
            listOf("SERVICE_DISABLED", "API_KEY_INVALID"),
        )) {
            val conflicting = decode(envelope(reasons.map { detail(it) }))
            assertEquals(ProviderErrorInfoReason.UNKNOWN, conflicting.diagnostic.errorInfoReason)
            assertSafe(conflicting)
        }
        val afterInitialPrefix = List(16) { detail("API_KEY_INVALID") } + detail("SERVICE_DISABLED")
        val body = envelope(afterInitialPrefix)
        assertTrue(body.size < 16_384)
        val lateConflict = decode(body)
        assertEquals(ProviderErrorInfoReason.UNKNOWN, lateConflict.diagnostic.errorInfoReason)
        assertSafe(lateConflict)
    }

    @Test
    fun `EIR05 unrelated unknown and mismatched details do not override a single exact supported reason`() {
        val entries =
            listOf(
                detail("SERVICE_DISABLED", domain = "other.example.invalid"),
                detail("SERVICE_DISABLED", type = "type.googleapis.com/google.rpc.BadRequest"),
                detail(PRIVATE),
                detail("API_KEY_INVALID"),
            )
        for (details in listOf(entries, entries.reversed())) {
            val failure = decode(envelope(details))
            assertEquals(ProviderErrorInfoReason.API_KEY_INVALID, failure.diagnostic.errorInfoReason)
            assertSafe(failure)
        }
    }

    @Test
    fun `EIR06 free text and metadata are not alternative sources of a Google reason`() {
        val wrongLocations =
            listOf(
                mapper.writeValueAsBytes(
                    mapOf("error" to mapOf("status" to "INVALID_ARGUMENT", "message" to "API_KEY_INVALID $PRIVATE")),
                ),
                mapper.writeValueAsBytes(
                    mapOf("error" to mapOf("status" to "INVALID_ARGUMENT", "reason" to "API_KEY_INVALID", "code" to "API_KEY_INVALID")),
                ),
                envelope(
                    listOf(
                        detail(null) +
                            mapOf(
                                "metadata" to mapOf("reason" to "API_KEY_INVALID", "domain" to "googleapis.com", "secret" to SECRET),
                            ),
                    ),
                ),
            )
        for (body in wrongLocations) {
            val failure = decode(body)
            assertEquals(ProviderErrorInfoReason.UNKNOWN, failure.diagnostic.errorInfoReason)
            assertSafe(failure)
        }
    }

    @Test
    fun `EIR07 gzip preserves only the same bounded ErrorInfo facts`() {
        val plain = envelope(listOf(detail("API_KEY_SERVICE_BLOCKED")))
        val compressed = gzip(plain)
        val failure = decode(compressed)
        assertEquals(decode(plain).diagnostic.copy(bodyBytes = compressed.size), failure.diagnostic)
        assertEquals(ProviderErrorInfoReason.API_KEY_SERVICE_BLOCKED, failure.diagnostic.errorInfoReason)
        assertFalse(failure.diagnostic.bodyLimitReached)
        assertFalse(failure.diagnostic.malformed)
        assertSafe(failure)
    }

    @Test
    fun `EIR08 malformed ambiguous or oversized envelopes never expose a partially parsed reason`() {
        val duplicate =
            """{"error":{"status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","domain":"googleapis.com","reason":"API_KEY_INVALID","reason":"SERVICE_DISABLED"}]}}"""
        val valid = envelope(listOf(detail("API_KEY_INVALID")))
        val bodies =
            listOf(
                byteArrayOf(),
                duplicate.toByteArray(),
                valid + " $PRIVATE".toByteArray(),
                gzip(valid).dropLast(4).toByteArray(),
                envelope(listOf(detail("API_KEY_INVALID")), message = PRIVATE.repeat(2_000)),
            )
        for (body in bodies) {
            val failure = decode(body)
            assertEquals(ProviderErrorInfoReason.UNKNOWN, failure.diagnostic.errorInfoReason)
            assertTrue(failure.diagnostic.bodyBytes <= 16_385)
            if (body.size > 16_384) assertTrue(failure.diagnostic.bodyLimitReached)
            assertSafe(failure)
        }
    }

    @Test
    fun `EIR09 structured reason does not override actual HTTP failure classification or retry policy`() {
        val cases =
            mapOf(
                400 to ParserFailureKind.INVALID_REQUEST,
                401 to ParserFailureKind.AUTHENTICATION,
                403 to ParserFailureKind.PERMISSION,
                503 to ParserFailureKind.SERVER,
                529 to ParserFailureKind.SERVER,
            )
        for ((status, expected) in cases) {
            val safe = ProviderRejectionDiagnostics.decode(AnalysisProvider.GEMINI, status, envelope(listOf(detail("BILLING_DISABLED"))))
            assertEquals(status, safe.diagnostic.httpStatus)
            assertEquals(ProviderErrorInfoReason.BILLING_DISABLED, safe.diagnostic.errorInfoReason)
            val kind = ProviderFailureClassifier.classifyStatus(status, null)
            val typed = NaturalLanguageParserException(kind, cause = safe)
            assertEquals(expected, kind)
            assertEquals(expected, ProviderFailureClassifier.classify(typed))
            assertEquals(status in setOf(503, 529), typed.retryable)
            assertFalse(typed.stackTraceToString().contains(PRIVATE))
            assertFalse(typed.stackTraceToString().contains(SECRET))
            assertSafe(safe)
        }
    }

    private fun decode(body: ByteArray) = ProviderRejectionDiagnostics.decode(AnalysisProvider.GEMINI, 400, body)

    private fun envelope(
        details: List<Map<String, Any?>>,
        message: String = PRIVATE,
    ): ByteArray =
        mapper.writeValueAsBytes(
            mapOf(
                "error" to mapOf("code" to 400, "status" to "INVALID_ARGUMENT", "message" to message, "details" to details),
            ),
        )

    private fun detail(
        reason: Any?,
        domain: Any? = "googleapis.com",
        type: Any? = ERROR_INFO_TYPE,
    ): Map<String, Any?> =
        linkedMapOf(
            "@type" to type,
            "domain" to domain,
            "reason" to reason,
            "metadata" to
                mapOf(
                    "api_key" to SECRET,
                    "consumer" to "projects/$PRIVATE",
                    "service" to "private-service.example.invalid",
                    "description" to "Unsupported parameter: candidateCount $PRIVATE",
                ),
        )

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

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
        assertFalse(exposed.contains("googleapis.com"))
        assertFalse(exposed.contains("google.rpc.ErrorInfo"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val PRIVATE = "OFFLINE_PRIVATE_ERROR_INFO_TEXT_1946"
        private const val SECRET = "sk-offline-error-info-secret-1946"
        private const val ERROR_INFO_TYPE = "type.googleapis.com/google.rpc.ErrorInfo"

        // Exact constants confirmed from the official common-protos Api.ErrorReason reference by the parent.
        private val documentedReasons =
            listOf(
                "API_KEY_INVALID",
                "SERVICE_DISABLED",
                "BILLING_DISABLED",
                "API_KEY_SERVICE_BLOCKED",
                "API_KEY_HTTP_REFERRER_BLOCKED",
                "API_KEY_IP_ADDRESS_BLOCKED",
                "RATE_LIMIT_EXCEEDED",
                "RESOURCE_QUOTA_EXCEEDED",
            )
    }
}
