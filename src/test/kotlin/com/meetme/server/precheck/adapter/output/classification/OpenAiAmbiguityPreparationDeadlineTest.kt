package com.meetme.server.precheck.adapter.output.classification

import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.Endpoint
import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.envelope
import com.meetme.server.precheck.adapter.output.classification.DecisionClassificationFixtures.request
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Preparation is part of the same absolute250ms budget; this test never sends HTTP. */
class OpenAiAmbiguityPreparationDeadlineTest {
    @Test
    fun `DCSP01 preparation expiration ends before transport admission and records only safe failure metrics`() {
        Endpoint().use { endpoint ->
            val delayedMapper = DelayOnePayloadMapper()
            val transportCalls = AtomicInteger()
            // A deadline-start-after-payload mutant must not be masked by loopback scheduling delay.
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        transportCalls.incrementAndGet()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(envelope().toResponseBody("application/json".toMediaType()))
                            .build()
                    }.build()
            val classifier = endpoint.bodyDeadlineClassifier(request, requestMapper = delayedMapper, client = client)
            assertEquals(0, delayedMapper.delayedCalls.get())
            delayedMapper.arm()
            val started = System.nanoTime()
            val failure =
                assertFailsWith<AmbiguityClassificationException> {
                    classifier.classify(request.copy(timeout = Duration.ofMillis(250)))
                }
            assertEquals("TIMEOUT", failure.failureCode)
            assertEquals(1, delayedMapper.delayedCalls.get())
            assertEquals(0, transportCalls.get())
            assertTrue(endpoint.sent.isEmpty())
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
            assertEquals(
                0.0,
                endpoint.registry
                    .find("meetme.precheck.classification.http.attempts")
                    .counter()
                    ?.count() ?: 0.0,
            )
            assertEquals(
                1.0,
                endpoint.registry
                    .find("meetme.precheck.classification.results")
                    .tags("outcome", "FAILED", "failure", "TIMEOUT")
                    .counter()
                    ?.count(),
            )
            assertEquals(
                1L,
                endpoint.registry
                    .find("meetme.precheck.classification.duration")
                    .timer()
                    ?.count(),
            )
            for (direction in listOf("input", "output")) {
                assertEquals(
                    0.0,
                    endpoint.registry
                        .find("meetme.precheck.classification.usage.$direction.unknown")
                        .counter()
                        ?.count() ?: 0.0,
                )
                assertEquals(
                    0L,
                    endpoint.registry
                        .find("meetme.precheck.classification.usage.$direction.tokens")
                        .summary()
                        ?.count() ?: 0L,
                )
            }
            assertEquals(
                0.0,
                endpoint.registry
                    .find("meetme.precheck.classification.usage.incomplete")
                    .counter()
                    ?.count() ?: 0.0,
            )
        }
    }

    private class DelayOnePayloadMapper : JsonMapper() {
        private val armed = AtomicBoolean(false)
        val delayedCalls = AtomicInteger()

        fun arm() {
            armed.set(true)
        }

        override fun writeValueAsBytes(value: Any?): ByteArray {
            if (armed.compareAndSet(true, false)) {
                delayedCalls.incrementAndGet()
                // A single fixed bounded preparation pause, not a retry or a changed product timeout.
                check(!CountDownLatch(1).await(350, TimeUnit.MILLISECONDS))
            }
            return super.writeValueAsBytes(value)
        }
    }
}
