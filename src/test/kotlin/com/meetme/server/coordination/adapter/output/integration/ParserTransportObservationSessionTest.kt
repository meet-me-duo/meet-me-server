package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #110 transport: local call lifecycle facts, never provider receipt or billing claims. No network executes here. */
class ParserTransportObservationSessionTest {
    private val context = ParserObservationContext(UUID(0, 110), UUID(0, 111))
    private val client = OkHttpClient()

    @Test
    fun `settled calls retain start order status and bounded failure without exception content`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        val session = session(snapshots)
        val first = call()
        val second = call()
        val firstListener = session.eventListenerFactory.create(first)
        val secondListener = session.eventListenerFactory.create(second)
        firstListener.callStart(first)
        secondListener.callStart(second)
        secondListener.responseHeadersEnd(second, response(second, 503))
        secondListener.callFailed(second, IOException("synthetic-private-body-token-url"))
        firstListener.responseHeadersEnd(first, response(first, 200))
        firstListener.callEnd(first)

        session.finish()

        val observed = snapshots.single()
        assertEquals(context, observed.context)
        assertEquals(AnalysisProvider.GEMINI, observed.provider)
        assertTrue(observed.parserDispatched)
        assertEquals(listOf(1, 2), observed.calls.map { it.callNumber })
        assertEquals(listOf(200, 503), observed.calls.map { it.httpStatus })
        assertEquals(listOf(ParserClientCallOutcome.COMPLETED, ParserClientCallOutcome.FAILED), observed.calls.map { it.outcome })
        assertTrue(observed.calls.all { it.elapsedNanos >= 0 })
        assertFalse(observed.toString().contains("synthetic-private"))
    }

    @Test
    fun `call start followed by connection failure is one local client call with unknown HTTP status`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        val session = session(snapshots)
        val call = call()
        val listener = session.eventListenerFactory.create(call)
        listener.callStart(call)
        listener.callFailed(call, IOException("synthetic-private-DNS-message"))
        session.finish()
        val observed = snapshots.single().calls.single()
        assertEquals(1, observed.callNumber)
        assertEquals(ParserClientCallOutcome.FAILED, observed.outcome)
        assertNull(observed.httpStatus)
        assertTrue(observed.elapsedNanos >= 0)
    }

    @Test
    fun `parser entry without any started HTTP call is a known zero snapshot`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        session(snapshots).finish()
        assertTrue(snapshots.single().parserDispatched)
        assertTrue(snapshots.single().calls.isEmpty())
    }

    @Test
    fun `explicit admission not dispatched is known false with no client calls`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        session(snapshots).finish(parserDispatched = false)
        assertFalse(snapshots.single().parserDispatched)
        assertTrue(snapshots.single().calls.isEmpty())
    }

    @Test
    fun `unfinished started call leaves observation absent instead of fabricating zero or completeness`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        val session = session(snapshots)
        val call = call()
        session.eventListenerFactory.create(call).callStart(call)
        session.finish()
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `overflow of bounded calls leaves snapshot unknown rather than silently truncating count`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        val session = session(snapshots)
        repeat(ParserTransportSnapshot.MAX_CLIENT_CALLS + 1) {
            val call = call()
            val listener = session.eventListenerFactory.create(call)
            listener.callStart(call)
            listener.responseHeadersEnd(call, response(call, 200))
            listener.callEnd(call)
        }
        session.finish()
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `legacy missing context cannot be attached to a guessed invocation`() {
        val snapshots = mutableListOf<ParserTransportSnapshot>()
        ParserTransportObservationSession(null, AnalysisProvider.GEMINI, ParserTransportObservationPort { snapshots += it }).finish()
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `telemetry failure cannot escape completed session boundary`() {
        var attempts = 0
        val session =
            ParserTransportObservationSession(
                context,
                AnalysisProvider.GEMINI,
                ParserTransportObservationPort {
                    attempts++
                    error("synthetic-private-database-message")
                },
            )
        session.finish()
        assertEquals(1, attempts)
    }

    @Test
    fun `observation models have no request response exception URL or header fields`() {
        fun fields(type: Class<*>): Set<String> =
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .toSet()
        assertEquals(setOf("context", "provider", "parserDispatched", "calls"), fields(ParserTransportSnapshot::class.java))
        assertEquals(
            setOf("callNumber", "elapsedNanos", "outcome", "httpStatus"),
            fields(com.meetme.server.coordination.application.port.output.ParserClientCallObservation::class.java),
        )
        assertEquals(setOf("invocationId", "attemptId"), fields(ParserObservationContext::class.java))
    }

    private fun session(snapshots: MutableList<ParserTransportSnapshot>) =
        ParserTransportObservationSession(
            context,
            AnalysisProvider.GEMINI,
            ParserTransportObservationPort {
                snapshots +=
                    it
            },
        )

    private fun call(): Call =
        client.newCall(Request.Builder().url("http://127.0.0.1:9/synthetic-private-path?token=synthetic-private-query").build())

    private fun response(
        call: Call,
        status: Int,
    ): Response =
        Response
            .Builder()
            .request(
                call.request(),
            ).protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("synthetic-private-response-message")
            .build()
}
