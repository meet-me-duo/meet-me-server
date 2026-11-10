package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Explicit transient inspection only; all content synthetic, no HTTP, persistence or provider request. */
class ProviderRejectionErrorObserverContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `REO01 explicit lease preserves actual message details code and absent fields without enriching diagnostics`() {
        val error =
            linkedMapOf<String, Any?>(
                "code" to 400,
                "status" to "INVALID_ARGUMENT",
                "message" to "Request contains an invalid argument.",
                "details" to
                    listOf(
                        mapOf(
                            "@type" to "type.googleapis.com/google.rpc.BadRequest",
                            "fieldViolations" to
                                listOf(
                                    mapOf(
                                        "field" to "generation_config.response_json_schema.properties.items.maxItems",
                                        "description" to "maxItems 0 must be at least 1",
                                    ),
                                ),
                        ),
                    ),
            )
        val bytes = body(error)
        val baseline = decode(bytes)
        val observed = mutableListOf<Map<String, Any?>>()
        decode(bytes)
        assertTrue(observed.isEmpty(), "No default observer or persistent recording is installed")
        ProviderRejectionDiagnostics.observeErrorForCurrentThread { observed.add(it) }.use {
            assertEquals(baseline.diagnostic, decode(bytes).diagnostic)
            assertEquals(error, observed.single())
            decode(body(mapOf("status" to "INVALID_ARGUMENT")))
            assertFalse(observed.last().containsKey("message"))
            assertFalse(observed.last().containsKey("details"))
            decode(body(mapOf("message" to null, "details" to null)))
            assertTrue(observed.last().containsKey("message"))
            assertNull(observed.last()["message"])
            assertTrue(observed.last().containsKey("details"))
            assertNull(observed.last()["details"])
        }
        val delivered = observed.size
        decode(bytes)
        assertEquals(delivered, observed.size, "Closed lease stops observation")
        assertFalse(baseline.toString().contains("Request contains"))
        assertFalse(baseline.toString().contains("must be at least"))
    }

    @Test
    fun `REO02 parsed error objects are bounded before observation including gzip and all HTTP error providers`() {
        val observed = mutableListOf<Map<String, Any?>>()
        val normal = body(mapOf("message" to "schema maxItems must be at least 1", "details" to listOf(mapOf("limit" to 1))))
        val oversized = body(mapOf("message" to "x".repeat(17_000)))
        ProviderRejectionDiagnostics.observeErrorForCurrentThread { observed.add(it) }.use {
            for ((provider, status) in listOf(
                AnalysisProvider.GEMINI to 400,
                AnalysisProvider.OPENAI to 400,
                AnalysisProvider.GEMINI to 503,
            )) {
                ProviderRejectionDiagnostics.decode(provider, status, gzip(normal))
            }
            assertEquals(3, observed.size)
            listOf(
                oversized,
                gzip(oversized),
                "{broken".toByteArray(),
                "{}".toByteArray(),
                """{"error":{"message":"a","message":"b"}}""".toByteArray(),
                normal + " true".toByteArray(),
            ).forEach { decode(it) }
            assertEquals(3, observed.size, "Malformed or over-limit bodies are not partially delivered")
        }
    }

    @Test
    fun `REO03 deep immutable copied observations and callback exceptions cannot modify classification`() {
        val error =
            mapOf(
                "status" to "INVALID_ARGUMENT",
                "message" to "unsupported candidateCount",
                "details" to listOf(mapOf("field" to "candidateCount")),
            )
        val bytes = body(error)
        val baseline = decode(bytes)
        var retained: Map<String, Any?>? = null
        ProviderRejectionDiagnostics
            .observeErrorForCurrentThread { seen ->
                retained = seen
                assertTrue(runCatching { mutable(seen)["status"] = "INTERNAL" }.isFailure)
                assertTrue(runCatching { mutable((seen["details"] as List<*>).single())["field"] = "temperature" }.isFailure)
                assertTrue(runCatching { mutableList(seen["details"]).clear() }.isFailure)
                throw IllegalArgumentException("SYNTHETIC_OBSERVER_PRIVATE_EXCEPTION")
            }.use {
                assertEquals(baseline.diagnostic, decode(bytes).diagnostic)
                assertEquals(error, retained)
            }
        assertEquals(baseline.diagnostic, decode(bytes).diagnostic)
        assertFalse(baseline.toString().contains("SYNTHETIC_OBSERVER"))
    }

    @Test
    fun `REO04 error lease is thread owned rejects nesting cleans up and coexists with old message lease`() {
        val observed = mutableListOf<Map<String, Any?>>()
        val messages = mutableListOf<String>()
        val lease = ProviderRejectionDiagnostics.observeErrorForCurrentThread { observed.add(it) }
        try {
            assertFailsWith<IllegalStateException> { ProviderRejectionDiagnostics.observeErrorForCurrentThread {} }
            val foreignFailure = AtomicReference<Throwable?>()
            val worker =
                Thread {
                    decode(body(mapOf("message" to "unleased foreign thread")))
                    try {
                        lease.close()
                    } catch (failure: Throwable) {
                        foreignFailure.set(failure)
                    }
                }
            worker.start()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(foreignFailure.get() is IllegalStateException)
            assertTrue(observed.isEmpty())
            ProviderRejectionDiagnostics.observeMessageForCurrentThread { messages.add(it) }.use {
                decode(body(mapOf("message" to "Request contains an invalid argument.")))
            }
            assertEquals(1, observed.size)
            assertEquals(listOf("Request contains an invalid argument."), messages)
        } finally {
            lease.close()
        }
        ProviderRejectionDiagnostics.observeErrorForCurrentThread { observed.add(it) }.use {
            lease.close()
            decode(body(mapOf("message" to "new lease after cleanup")))
        }
        assertEquals(2, observed.size)
    }

    private fun body(error: Map<String, Any?>): ByteArray = mapper.writeValueAsBytes(mapOf("error" to error))

    private fun decode(bytes: ByteArray) = ProviderRejectionDiagnostics.decode(AnalysisProvider.GEMINI, 400, bytes)

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also {
                GZIPOutputStream(it).use { gzip -> gzip.write(bytes) }
            }.toByteArray()

    @Suppress("UNCHECKED_CAST")
    private fun mutable(value: Any?) = value as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun mutableList(value: Any?) = value as MutableList<Any?>
}
