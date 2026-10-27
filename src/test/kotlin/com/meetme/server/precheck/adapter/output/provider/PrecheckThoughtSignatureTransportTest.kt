package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.GeminiProperties
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import com.meetme.server.precheck.domain.PrecheckChoice
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Official optional Part bytes metadata. Recorded meaning/usage, synthetic signature only; no paid calls. */
@ExtendWith(OutputCaptureExtension::class)
class PrecheckThoughtSignatureTransportTest {
    private val mapper = JsonMapper.builder().build()
    private val request =
        PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000012303"),
            "10월 24일 토요일 8시부터 9시까지 가능해요.",
            Instant.parse("2026-10-18T04:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 24),
            LocalDate.of(2026, 10, 25),
            Duration.ofSeconds(2),
        )
    private val syntheticBytes = byteArrayOf(0xfb.toByte(), 0xff.toByte()) + "synthetic-only-signature".toByteArray()

    @ParameterizedTest
    @ValueSource(strings = ["ABSENT", "NULL", "EMPTY", "STANDARD_PADDED", "STANDARD_UNPADDED", "URL_PADDED", "URL_UNPADDED"])
    fun `SIG01 optional official bytes metadata preserves exact recorded question and usage and is discarded`(
        scenario: String,
        output: CapturedOutput,
    ) {
        assertEquals(
            "bf0f16ba09b40c680f72f0cfe2093318791fd4b744f3b8712394ddbe859e3c7b",
            MessageDigest.getInstance("SHA-256").digest(recordedPayload.toByteArray()).joinToString("") { "%02x".format(it) },
        )
        Endpoint(envelope(scenario)).use { endpoint ->
            val result = assertDoesNotThrow<PrecheckProviderResult> { endpoint.adapter().check(request) }
            assertEquals(PrecheckVerdict.NEEDS_CHOICE, result.verdict)
            assertEquals("8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요.", result.prompt)
            assertEquals("8시부터 9시까지", result.sourceExcerpt)
            assertEquals(
                listOf(
                    PrecheckChoice("A", "오전 8시부터 9시까지", "10월 24일 토요일 오전 8시부터 9시까지 가능해요."),
                    PrecheckChoice("B", "오후 8시부터 9시까지", "10월 24일 토요일 오후 8시부터 9시까지 가능해요."),
                ),
                result.choices,
            )
            assertEquals(530L, result.inputTokens)
            assertEquals(554L, result.outputTokens)
            assertEquals("GEMINI", result.provider)
            assertEquals("gemini-3.8-flash", result.model)
            endpoint.assertOneCountAndOneGeneration()
            endpoint.assertUsage()
            val serialized = mapper.writeValueAsString(result)
            assertFalse(serialized.contains("thoughtSignature"))
            val signature = signature(scenario)
            if (signature is String && signature.isNotEmpty()) {
                assertFalse(serialized.contains(signature))
                assertFalse(output.all.contains(signature))
                assertTrue(endpoint.sent.none { it.second.contains(signature) })
                assertTrue(endpoint.registry.meters.none { meter -> meter.id.tags.any { it.value.contains(signature) } })
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["NUMBER", "BOOLEAN", "OBJECT", "ARRAY", "BAD_CHARACTER", "BAD_PADDING", "BAD_LENGTH", "WHITESPACE", "MIXED"])
    fun `SIG02 malformed optional bytes metadata fails closed`(scenario: String) {
        assertRejected(envelope(scenario))
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNKNOWN_PART", "THOUGHT_PART", "EXTRA_PART", "EXTRA_CANDIDATE", "DUPLICATE_SIGNATURE"])
    fun `SIG03 metadata compatibility keeps the single final text part and strict JSON envelope`(fault: String) {
        val extra =
            when (fault) {
                "UNKNOWN_PART" -> mapOf("unexpected" to null)
                "THOUGHT_PART" -> mapOf("thought" to true)
                else -> emptyMap()
            }
        var body =
            envelope(
                "STANDARD_PADDED",
                extraPartFields = extra,
                extraPart = fault == "EXTRA_PART",
                extraCandidate =
                    fault == "EXTRA_CANDIDATE",
            )
        if (fault == "DUPLICATE_SIGNATURE") {
            val field = "\"thoughtSignature\":" + mapper.writeValueAsString(signature("STANDARD_PADDED"))
            assertEquals(1, Regex(Regex.escape(field)).findAll(body).count())
            body = body.replace(field, "$field,$field")
        }
        assertRejected(body, knownUsage = fault != "DUPLICATE_SIGNATURE")
    }

    @ParameterizedTest
    @ValueSource(strings = ["WRONG_EXCERPT", "CHOICE_COUNT", "PROPOSAL_501", "UNKNOWN_PAYLOAD", "OUTPUT_CAP"])
    fun `SIG04 optional metadata cannot bypass question privacy source choice text or usage limits`(fault: String) {
        val payload: MutableMap<String, Any?> = mapper.readValue(recordedPayload, MutableMap::class.java) as MutableMap<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val choices = payload["choices"] as List<MutableMap<String, Any?>>
        when (fault) {
            "WRONG_EXCERPT" -> payload["sourceExcerpt"] = "다른 참여자의 합성 원문"
            "CHOICE_COUNT" -> payload["choices"] = choices.take(1)
            "PROPOSAL_501" -> choices.first()["proposedText"] = "가".repeat(501)
            "UNKNOWN_PAYLOAD" -> payload["privateOtherInput"] = "must not expose another participant"
        }
        assertRejected(
            envelope(
                "STANDARD_PADDED",
                text = mapper.writeValueAsString(payload),
                thoughtTokens = if (fault == "OUTPUT_CAP") 1861 else 366,
            ),
            thoughtTokens = if (fault == "OUTPUT_CAP") 1861 else 366,
        )
    }

    private fun assertRejected(
        body: String,
        knownUsage: Boolean = true,
        thoughtTokens: Long = 366,
    ) {
        Endpoint(body).use { endpoint ->
            val exception = assertFailsWith<PrecheckProviderException> { endpoint.adapter().check(request) }
            assertEquals("INVALID_RESPONSE", exception.failureCode)
            endpoint.assertOneCountAndOneGeneration()
            endpoint.assertUsage(knownUsage, thoughtTokens)
        }
    }

    private fun signature(scenario: String): Any? =
        when (scenario) {
            "ABSENT", "NULL" -> null
            "EMPTY" -> ""
            "STANDARD_PADDED" -> Base64.getEncoder().encodeToString(syntheticBytes)
            "STANDARD_UNPADDED" -> Base64.getEncoder().withoutPadding().encodeToString(syntheticBytes)
            "URL_PADDED" -> Base64.getUrlEncoder().encodeToString(syntheticBytes)
            "URL_UNPADDED" -> Base64.getUrlEncoder().withoutPadding().encodeToString(syntheticBytes)
            "NUMBER" -> 7
            "BOOLEAN" -> false
            "OBJECT" -> emptyMap<String, String>()
            "ARRAY" -> emptyList<String>()
            "BAD_CHARACTER" -> "not!base64"
            "BAD_PADDING" -> "A==="
            "BAD_LENGTH" -> "A"
            "WHITESPACE" -> " YQ=="
            "MIXED" -> "+_8="
            else -> error("Unknown synthetic scenario")
        }

    private fun envelope(
        scenario: String,
        text: String = recordedPayload,
        extraPartFields: Map<String, Any?> = emptyMap(),
        extraPart: Boolean = false,
        extraCandidate: Boolean = false,
        thoughtTokens: Int = 366,
    ): String {
        val part = linkedMapOf<String, Any?>("text" to text)
        if (scenario != "ABSENT") part["thoughtSignature"] = signature(scenario)
        part.putAll(extraPartFields)
        val candidate =
            mapOf(
                "finishReason" to "STOP",
                "content" to mapOf("role" to "model", "parts" to if (extraPart) listOf(part, part) else listOf(part)),
            )
        return mapper.writeValueAsString(
            mapOf(
                "candidates" to if (extraCandidate) listOf(candidate, candidate) else listOf(candidate),
                "usageMetadata" to
                    mapOf(
                        "promptTokenCount" to 530,
                        "candidatesTokenCount" to 188,
                        "thoughtsTokenCount" to thoughtTokens,
                        "totalTokenCount" to 530 + 188 + thoughtTokens,
                        "promptTokensDetails" to listOf(mapOf("modality" to "TEXT", "tokenCount" to 530)),
                        "serviceTier" to "standard",
                    ),
                "modelVersion" to "gemini-3.8-flash",
                "responseId" to "synthetic-response-not-the-recorded-id",
            ),
        )
    }

    private inner class Endpoint(
        private val generationBody: String,
    ) : AutoCloseable {
        val registry = SimpleMeterRegistry()
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    sent += exchange.requestURI.path to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val response = if (exchange.requestURI.path.endsWith(":countTokens")) """{"totalTokens":530}""" else generationBody
                    val bytes = response.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun adapter() =
            GeminiPreSubmissionCheckProvider.runtime(
                GeminiProperties(apiKey = "synthetic-metadata-key"),
                mapper,
                PrecheckProviderRuntimeProperties(enabled = true),
                meterRegistry = registry,
                endpoint = URI("http://127.0.0.1:${server.address.port}"),
            )

        fun assertOneCountAndOneGeneration() {
            assertEquals(2, sent.size)
            assertTrue(sent[0].first.endsWith(":countTokens"))
            assertTrue(sent[1].first.endsWith(":generateContent"))
        }

        fun assertUsage(
            known: Boolean = true,
            thoughtTokens: Long = 366,
        ) {
            for ((kind, tokens) in mapOf("INPUT" to 530L, "CANDIDATE" to 188L, "THOUGHT" to thoughtTokens)) {
                val summary =
                    registry
                        .find("meetme.precheck.provider.usage.tokens")
                        .tags("operation", "GENERATE_CONTENT", "kind", kind)
                        .summary()
                assertEquals(if (known) 1L else 0L, summary?.count() ?: 0L)
                assertEquals(if (known) tokens.toDouble() else 0.0, summary?.totalAmount() ?: 0.0)
                val unknown =
                    registry
                        .find("meetme.precheck.provider.usage.unknown")
                        .tags("operation", "GENERATE_CONTENT", "kind", kind)
                        .counter()
                        ?.count() ?: 0.0
                assertEquals(if (known) 0.0 else 1.0, unknown)
            }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
            registry.close()
        }
    }

    private val recordedPayload =
        """
        {
          "verdict": "NEEDS_CHOICE",
          "prompt": "8시부터 9시까지가 오전 시간대인지 오후(저녁) 시간대인지 알려주세요.",
          "sourceExcerpt": "8시부터 9시까지",
          "choices": [
            {
              "id": "A",
              "label": "오전 8시부터 9시까지",
              "proposedText": "10월 24일 토요일 오전 8시부터 9시까지 가능해요."
            },
            {
              "id": "B",
              "label": "오후 8시부터 9시까지",
              "proposedText": "10월 24일 토요일 오후 8시부터 9시까지 가능해요."
            }
          ]
        }
        """.trimIndent()
}
