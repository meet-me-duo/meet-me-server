package com.meetme.server.precheck.adapter.output.provider

import com.meetme.server.config.OpenAiProperties
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider
import com.meetme.server.precheck.application.port.output.PrecheckProviderException
import com.meetme.server.precheck.application.port.output.PrecheckProviderRequest
import com.meetme.server.precheck.application.port.output.PrecheckProviderResult
import com.meetme.server.precheck.application.port.output.PrecheckVerdict
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RR01-05: actual production bean and real environment binding; intercepted transport never opens a socket. */
class PrecheckRuntimeBindingContractTest {
    private val mapper: ObjectMapper = JsonMapper.builder().build()
    private val input =
        PrecheckProviderRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000201"),
            "내일 저녁 7~8시 가능",
            Instant.parse("2026-10-17T10:00:00Z"),
            ZoneId.of("Asia/Seoul"),
            "ko-KR",
            LocalDate.of(2026, 10, 17),
            LocalDate.of(2026, 10, 31),
            Duration.ofSeconds(2),
        )

    @ParameterizedTest
    @ValueSource(strings = ["ABSENT", "false"])
    fun `RR01 default or explicitly OFF production bean ignores a key and arbitrary verified flags`(enabled: String) {
        val capture = Capture()
        val env =
            linkedMapOf(
                "MEETME_PRECHECK_PROVIDER_TOTAL_OUTPUT_TOKEN_CAP_VERIFIED" to "true",
                "MEETME_PRECHECK_PROVIDER_VERIFIED" to "true",
                "MEETME_PRECHECK_PROVIDER_PROFILE" to "anything-is-verified",
            )
        if (enabled != "ABSENT") env["MEETME_PRECHECK_PROVIDER_ENABLED"] = enabled
        runner(capture, env).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
            val provider = context.getBean(PreSubmissionCheckProvider::class.java)
            assertIs<LunaPreSubmissionCheckProvider>(provider)
            assertEquals("CONFIGURATION", assertFailsWith<PrecheckProviderException> { provider.check(input) }.failureCode)
            assertTrue(capture.sent.isEmpty())
            assertEquals(0.0, attempts(capture.registry, "COUNT_TOKENS"))
            assertEquals(0.0, attempts(capture.registry, "GENERATE_CONTENT"))
        }
    }

    @Test
    fun `RR02 actual environment opt in selects only the documented fixed production profile`() {
        val capture = Capture()
        runner(
            capture,
            mapOf(
                "MEETME_PRECHECK_PROVIDER_ENABLED" to "true",
                "MEETME_PRECHECK_PROVIDER_ENDPOINT" to "http://127.0.0.1:1/evil",
                "MEETME_PRECHECK_PROVIDER_MODEL" to "arbitrary-model",
                "MEETME_PRECHECK_PROVIDER_MAX_OUTPUT_TOKENS" to "999999",
                "MEETME_PRECHECK_PROVIDER_THINKING_BUDGET" to "0",
            ),
        ).run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
            val provider = context.getBean(PreSubmissionCheckProvider::class.java)
            assertIs<LunaPreSubmissionCheckProvider>(provider)
            val result =
                org.junit.jupiter.api.Assertions
                    .assertDoesNotThrow<PrecheckProviderResult> { provider.check(input) }
            assertEquals(PrecheckVerdict.CLEAR, result.verdict)
            assertEquals(256L, result.inputTokens)
            assertEquals(25L, result.outputTokens)
            assertEquals(1, capture.sent.size)
            capture.sent.forEach {
                assertEquals("https", it.scheme)
                assertEquals("api.openai.com", it.host)
                assertEquals("POST", it.method)
                assertNull(it.query)
                assertEquals("Bearer synthetic-runtime-key", it.key)
            }
            assertEquals("/v1/responses", capture.sent.single().path)
            assertLunaProfile(capture.sent.single().body)
            assertEquals(0.0, attempts(capture.registry, "COUNT_TOKENS"))
            assertEquals(1.0, attempts(capture.registry, "GENERATE_CONTENT"))
        }
    }

    @Test
    fun `RR03 property binding can opt in without an arbitrary capability verification setting`() {
        val capture = Capture()
        runner(capture)
            .withPropertyValues("meetme.precheck.provider.enabled=true")
            .run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBean(PrecheckProviderRuntimeProperties::class.java).enabled)
                val provider = context.getBean(PreSubmissionCheckProvider::class.java)
                assertEquals(
                    PrecheckVerdict.CLEAR,
                    org.junit.jupiter.api.Assertions
                        .assertDoesNotThrow<PrecheckProviderResult> { provider.check(input) }
                        .verdict,
                )
                assertEquals(1, capture.sent.size)
            }
    }

    @Test
    fun `RR04 malformed enabled value does not create a permissive production provider`() {
        val capture = Capture()
        runner(capture, mapOf("MEETME_PRECHECK_PROVIDER_ENABLED" to "definitely-not-a-boolean")).run { context ->
            assertTrue(context.startupFailure != null)
            assertTrue(capture.sent.isEmpty())
        }
    }

    @Test
    fun `RR05 production opt in cannot change the output cap through a request`() {
        val capture = Capture()
        runner(capture, mapOf("MEETME_PRECHECK_PROVIDER_ENABLED" to "true")).run { context ->
            assertNull(context.startupFailure)
            val provider = context.getBean(PreSubmissionCheckProvider::class.java)
            assertEquals(
                "CONFIGURATION",
                assertFailsWith<PrecheckProviderException> { provider.check(input.copy(maximumOutputTokens = 2049)) }.failureCode,
            )
            assertTrue(capture.sent.isEmpty())
        }
    }

    @Test
    fun `RR06 production calls enforce the fifteen second ceiling without a real network wait`() {
        val capture = Capture()
        runner(capture, mapOf("MEETME_PRECHECK_PROVIDER_ENABLED" to "true")).run { context ->
            assertNull(context.startupFailure)
            val provider = context.getBean(PreSubmissionCheckProvider::class.java)
            org.junit.jupiter.api.Assertions.assertDoesNotThrow<PrecheckProviderResult> {
                provider.check(input.copy(timeout = Duration.ofSeconds(30)))
            }
            assertEquals(1, capture.sent.size)
            capture.sent.forEach {
                assertTrue(it.callTimeoutNanos in 1..Duration.ofSeconds(15).toNanos())
            }
        }
    }

    private fun runner(
        capture: Capture,
        environment: Map<String, String> = emptyMap(),
    ) = ApplicationContextRunner()
        .withUserConfiguration(PrecheckProviderConfiguration::class.java)
        .withBean(OpenAiProperties::class.java, Supplier { OpenAiProperties(apiKey = "synthetic-runtime-key") })
        .withBean(ObjectMapper::class.java, Supplier { mapper })
        .withBean(MeterRegistry::class.java, Supplier { capture.registry })
        .withBean(PrecheckProviderHttpTransport::class.java, Supplier { capture.transport })
        .withInitializer { context ->
            context.environment.propertySources.addFirst(SystemEnvironmentPropertySource("synthetic-runtime-environment", environment))
        }

    private fun assertLunaProfile(body: String) {
        val wire = mapper.readTree(body)
        assertEquals("gpt-6-luna", wire.path("model").asText())
        assertEquals("default", wire.path("service_tier").asText())
        assertFalse(wire.path("store").asBoolean())
        assertEquals("low", wire.path("reasoning").path("effort").asText())
        assertEquals(2048, wire.path("max_output_tokens").asInt())
        assertTrue(
            wire
                .path("text")
                .path("format")
                .path("strict")
                .asBoolean(),
        )
        assertEquals(input.rawText, mapper.readTree(wire.path("input").asText()).path("raw_text").asText())
    }

    private fun attempts(
        registry: MeterRegistry,
        operation: String,
    ) = registry
        .find("meetme.precheck.provider.http.attempts")
        .tag("operation", operation)
        .counter()
        ?.count() ?: 0.0

    private data class Sent(
        val scheme: String,
        val host: String,
        val path: String,
        val method: String,
        val query: String?,
        val key: String?,
        val body: String,
        val callTimeoutNanos: Long,
    )

    private inner class Capture {
        val registry = SimpleMeterRegistry()
        val sent = CopyOnWriteArrayList<Sent>()
        val transport =
            PrecheckProviderHttpTransport(
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        sent +=
                            Sent(
                                request.url.scheme,
                                request.url.host,
                                request.url.encodedPath,
                                request.method,
                                request.url.query,
                                request.header("Authorization"),
                                buffer.readUtf8(),
                                chain.call().timeout().timeoutNanos(),
                            )
                        val body =
                            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"verdict\":\"CLEAR\",\"prompt\":null,\"sourceExcerpt\":null,\"choices\":null}"}]}],"usage":{"input_tokens":256,"output_tokens":25,"output_tokens_details":{"reasoning_tokens":0}}}"""
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("synthetic response; no network")
                            .body(body.toResponseBody())
                            .build()
                    }.build(),
            )
    }
}
