package com.meetme.server.coordination.adapter.output.integration

import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserClientCallObservation
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException

internal class ParserTransportObservationSession(
    private val context: ParserObservationContext?,
    private val provider: AnalysisProvider,
    private val recorder: ParserTransportObservationPort?,
) {
    private val calls = mutableListOf<PendingCall>()
    private var overflow = false
    private var finished = false

    val eventListenerFactory: EventListener.Factory
        get() =
            EventListener.Factory {
                object : EventListener() {
                    private var pending: PendingCall? = null

                    override fun callStart(call: Call) {
                        synchronized(this@ParserTransportObservationSession) {
                            if (finished || context == null || recorder == null) return
                            if (calls.size == ParserTransportSnapshot.MAX_CLIENT_CALLS) {
                                overflow = true
                                return
                            }
                            pending = PendingCall(calls.size + 1, System.nanoTime()).also { calls += it }
                        }
                    }

                    override fun responseHeadersEnd(
                        call: Call,
                        response: Response,
                    ) {
                        synchronized(this@ParserTransportObservationSession) {
                            pending?.status = response.code.takeIf { it in 100..599 }
                        }
                    }

                    override fun callEnd(call: Call) = settle(ParserClientCallOutcome.COMPLETED)

                    override fun callFailed(
                        call: Call,
                        ioe: IOException,
                    ) = settle(ParserClientCallOutcome.FAILED)

                    private fun settle(outcome: ParserClientCallOutcome) {
                        synchronized(this@ParserTransportObservationSession) {
                            pending?.let { entry ->
                                if (entry.observation == null) {
                                    entry.observation =
                                        ParserClientCallObservation(
                                            entry.number,
                                            (System.nanoTime() - entry.started).coerceAtLeast(0),
                                            outcome,
                                            entry.status,
                                        )
                                }
                            }
                        }
                    }
                }
            }

    fun finish(parserDispatched: Boolean = true) {
        val snapshot =
            synchronized(this) {
                if (finished) return
                finished = true
                val linked = context ?: return
                if (overflow || calls.any { it.observation == null } || !parserDispatched && calls.isNotEmpty()) return
                ParserTransportSnapshot(linked, provider, parserDispatched, calls.map { requireNotNull(it.observation) })
            }
        try {
            recorder?.record(snapshot)
        } catch (_: Exception) {
            // Missing persistence remains unknown. Observability never changes the provider result.
        }
    }

    private class PendingCall(
        val number: Int,
        val started: Long,
    ) {
        var status: Int? = null
        var observation: ParserClientCallObservation? = null
    }
}
