package com.meetme.server.coordination.adapter.output.integration

import com.google.genai.errors.GenAiIOException
import com.meetme.server.coordination.application.port.output.AnalysisProvider
import com.meetme.server.coordination.application.port.output.ParserClientCallDiagnostic
import com.meetme.server.coordination.application.port.output.ParserClientCallObservation
import com.meetme.server.coordination.application.port.output.ParserClientCallOutcome
import com.meetme.server.coordination.application.port.output.ParserDiagnosticCause
import com.meetme.server.coordination.application.port.output.ParserDiagnosticEvent
import com.meetme.server.coordination.application.port.output.ParserDiagnosticEventKind
import com.meetme.server.coordination.application.port.output.ParserDiagnosticResetCode
import com.meetme.server.coordination.application.port.output.ParserObservationContext
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticPort
import com.meetme.server.coordination.application.port.output.ParserTransportDiagnosticSnapshot
import com.meetme.server.coordination.application.port.output.ParserTransportObservationPort
import com.meetme.server.coordination.application.port.output.ParserTransportSnapshot
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http2.StreamResetException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.Collections
import java.util.IdentityHashMap

internal class ParserTransportObservationSession(
    private val context: ParserObservationContext?,
    private val provider: AnalysisProvider,
    private val recorder: ParserTransportObservationPort?,
    private val diagnosticRecorder: ParserTransportDiagnosticPort? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val calls = mutableListOf<PendingCall>()
    private var overflow = false
    private var finished = false
    private var requestedTimeout: Long? = null
    private var adapterBudget: Long? = null
    private var dispatchDeadline: Long? = null
    private var configurationUnavailable = false

    fun configureTimeouts(
        requestedTimeoutNanos: Long?,
        adapterBudgetNanos: Long,
        dispatchDeadlineNanos: Long?,
    ) {
        if (diagnosticRecorder == null) return
        synchronized(this) {
            requestedTimeout = requestedTimeoutNanos?.coerceAtLeast(0)
            adapterBudget = adapterBudgetNanos.coerceAtLeast(0)
            dispatchDeadline = dispatchDeadlineNanos
            configurationUnavailable = requestedTimeoutNanos == null
        }
    }

    val eventListenerFactory: EventListener.Factory
        get() =
            EventListener.Factory {
                object : EventListener() {
                    private var pending: PendingCall? = null

                    override fun callStart(call: Call) {
                        synchronized(this@ParserTransportObservationSession) {
                            if (finished || context == null || recorder == null && diagnosticRecorder == null) return
                            if (calls.size == ParserTransportSnapshot.MAX_CLIENT_CALLS) {
                                overflow = true
                                return
                            }
                            val now = nanoTime()
                            val diagnostic =
                                if (diagnosticRecorder == null) {
                                    null
                                } else {
                                    DiagnosticCall(requestedTimeout, adapterBudget).apply {
                                        metadataUnavailable = configurationUnavailable
                                    }
                                }
                            pending = PendingCall(calls.size + 1, now, diagnostic).also { calls += it }
                            diagnostic?.apply {
                                event(ParserDiagnosticEventKind.CALL_START, 0)
                                dispatchRemaining = dispatchDeadline?.let { (it - now).coerceAtLeast(0) }
                                capture {
                                    val timeout = call.timeout()
                                    actualTimeout = timeout.timeoutNanos()
                                    startDeadline = if (timeout.hasDeadline()) (timeout.deadlineNanoTime() - now).coerceAtLeast(0) else null
                                }
                            }
                        }
                    }

                    override fun requestHeadersStart(call: Call) {
                        event(ParserDiagnosticEventKind.REQUEST_HEADERS_START) { diagnostic, now ->
                            if (!diagnostic.requestHeadersSeen) {
                                diagnostic.requestHeadersSeen = true
                                diagnostic.capture {
                                    val timeout = call.timeout()
                                    headersDeadline =
                                        if (timeout.hasDeadline()) (timeout.deadlineNanoTime() - now).coerceAtLeast(0) else null
                                }
                            }
                        }
                    }

                    override fun requestHeadersEnd(
                        call: Call,
                        request: Request,
                    ) = event(ParserDiagnosticEventKind.REQUEST_HEADERS_END)

                    override fun responseHeadersStart(call: Call) = event(ParserDiagnosticEventKind.RESPONSE_HEADERS_START)

                    override fun canceled(call: Call) = event(ParserDiagnosticEventKind.CANCELED)

                    override fun responseHeadersEnd(
                        call: Call,
                        response: Response,
                    ) {
                        synchronized(this@ParserTransportObservationSession) {
                            pending?.status = response.code.takeIf { it in 100..599 }
                            event(ParserDiagnosticEventKind.RESPONSE_HEADERS_END)
                        }
                    }

                    override fun callEnd(call: Call) = settle(ParserClientCallOutcome.COMPLETED, call, null)

                    override fun callFailed(
                        call: Call,
                        ioe: IOException,
                    ) = settle(ParserClientCallOutcome.FAILED, call, ioe)

                    private fun event(
                        kind: ParserDiagnosticEventKind,
                        extra: (DiagnosticCall, Long) -> Unit = { _, _ -> },
                    ) {
                        synchronized(this@ParserTransportObservationSession) {
                            if (finished) return
                            val entry = pending ?: return
                            val diagnostic = entry.diagnostic ?: return
                            val now = nanoTime()
                            diagnostic.event(kind, (now - entry.started).coerceAtLeast(0))
                            extra(diagnostic, now)
                        }
                    }

                    private fun settle(
                        outcome: ParserClientCallOutcome,
                        call: Call,
                        failure: IOException?,
                    ) {
                        synchronized(this@ParserTransportObservationSession) {
                            pending?.let { entry ->
                                if (entry.observation == null) {
                                    val elapsed = (nanoTime() - entry.started).coerceAtLeast(0)
                                    entry.diagnostic?.apply {
                                        event(
                                            if (failure ==
                                                null
                                            ) {
                                                ParserDiagnosticEventKind.CALL_END
                                            } else {
                                                ParserDiagnosticEventKind.CALL_FAILED
                                            },
                                            elapsed,
                                        )
                                        if (failure != null) {
                                            capture { canceled = call.isCanceled() }
                                            capture { causes(failure) }
                                        }
                                    }
                                    entry.observation = ParserClientCallObservation(entry.number, elapsed, outcome, entry.status)
                                }
                            }
                        }
                    }
                }
            }

    fun finish(parserDispatched: Boolean = true) {
        val snapshots =
            synchronized(this) {
                if (finished) return
                finished = true
                val linked = context ?: return
                val normal =
                    if (overflow || calls.any { it.observation == null } || !parserDispatched && calls.isNotEmpty()) {
                        null
                    } else {
                        ParserTransportSnapshot(linked, provider, parserDispatched, calls.map { requireNotNull(it.observation) })
                    }
                val diagnostic =
                    if (diagnosticRecorder == null) {
                        null
                    } else {
                        ParserTransportDiagnosticSnapshot(
                            linked,
                            provider,
                            calls.mapNotNull { it.diagnostic?.snapshot(it.number, it.observation != null) },
                            overflow,
                        )
                    }
                normal to diagnostic
            }
        try {
            snapshots.first?.let { recorder?.record(it) }
        } catch (_: Exception) {
            // Missing persistence remains unknown. Observability never changes the provider result.
        }
        try {
            snapshots.second?.let { diagnosticRecorder?.record(it) }
        } catch (_: Exception) {
            // Optional diagnostic export cannot replace the provider result or invoke provider work.
        }
    }

    private class PendingCall(
        val number: Int,
        val started: Long,
        val diagnostic: DiagnosticCall?,
    ) {
        var status: Int? = null
        var observation: ParserClientCallObservation? = null
    }

    private class DiagnosticCall(
        val requested: Long?,
        val budget: Long?,
    ) {
        var actualTimeout: Long? = null
        var startDeadline: Long? = null
        var headersDeadline: Long? = null
        var dispatchRemaining: Long? = null
        var canceled: Boolean? = null
        var requestHeadersSeen = false
        var metadataUnavailable = false
        private val observedEvents = mutableListOf<ParserDiagnosticEvent>()
        private var eventsTruncated = false
        private val observedCauses = mutableListOf<ParserDiagnosticCause>()
        private var causesTruncated = false
        private var resetCode: ParserDiagnosticResetCode? = null

        fun capture(action: DiagnosticCall.() -> Unit) {
            try {
                action()
            } catch (_: Exception) {
                metadataUnavailable = true
            }
        }

        fun event(
            kind: ParserDiagnosticEventKind,
            elapsed: Long,
        ) {
            if (observedEvents.size == ParserClientCallDiagnostic.MAX_EVENTS) {
                eventsTruncated = true
            } else {
                observedEvents += ParserDiagnosticEvent(kind, elapsed)
            }
        }

        fun causes(failure: Throwable) {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = failure
            while (current != null) {
                if (observedCauses.size == ParserClientCallDiagnostic.MAX_CAUSES || !seen.add(current)) {
                    causesTruncated = true
                    break
                }
                val type = current.javaClass
                observedCauses +=
                    when (type) {
                        GenAiIOException::class.java -> ParserDiagnosticCause.GENAI_IO
                        InterruptedIOException::class.java -> ParserDiagnosticCause.INTERRUPTED_IO
                        SocketTimeoutException::class.java -> ParserDiagnosticCause.SOCKET_TIMEOUT
                        HttpTimeoutException::class.java -> ParserDiagnosticCause.HTTP_TIMEOUT
                        StreamResetException::class.java -> ParserDiagnosticCause.STREAM_RESET
                        IOException::class.java -> ParserDiagnosticCause.IO
                        AiHttpDispatchException::class.java -> ParserDiagnosticCause.DISPATCH_DENIED
                        else -> ParserDiagnosticCause.OTHER
                    }
                if (type == StreamResetException::class.java && resetCode == null) {
                    val observed = (current as StreamResetException).errorCode.name
                    resetCode = ParserDiagnosticResetCode.entries.firstOrNull { it.name == observed } ?: ParserDiagnosticResetCode.UNKNOWN
                }
                current = current.cause
            }
        }

        fun snapshot(
            number: Int,
            settled: Boolean,
        ) = ParserClientCallDiagnostic(
            number,
            requested,
            budget,
            actualTimeout,
            startDeadline,
            headersDeadline,
            dispatchRemaining,
            canceled,
            observedCauses.toList(),
            causesTruncated,
            resetCode,
            observedEvents.toList(),
            eventsTruncated,
            metadataUnavailable,
            settled,
        )
    }
}
