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

/** Explicit, bounded, same-thread inspection only. Synthetic data; no provider or HTTP requests. */
class ProviderRejectionMessageObserverContractTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `RMO01 default diagnostics never retain message and a closed lease stops delivery`() {
        val text = "invalid schema: $PRIVATE"
        val baseline = decode(body(text))
        assertPrivate(baseline)
        val observed = mutableListOf<String>()
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            val diagnostic = decode(body(text))
            assertEquals(listOf(text), observed)
            assertEquals(baseline.diagnostic, diagnostic.diagnostic)
            assertPrivate(diagnostic)
        }
        decode(body(text))
        assertEquals(listOf(text), observed)
    }

    @Test
    fun `RMO02 only Gemini actual HTTP400 is observable and other diagnostics stay unchanged`() {
        val observed = mutableListOf<String>()
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            for ((provider, status) in listOf(
                AnalysisProvider.OPENAI to 400,
                AnalysisProvider.GEMINI to 401,
                AnalysisProvider.GEMINI to 403,
                AnalysisProvider.GEMINI to 429,
                AnalysisProvider.GEMINI to 503,
                AnalysisProvider.GEMINI to 529,
            )) {
                val diagnostic = ProviderRejectionDiagnostics.decode(provider, status, body(PRIVATE))
                assertEquals(status, diagnostic.diagnostic.httpStatus)
                assertEquals(provider, diagnostic.diagnostic.provider)
                assertPrivate(diagnostic)
            }
            assertTrue(observed.isEmpty())
            decode(body("invalid schema"))
            assertEquals(listOf("invalid schema"), observed)
        }
    }

    @Test
    fun `RMO03 an explicit lease neither observes nor installs a lease on another thread`() {
        val parent = mutableListOf<String>()
        val child = mutableListOf<String>()
        val childFailure = AtomicReference<Throwable?>()
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { parent.add(it) }.use {
            val worker =
                Thread {
                    try {
                        decode(body("without child lease"))
                        ProviderRejectionDiagnostics.observeMessageForCurrentThread { child.add(it) }.use {
                            decode(body("invalid child schema"))
                        }
                        decode(body("after child lease"))
                    } catch (failure: Throwable) {
                        childFailure.set(failure)
                    }
                }
            worker.start()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertNull(childFailure.get())
            assertTrue(parent.isEmpty())
            assertEquals(listOf("invalid child schema"), child)
            decode(body("invalid parent schema"))
            assertEquals(listOf("invalid parent schema"), parent)
        }
    }

    @Test
    fun `RMO04 nested installation is rejected without displacing the current lease`() {
        val observed = mutableListOf<String>()
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            assertFailsWith<IllegalStateException> {
                ProviderRejectionDiagnostics.observeMessageForCurrentThread { error("must not install") }
            }
            decode(body("invalid schema"))
            assertEquals(listOf("invalid schema"), observed)
        }
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            decode(body("nullable schema"))
        }
        assertEquals(listOf("invalid schema", "nullable schema"), observed)
    }

    @Test
    fun `RMO05 foreign close is rejected and repeated stale close cannot remove a newer lease`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val lease = ProviderRejectionDiagnostics.observeMessageForCurrentThread { first.add(it) }
        try {
            val foreignFailure = AtomicReference<Throwable?>()
            val worker =
                Thread {
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
            decode(body("first schema"))
            assertEquals(listOf("first schema"), first)
            lease.close()
            ProviderRejectionDiagnostics.observeMessageForCurrentThread { second.add(it) }.use {
                lease.close()
                decode(body("second schema"))
            }
            assertEquals(listOf("second schema"), second)
        } finally {
            lease.close()
        }
    }

    @Test
    fun `RMO06 observer failure never changes the original diagnostic or leaks its throwable`() {
        val bytes = body("invalid schema: $PRIVATE")
        val baseline = decode(bytes)
        ProviderRejectionDiagnostics
            .observeMessageForCurrentThread {
                throw IllegalArgumentException("$PRIVATE observer must not escape")
            }.use {
                val actual = decode(bytes)
                assertEquals(baseline.diagnostic, actual.diagnostic)
                assertFalse(actual.diagnostic.malformed)
                assertPrivate(actual)
            }
        val observed = mutableListOf<String>()
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            decode(body("invalid schema"))
        }
        assertEquals(listOf("invalid schema"), observed)
    }

    @Test
    fun `RMO07 nonstring absent malformed duplicate and trailing messages are never observable`() {
        val observed = mutableListOf<String>()
        val invalidBodies =
            listOf(
                body(null),
                body(400),
                body(listOf(PRIVATE)),
                body(mapOf("text" to PRIVATE)),
                "{}".toByteArray(),
                "{broken".toByteArray(),
                """{"error":{"message":"$PRIVATE","message":"invalid schema"}}""".toByteArray(),
                body(PRIVATE) + " true".toByteArray(),
            )
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            invalidBodies.forEach { assertPrivate(decode(it)) }
        }
        assertTrue(observed.isEmpty())
    }

    @Test
    fun `RMO08 message and raw or decompressed body bounds hold before observation`() {
        val observed = mutableListOf<String>()
        val message = "x".repeat(3_000)
        val bounded = body(message)
        val oversized = body(PRIVATE, "x".repeat(17_000))
        assertTrue(oversized.size > 16_384)
        ProviderRejectionDiagnostics.observeMessageForCurrentThread { observed.add(it) }.use {
            decode(bounded)
            assertEquals(listOf(message.take(2_048)), observed)
            decode(gzip(body("invalid schema")))
            assertEquals(listOf(message.take(2_048), "invalid schema"), observed)
            decode(oversized)
            decode(gzip(oversized))
            decode(gzip(body(PRIVATE)).dropLast(4).toByteArray())
            assertEquals(listOf(message.take(2_048), "invalid schema"), observed)
        }
    }

    private fun body(
        message: Any?,
        padding: String = "",
    ): ByteArray =
        mapper.writeValueAsBytes(
            mapOf("error" to mapOf("status" to "INVALID_ARGUMENT", "message" to message, "padding" to padding)),
        )

    private fun decode(bytes: ByteArray) = ProviderRejectionDiagnostics.decode(AnalysisProvider.GEMINI, 400, bytes)

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(bytes) }
            output.toByteArray()
        }

    private fun assertPrivate(failure: ProviderRejectionDiagnosticException) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        val surfaces =
            listOf(
                failure.toString(),
                failure.message.orEmpty(),
                failure.stackTraceToString(),
                mapper.writeValueAsString(failure.diagnostic),
            )
        for (surface in surfaces) {
            assertFalse(surface.contains(PRIVATE))
            assertFalse(surface.contains("observer must not escape"))
        }
    }

    companion object {
        private const val PRIVATE = "synthetic-private-marker-9a1f"
    }
}
